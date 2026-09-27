package com.hyauth.agent.util;

import com.hyauth.agent.config.ListManager;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * 让<b>离线名单玩家</b>单独绕开 {@code enforce-secure-profile}，而不影响其他人。
 *
 * <p><b>背景</b>（26.3 真实字节码）：服务端为每个连接构造聊天解码器时用的是
 * <pre>
 * // ServerGamePacketListenerImpl 构造器
 * this.signedMessageDecoder = SignedMessageChain.Decoder.unsigned(uuid, () -> server.enforceSecureProfile());
 *
 * // Decoder.unsigned 的实现
 * if (enforceSecureProfile.getAsBoolean())
 *     throw new DecodeException(MISSING_PROFILE_KEY);      // 红字 + 消息被丢弃
 * else
 *     return PlayerChatMessage.unsigned(uuid, content);    // 未签名消息，正常广播
 * </pre>
 *
 * <p>也就是说：<b>没有聊天公钥的玩家</b>（离线名单玩家就是）在
 * {@code enforce-secure-profile=true} 时连普通聊天都发不出去。
 * 这类玩家手里根本没有密钥，聊天密钥"桥接"对他们无从谈起 —— 能做的就是判定
 * "这个玩家允不允许发未签名消息"。
 *
 * <p><b>本类的做法</b>：切 {@code Decoder.unsigned(UUID, BooleanSupplier)} 的参数，
 * 把那个「要不要强制安全档案」的 {@link BooleanSupplier} 换成包装版：
 * <b>只对离线名单里的 UUID 返回 false</b>，其他人原样透传服务端设置。
 * 于是
 * <ul>
 *     <li>离线名单玩家：可以发未签名消息（客户端显示 [Not Secure]），
 *         <b>不需要把服务器全局的 enforce-secure-profile 关掉</b>；</li>
 *     <li>正版玩家：仍按服务端设置强制校验聊天签名，行为与原来完全一致；</li>
 *     <li>LittleSkin 等有密钥的外置账号：走 {@link ChatKeyBridge} 验签，拿到正常会话。</li>
 * </ul>
 *
 * <p>本类字节码只引用 JDK 类型（{@code UUID} / {@code BooleanSupplier}），
 * 因此切面可以安全地直接声明它们（见 {@link AuthRejection} 的说明）。
 */
public final class OfflineChatExempt {

    private static volatile boolean announced = false;

    private OfflineChatExempt() {
    }

    /**
     * 包装解码器的「是否强制安全档案」判断。
     *
     * @param uuid     该连接的玩家 UUID
     * @param original 原版判断（{@code () -> server.enforceSecureProfile()}）
     * @return 离线名单玩家 → 恒为 false（允许未签名聊天）；其他人 → 原样返回
     */
    public static BooleanSupplier wrap(UUID uuid, BooleanSupplier original) {
        if (!ListManager.isOfflineChatExempt() || uuid == null || !ListManager.isOfflineUuid(uuid)) {
            return original;
        }
        if (!announced) {
            announced = true;
            System.out.println("[HyAuth] 离线名单玩家已豁免 enforce-secure-profile："
                    + "他们可以正常聊天（显示 [Not Secure]），正版玩家仍按服务端设置强制校验。");
        }
        return new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return false;
            }
        };
    }
}
