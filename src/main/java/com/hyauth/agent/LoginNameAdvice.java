package com.hyauth.agent;

import com.hyauth.agent.config.OfflinePlayer;
import com.hyauth.agent.config.ListManager;
import net.bytebuddy.asm.Advice;

/**
 * 登录握手切面（一）：记录 LoginStart 里的玩家名。
 *
 * <p>挂载点：{@code net.minecraft.server.network.ServerLoginPacketListenerImpl#handleHello(ServerboundHelloPacket)}
 * （Minecraft 26.x 的服务端类是未混淆的）。名字通过 {@link LoginFlowContext} 传给紧接着
 * 构造 Hello 包的 {@link HelloPacketAdvice}，方法退出时清除。
 */
public class LoginNameAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(0) Object helloPacket) {
        try {
            LoginFlowContext.set(LoginFlowContext.nameOfLoginStart(helloPacket));
        } catch (Throwable t) {
            LoginFlowContext.clear();
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit() {
        // 无论走哪条分支（在线/离线/内存连接/异常），都不让名字残留到后续连接
        LoginFlowContext.clear();
    }
}
