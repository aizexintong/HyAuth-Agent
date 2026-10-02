package com.hyauth.agent.util;

import com.hyauth.agent.config.ListManager;
import com.hyauth.agent.config.OfflinePlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 管理员命令的分发中心（由 {@code CommandAdvice} 在 {@code Commands#performPrefixedCommand} 处调用）。
 *
 * <p>命令表（根命令默认 {@code hy} / {@code ha} / {@code hyauth}，另有 {@code lag} 直达勘探组）：
 * <pre>
 *   /hy help                          帮助
 *   /hy status                        运行状态 + 各类勘探切面的能力探测
 *   /hy reload                        重新读配置（并同步常驻采样开关）
 *   /hy list                          查看两个名单
 *   /hy add &lt;名字&gt;                   加入 LittleSkin 外置名单
 *   /hy off &lt;名字&gt; [uuid|force]      加入离线名单（默认自动生成 UUIDv7，先查重）
 *   /hy del &lt;名字&gt;                   从两个名单移除
 *   /hy whois &lt;名字&gt;                 查这个名字在服务端上的历史 UUID
 *   /hy lag scan [秒]                 采样 N 秒后自动出报告
 *   /hy lag list                      立刻看异常区块坐标范围（聊天里可点击传送）
 *   /hy lag top [N]                   单区块耗时榜
 *   /hy lag tp &lt;序号&gt; | &lt;x&gt; &lt;z&gt;       传送到现场
 *   /hy lag here                      看脚下这块的耗时分项
 *   /hy lag on|off|stop|clear|status  常驻开关 / 停止扫描 / 清空 / 状态
 * </pre>
 *
 * <p><b>两条硬规矩</b>：
 * <ol>
 *     <li>命令首词不命中我们的根命令 → 返回 {@code false}，原版命令系统分毫不动；</li>
 *     <li>我们自己的处理过程抛任何异常 → 也只放行原版（见 {@code CommandAdvice}），
 *         宁可"这条命令无效"，也绝不能把 {@code /tp}、{@code /list} 之类吞掉。</li>
 * </ol>
 */
public final class AdminCommands {

    private AdminCommands() {
    }

    /** 命令入口：返回 {@code true} 表示"这条命令我们处理了，别交给原版"。 */
    public static boolean tryHandle(Object commands, Object source, String rawCommand) {
        if (rawCommand == null) {
            return false;
        }
        source = unwrapSource(source);
        String line = rawCommand.trim();
        if (line.isEmpty()) {
            return false;
        }
        if (line.charAt(0) == '/') {
            line = line.substring(1).trim();
        }
        if (line.isEmpty()) {
            return false;
        }
        String[] tokens = line.split("\\s+");
        String root = tokens[0].toLowerCase(Locale.ROOT);
        if (!ListManager.isCommandRoot(root)) {
            return false;
        }

        ChunkLagSampler.initFromConfig();

        int required = ListManager.getCommandOpLevel();
        int level = permissionLevel(source);
        if (level < required) {
            ChatOut.error(source, "HyAuth 管理员命令需要权限等级 ≥ " + required + "（当前等级 " + level + "）。");
            return true;
        }

        try {
            dispatch(root, tokens, commands, source);
        } catch (Throwable t) {
            ChatOut.error(source, "命令执行异常: " + t);
            t.printStackTrace();
        }
        return true;
    }

    /**
     * 把我们的命令节点注册进<b>真实的 Brigadier 命令树</b>。
     *
     * <p>为什么必须注册：只做"分发入口拦截"时，客户端的命令树里没有 {@code hy}/{@code lag}，
     * 于是 ① Tab 补全没有我们的命令；② 聊天里 {@code ClickEvent.RunCommand} 会被<b>客户端</b>
     * 本地拒绝（玩家看到红字"未知或不完整的命令"，服务端根本没收到）；③ 插件命令看起来像不存在。
     * 注册之后这三件事一起解决，而执行仍然由分发入口的切面负责（注册的节点只是"占位 + 可执行兜底"）。
     *
     * <p>实现上全部走反射：切面类字节码里不能出现服务端/Brigadier 类型引用（README §10.3），
     * 而 {@code com.mojang.brigadier} 只在服务端子加载器里可见。
     *
     * <p>注册时机：{@code Commands} 构造完成时（见 {@code CommandTreeAdvice}）——
     * 那时玩家还没登录，登录时下发的命令树就已经包含我们；{@code /hy reload} 也会再注册一次。
     *
     * @return 成功注册的根命令个数（0 表示失败，原因在 {@link #lastTreeError()}）
     */
    public static int registerCommandTree(Object commands, java.util.Collection<String> roots) {
        try {
            ClassLoader loader = VanillaReflect.loaderFor(commands);
            Object dispatcher = VanillaReflect.call(commands, "getDispatcher");
            Object root = dispatcher == null ? null : VanillaReflect.call(dispatcher, "getRoot");
            if (root == null) {
                lastTreeError = "拿不到 CommandDispatcher#getRoot（版本差异？）";
                return 0;
            }
            Class<?> literal = VanillaReflect.findClass("com.mojang.brigadier.builder.LiteralArgumentBuilder", loader);
            Class<?> required = VanillaReflect.findClass("com.mojang.brigadier.builder.RequiredArgumentBuilder", loader);
            Class<?> stringType = VanillaReflect.findClass("com.mojang.brigadier.arguments.StringArgumentType", loader);
            Class<?> commandIface = VanillaReflect.findClass("com.mojang.brigadier.Command", loader);
            if (literal == null || required == null || stringType == null || commandIface == null) {
                lastTreeError = "找不到 Brigadier 类（loader=" + loader + "）";
                return 0;
            }
            Object executor = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] { commandIface },
                    new CommandInvoker(commands));
            Object greedy = VanillaReflect.callStaticExact(stringType, "greedyString", new Class<?>[0]);
            // ★ 权限门槛：只让"权限等级 ≥ commands.op_level"的来源（OP / 控制台 / RCON）看到这些命令。
            //   原版给每个玩家下发命令树时会按 requires 过滤，所以非 OP 既没有 Tab 补全，
            //   客户端也不会把这些命令发出去；服务端侧 tryHandle 里还有第二道检查。
            java.util.function.Predicate<Object> requirement = new java.util.function.Predicate<Object>() {
                @Override
                public boolean test(Object commandSource) {
                    try {
                        return permissionLevel(commandSource) >= ListManager.getCommandOpLevel();
                    } catch (Throwable t) {
                        return false;
                    }
                }
            };
            int registered = 0;
            for (String name : roots) {
                Object builder = VanillaReflect.callStaticExact(literal, "literal", new Class<?>[] { String.class }, name);
                if (builder == null) {
                    continue;
                }
                builder = VanillaReflect.callMatching(builder, "requires", requirement);
                builder = VanillaReflect.callMatching(builder, "executes", executor);
                for (String verb : subcommandsOf(name)) {
                    Object verbBuilder = VanillaReflect.callStaticExact(literal, "literal",
                            new Class<?>[] { String.class }, verb);
                    if (verbBuilder == null) {
                        continue;
                    }
                    verbBuilder = VanillaReflect.callMatching(verbBuilder, "requires", requirement);
                    verbBuilder = VanillaReflect.callMatching(verbBuilder, "executes", executor);
                    if (greedy != null) {
                        Object argument = VanillaReflect.callStaticExact(required, "argument",
                                new Class<?>[] { String.class,
                                        VanillaReflect.findClass("com.mojang.brigadier.arguments.ArgumentType", loader) },
                                "args", greedy);
                        if (argument != null) {
                            argument = VanillaReflect.callMatching(argument, "requires", requirement);
                            argument = VanillaReflect.callMatching(argument, "executes", executor);
                            verbBuilder = VanillaReflect.callMatching(verbBuilder, "then", argument);
                        }
                    }
                    builder = VanillaReflect.callMatching(builder, "then", verbBuilder);
                }
                Object node = VanillaReflect.callMatching(builder, "build");
                if (node != null) {
                    VanillaReflect.callMatching(root, "addChild", node);
                    registered++;
                }
            }
            commandTreeRegistered = registered > 0;
            lastTreeError = registered > 0 ? null : "没有任何根命令注册成功";
            if (registered > 0) {
                System.out.println("[HyAuth] 已把命令注册进服务端命令树: " + roots
                        + "（仅权限等级 ≥ " + ListManager.getCommandOpLevel()
                        + " 可见可用：OP 与控制台/RCON；子命令带 Tab 补全；执行仍由分发入口切面接管）");
            }
            return registered;
        } catch (Throwable t) {
            lastTreeError = String.valueOf(t);
            commandTreeRegistered = false;
            System.err.println("[HyAuth] 注册命令树失败（只影响 Tab 补全与点击执行，命令本身仍可用）: " + t);
            return 0;
        }
    }

    /** 命令树是否注册成功（决定聊天里的点击用 RunCommand 还是 SuggestCommand）。 */
    public static boolean isCommandTreeRegistered() {
        return commandTreeRegistered;
    }

    /** 上一次注册失败的原因（/hy status 显示）。 */
    public static String lastTreeError() {
        return lastTreeError;
    }

    private static volatile boolean commandTreeRegistered;

    private static volatile String lastTreeError;

    /** 某个根命令下要注册的子命令（只为 Tab 补全与合法性，执行仍走入口切面）。 */
    private static String[] subcommandsOf(String root) {
        if ("lag".equals(root)) {
            return new String[] { "scan", "top", "tp", "here", "on", "off", "stop", "clear", "status", "help" };
        }
        return new String[] { "help", "status", "reload", "list", "ls", "add", "off", "offline",
            "del", "remove", "whois", "id", "clear", "lag" };
    }

    /**
     * 注册进命令树的"执行器"：正常情况下根本不会走到这里（入口切面已经把命令接管了），
     * 万一走到了（例如某个版本入口方法变了），至少给出明确回复而不是报错。
     */
    private static final class CommandInvoker implements java.lang.reflect.InvocationHandler {
        private final Object commands;

        CommandInvoker(Object commands) {
            this.commands = commands;
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            String name = method.getName();
            if ("run".equals(name) && args != null && args.length == 1) {
                Object context = args[0];
                Object source = VanillaReflect.call(context, "getSource");
                Object input = VanillaReflect.call(context, "getInput");
                String line = input instanceof String ? (String) input : null;
                if (line == null || line.isEmpty()) {
                    ChatOut.warn(source, "HyAuth 命令已被接管，但拿不到原始输入（版本差异）；请手工敲 /hy help。");
                    return Integer.valueOf(0);
                }
                return Integer.valueOf(tryHandle(commands, source, line) ? 1 : 0);
            }
            if ("toString".equals(name)) {
                return "HyAuthCommand";
            }
            if ("hashCode".equals(name)) {
                return Integer.valueOf(System.identityHashCode(proxy));
            }
            if ("equals".equals(name)) {
                return Boolean.valueOf(proxy == (args == null || args.length == 0 ? null : args[0]));
            }
            return null;
        }
    }

    /**
     * 把"命令来源"归一化成真正的 {@code CommandSourceStack}。
     *
     * <p>为什么要这一步：控制台/RCON 走 {@code Commands#performPrefixedCommand(CommandSourceStack,String)}，
     * 参数 0 就是命令来源；而<b>游戏内命令</b>走 {@code Commands#performCommand(ParseResults,String)} ——
     * 参数 0 是 ParseResults！真机上因此出现过：命令确实被我们接管了，但权限判定拿不到权限、
     * 发消息也没有通道，结果"游戏里什么都看不见、只有控制台有输出"。
     *
     * <p>{@code ParseResults#getContext()#getSource()} 就是那个 CommandSourceStack（替身与真实版都有）。
     */
    private static Object unwrapSource(Object source) {
        if (source == null || VanillaReflect.method(source.getClass(), "getContext", 0) == null) {
            return source;
        }
        Object context = VanillaReflect.call(source, "getContext");
        if (context == null) {
            return source;
        }
        Object unwrapped = VanillaReflect.call(context, "getSource");
        return unwrapped == null ? source : unwrapped;
    }

    // ==================================================================
    // 分发
    // ==================================================================

    private static void dispatch(String root, String[] tokens, Object commands, Object source) {
        String verb = tokens.length > 1 ? tokens[1].toLowerCase(Locale.ROOT) : "help";
        if ("lag".equals(root) || "lag".equals(verb)) {
            int offset = "lag".equals(root) ? 1 : 2;
            lag(tokens, offset, commands, source, root);
            return;
        }
        if (tokens.length == 1 || "help".equals(verb) || "?".equals(verb)) {
            help(source, root);
        } else if ("status".equals(verb)) {
            status(source, root);
        } else if ("reload".equals(verb)) {
            reload(source, root);
        } else if ("list".equals(verb) || "ls".equals(verb)) {
            list(source, root);
        } else if ("add".equals(verb)) {
            add(tokens, 2, source, root);
        } else if ("off".equals(verb) || "offline".equals(verb)) {
            offline(tokens, 2, source, root);
        } else if ("del".equals(verb) || "remove".equals(verb)) {
            remove(tokens, 2, source, root);
        } else if ("whois".equals(verb) || "id".equals(verb)) {
            whois(tokens, 2, source, root);
        } else if ("clear".equals(verb) || "dig".equals(verb)) {
            clear(tokens, 2, commands, source, root);
        } else {
            String guess = closestSubcommand(verb);
            ChatOut.warn(source, "未知子命令: " + verb + "（/" + root + " help 看用法）"
                    + (guess == null ? "" : " —— 你是不是想用 /" + root + " " + guess + "？"));
        }
    }

    /** 已实现的子命令名（用于"你是不是想用…"的拼写建议）。 */
    private static final String[] KNOWN_SUBCOMMANDS = {
        "help", "status", "reload", "list", "add", "off", "del", "whois", "clear", "lag"
    };

    /** 拼错时给一个最可能的建议：先看前缀/包含关系，再看编辑距离 ≤ 2。 */
    private static String closestSubcommand(String verb) {
        if (verb == null || verb.isEmpty()) {
            return null;
        }
        for (String known : KNOWN_SUBCOMMANDS) {
            if (known.startsWith(verb) || verb.startsWith(known)) {
                return known;
            }
        }
        String best = null;
        int bestDistance = 3;
        for (String known : KNOWN_SUBCOMMANDS) {
            int distance = editDistance(verb, known);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = known;
            }
        }
        return best;
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    // ==================================================================
    // 空置域挖掘（命令版世吞）
    // ==================================================================

    private static void clear(String[] tokens, int at, Object commands, Object source, String root) {
        // 破坏性操作：单独用更高的权限门槛（clear.op_level，默认 3）
        int required = ListManager.getClearOpLevel();
        int level = permissionLevel(source);
        if (level < required) {
            ChatOut.error(source, "空置域挖掘需要权限等级 ≥ " + required + "（当前 " + level
                    + "）：这是不可撤销的大范围删方块操作。");
            return;
        }
        String verb = at < tokens.length ? tokens[at].toLowerCase(Locale.ROOT) : "help";

        // 最常用：直接给数字。/hy clear <x1> <z1> <x2> <z2>，或 <x2> <z2>（角点1=你的站位），或 <边长>
        int[] numbers = parseInts(tokens, at);
        if (numbers.length > 0) {
            if (numbers.length > 4) {
                ChatOut.error(source, "数字最多四个：/" + root + " clear <x1> <z1> <x2> <z2>"
                        + "（两个数字=以你站位为角点1，一个数字=以你为中心的边长）");
                return;
            }
            ClearJob.quickSelect(source, root, numbers);
            return;
        }

        if ("pos1".equals(verb) || "pos2".equals(verb)) {
            int index = verb.endsWith("2") ? 2 : 1;
            Integer explicitX = at + 1 < tokens.length ? parseInt(tokens[at + 1]) : null;
            Integer explicitZ = at + 2 < tokens.length ? parseInt(tokens[at + 2]) : null;
            if (explicitX != null && explicitZ != null) {
                ClearJob.selectAt(index, explicitX.intValue(), explicitZ.intValue(), source, root);
            } else {
                ClearJob.selectHere(index, source, root);
            }
        } else if ("preview".equals(verb) || "plan".equals(verb)) {
            ClearJob.preview(source, root);
        } else if ("start".equals(verb)) {
            boolean confirmed = at + 1 < tokens.length && "confirm".equalsIgnoreCase(tokens[at + 1]);
            ClearJob.start(source, commands, root, confirmed);
        } else if ("go".equals(verb)) {
            // 便捷别名：预演之后一条 /hy clear go 就开挖
            ClearJob.start(source, commands, root, true);
        } else if ("dim".equals(verb) || "dimension".equals(verb)) {
            ClearJob.selectDimension(source, at + 1 < tokens.length ? tokens[at + 1] : null, root);
        } else if ("stop".equals(verb)) {
            ClearJob.stop(source, root);
        } else if ("status".equals(verb)) {
            ClearJob.status(source, root);
        } else if ("reset".equals(verb)) {
            ClearJob.reset(source, root);
        } else {
            clearHelp(source, root);
        }
    }

    /** 把从 {@code from} 开始的所有词解析成整数数组；只要有一个不是整数就返回空数组。 */
    private static int[] parseInts(String[] tokens, int from) {
        if (tokens == null || from >= tokens.length) {
            return new int[0];
        }
        int[] values = new int[tokens.length - from];
        for (int i = from; i < tokens.length; i++) {
            Integer parsed = parseInt(tokens[i]);
            if (parsed == null) {
                return new int[0];
            }
            values[i - from] = parsed.intValue();
        }
        return values;
    }

    private static void clearHelp(Object source, String root) {
        ChatOut.head(source, "===== 空置域挖掘（命令版世吞）=====");
        ChatOut.line(source, "  最常用（选完直接给预演，再一条 go 就开挖）:");
        ChatOut.line(source, "    /" + root + " clear <x1> <z1> <x2> <z2>   四个数字＝对角两点，例如 /" + root
                + " clear -576 -66 -193 -448");
        ChatOut.line(source, "    /" + root + " clear <x2> <z2>             两个数字＝以你当前站位为角点 1");
        ChatOut.line(source, "    /" + root + " clear <边长>                 一个数字＝以你为中心挖这么大，例如 /" + root
                + " clear 100");
        ChatOut.line(source, "    /" + root + " clear go                   确认开始（= start confirm）");
        ChatOut.line(source, "  也可以一个角一个角地选（先站到对角两点上）:");
        ChatOut.line(source, "    /" + root + " clear pos1 | pos2          不带坐标＝取当前所站位置");
        ChatOut.line(source, "    /" + root + " clear dim the_nether       控制台/RCON 指定要挖的维度");
        ChatOut.line(source, "  其它:");
        ChatOut.line(source, "    /" + root + " clear preview              单独再预演一次（不动世界）");
        ChatOut.line(source, "    /" + root + " clear status | stop | reset  进度 / 中止 / 清空选区");
        ChatOut.note(source, "三阶段: 外圈上方清空 → 四边防爆沟填 " + ListManager.getClearTrenchBlock()
                + " → 内圈清空。挖完的区域从 y=" + ListManager.getClearMinY() + " 一直空到 y="
                + (ListManager.getClearTopY() + ListManager.getClearAboveHeight() - 1)
                + "，等于一个空置域。");
        ChatOut.note(source, "效果与代价: 不掉落物、没有爆炸特效；挖过的方块不可恢复（先备份存档或先在测试区试一遍）。");
    }

    // ==================================================================
    // 勘探组
    // ==================================================================

    private static void lag(String[] tokens, int offset, Object commands, Object source, String root) {
        String verb = tokens.length > offset ? tokens[offset].toLowerCase(Locale.ROOT) : "";
        // 便捷形式：/hy lag（无参）＝直接出报告；/hy lag 30 ＝采样 30 秒
        Integer shortcutSeconds = verb.isEmpty() ? null : parseInt(verb);
        if (shortcutSeconds != null) {
            scan(source, root, shortcutSeconds.intValue());
            return;
        }
        if (verb.isEmpty()) {
            ChunkLagSampler.Analysis analysis = analyzeCurrentWindow();
            ChunkLagSampler.setLastAnalysis(analysis);
            ChatOut.head(source, "===== HyAuth 区块卡顿勘探 =====");
            LagReport.render(source, analysis, root);
            return;
        }
        if ("scan".equals(verb)) {
            int seconds = ListManager.getLagScanDefaultSeconds();
            if (tokens.length > offset + 1) {
                Integer parsed = parseInt(tokens[offset + 1]);
                if (parsed == null) {
                    ChatOut.error(source, "秒数必须是整数，例如 /" + root + " lag scan 30（也可以直接 /" + root + " lag 30）");
                    return;
                }
                seconds = parsed;
            }
            scan(source, root, seconds);
        } else if ("list".equals(verb) || "report".equals(verb)) {
            ChunkLagSampler.Analysis analysis = analyzeCurrentWindow();
            ChunkLagSampler.setLastAnalysis(analysis);
            ChatOut.head(source, "===== HyAuth 区块卡顿勘探 =====");
            LagReport.render(source, analysis, root);
        } else if ("top".equals(verb)) {
            int limit = ListManager.getLagReportTop();
            if (tokens.length > offset + 1) {
                Integer parsed = parseInt(tokens[offset + 1]);
                if (parsed == null) {
                    ChatOut.error(source, "用法: /" + root + " lag top [条数]");
                    return;
                }
                limit = Math.max(1, Math.min(200, parsed));
            }
            ChunkLagSampler.Analysis analysis = analyzeCurrentWindow();
            ChunkLagSampler.setLastAnalysis(analysis);
            LagReport.renderTop(source, analysis, limit, root);
        } else if ("tp".equals(verb) || "teleport".equals(verb)) {
            teleport(tokens, offset + 1, commands, source, root);
        } else if ("here".equals(verb)) {
            LagReport.renderHere(source, root);
        } else if ("clear".equals(verb)) {
            ChunkLagSampler.clearAll();
            ChatOut.ok(source, "已清空常驻窗口与扫描窗口的累计数据（跟踪区块数归零）。");
        } else if ("on".equals(verb)) {
            ChunkLagSampler.setResidentEnabled(true);
            ChatOut.ok(source, "常驻区块卡顿采样已开启（开销：每区块/每实体 tick 两次 nanoTime + 一次无装箱哈希查找）。");
        } else if ("off".equals(verb)) {
            ChunkLagSampler.setResidentEnabled(false);
            ChatOut.warn(source, "常驻区块卡顿采样已关闭；按需扫描仍可用：/" + root + " lag scan 30。");
        } else if ("stop".equals(verb)) {
            ChunkLagSampler.Window window = ChunkLagSampler.stopScan();
            if (window == null) {
                ChatOut.warn(source, "当前没有正在进行的按需扫描（常驻采样要关的话用 /" + root + " lag off）。");
                return;
            }
            ChunkLagSampler.Analysis analysis = ChunkLagSampler.analyze(
                    "按需扫描（手动停止于 " + LagReport.format(window.getElapsedSeconds()) + "s）",
                    window, ListManager.getLagReportTop(), ListManager.getLagMaxClusters());
            ChunkLagSampler.setLastAnalysis(analysis);
            ChatOut.head(source, "===== HyAuth 区块卡顿勘探 · 手动停止 =====");
            LagReport.render(source, analysis, root);
        } else if ("status".equals(verb)) {
            lagStatus(source, root);
        } else {
            lagHelp(source, root);
        }
    }

    /** 开始一次按需扫描（scan 与"直接给秒数"两条路共用）。 */
    private static void scan(Object source, String root, int seconds) {
        if (seconds < 1 || seconds > 3600) {
            ChatOut.error(source, "采样秒数需在 1~3600 之间（当前 " + seconds + "）。");
            return;
        }
        if (!ChunkLagSampler.startScan(source, seconds, root)) {
            ChatOut.warn(source, "已经有一次按需扫描在进行中：/" + root + " lag stop 可以先停掉它。");
            return;
        }
        ChatOut.ok(source, "开始区块卡顿勘探：采样 " + seconds + " 秒（覆盖当前所有正在 tick 的区块）。");
        ChatOut.note(source, "到点会自动把报告发给你；想提前结束用 /" + root + " lag stop，想随时看当前累计用 /"
                + root + " lag。（也支持 /" + root + " lag scan " + seconds + " 这种完整写法）");
    }

    private static ChunkLagSampler.Analysis analyzeCurrentWindow() {
        boolean scanning = ChunkLagSampler.isScanning();
        ChunkLagSampler.Window window = scanning
                ? ChunkLagSampler.scanWindow() : ChunkLagSampler.residentWindow();
        String title = scanning
                ? "按需扫描进行中 " + LagReport.format(window.getElapsedSeconds()) + "/" + window.getPlannedSeconds() + "s"
                : "常驻窗口（最近 " + LagReport.format(window.getElapsedSeconds()) + "s）";
        return ChunkLagSampler.analyze(title, window,
                ListManager.getLagReportTop(), ListManager.getLagMaxClusters());
    }

    private static void teleport(String[] tokens, int at, Object commands, Object source, String root) {
        if (tokens.length <= at) {
            ChatOut.warn(source, "用法: /" + root + " lag tp <序号>  或  /" + root + " lag tp <x> <z> [玩家名]");
            return;
        }
        ChunkLagSampler.Analysis analysis = ChunkLagSampler.getLastAnalysis();
        String first = tokens[at];
        if (tokens.length - at == 1) {
            Integer index = parseInt(first);
            if (index == null) {
                ChatOut.error(source, "序号必须是整数（例如 /" + root + " lag tp 1）；两个数字的形式是方块坐标。");
                return;
            }
            if (analysis == null || analysis.clusters.isEmpty()) {
                ChatOut.warn(source, "还没有可用的报告：先 /" + root + " lag list（或 scan）再传送。");
                return;
            }
            if (index < 1 || index > analysis.clusters.size()) {
                ChatOut.error(source, "序号需在 1~" + analysis.clusters.size() + " 之间（当前报告共 "
                        + analysis.clusters.size() + " 组异常范围）。");
                return;
            }
            ChunkLagSampler.Cluster cluster = analysis.clusters.get(index - 1);
            LagReport.teleport(commands, source, cluster.centerBlockX(), cluster.centerBlockZ(),
                    cluster.dimension, null, root);
            return;
        }

        Integer blockX = parseInt(first);
        Integer blockZ = parseInt(tokens[at + 1]);
        if (blockX == null || blockZ == null) {
            ChatOut.error(source, "用法: /" + root + " lag tp <x> <z> [玩家名]（x/z 是方块坐标）");
            return;
        }
        String target = tokens.length > at + 2 ? tokens[at + 2] : null;
        String dimension = dimensionOfCoordinate(analysis, blockX >> 4, blockZ >> 4);
        LagReport.teleport(commands, source, blockX, blockZ, dimension, target, root);
    }

    /** 从最近一次报告里找出该区块属于哪个维度（找不到返回 {@code null} = 沿用执行者当前维度）。 */
    private static String dimensionOfCoordinate(ChunkLagSampler.Analysis analysis, int chunkX, int chunkZ) {
        if (analysis == null) {
            return null;
        }
        for (ChunkLagSampler.Cluster cluster : analysis.clusters) {
            if (chunkX >= cluster.minX && chunkX <= cluster.maxX
                    && chunkZ >= cluster.minZ && chunkZ <= cluster.maxZ) {
                return cluster.dimension;
            }
        }
        for (ChunkLagSampler.ChunkRow row : analysis.top) {
            if (row.chunkX == chunkX && row.chunkZ == chunkZ) {
                return row.dimension;
            }
        }
        return null;
    }

    private static void lagStatus(Object source, String root) {
        ChunkLagSampler.Window resident = ChunkLagSampler.residentWindow();
        ChatOut.head(source, "===== 区块卡顿勘探状态 =====");
        ChatOut.line(source, "常驻采样: " + (ChunkLagSampler.isResidentEnabled() ? "开" : "关")
                + "（/" + root + " lag on|off）· 按需扫描: "
                + (ChunkLagSampler.isScanning()
                ? "进行中 " + LagReport.format(ChunkLagSampler.scanWindow().getElapsedSeconds()) + "/"
                + ChunkLagSampler.scanWindow().getPlannedSeconds() + "s"
                : "未开始（/" + root + " lag scan 30）"));
        ChatOut.line(source, "常驻窗口: 跟踪区块 " + resident.getTrackedChunks() + " 个 · " + resident.getDimensionCount()
                + " 个维度 · MSPT 均值 " + LagReport.format(resident.getServerAvgMs()) + "ms（近期 "
                + LagReport.format(resident.getServerEwmaMs()) + "ms，峰值 "
                + LagReport.format(resident.getServerPeakMs()) + "ms）");
        ChatOut.line(source, "异常判据: 合计 ≥ " + LagReport.format(ListManager.getLagFlagThresholdMs())
                + "ms 或 ≥ 中位数 " + LagReport.format(ListManager.getLagRelativeFactor()) + " 倍 · 跟踪上限 "
                + ListManager.getLagMaxChunks() + " 区块 · 方块实体口径: "
                + (resident.blockEntitiesNested() ? "已包含在区块 tick 内（合计不重复计）" : "独立计入合计"));
        ChatOut.line(source, "切面能力探测（缺哪个版本差异项，对应类别就是 0，其它类别照常）：");
        capability(source, "区块 tick", "net.minecraft.server.level.ServerLevel", "tickChunk", 2);
        capability(source, "实体（非乘客）", "net.minecraft.server.level.ServerLevel", "tickNonPassenger", 1);
        capability(source, "实体（乘客）", "net.minecraft.server.level.ServerLevel", "tickPassenger", 2);
        // 方块实体有两个时代的入口：26.x 起改成 TickingBlockEntity#tick（逐个方块实体），
        // 老版本是 LevelChunk#tickBlockEntities（按区块）。两个都报，避免"实际挂上了却显示未找到"。
        boolean tickerHook = capability(source, "方块实体（26.x：TickingBlockEntity#tick）",
                "net.minecraft.world.level.block.entity.TickingBlockEntity", "tick", 0);
        boolean legacyHook = capability(source, "方块实体（旧版：LevelChunk#tickBlockEntities）",
                "net.minecraft.world.level.chunk.LevelChunk", "tickBlockEntities", 0);
        if (!tickerHook && !legacyHook) {
            ChatOut.note(source, "      ↑ 两条都没有 ⇒ 方块实体分项会一直是 0（其它分项照常）");
        }
        capability(source, "整服 MSPT", "net.minecraft.server.MinecraftServer", "tickServer", 1);
        capability(source, "命令接管", "net.minecraft.commands.Commands", "performPrefixedCommand", 2);
        capability(source, "命令接管（旧名）", "net.minecraft.commands.Commands", "performCommand", 2);
        // 权限：两代 API 都探，报告哪一种可用（26.3 删掉了 hasPermission(int)）
        boolean oldPerm = VanillaReflect.method(VanillaReflect.findClass(
                "net.minecraft.commands.CommandSourceStack", VanillaReflect.loaderFor(source)),
                "hasPermission", 1) != null;
        boolean newPerm = VanillaReflect.findClass("net.minecraft.server.permissions.PermissionSet",
                VanillaReflect.loaderFor(source)) != null
                && VanillaReflect.method(VanillaReflect.findClass(
                        "net.minecraft.commands.CommandSourceStack", VanillaReflect.loaderFor(source)),
                        "permissions", 0) != null;
        ChatOut.note(source, "  · 权限判定: " + (oldPerm ? "旧 API hasPermission(int)" : "")
                + (oldPerm && newPerm ? " + " : "")
                + (newPerm ? "26.x 权限模型 permissions()/PermissionSet（COMMANDS_*）" : "")
                + (!oldPerm && !newPerm ? "都没找到（只会放行控制台/RCON）" : ""));
    }

    private static boolean capability(Object source, String label, String className, String method, int arity) {
        Class<?> type = VanillaReflect.findClass(className, VanillaReflect.loaderFor(source));
        if (type == null) {
            ChatOut.note(source, "  · " + label + ": 未找到 " + className + "（该版本可能改名或混淆）");
            return false;
        }
        boolean ok = VanillaReflect.method(type, method, arity) != null;
        ChatOut.note(source, "  · " + label + ": " + (ok ? "可挂载（" + method + "）"
                : "未找到 " + method + "/" + arity + "（该类别将为 0）"));
        return ok;
    }

    // ==================================================================
    // 名单管理
    //
    //   加人：/hy add ex  <名字…>      加入 LittleSkin 外置名单
    //         /hy add off <名字…>      加入离线名单：先查户口，有历史身份就沿用；没有才发新 UUIDv7（并查重）
    //   改离线：/hy off <名字…> force   给它换一个全新的 UUIDv7（明确要"换人"时用）
    //   删除：/hy del <名字…>          按名字删（名字唯一，不分外置/离线）
    //
    //   批量：名字之间用空格隔开即可。
    //   没有"手填 UUID"这种命令：沿用是自动的（查户口查得到），换新用 force —— 这两条已经覆盖了全部情况。
    // ==================================================================

    private static void add(String[] tokens, int at, Object source, String root) {
        if (at >= tokens.length) {
            printAddHelp(source, root);
            return;
        }
        String kind = tokens[at].toLowerCase(Locale.ROOT);
        List<String> rest = namesFrom(tokens, at + 1);
        if (isExternalKind(kind)) {
            if (rest.isEmpty()) {
                ChatOut.warn(source, "用法: /" + root + " add ex <名字> [更多名字…]   例: /" + root + " add ex Steve Alex");
                return;
            }
            addLittleSkinBatch(rest, source, root);
        } else if (isOfflineKind(kind)) {
            if (rest.isEmpty()) {
                ChatOut.warn(source, "用法: /" + root + " add off <名字> [更多名字…]   例: /" + root + " add off Steve Alex");
                return;
            }
            handleOfflineNames(rest, false, source, root);
        } else {
            ChatOut.error(source, "要说明加哪一种：/" + root + " add ex <名字>（外置）或 /" + root
                    + " add off <名字>（离线）。");
            printAddHelp(source, root);
        }
    }

    private static void printAddHelp(Object source, String root) {
        ChatOut.head(source, "===== 加入名单 =====");
        ChatOut.line(source, "  /" + root + " add ex  <名字> [名字…]     加入 LittleSkin 外置名单（玩家用外置登录进服）");
        ChatOut.line(source, "  /" + root + " add off <名字> [名字…]     加入离线名单：先查户口，"
                + "有历史身份（usercache / 存档 / 原版离线算法）就沿用，没有才发新 UUIDv7 并查重");
        ChatOut.note(source, "批量＝名字之间用空格隔开，例如 /" + root + " add ex A B C、/" + root + " add off D E。");
        ChatOut.note(source, "要给某个已有玩家换一个全新身份：/" + root + " off <名字> force（老存档的背包/成就不跟过去）。");
    }

    private static boolean isExternalKind(String kind) {
        return "ex".equals(kind) || "ext".equals(kind) || "external".equals(kind)
                || "littleskin".equals(kind) || "skin".equals(kind) || "外置".equals(kind);
    }

    private static boolean isOfflineKind(String kind) {
        return "off".equals(kind) || "offline".equals(kind) || "离线".equals(kind);
    }

    /** 批量加入 LittleSkin 外置名单。 */
    private static void addLittleSkinBatch(List<String> names, Object source, String root) {
        int ok = 0;
        int failed = 0;
        for (String name : names) {
            String problem = ExistingUuidScan.validateName(name);
            if (problem != null) {
                ChatOut.error(source, problem);
                failed++;
                continue;
            }
            ConfigWriter.Result result = ConfigWriter.addLittleSkin(name);
            if (result.isOk()) {
                ChatOut.ok(source, result.getMessage());
                ok++;
            } else {
                ChatOut.error(source, result.getMessage());
                failed++;
            }
        }
        summarize(names.size(), ok, 0, failed, source);
    }

    private static void summarize(int total, int ok, int blocked, int failed, Object source) {
        if (total <= 1) {
            return;
        }
        StringBuilder line = new StringBuilder("批量结果（共 " + total + " 个）: 成功 " + ok + " 个");
        if (blocked > 0) {
            line.append("，因已有身份被拦下 ").append(blocked).append(" 个");
        }
        if (failed > 0) {
            line.append("，失败 ").append(failed).append(" 个");
        }
        ChatOut.line(source, line.toString());
    }

    /** 从 tokens[at..] 收集名字（保持管理员写法）。 */
    private static List<String> namesFrom(String[] tokens, int at) {
        List<String> names = new ArrayList<String>();
        if (tokens == null) {
            return names;
        }
        for (int i = at; i < tokens.length; i++) {
            String name = tokens[i] == null ? null : tokens[i].trim();
            if (name != null && !name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * 离线名单的入口：{@code off <名字…> [uuid]}。
     *
     * <p>不带 uuid ＝ 沿用该名字在服务端上已有的身份（`usercache.json` / 存档 / 原版离线算法都算），
     * 没有历史身份才发新的 UUIDv7（并查重）；带 {@code force} ＝ 明确给它换一个全新身份。
     */
    private static void offline(String[] tokens, int at, Object source, String root) {
        List<String> names = namesFrom(tokens, at);
        if (names.isEmpty()) {
            ChatOut.head(source, "===== 离线名单 =====");
            ChatOut.line(source, "  /" + root + " add off <名字> [名字…]      加入离线名单（有历史身份就沿用，没有才发新 UUIDv7）");
            ChatOut.line(source, "  /" + root + " off <名字> [名字…] force    给它换一个全新的 UUIDv7（明确要换人时用）");
            ChatOut.note(source, "沿用是自动的：只要服务端上查得到这个名字的历史身份（usercache / 存档 / 原版离线算法），"
                    + "就不用你手填 UUID。");
            ChatOut.note(source, "批量＝名字之间用空格隔开：/" + root + " add off A B、/" + root + " off A B force。");
            return;
        }
        boolean force = false;
        if (names.size() >= 2) {
            String last = names.get(names.size() - 1);
            if ("force".equalsIgnoreCase(last) || "new".equalsIgnoreCase(last)) {
                force = true;
                names.remove(names.size() - 1);
            }
        }
        if (names.isEmpty()) {
            ChatOut.warn(source, "只给了 force，没有名字：/" + root + " off <名字> force");
            return;
        }
        handleOfflineNames(names, force, source, root);
    }

    /** 离线名单的实际处理（add off 与 off 两条路共用）。 */
    private static void handleOfflineNames(List<String> names, boolean force, Object source, String root) {
        int ok = 0;
        int failed = 0;
        for (String name : names) {
            int status = offlineOne(name, force, source, root);
            if (status == 0) {
                ok++;
            } else {
                failed++;
            }
        }
        if (names.size() > 1) {
            ChatOut.line(source, "批量结果（共 " + names.size() + " 个）: 成功 " + ok + " 个"
                    + (failed > 0 ? "，失败 " + failed + " 个" : ""));
        }
    }

    /**
     * 处理单个名字。
     *
     * @return 0 = 已写入；2 = 失败
     */
    private static int offlineOne(String name, boolean force, Object source, String root) {
        String problem = ExistingUuidScan.validateName(name);
        if (problem != null) {
            ChatOut.error(source, problem);
            return 2;
        }

        // ★ 先查户口：扫配置文件 + usercache.json + 存档 playerdata/stats/advancements + 原版离线算法
        ExistingUuidScan.Report scan = ExistingUuidScan.scan(name);
        UUID uuid;
        if (force) {
            // 明确要求换一个全新身份：生成新的 UUIDv7 并查重（老存档仍挂在旧 UUID 下，必须说清）
            uuid = UuidV7.generate();
            for (int attempt = 0; attempt < 8 && scan.isKnown(uuid); attempt++) {
                uuid = UuidV7.generate();
            }
            if (scan.isKnown(uuid)) {
                ChatOut.error(source, "连续生成的 UUIDv7 都与已知 UUID 撞车（概率极低），已放弃。");
                return 2;
            }
            if (scan.getExistingUuid() != null) {
                ChatOut.warn(source, "按 force 给 " + name + " 换全新身份：旧 UUID " + scan.getExistingUuid()
                        + " → 新 UUID " + uuid + "。老存档的背包/成就/统计仍挂在旧 UUID 下，不会跟过来。");
            }
        } else if (scan.getExistingUuid() != null) {
            // 已有身份 → 直接沿用。这既是"先扫已有 UUID"的用途，也是保住背包/成就/统计的唯一做法。
            uuid = scan.getExistingUuid();
            ChatOut.note(source, "名字 " + name + " 在服务端上已有身份 " + uuid + "（来源: "
                    + scan.getExistingSource() + "）→ 已沿用这个 UUID（保住背包/成就）。");
        } else {
            uuid = UuidV7.generate();
            for (int attempt = 0; attempt < 8 && scan.isKnown(uuid); attempt++) {
                uuid = UuidV7.generate();
            }
            if (scan.isKnown(uuid)) {
                ChatOut.error(source, "连续生成的 UUIDv7 都与已知 UUID 撞车（概率极低），已放弃。");
                return 2;
            }
        }

        ConfigWriter.Result result = ConfigWriter.addOffline(name, uuid);
        if (!result.isOk()) {
            ChatOut.error(source, result.getMessage());
            return 2;
        }
        ChatOut.ok(source, result.getMessage());
        ChatOut.line(source, "UUID: " + uuid + "（version=" + UuidV7.version(uuid) + "，variant=" + UuidV7.variant(uuid)
                + "，生成时间 " + UuidV7.describeTime(uuid) + "）");
        ChatOut.line(source, "查重依据: 已知 UUID 共 " + scan.knownCount() + " 个（离线名单 / usercache.json / 存档 playerdata·stats·advancements）");
        if (scan.hasVanillaOfflineData()) {
            ChatOut.warn(source, "存档里已存在原版离线 UUID（" + scan.getVanillaOfflineUuid()
                    + "）的玩家数据；上面沿用的就是能保住背包/成就的那个身份。");
        }
        for (String note : scan.getNotes()) {
            ChatOut.note(source, "  · " + note);
        }
        ChatOut.note(source, "该玩家下次连接时按这个 UUID 进入（需要客户端用离线登录、名字与配置一致；"
                + "white-list 开着的话记得把该 UUID 加进 whitelist.json）。");
        return 0;
    }

    private static void remove(String[] tokens, int at, Object source, String root) {
        List<String> names = namesFrom(tokens, at);
        if (names.isEmpty()) {
            ChatOut.warn(source, "用法: /" + root + " del <名字> [更多名字…]   例: /" + root + " del Steve Alex");
            ChatOut.note(source, "按名字删除即可：名字在服务端上是唯一的，不用管它在哪个名单里。");
            return;
        }
        int ok = 0;
        int failed = 0;
        for (String name : names) {
            ConfigWriter.Result result = ConfigWriter.remove(name);
            if (result.isOk()) {
                ChatOut.ok(source, result.getMessage());
                ok++;
            } else {
                ChatOut.error(source, result.getMessage());
                failed++;
            }
        }
        if (names.size() > 1) {
            ChatOut.line(source, "批量结果: 成功移除 " + ok + " 个" + (failed > 0 ? "，没找到/失败 " + failed + " 个" : "")
                    + "（共 " + names.size() + " 个）");
        }
    }

    private static void whois(String[] tokens, int at, Object source, String root) {
        String name = at < tokens.length ? tokens[at] : null;
        if (name == null) {
            ChatOut.warn(source, "用法: /" + root + " whois <玩家名>");
            return;
        }
        ExistingUuidScan.Report report = ExistingUuidScan.scan(name);
        ChatOut.head(source, "===== 名字身份扫描: " + name + " =====");
        if (report.getExistingUuid() != null) {
            ChatOut.warn(source, "已有身份: " + report.getExistingUuid() + "（来源: " + report.getExistingSource() + "）");
        } else {
            ChatOut.ok(source, "没有找到这个名字的已有身份（离线名单与 usercache.json 里都没有）。");
        }
        ChatOut.line(source, "原版离线算法会算出: " + report.getVanillaOfflineUuid()
                + (report.hasVanillaOfflineData() ? "  ★ 存档里已有该 UUID 的玩家数据" : "（存档里没有对应玩家数据）"));
        ChatOut.line(source, "已扫描到的已知 UUID 共 " + report.knownCount() + " 个"
                + "（离线名单 + usercache.json + " + ExistingUuidScan.levelName()
                + "/playerdata·stats·advancements）");
        for (String note : report.getNotes()) {
            ChatOut.note(source, "  · " + note);
        }
        ChatOut.note(source, "下一步: /" + root + " off " + name + " [uuid|force]（不带参数=自动生成 UUIDv7 并查重）");
    }

    private static void list(Object source, String root) {
        ChatOut.head(source, "===== HyAuth 名单 =====");
        Set<String> littleskin = ListManager.getLittleSkinPlayers();
        if (littleskin.isEmpty()) {
            ChatOut.line(source, "LittleSkin 外置名单: （空）");
        } else {
            StringBuilder builder = new StringBuilder();
            for (String name : littleskin) {
                if (builder.length() > 0) {
                    builder.append(", ");
                }
                builder.append(name);
            }
            ChatOut.line(source, "LittleSkin 外置名单（" + littleskin.size() + " 人）: " + builder);
        }
        Map<String, OfflinePlayer> offline = ListManager.getOfflinePlayers();
        ChatOut.line(source, "离线名单（" + offline.size() + " 人）:");
        if (offline.isEmpty()) {
            ChatOut.note(source, "  （空）");
        } else {
            for (OfflinePlayer player : offline.values()) {
                UUID uuid = player.getUuid();
                String extra = UuidV7.version(uuid) == 7 ? "（UUIDv7，生成于 " + UuidV7.describeTime(uuid) + "）" : "";
                ChatOut.note(source, "  " + player.getName() + "  ->  " + uuid + extra);
            }
        }
        ChatOut.note(source, "增删用 /" + root + " add|off|del，改完自动热重载（不用重启服务端）。");
    }

    private static void reload(Object source, String root) {
        ListManager.reload();
        ChunkLagSampler.setResidentEnabled(ListManager.isLagResident());
        // 命令根可能被改了：重新注册一次命令树（addChild 对同名节点是合并，重复注册无害）
        Object server = VanillaReflect.call(source, "getServer");
        Object commands = server == null ? null : VanillaReflect.call(server, "getCommands");
        int registered = commands == null ? 0 : registerCommandTree(commands, ListManager.getCommandRoots());
        ChatOut.ok(source, "已重新读取 littleskin_config.json，并把 chunk_lag.resident = "
                + ListManager.isLagResident() + " 同步到常驻采样开关。");
        ChatOut.note(source, registered > 0
                ? "命令树已按新的 roots 重新注册（" + ListManager.describeRoots() + "）。"
                : "命令树本次未注册成功（只影响 Tab 补全与点击执行）：" + lastTreeError);
    }

    // ==================================================================
    // 状态与帮助
    // ==================================================================

    private static void status(Object source, String root) {
        ChatOut.head(source, "===== HyAuth 状态 =====");
        ChatOut.line(source, "版本: " + version() + " · Java " + System.getProperty("java.version")
                + " · 命令根: " + ListManager.describeRoots() + "（需权限 ≥ " + ListManager.getCommandOpLevel() + "）");
        ChatOut.line(source, "名单: LittleSkin " + ListManager.whitelistSize() + " 人 · 离线 "
                + ListManager.offlinePlayerCount() + " 人 · API " + ListManager.getApiRoot());
        ChatOut.line(source, "配置文件: " + ListManager.FILE_NAME + " · 版本 v" + ListManager.getConfigVersion()
                + "（本插件支持 v" + ListManager.CONFIG_VERSION + "）；低版本会在加载时自动补齐默认项，账号信息不动");
        ChatOut.line(source, "命令树注册: " + (commandTreeRegistered
                ? "已注册（Tab 补全能补出我们的命令，聊天里点一下可直接执行）"
                : "未注册（" + (lastTreeError == null ? "尚未触发" : lastTreeError)
                        + "）—— 只影响补全与点击，命令本身仍可用"));
        ChatOut.line(source, "聊天输出: " + ChatOut.describeSendPath() + " · 组件构造: " + ChatOut.componentAvailability());
        lagStatus(source, root);
    }

    private static void help(Object source, String root) {
        ChatOut.head(source, "===== HyAuth 管理员命令（需要权限等级 ≥ " + ListManager.getCommandOpLevel() + "）=====");
        ChatOut.line(source, "  —— 名单 ——（批量＝名字用空格隔开，例: /" + root + " add off A B C）");
        ChatOut.line(source, "  /" + root + " add ex  <名字> [名字…]     加入 LittleSkin 外置名单");
        ChatOut.line(source, "  /" + root + " add off <名字> [名字…]     加入离线名单：有历史身份就沿用，没有才发新 UUIDv7（先查重）");
        ChatOut.line(source, "  /" + root + " off <名字> [名字…] force   给已有玩家换一个全新的 UUIDv7（老存档不跟过去）");
        ChatOut.line(source, "  /" + root + " del <名字> [名字…]         按名字删除（名字唯一，不分外置/离线）");
        ChatOut.line(source, "  /" + root + " list | ls · whois <名字> | id  查看两个名单 / 查名字的历史身份");
        ChatOut.line(source, "  /" + root + " reload · status            重读配置 / 运行状态与切面能力探测");
        ChatOut.head(source, "  —— 区块卡顿勘探（服务端侧，无需客户端 Mod）——");
        ChatOut.line(source, "  /" + root + " lag                      直接看异常区块坐标范围（最常用，点一下就能传送）");
        ChatOut.line(source, "  /" + root + " lag 30                   采样 30 秒后自动出报告（= lag scan 30）");
        ChatOut.line(source, "  /" + root + " lag top [N]              单区块耗时榜（默认 " + ListManager.getLagReportTop() + "）");
        ChatOut.line(source, "  /" + root + " lag tp <序号> | <x> <z>  传送到某组异常范围 / 某个方块坐标（可加玩家名）");
        ChatOut.line(source, "  /" + root + " lag here                 看脚下这块的耗时分项");
        ChatOut.line(source, "  /" + root + " lag on|off|stop|clear    常驻采样开关 / 停止按需扫描 / 清空累计");
        ChatOut.head(source, "  —— 空置域挖掘（命令版世吞，需权限 ≥ " + ListManager.getClearOpLevel() + "）——");
        ChatOut.line(source, "  /" + root + " clear <x1> <z1> <x2> <z2>  对角两点直接开挖（最常用），选完给预演");
        ChatOut.line(source, "  /" + root + " clear <x2> <z2> | <边长>  以你站位为角点1 / 以你为中心的方形");
        ChatOut.line(source, "  /" + root + " clear go | stop | status   确认开始 / 中止 / 进度");
        ChatOut.line(source, "  /" + root + " clear pos1|pos2 | dim | preview | reset  逐点选 / 指定维度 / 预演 / 清空");
        ChatOut.note(source, "提示: /lag <子命令> 是 /" + root + " lag 的快捷方式；报告里的每一行都能点一下直接传送。");
    }

    private static void lagHelp(Object source, String root) {
        ChatOut.head(source, "===== 区块卡顿勘探用法 =====");
        ChatOut.line(source, "  /" + root + " lag               直接看异常区块坐标范围（最常用）");
        ChatOut.line(source, "  /" + root + " lag 30            采样 30 秒后自动出报告（= lag scan 30）");
        ChatOut.line(source, "  /" + root + " lag top [N]       单区块耗时榜");
        ChatOut.line(source, "  /" + root + " lag tp <序号>     传送到报告里第 N 组范围的中心");
        ChatOut.line(source, "  /" + root + " lag tp <x> <z>    传送到指定方块坐标（报告里显示的坐标）");
        ChatOut.line(source, "  /" + root + " lag here          看脚下这块的耗时分项");
        ChatOut.line(source, "  /" + root + " lag on|off        常驻采样开关（默认开，随时可看）");
        ChatOut.line(source, "  /" + root + " lag stop          提前结束按需扫描并出报告");
        ChatOut.line(source, "  /" + root + " lag clear         清空累计数据");
        ChatOut.line(source, "  /" + root + " lag status        采样状态 + 切面能力探测");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * 读取命令来源的原版权限等级（0~4）。控制台 / RCON 天然是 4。
     *
     * <p>两大代的 API 完全不同，这里两条都试：
     * <ul>
     *   <li>≤ 1.21.x：{@code CommandSourceStack#hasPermission(int)} → boolean；</li>
     *   <li>26.x：{@code CommandSourceStack#permissions()} → {@code PermissionSet}，再用
     *       {@code Permissions.COMMANDS_MODERATOR / GAMEMASTER / ADMIN / OWNER} 反推等级
     *       —— 26.3 实测已经删掉了 {@code hasPermission(int)}，只走旧路径会把所有玩家都判成 0 级
     *       （于是"命令挂上了，但谁都用不了"）。</li>
     * </ul>
     */
    private static int permissionLevel(Object source) {
        if (source == null) {
            return 0;
        }
        for (int level = 4; level >= 0; level--) {
            Object result = VanillaReflect.callMatching(source, "hasPermission", Integer.valueOf(level));
            if (result instanceof Boolean) {
                if (((Boolean) result).booleanValue()) {
                    return level;
                }
            } else {
                break; // 该版本没有 hasPermission(int)，改走下面的 PermissionSet 路径
            }
        }
        Object permissionSet = VanillaReflect.callMatching(source, "permissions");
        if (permissionSet != null) {
            for (int level = 4; level >= 1; level--) {
                Object permission = commandPermission(source, level);
                if (permission != null && Boolean.TRUE.equals(
                        VanillaReflect.callMatching(permissionSet, "hasPermission", permission))) {
                    return level;
                }
            }
            return 0;
        }
        // 两条路都不通（更远的未来又换 API）：保守处理，只放行控制台/RCON
        Object entity = VanillaReflect.call(source, "getEntity");
        return entity == null ? 4 : 0;
    }

    /** {@code Permissions.COMMANDS_*} → 原版等级（1 moderator / 2 gamemaster / 3 admin / 4 owner）。 */
    private static Object commandPermission(Object source, int level) {
        String field;
        switch (level) {
            case 4:
                field = "COMMANDS_OWNER";
                break;
            case 3:
                field = "COMMANDS_ADMIN";
                break;
            case 2:
                field = "COMMANDS_GAMEMASTER";
                break;
            default:
                field = "COMMANDS_MODERATOR";
                break;
        }
        Class<?> permissions = VanillaReflect.findClass("net.minecraft.server.permissions.Permissions",
                VanillaReflect.loaderFor(source));
        return permissions == null ? null : VanillaReflect.staticField(permissions, field);
    }

    private static Integer parseInt(String raw) {
        try {
            return Integer.valueOf(raw.trim());
        } catch (Throwable t) {
            return null;
        }
    }

    private static String version() {
        // 优先用 AgentMain 在 premain 阶段写下的系统属性：辅助类被注入到服务端类加载器后，
        // 通过 Package 读不到 agent jar 的清单（真机上就显示成了"开发版"）。
        String fromProperty = System.getProperty("hyauth.version");
        if (fromProperty != null && !fromProperty.isEmpty()) {
            return fromProperty;
        }
        Package owner = AdminCommands.class.getPackage();
        String version = owner == null ? null : owner.getImplementationVersion();
        return version == null || version.isEmpty() ? "开发版（未标注）" : version;
    }
}
