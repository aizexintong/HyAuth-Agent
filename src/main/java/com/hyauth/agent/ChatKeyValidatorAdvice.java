package com.hyauth.agent;

import com.hyauth.agent.util.ChatKeyPolicy;
import net.bytebuddy.asm.Advice;

/**
 * {@code net.minecraft.server.Services#profileKeySignatureValidator()} 切面。
 *
 * <p>这个方法返回的校验器用来校验玩家上报的<b>聊天签名密钥</b>。原版用的是 Mojang 服务密钥，
 * 因此 LittleSkin 这类外置账号的密钥必然校验失败，玩家会被
 * “Invalid signature for profile public key” 直接踢下线（详见 {@link ChatKeyPolicy}）。
 *
 * <p>切面在方法出口把返回值换成原版的 {@code SignatureValidator.NO_VALIDATION}，
 * 从而让服务端不再校验该签名 —— 玩家正常进入，且聊天签名链路照常建立。
 * 该行为受配置项 {@code relax_chat_keys} 控制（默认开启）。
 *
 * <p>注意：本类字节码里不能出现服务端类型（返回类型用 {@code Object} 承载，
 * 并由 {@code AgentMain.LENIENT_ASSIGNER} 插入 CHECKCAST），原因见
 * {@link com.hyauth.agent.util.AuthRejection} 的类注释。
 */
public class ChatKeyValidatorAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.Return(readOnly = false) Object result) {
        result = ChatKeyPolicy.relax(result);
    }
}
