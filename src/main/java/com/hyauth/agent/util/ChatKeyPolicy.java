package com.hyauth.agent.util;

import com.hyauth.agent.config.ListManager;

/**
 * 聊天签名密钥策略：让外置账号不再被“Invalid signature for profile public key”踢下线。
 *
 * <p><b>问题（依据 26.3 真实字节码，客户端与服务端两侧都已核对）：</b>
 * <ol>
 *     <li>客户端 {@code ClientPacketListener.handleLogin}：
 *         {@code if (packet.onlineMode()) prepareKeyPair();} —— 只要服务端是
 *         {@code online-mode=true}（本 Agent 的必需前提），客户端就会生成聊天签名密钥，
 *         并在进服后用 {@code ServerboundChatSessionUpdatePacket} 上报，<b>此处不看
 *         {@code enforce-secure-profile}</b>；</li>
 *     <li>服务端 {@code ServerGamePacketListenerImpl.handleChatSessionUpdate}：
 *         用 {@code Services#profileKeySignatureValidator()}（<b>Mojang 服务密钥</b>）
 *         校验上报密钥的签名；外置账号（LittleSkin）的密钥不是 Mojang 签的 →
 *         {@code ProfilePublicKey.ValidationException} → <b>玩家被直接踢下线</b>。</li>
 * </ol>
 *
 * <p>所以 {@code enforce-secure-profile=false} 治不了这个踢人 —— 它只影响
 * “无签名指令”（{@code performUnsignedChatCommand}）的拦截，与本路径无关。
 *
 * <p><b>本类的做法：</b>在 {@code Services#profileKeySignatureValidator()} 出口把校验器换成
 * {@link ChatKeyBridge} 的<b>三级桥接校验器</b>：
 * Mojang 官方验签 → LittleSkin 公钥验签（SHA1/SHA256/SHA512withRSA 依次尝试）
 * → 仍失败时按 {@code chat_key_strict} 放行或拒绝。于是外置账号的密钥能获得<b>真实验签</b>，
 * 而不再是无条件放行；正版玩家仍走 Mojang 校验，且不会产生任何额外网络请求。
 *
 * <p>该校验器在 26.3 全 jar 中<b>只被这一处调用</b>（已用字节码扫描确认），
 * 因此不会影响登录、皮肤或其它功能。
 */
public final class ChatKeyPolicy {

    private static volatile boolean announced = false;

    private ChatKeyPolicy() {
    }

    /**
     * 按配置放宽校验器。
     *
     * <p>默认（{@code relax_chat_keys=true}）返回的是 {@link ChatKeyBridge} 的
     * <b>三级桥接校验器</b>：先走 Mojang 官方验签，失败再用 LittleSkin 公钥按
     * SHA1/SHA256/SHA512withRSA 依次验签，仍失败才按 {@code chat_key_strict} 决定放行或拒绝。
     * 也就是"能验就真验，验不了才放宽"，而不是无条件放行。
     *
     * @param original 原版返回值（{@code SignatureValidator} 实例，可能为 null）
     * @return 桥接后的校验器；配置关闭或处理失败时原样返回
     */
    public static Object relax(Object original) {
        if (original == null) {
            return null; // 原版本就没有公钥 → 服务端本来就会忽略聊天密钥
        }
        if (!ListManager.isRelaxChatKeys()) {
            return original; // 完全原版行为
        }
        if (!announced) {
            announced = true;
            System.out.println("[HyAuth] 已启用聊天密钥桥接（relax_chat_keys=true）："
                    + "Mojang 验签 → LittleSkin 公钥验签 → 按 chat_key_strict 决定，"
                    + "外置账号不会再被 “Invalid signature for profile public key” 踢下线。");
        }
        return ChatKeyBridge.wrap(original, new ChatKeyBridge.ExternalKeySource() {
            @Override
            public java.security.PublicKey get() {
                // 与属性验签用同一把 LittleSkin 公钥（已缓存；Mojang 验签通过时根本不会走到这里）
                return YggdrasilAuthUtil.resolvePublicKey(ListManager.getApiRoot());
            }
        }, ListManager.isChatKeyStrict());
    }
}
