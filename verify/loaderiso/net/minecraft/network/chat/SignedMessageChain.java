package net.minecraft.network.chat;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * 测试替身：保留真实 {@code SignedMessageChain$Decoder} 的关键形状 ——
 * 静态工厂 {@code unsigned(UUID, BooleanSupplier)}，其实现按该判断决定
 * 「拒绝没有公钥的消息」还是「按未签名放行」。
 */
public class SignedMessageChain {

    public interface Decoder {
        String unpack(Object signature, Object body);

        static Decoder unsigned(UUID uuid, BooleanSupplier enforceSecureProfile) {
            return new Decoder() {
                @Override
                public String unpack(Object signature, Object body) {
                    return enforceSecureProfile.getAsBoolean() ? "MISSING_PROFILE_KEY" : "UNSIGNED_OK";
                }
            };
        }
    }
}
