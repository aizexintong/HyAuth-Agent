package com.hyauth.agent;

import com.hyauth.agent.util.OfflineChatExempt;
import net.bytebuddy.asm.Advice;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * {@code SignedMessageChain.Decoder#unsigned(UUID, BooleanSupplier)} 切面。
 *
 * <p>原版用它为「没有聊天公钥的玩家」构造解码器，第二个参数就是
 * {@code () -> server.enforceSecureProfile()}。这里把这个判断换成
 * {@link OfflineChatExempt#wrap} 的包装版：只对离线名单玩家返回 false，
 * 其他人保持服务端设置不变。
 *
 * <p>效果：离线名单玩家可以正常聊天（未签名，显示 [Not Secure]），
 * <b>不需要把服务器全局的 {@code enforce-secure-profile} 关掉</b>；
 * 正版玩家的强制校验完全不受影响。
 *
 * <p>{@code UUID} 与 {@code BooleanSupplier} 都是 JDK 类型，因此本类字节码里不出现
 * 服务端类型（原因见 {@link com.hyauth.agent.util.AuthRejection}）。
 */
public class OfflineDecoderAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(
            @Advice.Argument(0) UUID uuid,
            @Advice.Argument(value = 1, readOnly = false) BooleanSupplier enforceSecureProfile) {

        enforceSecureProfile = OfflineChatExempt.wrap(uuid, enforceSecureProfile);
    }
}
