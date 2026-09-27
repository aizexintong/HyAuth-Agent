package com.hyauth.agent;

import com.hyauth.agent.config.ListManager;
import com.hyauth.agent.config.OfflinePlayer;
import net.bytebuddy.asm.Advice;

/**
 * 登录握手切面（二）：让离线名单玩家跳过客户端的会话上报。
 *
 * <p>背景（依据 Minecraft 26.3 真实字节码）：
 * <ul>
 *     <li>客户端 {@code ClientHandshakePacketListenerImpl.handleHello}：
 *         {@code shouldAuthenticate == true} 时调用 {@code authenticateServer()} →
 *         {@code SessionService.joinServer(...)}，一旦抛 {@code AuthenticationException}
 *         就返回 {@code disconnect.loginFailedInfo.*} 让<b>客户端自己断开</b>；
 *         而 {@code shouldAuthenticate == false} 时直接跳过会话上报、继续完成加密握手。</li>
 *     <li>服务端 {@code ServerLoginPacketListenerImpl.handleHello} 把该布尔<b>硬编码为 true</b>
 *         （字节码 {@code iconst_1}），且之后无论该布尔为何值，在线模式分支都会照常调用
 *         {@code SessionService.hasJoinedServer(username, serverId, address)}。</li>
 * </ul>
 *
 * <p>因此：把服务端下发的 {@code ClientboundHelloPacket} 第 4 个参数（shouldAuthenticate）
 * 对<b>离线名单内玩家</b>改写为 false，纯离线客户端（HMCL/PCL2 离线登录、原版客户端）即可
 * 顺利走到服务端校验环节，再由 {@link HasJoinedAdvice} 按管理员指定的 UUID 放行；
 * 非名单玩家保持原样，正版校验不受影响。
 */
public class HelloPacketAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(value = 3, readOnly = false) boolean shouldAuthenticate) {
        String username = LoginFlowContext.consume();
        if (username == null || !shouldAuthenticate) {
            return;
        }
        try {
            OfflinePlayer offline = ListManager.getOfflinePlayer(username);
            if (offline == null) {
                return;
            }
            shouldAuthenticate = false;
            System.out.println("[HyAuth] 离线名单玩家 " + username
                    + "：已关闭客户端会话校验（shouldAuthenticate=false），将由 Agent 按指定 UUID 放行。");
        } catch (Throwable t) {
            System.err.println("[HyAuth] 处理离线名单登录握手失败: " + t);
        }
    }
}
