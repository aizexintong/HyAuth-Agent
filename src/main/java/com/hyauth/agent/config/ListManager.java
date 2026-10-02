package com.hyauth.agent.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * JSON 配置解析与文件热监听器。
 *
 * <p>配置文件为服务端根目录下的 {@value #FILE_NAME}，核心字段 {@code littleskin_players}
 * 为允许走 LittleSkin 外置验证的玩家 ID 列表（判定时忽略大小写）。
 * 其余字段均为可选，用于覆盖默认的 LittleSkin API 地址、超时与公钥。
 *
 * <p>文件被修改并保存后，守护线程 {@code HyAuth-ConfigWatcher} 会自动重载配置，
 * 新增/删除外置玩家无需重启服务端。
 */
public final class ListManager {

    /** 配置文件名（相对于服务端工作目录）。 */
    public static final String FILE_NAME = "littleskin_config.json";

    /** 默认 LittleSkin Yggdrasil API 根地址。 */
    public static final String DEFAULT_API_ROOT = "https://littleskin.cn/api/yggdrasil";

    private static final int DEFAULT_TIMEOUT_MS = 5000;

    /**
     * 白名单：全部以小写形式存储，判定时不区分大小写。
     * <p>整体替换（volatile 引用）而非原地清空，避免热重载瞬间出现“空名单窗口”。
     */
    private static volatile Set<String> whitelist = Collections.emptySet();

    /**
     * 离线名单：玩家名（小写）→ 管理员指定的 UUID / 名字写法。
     * <p>命中该名单的玩家完全跳过外部校验，直接以指定 UUID 进入。
     */
    private static volatile Map<String, OfflinePlayer> offlinePlayers = Collections.emptyMap();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile String apiRoot = DEFAULT_API_ROOT;
    private static volatile String publicKey = "";
    private static volatile int connectTimeoutMs = DEFAULT_TIMEOUT_MS;
    private static volatile int readTimeoutMs = DEFAULT_TIMEOUT_MS;
    private static volatile boolean debug = false;

    /**
     * 是否放宽聊天签名密钥校验（默认 true）。
     *
     * <p>原版服务端在玩家上报聊天签名密钥时，会用 <b>Mojang 的服务密钥</b>校验该密钥的签名；
     * 而 LittleSkin 这类外置账号的密钥不是 Mojang 签的，校验必然失败，
     * 玩家会被 <b>“Invalid signature for profile public key” 直接踢下线</b>
     * （且此处不受 {@code enforce-secure-profile} 影响）。
     *
     * <p>开启后，{@code Services#profileKeySignatureValidator()} 返回原版自带的
     * {@code SignatureValidator.NO_VALIDATION}，服务端不再校验该签名，外置账号即可正常游戏。
     * 代价：服务端不再强制聊天签名（等同"不校验"），正版玩家不受影响。
     */
    private static volatile boolean relaxChatKeys = true;

    /**
     * 聊天密钥桥接是否严格（默认 false）。
     *
     * <p>false：Mojang 与 LittleSkin 公钥都验不过时<b>放行</b>（保证能进服，日志会说明）。
     * true：都验不过时<b>拒绝</b>该密钥（玩家会被踢下线，等同原版严格行为）。
     *
     * <p>建议先在日志里确认出现过
     * {@code 聊天密钥桥接：皮肤站公钥验签通过（算法 …）}，再把此开关打开。
     */
    private static volatile boolean chatKeyStrict = false;

    /**
     * 是否让「没有聊天公钥的离线名单玩家」的指令按未签名处理（默认 true）。
     *
     * <p>这类玩家的客户端发"带可签名实参"的指令（{@code /say}、{@code /me}、{@code /msg} …）
     * 时用的是签名版指令包，原版会因为找不到公钥而丢弃它并回一条红字；
     * 开启本项后改用原版自带的未签名分支，指令恢复正常。
     *
     * <p>详见 {@code com.hyauth.agent.util.KeylessChatBypass}。
     */
    private static volatile boolean bypassSignedCommands = true;

    /**
     * 离线名单玩家是否豁免 {@code enforce-secure-profile}（默认 true）。
     *
     * <p>没有聊天公钥的玩家在 {@code enforce-secure-profile=true} 时连普通聊天都发不出去，
     * 开启本项后只对离线名单玩家放宽（正版玩家仍强制校验）。
     * 详见 {@code com.hyauth.agent.util.OfflineChatExempt}。
     */
    private static volatile boolean offlineChatExempt = true;

    /**
     * 外置账号的聊天是否按「逐接收者未签名（伪装聊天）」广播（默认 true）。
     *
     * <p>外置账号的聊天密钥是皮肤站签的，正版客户端验不过 → 清空其会话、不接收其"带签名"的消息，
     * 而服务端仍把签名记进 last-seen 账本 → 两边不一致 → <b>下一个发言的人被踢</b>
     * （{@code Checksum mismatch on last seen update}）；但若把消息统一换成未签名，
     * <b>作者自己</b>的客户端又因为用的是"链式校验器"而拒收，表现为"自己看不到 + 红字聊天验证错误"。
     *
     * <p>所以本开关打开时按接收者分流（切 {@code ServerGamePacketListenerImpl#sendPlayerChatMessage}）：
     * 发给<b>作者本人</b>的那一份保持原版签名消息，发给<b>其他人</b>的改用原版自带的伪装聊天
     * （{@code sendDisguisedChatMessage}，不参与签名账本）。两个问题一起消失。
     * 详见 {@code com.hyauth.agent.util.ExternalChatBroadcast}。
     */
    private static volatile boolean unsignedExternalChat = true;

    /**
     * 是否让**所有**玩家的聊天都走上面这条「逐接收者未签名广播」管线（默认 false）。
     *
     * <p>{@code true}：不管正版 / 外置 / 离线，消息发给"作者以外的人"时一律改用原版伪装聊天 ——
     * 签名账本从源头不参与，**任何情况下都不会因为聊天记账不一致而踢人**。
     * 作者本人依旧收到原版签名消息（因此正版玩家的"自己发的话"照常显示、签名链路自洽）。
     *
     * <p>{@code false}（默认）：只处理外置账号，**正版玩家之间保持签名聊天**（客户端显示为安全聊天）。
     *
     * <p>代价：被"伪装"的那一份不再携带聊天签名（身份认证 / 可举报证据）。
     * 身份仍然在**登录时**被验证过（正版走 Mojang、外置走皮肤站公钥验签、离线名单由管理员指定 UUID），
     * 只是这条信息不再随每条消息传递给其它客户端。
     */
    private static volatile boolean unsignedAllChat = false;

    /** 离线名单的 UUID 集合（豁免判定按 UUID 进行）。 */
    private static volatile Set<UUID> offlineUuids = Collections.emptySet();

    // ------------------------------------------------------------------
    // 区块卡顿勘探 + 管理员命令（v1.0.3 新增，见 README 第十五章）
    // ------------------------------------------------------------------

    /** 是否开启常驻区块卡顿采样（默认 true，几乎零开销）。 */
    private static volatile boolean lagResident = true;

    /** 近实时均值（EWMA）的平滑系数，越大越"跟手"（默认 0.05）。 */
    private static volatile double lagEwmaAlpha = 0.05D;

    /** 判定异常区块的绝对阈值，单位 ms/区块 tick（默认 1.0）。 */
    private static volatile double lagFlagThresholdMs = 1.0D;

    /** 相对判据：合计耗时达到全体中位数的多少倍也算异常（默认 6.0）。 */
    private static volatile double lagRelativeFactor = 6.0D;

    /** {@code /hy lag scan} 不带秒数时的默认采样时长（默认 30 秒）。 */
    private static volatile int lagScanDefaultSeconds = 30;

    /** 报告里单区块榜的条数（默认 10）。 */
    private static volatile int lagReportTop = 10;

    /** 报告里最多列出多少组"坐标范围"（默认 8）。 */
    private static volatile int lagMaxClusters = 8;

    /** 常驻窗口最多跟踪多少个区块（默认 20000，超了不再纳入新区块）。 */
    private static volatile int lagMaxChunks = 20000;

    /** 管理员命令的根命令名（默认 hy / ha / hyauth，另有 lag 作为勘探组快捷方式）。 */
    private static volatile Set<String> commandRoots = defaultCommandRoots();

    /** 执行管理员命令所需的原版权限等级（默认 2 = OP，控制台天然满足）。 */
    private static volatile int commandOpLevel = 2;

    // ------------------------------------------------------------------
    // 空置域挖掘（v1.0.4 新增，见 README 第十八章）
    // ------------------------------------------------------------------

    /** 执行空置域挖掘所需的原版权限等级（默认 3：破坏性操作门槛更高）。 */
    private static volatile int clearOpLevel = 3;

    /** 执行节流：每多少 tick 推进一个步骤（默认 4 tick = 200ms）。 */
    private static volatile int clearIntervalTicks = 4;

    /** 每个节拍最多推进几步（默认 2）。 */
    private static volatile int clearFillsPerStep = 2;

    /** {@code forceload add} 之后等几个 tick 再开始 fill（默认 5）。 */
    private static volatile int clearLoadWaitTicks = 5;

    /** 实体清理模式：{@code none} / {@code items}（默认）/ {@code all}（不含玩家）。 */
    private static volatile String clearKill = "items";

    /** 内圈清空的下界 y（默认 -64，主世界基岩层下）。 */
    private static volatile int clearMinY = -64;

    /** "地面"y：这一层及以上按外圈清空、以下按内圈清空（默认 63）。 */
    private static volatile int clearTopY = 63;

    /** 上界相对 {@code top_y} 的高度（默认 256 → 主世界挖到 y=319）。 */
    private static volatile int clearAboveHeight = 256;

    /** 是否挖防爆沟（默认 true）。 */
    private static volatile boolean clearTrench = true;

    /** 防爆沟填充方块（默认沙子）。 */
    private static volatile String clearTrenchBlock = "sand";

    /** 每批多少区块的边长（默认 4 → 4x4 区块 = 64x64 方块）。 */
    private static volatile int clearBatchChunks = 4;

    /** 选区边长上限（默认 2048 方块）。 */
    private static volatile int clearMaxSide = 2048;

    /** 单次任务的清理体积上限（默认 5 亿方块）。 */
    private static volatile long clearMaxVolume = 500_000_000L;

    /** 进度播报间隔（百分比，默认 10）。 */
    private static volatile int clearAnnouncePercent = 10;

    /**
     * 是否连基岩层一起挖掉（默认 true，与"命令版清区块"脚本一致）。
     *
     * <p>{@code true}：从 {@code min_y}（默认 -64）开始清 ⇒ <b>基岩也会被挖掉</b>，底部直接是虚空
     * （东西掉下去就没了，但这也是很多世吞设计想要的"全空底"）。
     * <p>{@code false}：底部<b>保留基岩层</b> —— 挖掘下界抬到 {@code bedrock_top_y} 的上一格
     * （默认 -60 的基岩 ⇒ 从 -59 开始清），想保留世界底板时用这个。
     */
    private static volatile boolean clearBreakBedrock = true;

    /** 基岩层顶面 y（{@code break_bedrock=false} 时用来算保留基岩后的挖掘下界）。 */
    private static volatile int clearBedrockTopY = -60;

    private static Set<String> defaultCommandRoots() {
        Set<String> roots = new LinkedHashSet<String>();
        roots.add("hy");
        roots.add("ha");
        roots.add("hyauth");
        roots.add("lag");
        return Collections.unmodifiableSet(roots);
    }

    /**
     * 原版命令名黑名单：绝不允许拿来当我们的命令根。
     *
     * <p>因为我们的命令是"在 {@code performPrefixedCommand} 处按首词拦截"，
     * 一旦根名与原版命令同名（最典型的就是 {@code clear}、{@code tp}、{@code kill}），
     * 那条原版命令就会被我们<b>整条遮蔽</b>——例如把根设成 {@code clear}
     * 会让所有人的 {@code /clear}（清理背包）失效。配置里写了这类名字会被直接忽略并告警。
     */
    private static final Set<String> VANILLA_COMMAND_NAMES = Collections.unmodifiableSet(
            new HashSet<String>(java.util.Arrays.asList(
                    "advancement", "attribute", "ban", "ban-ip", "banlist", "bossbar", "clear", "clone",
                    "damage", "data", "datapack", "debug", "defaultgamemode", "deop", "dialog", "difficulty",
                    "effect", "enchant", "execute", "experience", "fill", "fillbiome", "forceload", "function",
                    "gamemode", "gamerule", "give", "help", "item", "jigsaw", "kick", "kill", "list", "locate",
                    "loot", "me", "msg", "op", "pardon", "particle", "place", "playsound", "publish", "random",
                    "recipe", "reload", "return", "ride", "say", "schedule", "scoreboard", "seed", "setblock",
                    "setidletimeout", "setworldspawn", "spawnpoint", "spectate", "spreadplayers", "stop",
                    "stopsound", "summon", "tag", "team", "teammsg", "teleport", "tell", "tellraw", "tick",
                    "time", "title", "tm", "tp", "transfer", "trigger", "w", "waypoint", "weather", "whitelist",
                    "worldborder", "xp")));

    private ListManager() {
    }

    /** Agent 启动时调用：确保配置文件存在 → 首次加载 → 启动热监听线程。 */
    public static void init() {
        File file = new File(FILE_NAME);
        if (!file.exists()) {
            createDefaultConfig(file);
        }
        reload();
        startWatchThread(file);
        warnAboutSecureProfile();
    }

    /**
     * 启动自检：名单非空 + {@code enforce-secure-profile=true} 时给出**必须处理**的提示。
     *
     * <p>关键在于<b>客户端</b>（26.3 真实字节码）：
     * <pre>
     * // net.minecraft.client.multiplayer.PlayerInfo
     * private static SignedMessageValidator fallbackMessageValidator(boolean enforceSecureProfile) {
     *     return enforceSecureProfile ? SignedMessageValidator.REJECT_ALL      // 丢弃一切并提示
     *                                 : SignedMessageValidator.ACCEPT_UNSIGNED;
     * }
     * // SignedMessageValidator.REJECT_ALL 的实现：
     * LOGGER.error("Received chat message from {}, but they have no chat session initialized and secure chat is enforced");
     * return null;   → 客户端不显示该消息，并弹出红字「聊天验证错误」
     * </pre>
     *
     * <p>也就是说：外置账号的密钥不是 Mojang 签的、离线名单玩家根本没有密钥，
     * 客户端会认为他们"没有聊天会话"，于是在 {@code enforce-secure-profile=true} 时
     * <b>把他们的每一条消息都丢掉</b>（服务端其实已经正常广播了，日志里也看得到）。
     * 这个判断由每个客户端各自做出，取决于登录包里的
     * {@code enforcesSecureChat}（= 这个开关），<b>服务端切面无法干预</b>。
     *
     * <p>所以必须把它设为 {@code false}：之后客户端改用 {@code ACCEPT_UNSIGNED}，
     * 无法验证签名的消息照常显示（标注 [Not Secure]），正版玩家的签名消息不受影响。
     */
    private static void warnAboutSecureProfile() {
        if (offlinePlayers.isEmpty() && whitelist.isEmpty()) {
            return;
        }
        boolean enforce = true; // server.properties 缺省即 true
        File properties = new File("server.properties");
        if (properties.isFile()) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new FileInputStream(properties), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    int eq = trimmed.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    if ("enforce-secure-profile".equals(trimmed.substring(0, eq).trim())) {
                        enforce = Boolean.parseBoolean(trimmed.substring(eq + 1).trim());
                    }
                }
            } catch (Exception e) {
                System.err.println("[HyAuth] 读取 server.properties 失败（不影响运行）: " + e.getMessage());
            }
        }
        if (!enforce) {
            System.out.println("[HyAuth] enforce-secure-profile=false：外置/离线玩家的聊天可被客户端正常显示（正版签名消息不受影响）。");
            return;
        }
        System.out.println("[HyAuth] 重要提示：server.properties 的 enforce-secure-profile 是 true（或未设置），"
                + "而名单里有外置/离线玩家（LittleSkin " + whitelist.size() + " 人，离线 " + offlinePlayers.size() + " 人）。");
        if (!whitelist.isEmpty()) {
            System.out.println("[HyAuth]   外置（LittleSkin）账号：本版把他们的聊天按「逐接收者未签名（伪装聊天）」广播，"
                    + "客户端不再走签名校验 ⇒ 不受本开关影响；作者本人收到的仍是原版签名消息（自己看得到自己发的话）。");
        }
        if (!offlinePlayers.isEmpty()) {
            System.out.println("[HyAuth]   离线名单玩家：他们没有聊天会话，客户端在 enforce-secure-profile=true 时用 REJECT_ALL"
                    + " 逐条丢弃他们的消息并弹红字「聊天验证错误」（连自己看自己发的话也一样）。");
            System.out.println("[HyAuth]   该判断由每个客户端按登录包里的 enforcesSecureChat（= 本开关）决定，服务端切面无法干预。");
            System.out.println("[HyAuth]   解决：server.properties 改成 enforce-secure-profile=false 并重启服务端。"
                    + "正版玩家的签名聊天不受影响。");
        }
    }

    private static void createDefaultConfig(File file) {
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            JsonObject config = new JsonObject();
            config.addProperty("description",
                    "HyAuth-Agent 配置文件：littleskin_players = 走 LittleSkin 外置验证的玩家；"
                            + "offline_players = 管理员手动指定 UUID 的离线玩家（优先级最高）");

            JsonArray players = new JsonArray();
            players.add("Xiao_Ming");
            players.add("Player_Demo");
            config.add("littleskin_players", players);

            // 离线名单：只有在这里显式登记了 name + uuid 的玩家才允许离线登录
            config.add("offline_players", new JsonArray());

            // 以下均为可选字段，删除后自动使用默认值
            config.addProperty("api_root", DEFAULT_API_ROOT);
            config.addProperty("connect_timeout_ms", DEFAULT_TIMEOUT_MS);
            config.addProperty("read_timeout_ms", DEFAULT_TIMEOUT_MS);
            config.addProperty("relax_chat_keys", true);
            config.addProperty("unsigned_external_chat", true);
            config.addProperty("chat_key_strict", false);
            config.addProperty("bypass_signed_commands", true);
            config.addProperty("offline_chat_exempt", true);
            config.addProperty("debug", false);

            // 区块卡顿勘探（第十五章）：常驻 + 按需两套采样
            JsonObject chunkLag = new JsonObject();
            chunkLag.addProperty("resident", true);
            chunkLag.addProperty("ewma_alpha", 0.05D);
            chunkLag.addProperty("flag_threshold_ms", 1.0D);
            chunkLag.addProperty("flag_relative_factor", 6.0D);
            chunkLag.addProperty("scan_default_seconds", 30);
            chunkLag.addProperty("report_top", 10);
            chunkLag.addProperty("max_clusters", 8);
            chunkLag.addProperty("max_tracked_chunks", 20000);
            config.add("chunk_lag", chunkLag);

            // 管理员命令（第十六章）
            JsonObject commands = new JsonObject();
            JsonArray roots = new JsonArray();
            for (String root : defaultCommandRoots()) {
                roots.add(root);
            }
            commands.add("roots", roots);
            commands.addProperty("op_level", 2);
            config.add("commands", commands);

            // 空置域挖掘（第十八章）
            JsonObject clear = new JsonObject();
            clear.addProperty("op_level", 3);
            clear.addProperty("interval_ticks", 4);
            clear.addProperty("fills_per_step", 2);
            clear.addProperty("load_wait_ticks", 5);
            clear.addProperty("kill", "items");
            clear.addProperty("min_y", -64);
            clear.addProperty("top_y", 63);
            clear.addProperty("above_height", 256);
            clear.addProperty("trench", true);
            clear.addProperty("trench_block", "sand");
            clear.addProperty("batch_chunks", 4);
            clear.addProperty("max_side", 2048);
            clear.addProperty("max_volume", 500000000);
            clear.addProperty("announce_percent", 10);
            clear.addProperty("break_bedrock", true);
            clear.addProperty("bedrock_top_y", -60);
            config.add("clear", clear);

            GSON.toJson(config, writer);
            System.out.println("[HyAuth] 已自动创建默认配置文件: " + FILE_NAME);
        } catch (Exception e) {
            System.err.println("[HyAuth] 创建默认配置文件失败: " + e.getMessage());
        }
    }

    /**
     * 重新加载配置文件。
     *
     * <p>解析失败时保留上一次生效的名单与参数，不会把白名单清空。
     */
    public static synchronized void reload() {
        try (Reader reader = new InputStreamReader(new FileInputStream(FILE_NAME), StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || !root.isJsonObject()) {
                throw new IllegalStateException("配置根节点必须是 JSON 对象");
            }
            JsonObject json = root.getAsJsonObject();

            Set<String> players = new HashSet<>();
            if (json.has("littleskin_players") && json.get("littleskin_players").isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray("littleskin_players")) {
                    if (element == null || element.isJsonNull()) {
                        continue;
                    }
                    String name = element.getAsString().trim();
                    if (!name.isEmpty()) {
                        players.add(name.toLowerCase(Locale.ROOT));
                    }
                }
            }

            whitelist = Collections.unmodifiableSet(players);

            Map<String, OfflinePlayer> offline = new HashMap<String, OfflinePlayer>();
            if (json.has("offline_players") && json.get("offline_players").isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray("offline_players")) {
                    if (element == null || !element.isJsonObject()) {
                        System.err.println("[HyAuth] offline_players 中的条目必须是 {\"name\":\"…\",\"uuid\":\"…\"} 对象，已跳过。");
                        continue;
                    }
                    JsonObject entry = element.getAsJsonObject();
                    String entryName = getString(entry, "name");
                    String rawUuid = getString(entry, "uuid");
                    if (entryName == null || entryName.trim().isEmpty()) {
                        System.err.println("[HyAuth] offline_players 条目缺少 name，已跳过。");
                        continue;
                    }
                    UUID uuid = parseUuid(rawUuid);
                    if (uuid == null) {
                        System.err.println("[HyAuth] offline_players 条目 " + entryName
                                + " 的 uuid 非法（需为 32 位无符号或标准带连字符 UUID），已跳过。");
                        continue;
                    }
                    String key = entryName.trim().toLowerCase(Locale.ROOT);
                    OfflinePlayer previous = offline.put(key, new OfflinePlayer(entryName.trim(), uuid));
                    if (previous != null) {
                        System.err.println("[HyAuth] offline_players 中玩家名重复: " + entryName + "，后者生效。");
                    }
                }
            }
            offlinePlayers = Collections.unmodifiableMap(offline);

            Set<UUID> uuids = new HashSet<UUID>();
            for (OfflinePlayer player : offline.values()) {
                uuids.add(player.getUuid());
            }
            offlineUuids = Collections.unmodifiableSet(uuids);

            apiRoot = optString(json, "api_root", DEFAULT_API_ROOT);
            publicKey = optString(json, "public_key", "");
            connectTimeoutMs = optInt(json, "connect_timeout_ms", DEFAULT_TIMEOUT_MS);
            readTimeoutMs = optInt(json, "read_timeout_ms", DEFAULT_TIMEOUT_MS);
            relaxChatKeys = !json.has("relax_chat_keys") || json.get("relax_chat_keys").isJsonNull()
                    || json.get("relax_chat_keys").getAsBoolean();
            chatKeyStrict = json.has("chat_key_strict") && !json.get("chat_key_strict").isJsonNull()
                    && json.get("chat_key_strict").getAsBoolean();
            bypassSignedCommands = !json.has("bypass_signed_commands")
                    || json.get("bypass_signed_commands").isJsonNull()
                    || json.get("bypass_signed_commands").getAsBoolean();
            offlineChatExempt = !json.has("offline_chat_exempt")
                    || json.get("offline_chat_exempt").isJsonNull()
                    || json.get("offline_chat_exempt").getAsBoolean();
            unsignedExternalChat = !json.has("unsigned_external_chat")
                    || json.get("unsigned_external_chat").isJsonNull()
                    || json.get("unsigned_external_chat").getAsBoolean();
            unsignedAllChat = json.has("unsigned_all_chat")
                    && !json.get("unsigned_all_chat").isJsonNull()
                    && json.get("unsigned_all_chat").getAsBoolean();
            debug = json.has("debug") && !json.get("debug").isJsonNull() && json.get("debug").getAsBoolean();

            // 区块卡顿勘探
            JsonObject chunkLag = json.has("chunk_lag") && !json.get("chunk_lag").isJsonNull()
                    && json.get("chunk_lag").isJsonObject() ? json.getAsJsonObject("chunk_lag") : null;
            lagResident = chunkLag == null || !chunkLag.has("resident") || chunkLag.get("resident").isJsonNull()
                    || chunkLag.get("resident").getAsBoolean();
            lagEwmaAlpha = clampDouble(optDouble(chunkLag, "ewma_alpha", 0.05D), 0.001D, 1.0D);
            lagFlagThresholdMs = Math.max(0.01D, optDouble(chunkLag, "flag_threshold_ms", 1.0D));
            lagRelativeFactor = Math.max(1.0D, optDouble(chunkLag, "flag_relative_factor", 6.0D));
            lagScanDefaultSeconds = clampInt(optInt(chunkLag, "scan_default_seconds", 30), 1, 3600);
            lagReportTop = clampInt(optInt(chunkLag, "report_top", 10), 1, 200);
            lagMaxClusters = clampInt(optInt(chunkLag, "max_clusters", 8), 1, 100);
            lagMaxChunks = clampInt(optInt(chunkLag, "max_tracked_chunks", 20000), 100, 2_000_000);

            // 管理员命令
            JsonObject commands = json.has("commands") && !json.get("commands").isJsonNull()
                    && json.get("commands").isJsonObject() ? json.getAsJsonObject("commands") : null;
            Set<String> roots = new LinkedHashSet<String>();
            if (commands != null && commands.has("roots") && commands.get("roots").isJsonArray()) {
                for (JsonElement element : commands.getAsJsonArray("roots")) {
                    if (element == null || element.isJsonNull()) {
                        continue;
                    }
                    String commandName = element.getAsString().trim().toLowerCase(Locale.ROOT);
                    if (!commandName.matches("[a-z0-9_]{1,16}")) {
                        if (!commandName.isEmpty()) {
                            System.err.println("[HyAuth] commands.roots 里的 \"" + commandName
                                    + "\" 不是合法命令名（只允许小写字母/数字/下划线，1~16 位），已忽略。");
                        }
                    } else if (VANILLA_COMMAND_NAMES.contains(commandName)) {
                        // 遮蔽原版命令是灾难（例如把根设成 clear 会让所有人的 /clear 失效），直接拒绝并大声说明
                        System.err.println("[HyAuth] commands.roots 里的 \"" + commandName
                                + "\" 与原版命令同名，会把原版命令整条遮蔽掉，已忽略。"
                                + "请换一个名字（例如 hy / ha / hyauth）。");
                    } else {
                        roots.add(commandName);
                    }
                }
            }
            if (roots.isEmpty()) {
                roots.addAll(defaultCommandRoots());
            } else if (!roots.contains("lag")) {
                roots.add("lag"); // 保留 /lag 这个勘探快捷方式
            }
            commandRoots = Collections.unmodifiableSet(roots);
            commandOpLevel = clampInt(optInt(commands, "op_level", 2), 0, 4);

            // 空置域挖掘
            JsonObject clear = json.has("clear") && !json.get("clear").isJsonNull() && json.get("clear").isJsonObject()
                    ? json.getAsJsonObject("clear") : null;
            clearOpLevel = clampInt(optInt(clear, "op_level", 3), 0, 4);
            clearIntervalTicks = clampInt(optInt(clear, "interval_ticks", 4), 1, 200);
            clearFillsPerStep = clampInt(optInt(clear, "fills_per_step", 2), 1, 64);
            clearLoadWaitTicks = clampInt(optInt(clear, "load_wait_ticks", 5), 0, 200);
            clearKill = normalizeKill(optString(clear, "kill", "items"));
            clearMinY = clampInt(optInt(clear, "min_y", -64), -2048, 2048);
            clearTopY = clampInt(optInt(clear, "top_y", 63), -2048, 2048);
            clearAboveHeight = clampInt(optInt(clear, "above_height", 256), 1, 1024);
            clearTrench = clear == null || !clear.has("trench") || clear.get("trench").isJsonNull()
                    || clear.get("trench").getAsBoolean();
            clearTrenchBlock = optString(clear, "trench_block", "sand");
            clearBatchChunks = clampInt(optInt(clear, "batch_chunks", 4), 1, 32);
            clearMaxSide = clampInt(optInt(clear, "max_side", 2048), 1, 100000);
            clearMaxVolume = Math.max(1L, optLong(clear, "max_volume", 500_000_000L));
            clearAnnouncePercent = clampInt(optInt(clear, "announce_percent", 10), 1, 50);
            clearBreakBedrock = clear == null || !clear.has("break_bedrock")
                    || clear.get("break_bedrock").isJsonNull() || clear.get("break_bedrock").getAsBoolean();
            clearBedrockTopY = clampInt(optInt(clear, "bedrock_top_y", -60), -2048, 2048);

            System.out.println("[HyAuth] 配置重载完成（JSON 配置加载成功），当前 LittleSkin 白名单人数: "
                    + whitelist.size() + "，离线名单人数: " + offlinePlayers.size() + "，API: " + apiRoot);
            System.out.println("[HyAuth] 管理员命令: /" + getPrimaryCommandRoot() + " …（另有 "
                    + describeRoots() + "），需要权限等级 ≥ " + commandOpLevel
                    + "；区块卡顿勘探常驻采样: " + (lagResident ? "开" : "关"));
        } catch (Exception e) {
            System.err.println("[HyAuth] 读取配置文件失败，请检查 JSON 格式: " + e.getMessage());
        }
    }

    /** 判断玩家是否在 LittleSkin 名单内（忽略大小写）。 */
    public static boolean isLittleSkinPlayer(String username) {
        if (username == null) {
            return false;
        }
        return whitelist.contains(username.trim().toLowerCase(Locale.ROOT));
    }

    /** 当前生效的 LittleSkin 白名单人数（只读诊断用）。 */
    public static int whitelistSize() {
        return whitelist.size();
    }

    /** 当前生效的离线名单人数（只读诊断用）。 */
    public static int offlinePlayerCount() {
        return offlinePlayers.size();
    }

    /**
     * 查询离线名单。命中则返回管理员指定的 UUID 与名字写法，否则返回 {@code null}。
     * <p>判定不区分大小写。
     */
    public static OfflinePlayer getOfflinePlayer(String username) {
        if (username == null) {
            return null;
        }
        return offlinePlayers.get(username.trim().toLowerCase(Locale.ROOT));
    }

    /** 玩家是否在离线名单内。 */
    public static boolean isOfflinePlayer(String username) {
        return getOfflinePlayer(username) != null;
    }

    private static String getString(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        return json.get(key).getAsString();
    }

    // ------------------------------------------------------------------
    // 区块卡顿勘探 / 命令配置读取
    // ------------------------------------------------------------------

    /** 常驻采样是否开启（见 {@link #lagResident}）。 */
    public static boolean isLagResident() {
        return lagResident;
    }

    /** EWMA 平滑系数（见 {@link #lagEwmaAlpha}）。 */
    public static double getLagEwmaAlpha() {
        return lagEwmaAlpha;
    }

    /** 异常区块绝对阈值 ms（见 {@link #lagFlagThresholdMs}）。 */
    public static double getLagFlagThresholdMs() {
        return lagFlagThresholdMs;
    }

    /** 异常区块相对倍数（见 {@link #lagRelativeFactor}）。 */
    public static double getLagRelativeFactor() {
        return lagRelativeFactor;
    }

    /** {@code /hy lag scan} 的默认秒数。 */
    public static int getLagScanDefaultSeconds() {
        return lagScanDefaultSeconds;
    }

    /** 单区块榜条数。 */
    public static int getLagReportTop() {
        return lagReportTop;
    }

    /** 报告里最多列出多少组坐标范围。 */
    public static int getLagMaxClusters() {
        return lagMaxClusters;
    }

    /** 常驻窗口跟踪区块数上限。 */
    public static int getLagMaxChunks() {
        return lagMaxChunks;
    }

    /** 管理员命令的全部根命令名（含 lag）。 */
    public static Set<String> getCommandRoots() {
        return commandRoots;
    }

    /** 主根命令名（回显与帮助里显示的那个）。 */
    public static String getPrimaryCommandRoot() {
        for (String root : commandRoots) {
            if (!"lag".equals(root)) {
                return root;
            }
        }
        return commandRoots.isEmpty() ? "hy" : commandRoots.iterator().next();
    }

    /** 该词是不是我们认的命令根（判断是否拦截这次命令派发）。 */
    public static boolean isCommandRoot(String token) {
        return token != null && commandRoots.contains(token.toLowerCase(Locale.ROOT));
    }

    /** 执行管理员命令所需的原版权限等级。 */
    public static int getCommandOpLevel() {
        return commandOpLevel;
    }

    /** 把根命令名拼成一行诊断文本。 */
    public static String describeRoots() {
        StringBuilder builder = new StringBuilder();
        for (String root : commandRoots) {
            if (builder.length() > 0) {
                builder.append(" / ");
            }
            builder.append('/').append(root);
        }
        return builder.toString();
    }

    /** 当前 LittleSkin 外置名单（只读，名字为小写存储形式）。 */
    public static Set<String> getLittleSkinPlayers() {
        return whitelist;
    }

    /** 当前离线名单（只读，小写名字 → 条目）。 */
    public static Map<String, OfflinePlayer> getOfflinePlayers() {
        return offlinePlayers;
    }

    // ------------------------------------------------------------------
    // 空置域挖掘配置
    // ------------------------------------------------------------------

    /** 执行空置域挖掘所需的权限等级（见 {@link #clearOpLevel}）。 */
    public static int getClearOpLevel() {
        return clearOpLevel;
    }

    public static int getClearIntervalTicks() {
        return clearIntervalTicks;
    }

    public static int getClearFillsPerStep() {
        return clearFillsPerStep;
    }

    public static int getClearLoadWaitTicks() {
        return clearLoadWaitTicks;
    }

    public static String getClearKill() {
        return clearKill;
    }

    public static int getClearMinY() {
        return clearMinY;
    }

    public static int getClearTopY() {
        return clearTopY;
    }

    public static int getClearAboveHeight() {
        return clearAboveHeight;
    }

    public static boolean isClearTrench() {
        return clearTrench;
    }

    public static String getClearTrenchBlock() {
        return clearTrenchBlock;
    }

    public static int getClearBatchChunks() {
        return clearBatchChunks;
    }

    public static int getClearMaxSide() {
        return clearMaxSide;
    }

    public static long getClearMaxVolume() {
        return clearMaxVolume;
    }

    public static int getClearAnnouncePercent() {
        return clearAnnouncePercent;
    }

    /** 是否连基岩层一起挖掉（见 {@link #clearBreakBedrock}）。 */
    public static boolean isClearBreakBedrock() {
        return clearBreakBedrock;
    }

    /** 基岩层顶面 y（保留基岩时用来算挖掘下界）。 */
    public static int getClearBedrockTopY() {
        return clearBedrockTopY;
    }

    /** kill 模式只认 none / items / all，其余一律按 items（最保守的"只清掉落物"）。 */
    private static String normalizeKill(String raw) {
        if (raw == null) {
            return "items";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if ("none".equals(value) || "off".equals(value) || "false".equals(value)) {
            return "none";
        }
        if ("all".equals(value) || "entities".equals(value)) {
            return "all";
        }
        if (!"items".equals(value) && !"item".equals(value)) {
            System.err.println("[HyAuth] clear.kill 只支持 none / items / all，收到 \"" + raw + "\"，按 items 处理。");
        }
        return "items";
    }

    private static long optLong(JsonObject json, String key, long fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsLong();
        } catch (Exception e) {
            System.err.println("[HyAuth] 配置项 " + key + " 不是合法整数，已使用默认值 " + fallback);
            return fallback;
        }
    }

    /** 兼容 32 位无符号与标准带连字符两种 UUID 写法。 */
    private static UUID parseUuid(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            if (value.length() == 32 && value.indexOf('-') < 0) {
                value = value.substring(0, 8) + "-" + value.substring(8, 12) + "-" + value.substring(12, 16)
                        + "-" + value.substring(16, 20) + "-" + value.substring(20);
            }
            return UUID.fromString(value);
        } catch (Exception e) {
            return null;
        }
    }

    public static String getApiRoot() {
        String value = apiRoot;
        if (value == null || value.trim().isEmpty()) {
            return DEFAULT_API_ROOT;
        }
        return value.trim();
    }

    public static String getPublicKey() {
        String value = publicKey;
        return value == null ? "" : value.trim();
    }

    public static int getConnectTimeoutMs() {
        return connectTimeoutMs > 0 ? connectTimeoutMs : DEFAULT_TIMEOUT_MS;
    }

    public static int getReadTimeoutMs() {
        return readTimeoutMs > 0 ? readTimeoutMs : DEFAULT_TIMEOUT_MS;
    }

    public static boolean isDebug() {
        return debug;
    }

    /** 是否放宽聊天签名密钥校验（见 {@link #relaxChatKeys}）。 */
    public static boolean isRelaxChatKeys() {
        return relaxChatKeys;
    }

    /** 聊天密钥桥接是否严格（见 {@link #chatKeyStrict}）。 */
    public static boolean isChatKeyStrict() {
        return chatKeyStrict;
    }

    /** 外置账号的聊天是否按未签名广播（见 {@link #unsignedExternalChat}）。 */
    public static boolean isUnsignedExternalChat() {
        return unsignedExternalChat;
    }

    /** 是否让所有玩家的聊天都按未签名广播（见 {@link #unsignedAllChat}）。 */
    public static boolean isUnsignedAllChat() {
        return unsignedAllChat;
    }

    /** 无聊天公钥的离线玩家是否按未签名处理指令（见 {@link #bypassSignedCommands}）。 */
    public static boolean isBypassSignedCommands() {
        return bypassSignedCommands;
    }

    /** 离线名单玩家是否豁免 enforce-secure-profile（见 {@link #offlineChatExempt}）。 */
    public static boolean isOfflineChatExempt() {
        return offlineChatExempt;
    }

    /** 该 UUID 是否属于离线名单。 */
    public static boolean isOfflineUuid(UUID uuid) {
        return uuid != null && offlineUuids.contains(uuid);
    }

    /** 当前离线名单人数（只读诊断用）。 */
    public static int offlineUuidCount() {
        return offlineUuids.size();
    }

    private static String optString(JsonObject json, String key, String fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        String value = json.get(key).getAsString();
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static int optInt(JsonObject json, String key, int fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsInt();
        } catch (Exception e) {
            System.err.println("[HyAuth] 配置项 " + key + " 不是合法整数，已使用默认值 " + fallback);
            return fallback;
        }
    }

    private static double optDouble(JsonObject json, String key, double fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsDouble();
        } catch (Exception e) {
            System.err.println("[HyAuth] 配置项 " + key + " 不是合法数字，已使用默认值 " + fallback);
            return fallback;
        }
    }

    private static int clampInt(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static double clampDouble(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }

    /**
     * 启动配置文件热监听线程。
     * <p>监听目录而非文件本身（文件被编辑器“删除 + 重建”时也能感知），
     * 并在监听器异常退出后自动重建，保证长期运行可靠性。
     */
    private static void startWatchThread(final File file) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                final Path dir = file.getAbsoluteFile().getParentFile().toPath();
                while (!Thread.currentThread().isInterrupted()) {
                    WatchService watchService = null;
                    try {
                        watchService = FileSystems.getDefault().newWatchService();
                        dir.register(watchService,
                                StandardWatchEventKinds.ENTRY_MODIFY,
                                StandardWatchEventKinds.ENTRY_CREATE);
                        System.out.println("[HyAuth] 配置热监听已启动: " + dir.resolve(FILE_NAME));

                        while (!Thread.currentThread().isInterrupted()) {
                            WatchKey key = watchService.take();
                            boolean changed = false;
                            for (WatchEvent<?> event : key.pollEvents()) {
                                Object context = event.context();
                                if (context != null && FILE_NAME.equalsIgnoreCase(context.toString())) {
                                    changed = true;
                                }
                            }
                            if (!key.reset()) {
                                break; // 监听目录已失效，退出内层循环后重建
                            }
                            if (changed) {
                                Thread.sleep(500); // 等待编辑器释放文件写锁
                                reload();
                            }
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception e) {
                        System.err.println("[HyAuth] 配置热监听异常，3 秒后重建: " + e);
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    } finally {
                        if (watchService != null) {
                            try {
                                watchService.close();
                            } catch (Exception ignored) {
                                // 忽略关闭异常
                            }
                        }
                    }
                }
            }
        });
        thread.setDaemon(true);
        thread.setName("HyAuth-ConfigWatcher");
        thread.start();
    }
}
