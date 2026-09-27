package com.hyauth.agent.util;

import com.hyauth.agent.config.ListManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * 把外置账号（LittleSkin）的聊天**按接收者**改成「伪装聊天（Disguised）」再发出去。
 *
 * <p>这一个切面同时解决两个现象，两个现象其实是同一条链路上的两半：
 *
 * <h2>① 交替发言时「下一个发言的人」被踢</h2>
 * 外置账号的聊天密钥是<b>皮肤站</b>签的，不是 Mojang 签的。正版客户端在
 * {@code ClientPacketListener.initializeChatSession} 里校验该会话密钥时会失败
 * （{@code ProfilePublicKey.ValidationException: 无效的玩家档案公钥签名}），于是清空该玩家的
 * 聊天会话、改用 {@code SignedMessageValidator.ACCEPT_UNSIGNED}：
 * <ul>
 *     <li>它<b>不再接收</b>该玩家那些"带签名"的消息（也就不把签名记进自己的 last-seen 账本）；</li>
 *     <li>而服务端照旧把签名记进了"我发给你哪些签名"的账本
 *         （{@code ServerGamePacketListenerImpl} 里的 {@code lastSeenMessages} 校验器）；</li>
 *     <li>两边账本一旦不一致，<b>这个客户端下一次发言</b>就会带着错误的校验和 →
 *         服务端 {@code Checksum mismatch on last seen update} → <b>把它踢下线</b>
 *         （{@code 聊天消息验证失败}）。</li>
 * </ul>
 *
 * <h2>② 外置玩家自己看不到自己发的消息，并弹红字「聊天验证错误」</h2>
 * 上一版的修法是"整条消息换成未签名"（{@code PlayerChatMessage.unsigned}），账本确实不分歧了，
 * 但**发送者自己的那一份也变成了未签名**：
 * <ul>
 *     <li>发送者自己的客户端是外置登录客户端（authlib-injector 之类），它<b>能</b>用皮肤站公钥
 *         验证自己那把会话密钥 ⇒ 它对"自己"用的是**链式校验器**（要求消息带签名）；</li>
 *     <li>而未签名的消息过不了链式校验器 ⇒ 客户端
 *         {@code ChatListener.handleChatMessageError} ⇒ 红字 {@code chat.validation_error}
 *         （「聊天验证错误」）+ <b>消息不显示</b>；</li>
 *     <li>别人（正版客户端）对该玩家用的是 {@code ACCEPT_UNSIGNED} ⇒ 未签名消息照常显示，
 *         于是就成了"自己看不到、别人都看得到"。</li>
 * </ul>
 *
 * <h2>本类的修法：按接收者分流，谁都别丢消息</h2>
 * 切在 {@code ServerGamePacketListenerImpl#sendPlayerChatMessage(PlayerChatMessage, ChatType.Bound)}
 * —— 这是原版<b>每个接收者各发一次</b>的那个方法（{@code PlayerList#broadcastChatMessage} 里
 * {@code player.sendChatMessage(...)} → {@code OutgoingChatMessage.Player#sendToPlayer} → 这里），
 * 因此能精确区分"发给谁"：
 * <pre>
 *   发给作者本人 → 原样放行（保持原版「带签名」消息）
 *                  · 客户端用自己的公钥验签通过 ⇒ 自己能看到自己发的消息
 *                  · 服务端账本与客户端记录一致 ⇒ 不会有校验和分歧、不会被踢
 *   发给其他人   → 改用原版自带的「伪装聊天」{@code sendDisguisedChatMessage(Component, ChatType.Bound)}
 *                  · 客户端把它当系统/伪装消息直接上屏，**完全不走签名校验**
 *                    ⇒ 谁都不会弹「聊天验证错误」（连能验证皮肤站密钥的其它外置客户端也一样）
 *                  · 服务端不再往"已发送签名"账本里记账 ⇒ 与客户端记录天然一致，不会被踢
 *                  · 显示效果与原版聊天一致：服务端只送内容，客户端照旧用 ChatType.Bound
 *                    拼出 <玩家名> 内容
 * </pre>
 *
 * <p>这正是原版自己对「系统来源的玩家消息」用的那条路（{@code OutgoingChatMessage.create()} 里
 * {@code message.isSystem()} 分支会走 {@code Disguised}），所以不是自创管线。
 *
 * <p>只影响<b>外置名单玩家</b>（{@code unsigned_external_chat}，默认开启）；正版玩家的签名聊天
 * 完全不动。配置项 {@code unsigned_all_chat} 打开后同样处理所有玩家（作者本人依旧保留签名）。
 *
 * <p>实现全为 JDK 反射（鸭子类型），字节码里不出现任何服务端类型（原因见
 * {@link AuthRejection}）；任何一步失败都返回 {@code false} 让调用方执行原版逻辑
 * （fail-safe，绝不会让聊天变得更糟）。
 */
public final class ExternalChatBroadcast {

    /** 原版「伪装聊天」发送方法：{@code ServerGamePacketListenerImpl#sendDisguisedChatMessage(Component, ChatType.Bound)}。 */
    private static final String DISGUISED_SEND_METHOD = "sendDisguisedChatMessage";

    /** 连接对象里指向「接收者自己」的字段：{@code ServerGamePacketListenerImpl#player}。 */
    private static final String CONNECTION_PLAYER_FIELD = "player";

    /** 连接对象里指向服务端的字段：{@code ServerGamePacketListenerImpl#server}。 */
    private static final String CONNECTION_SERVER_FIELD = "server";

    /** {@code MinecraftServer#getPlayerList()}。 */
    private static final String SERVER_PLAYER_LIST_METHOD = "getPlayerList";

    /** {@code PlayerList#getPlayer(UUID)}（拿不到时退化为遍历 {@code getPlayers()}）。 */
    private static final String PLAYER_LIST_GET_PLAYER_METHOD = "getPlayer";

    /** {@code PlayerList#getPlayers()}：在线玩家列表（退化路线用）。 */
    private static final String PLAYER_LIST_GET_PLAYERS_METHOD = "getPlayers";

    private static volatile boolean announced = false;
    private static volatile boolean reportedFailure = false;
    private static volatile boolean reportedNoDisguisedMethod = false;

    // ---- 反射缓存（每类一份，聊天频率低，这里只是避免每句话都重新查一遍） ----

    private static volatile Class<?> disguisedOwner;
    private static volatile Method disguisedMethod;

    private static volatile Class<?> playerFieldOwner;
    private static volatile Field playerField;

    private static volatile Class<?> serverFieldOwner;
    private static volatile Field serverField;

    private static volatile Class<?> playerListOwner;
    private static volatile Method getPlayerByIdMethod;
    private static volatile Method getPlayersMethod;

    private ExternalChatBroadcast() {
    }

    /**
     * 该不该把这条消息"伪装"后发给这个接收者。
     *
     * @param connection 接收者的连接对象（{@code ServerGamePacketListenerImpl}）
     * @param message    原始消息（{@code PlayerChatMessage}）
     * @param chatType   聊天类型绑定（{@code ChatType.Bound}）
     * @return {@code true} = 已经用「伪装聊天」发给了该接收者，调用方应跳过原方法；
     *         {@code false} = 按原版逻辑继续
     */
    public static boolean sendAsDisguised(Object connection, Object message, Object chatType) {
        try {
            if (connection == null || message == null || chatType == null
                    || !ListManager.isUnsignedExternalChat()) {
                return false;
            }

            UUID sender = senderOf(message);
            if (sender == null) {
                return false;
            }

            UUID recipient = uuidOf(playerOf(connection));
            if (recipient != null && recipient.equals(sender)) {
                // 作者本人：保持原版「带签名」的消息（否则会重演上一版"自己看不到 + 红字"的问题）
                return false;
            }

            if (!ListManager.isUnsignedAllChat() && !isExternalSender(connection, sender)) {
                // 正版玩家之间签名聊天照旧，Agent 不插手
                return false;
            }

            Object content = contentOf(message);
            if (content == null) {
                return false;
            }
            if (!sendDisguised(connection, content, chatType)) {
                return false;
            }

            announce();
            return true;
        } catch (Throwable t) {
            reportFailure(t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 发送者 / 接收者判定
    // ------------------------------------------------------------------

    /** 消息的发送者 UUID。 */
    private static UUID senderOf(Object message) {
        Object value = invokeNoArg(message, "sender");
        if (value instanceof UUID) {
            return (UUID) value;
        }
        Object link = invokeNoArg(message, "link");
        value = invokeNoArg(link, "sender");
        return value instanceof UUID ? (UUID) value : null;
    }

    /** 连接对应的接收者（{@code ServerPlayer}）。 */
    private static Object playerOf(Object connection) {
        Field field = playerField;
        if (field == null || playerFieldOwner != connection.getClass()) {
            field = lookupField(connection.getClass(), CONNECTION_PLAYER_FIELD);
            playerField = field;
            playerFieldOwner = connection.getClass();
        }
        return field == null ? null : readField(field, connection);
    }

    private static UUID uuidOf(Object player) {
        Object value = invokeNoArg(player, "getUUID");
        return value instanceof UUID ? (UUID) value : null;
    }

    /**
     * 发送者是不是「别人拿不到可信会话密钥」的外置（LittleSkin）账号。
     *
     * <p>判定方式：用服务端的在线玩家表按 UUID 找到发送者，取其玩家名去 LittleSkin 名单里比对 ——
     * 名单里的玩家必然是用皮肤站登录的（登录时就是拿它跟皮肤站校验的），
     * 其聊天密钥由皮肤站签发，正版客户端验不了。查不到就返回 {@code false}（按原版处理，绝不误伤正版玩家）。
     */
    private static boolean isExternalSender(Object connection, UUID sender) {
        String name = senderName(connection, sender);
        return name != null && ListManager.isLittleSkinPlayer(name);
    }

    private static String senderName(Object connection, UUID sender) {
        Object server = serverOf(connection);
        Object playerList = invokeNoArg(server, SERVER_PLAYER_LIST_METHOD);
        if (playerList == null) {
            return null;
        }
        return nameOf(findPlayer(playerList, sender));
    }

    private static Object serverOf(Object connection) {
        Field field = serverField;
        if (field == null || serverFieldOwner != connection.getClass()) {
            field = lookupField(connection.getClass(), CONNECTION_SERVER_FIELD);
            serverField = field;
            serverFieldOwner = connection.getClass();
        }
        return field == null ? null : readField(field, connection);
    }

    /** 在在线玩家表里按 UUID 找人：优先 {@code getPlayer(UUID)}，拿不到就遍历 {@code getPlayers()}。 */
    private static Object findPlayer(Object playerList, UUID uuid) {
        Class<?> owner = playerList.getClass();
        if (playerListOwner != owner) {
            // 每次换了实现类就重新找一遍（缓存按类走，避免把 A 类的方法用到 B 类实例上）
            getPlayerByIdMethod = findMethod(owner, PLAYER_LIST_GET_PLAYER_METHOD, uuid);
            getPlayersMethod = findMethod(owner, PLAYER_LIST_GET_PLAYERS_METHOD);
            playerListOwner = owner;
        }
        Method byId = getPlayerByIdMethod;
        if (byId != null) {
            Object player = invoke(byId, playerList, uuid);
            if (player != null) {
                return player;
            }
        }
        Method players = getPlayersMethod;
        Object list = players == null ? null : invoke(players, playerList);
        if (list instanceof List) {
            for (Object candidate : (List<?>) list) {
                if (uuid.equals(uuidOf(candidate))) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** 取玩家名：26.3 的 {@code nameAndId().name()}，旧版 {@code getGameProfile().getName()} 都能吃。 */
    private static String nameOf(Object player) {
        if (player == null) {
            return null;
        }
        String name = nestedString(player, "nameAndId", "name");
        if (name != null) {
            return name;
        }
        name = nestedString(player, "getGameProfile", "name");
        if (name != null) {
            return name;
        }
        return nestedString(player, "getGameProfile", "getName");
    }

    /** 先调 {@code first} 拿到对象，再在该对象上找 {@code accessor} 取字符串。 */
    private static String nestedString(Object target, String first, String accessor) {
        Object holder = invokeNoArg(target, first);
        Object value = invokeNoArg(holder, accessor);
        return value instanceof String ? (String) value : null;
    }

    // ------------------------------------------------------------------
    // 内容与发送
    // ------------------------------------------------------------------

    /** 消息内容（Component）：优先服务端装饰后的内容，兜底未签名内容。 */
    private static Object contentOf(Object message) {
        Object content = invokeNoArg(message, "decoratedContent");
        if (content != null) {
            return content;
        }
        return invokeNoArg(message, "unsignedContent");
    }

    /** 借原版自己的伪装聊天管线把内容发给该接收者。 */
    private static boolean sendDisguised(Object connection, Object content, Object chatType) {
        Method method = disguisedMethod;
        if (method == null || disguisedOwner != connection.getClass()) {
            method = findMethod(connection.getClass(), DISGUISED_SEND_METHOD, content, chatType);
            disguisedMethod = method;
            disguisedOwner = connection.getClass();
            if (method == null) {
                if (!reportedNoDisguisedMethod) {
                    reportedNoDisguisedMethod = true;
                    System.err.println("[HyAuth] 未找到原版伪装聊天方法 " + DISGUISED_SEND_METHOD
                            + "，外置聊天按原版广播。");
                }
                return false;
            }
        }
        try {
            method.invoke(connection, content, chatType);
            return true;
        } catch (Throwable t) {
            reportFailure(t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 反射工具
    // ------------------------------------------------------------------

    /** 按名字 + 参数个数（并校验参数类型可以接收实际值）找 public 方法。 */
    private static Method findMethod(Class<?> owner, String name, Object... args) {
        if (owner == null || name == null) {
            return null;
        }
        for (Method method : owner.getMethods()) {
            if (!name.equals(method.getName())) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (types.length != args.length) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < types.length; i++) {
                if (args[i] != null && !types[i].isInstance(args[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return method;
            }
        }
        return null;
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 找字段：沿类层次一路往上找（{@code server} 通常声明在父类
     * {@code ServerCommonPacketListenerImpl} 里，而 {@code player} 在子类），
     * 非 public（protected / private）也要能拿到 —— 服务端类在 Bundler 的
     * URLClassLoader 里（未命名模块），{@code setAccessible(true)} 是允许的。
     */
    private static Field lookupField(Class<?> owner, String name) {
        for (Class<?> type = owner; type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (Throwable ignored) {
                // 该类没有这个字段，继续往父类找
            }
        }
        try {
            return owner.getField(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object readField(Field field, Object target) {
        try {
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void announce() {
        if (announced) {
            return;
        }
        announced = true;
        System.out.println("[HyAuth] 外置账号的聊天改为「逐接收者未签名（伪装聊天）」广播："
                + "作者本人仍收到原版签名消息（所以自己看得到自己发的话），"
                + "其他人收到不参与签名账本的消息（既不弹「聊天验证错误」，也不会因记账不一致被踢）。");
    }

    private static void reportFailure(Throwable t) {
        if (reportedFailure) {
            return;
        }
        reportedFailure = true;
        System.err.println("[HyAuth] 外置聊天未签名化失败，按原版广播: " + t);
    }
}
