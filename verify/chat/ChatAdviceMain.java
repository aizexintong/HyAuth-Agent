package chat;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 外置账号聊天广播（逐接收者未签名）离线回归测试。
 *
 * <p>复现原版 26.3 的真实调用形状
 * （{@code ServerPlayer#sendChatMessage} → {@code OutgoingChatMessage.Player#sendToPlayer}
 * → {@code ServerGamePacketListenerImpl#sendPlayerChatMessage(PlayerChatMessage, ChatType.Bound)}），
 * 用 Byte Buddy 把 <b>Agent 真正的切面类</b> {@code com.hyauth.agent.ExternalChatAdvice}
 * 内联进替身，然后断言：
 *
 * <ol>
 *   <li>外置（LittleSkin）玩家发给<b>别人</b> → 走原版伪装聊天（不进签名账本）；</li>
 *   <li>外置玩家发给<b>自己</b> → 保持原版签名消息（这是"自己能看到自己发的话"的关键）；</li>
 *   <li>外置玩家发给<b>另一个外置玩家</b> → 同样是伪装聊天（对方客户端也不会拒收）；</li>
 *   <li>正版玩家 → 完全不动；</li>
 *   <li>{@code unsigned_all_chat=true} → 正版玩家的消息对别人也伪装，作者本人仍保留签名；</li>
 *   <li>{@code unsigned_external_chat=false} → 完全不干预；</li>
 *   <li>发送者查不到（不在在线列表）→ 按原版处理（fail-safe）；</li>
 *   <li>退化形状：旧版 {@code getGameProfile().getName()} 取名字、玩家表没有
 *       {@code getPlayer(UUID)} → 依然能识别外置账号；</li>
 *   <li>发送方法声明在<b>父类</b>、实例是子类（Agent 子类与父类都挂了切面）→ 同样生效。</li>
 * </ol>
 */
public class ChatAdviceMain {

    private static final UUID EXT_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EXT2_UUID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID PREM_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID UNKNOWN_UUID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static int failures = 0;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        String stubDir = args[0];
        ClassLoader app = ChatAdviceMain.class.getClassLoader();

        // ---------- 配置：ExtPlayer 在外置名单里 ----------
        configure(true, false);
        Class<?> listManager = Class.forName("com.hyauth.agent.config.ListManager", true, app);
        listManager.getMethod("reload").invoke(null);
        check(Boolean.TRUE.equals(listManager.getMethod("isLittleSkinPlayer", String.class)
                        .invoke(null, "ExtPlayer")),
                "前提：配置里的外置名单读到了 ExtPlayer");

        // ---------- 把 Agent 真正的切面内联进替身 ----------
        Class<?> advice = Class.forName("com.hyauth.agent.ExternalChatAdvice", false, app);
        URLClassLoader child = new URLClassLoader(new URL[]{new File(stubDir).toURI().toURL()}, app);
        Class<?> connection = redefine(child, advice,
                "net.minecraft.server.network.ServerGamePacketListenerImpl");
        Class<?> legacyConnection = redefine(child, advice,
                "net.minecraft.server.network.LegacyServerGamePacketListenerImpl");

        Class<?> playerType = child.loadClass("net.minecraft.server.level.ServerPlayer");
        Class<?> legacyPlayerType = child.loadClass("net.minecraft.server.level.LegacyPlayer");
        Class<?> listType = child.loadClass("net.minecraft.server.players.PlayerList");
        Class<?> simpleListType = child.loadClass("net.minecraft.server.players.SimplePlayerList");
        Class<?> serverType = child.loadClass("net.minecraft.server.MinecraftServer");
        Class<?> legacyServerType = child.loadClass("net.minecraft.server.SimpleMinecraftServer");
        Class<?> messageType = child.loadClass("net.minecraft.network.chat.PlayerChatMessage");
        Class<?> componentType = child.loadClass("net.minecraft.network.chat.Component");
        Class<?> boundType = child.loadClass("net.minecraft.network.chat.ChatType$Bound");

        Object ext = newPlayer(playerType, EXT_UUID, "ExtPlayer");
        Object ext2 = newPlayer(playerType, EXT2_UUID, "ExtPlayer2");
        Object prem = newPlayer(playerType, PREM_UUID, "PremiumPlayer");
        Object other = newPlayer(playerType, OTHER_UUID, "OtherPlayer");

        Object list = listType.getConstructor().newInstance();
        add(listType, list, ext);
        add(listType, list, ext2);
        add(listType, list, prem);
        add(listType, list, other);
        Object server = serverType.getConstructor(listType).newInstance(list);

        // ---------- 1) 外置玩家 → 别人：伪装聊天 ----------
        List<?> toOther = deliver(connection, other, server, messageType, componentType, boundType,
                EXT_UUID, "hi");
        check(isOnly(toOther, "DISGUISED:hi"),
                "外置玩家发给别人：改用原版伪装聊天（不参与签名账本）→ " + toOther);

        // ---------- 2) 外置玩家 → 自己：原版签名消息（修复的关键） ----------
        List<?> toSelf = deliver(connection, ext, server, messageType, componentType, boundType,
                EXT_UUID, "hi");
        check(isOnly(toSelf, "PLAYER:hi"),
                "外置玩家自己那一份：保持原版签名消息（所以自己看得到、不会弹聊天验证错误）→ " + toSelf);

        // ---------- 3) 外置玩家 → 另一个外置玩家：同样是伪装聊天（谁都不会弹验证错误） ----------
        List<?> toExternalPeer = deliver(connection, ext2, server, messageType, componentType, boundType,
                EXT_UUID, "hi");
        check(isOnly(toExternalPeer, "DISGUISED:hi"),
                "外置玩家发给另一个外置玩家：也是伪装聊天（对方客户端不再拒收）→ " + toExternalPeer);

        // ---------- 4) 正版玩家：完全不动 ----------
        List<?> premium = deliver(connection, other, server, messageType, componentType, boundType,
                PREM_UUID, "yo");
        check(isOnly(premium, "PLAYER:yo"),
                "正版玩家：聊天完全不动（签名链路照旧）→ " + premium);

        // ---------- 5) unsigned_all_chat=true：正版玩家的消息对别人也伪装 ----------
        configure(true, true);
        listManager.getMethod("reload").invoke(null);
        List<?> allToOther = deliver(connection, other, server, messageType, componentType, boundType,
                PREM_UUID, "yo2");
        check(isOnly(allToOther, "DISGUISED:yo2"),
                "unsigned_all_chat=true：正版玩家发给别人也走伪装聊天 → " + allToOther);
        List<?> allToSelf = deliver(connection, prem, server, messageType, componentType, boundType,
                PREM_UUID, "yo3");
        check(isOnly(allToSelf, "PLAYER:yo3"),
                "unsigned_all_chat=true：作者本人依旧是原版签名消息 → " + allToSelf);

        // ---------- 6) 关掉开关：完全不干预 ----------
        configure(false, false);
        listManager.getMethod("reload").invoke(null);
        List<?> disabled = deliver(connection, other, server, messageType, componentType, boundType,
                EXT_UUID, "hi2");
        check(isOnly(disabled, "PLAYER:hi2"),
                "unsigned_external_chat=false：完全不干预（恢复原版）→ " + disabled);

        // ---------- 7) 发送者不在在线列表 / 查不到名字：按原版处理 ----------
        configure(true, false);
        listManager.getMethod("reload").invoke(null);
        List<?> unknown = deliver(connection, other, server, messageType, componentType, boundType,
                UNKNOWN_UUID, "hi3");
        check(isOnly(unknown, "PLAYER:hi3"),
                "发送者查不到时按原版处理（fail-safe，不误伤）→ " + unknown);

        // ---------- 8) 退化形状：旧版取名字 + 玩家表没有 getPlayer(UUID) ----------
        Object legacyExt = legacyPlayerType.getConstructor(UUID.class, String.class)
                .newInstance(EXT_UUID, "ExtPlayer");
        Object simpleList = simpleListType.getConstructor().newInstance();
        add(simpleListType, simpleList, legacyExt);
        add(simpleListType, simpleList, other);
        Object legacyServer = legacyServerType.getConstructor(simpleListType).newInstance(simpleList);

        List<?> legacy = deliver(legacyConnection, other, legacyServer, messageType, componentType,
                boundType, EXT_UUID, "hi4");
        check(isOnly(legacy, "DISGUISED:hi4"),
                "旧版取名字 + 玩家表没有 getPlayer(UUID)：仍能识别外置账号 → " + legacy);

        // ---------- 9) 发送方法声明在父类、实例是子类（两处都挂切面时的另一条路径） ----------
        // 先把切面挂到「声明方法的父类」上，再加载子类（子类会自动链接到改写后的父类）
        redefine(child, advice, "net.minecraft.server.network.ServerCommonPacketListenerImpl");
        Class<?> inherited = child.loadClass("net.minecraft.server.network.InheritedGamePacketListenerImpl");

        List<?> inheritedToOther = deliver(inherited, other, server, messageType, componentType,
                boundType, EXT_UUID, "hi5");
        check(isOnly(inheritedToOther, "DISGUISED:hi5"),
                "发送方法声明在父类、实例是子类：外置玩家发给别人仍走伪装聊天 → " + inheritedToOther);
        List<?> inheritedToSelf = deliver(inherited, ext, server, messageType, componentType,
                boundType, EXT_UUID, "hi6");
        check(isOnly(inheritedToSelf, "PLAYER:hi6"),
                "发送方法声明在父类、实例是子类：作者本人依旧保留签名消息 → " + inheritedToSelf);

        System.out.println(failures == 0 ? "[chat] 全部通过" : "[chat] 失败项: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------

    /** 用 Byte Buddy 把切面内联进替身类，并注入到子加载器。 */
    private static Class<?> redefine(ClassLoader child, Class<?> advice, String className) {
        ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(child);
        TypeDescription target = TypePool.Default.WithLazyResolution.of(locator)
                .describe(className).resolve();
        return new ByteBuddy()
                .redefine(target, locator)
                .visit(Advice.to(advice)
                        .on(ElementMatchers.named("sendPlayerChatMessage")
                                .and(ElementMatchers.takesArguments(2))))
                .make()
                .load(child, ClassLoadingStrategy.Default.INJECTION)
                .getLoaded();
    }

    /** 造一个玩家替身。 */
    private static Object newPlayer(Class<?> playerType, UUID uuid, String name) throws Exception {
        return playerType.getConstructor(UUID.class, String.class).newInstance(uuid, name);
    }

    private static void add(Class<?> listType, Object list, Object player) throws Exception {
        listType.getMethod("add", Object.class).invoke(list, player);
    }

    /** 投递一条消息，返回该连接记录下来的发送轨迹。 */
    private static List<?> deliver(Class<?> connectionType, Object recipient, Object server,
                                   Class<?> messageType, Class<?> componentType, Class<?> boundType,
                                   UUID sender, String text) throws Exception {
        Object content = componentType.getConstructor(String.class).newInstance(text);
        Object message = messageType.getConstructor(UUID.class, componentType).newInstance(sender, content);
        Object bound = boundType.getConstructor(String.class).newInstance("chat");

        Constructor<?> constructor = connectionType.getConstructors()[0];
        Object connection = constructor.newInstance(recipient, server);
        connectionType.getMethod("sendPlayerChatMessage", messageType, boundType)
                .invoke(connection, message, bound);
        return (List<?>) connectionType.getMethod("sent").invoke(connection);
    }

    private static boolean isOnly(List<?> sent, String expected) {
        return sent != null && sent.size() == 1 && expected.equals(sent.get(0));
    }

    /** 写测试配置（工作目录下的 littleskin_config.json）。 */
    private static void configure(boolean unsignedExternalChat, boolean unsignedAllChat) throws Exception {
        String json = "{\n"
                + "  \"config_version\": -1," + "\n"
                + "  \"littleskin_players\": [ \"ExtPlayer\", \"ExtPlayer2\" ]," + "\n"
                + "  \"offline_players\": [],\n"
                + "  \"api_root\": \"http://127.0.0.1:25588/api/yggdrasil\",\n"
                + "  \"unsigned_external_chat\": " + unsignedExternalChat + ",\n"
                + "  \"unsigned_all_chat\": " + unsignedAllChat + ",\n"
                + "  \"debug\": true\n"
                + "}\n";
        File file = new File("littleskin_config.json");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(json);
        }
    }
    /** 写配置时用的版本号 = ListManager.CONFIG_VERSION（跟着编译版本走）。 */
    private static int hyauthConfigVersion() {
        try {
            Class<?> type = Class.forName("com.hyauth.agent.config.ListManager");
            return type.getField("CONFIG_VERSION").getInt(null);
        } catch (Throwable t) {
            return 10000;
        }
    }
}
