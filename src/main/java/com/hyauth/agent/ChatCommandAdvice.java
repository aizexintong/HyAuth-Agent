package com.hyauth.agent;

import com.hyauth.agent.util.KeylessChatBypass;
import net.bytebuddy.asm.Advice;

/**
 * {@code ServerGamePacketListenerImpl#collectSignedArguments} 切面。
 *
 * <p>离线名单玩家没有聊天公钥，而客户端发"带可签名实参"的指令时一定会用签名版指令包，
 * 原版会因此抛 {@code DecodeException}（红字：missing profile public key）并丢弃该指令。
 * 这里对这类玩家改用原版自带的 {@code collectUnsignedArguments} 分支，
 * 让 {@code /say}、{@code /me}、{@code /msg} 等指令恢复正常（按未签名执行）。
 *
 * <p>只有「聊天公钥为空 + 在离线名单里」的玩家会被接管；正版与外置（LittleSkin）玩家
 * 有会话，{@code shouldBypass} 直接返回 false，行为与原版完全一致。
 *
 * <p>受配置项 {@code bypass_signed_commands} 控制（默认开启）。
 * 详见 {@link KeylessChatBypass}。
 */
public class ChatCommandAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static Object onEnter(
            @Advice.This Object listener,
            @Advice.Argument(1) Object signableCommand) {

        if (!KeylessChatBypass.shouldBypass(listener)) {
            return null; // 不跳过 → 原版逻辑
        }
        return KeylessChatBypass.collectUnsigned(listener, signableCommand);
    }
}
