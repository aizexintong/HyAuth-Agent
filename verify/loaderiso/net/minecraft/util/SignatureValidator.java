package net.minecraft.util;

import java.security.PublicKey;

/**
 * 测试替身（只存在于自检脚手架，不会进入 Agent jar）。
 *
 * <p>它模拟真实 {@code net.minecraft.util.SignatureValidator} 的关键特征：
 * <ul>
 *     <li>函数式接口 {@code validate(SignatureUpdater, byte[])}；</li>
 *     <li>静态工厂 {@code from(PublicKey, String)} —— 真的用该公钥 + 指定算法做 RSA 验签
 *         （与真实实现一致：失败时返回 false，不抛异常）；</li>
 *     <li>恒真常量 {@code NO_VALIDATION}。</li>
 * </ul>
 * 只出现在「父加载器为 platform 的子加载器」里，因此也一并验证了
 * “切面运行期能按目标类加载器解析出这个类”。
 */
public interface SignatureValidator {

    SignatureValidator NO_VALIDATION = (updater, signature) -> true;

    /**
     * 模拟「正版账号的 Mojang 校验器」：两个重载都返回 true（正常校验器）。
     */
    SignatureValidator MOJANG_LIKE = new SignatureValidator() {
        @Override
        public boolean validate(SignatureUpdater updater, byte[] signature) {
            return true;
        }

        @Override
        public boolean validate(byte[] payload, byte[] signature) {
            return true;
        }
    };

    /**
     * 故意「两个重载结论不同」的校验器：抽象重载 true、byte[] 默认重载 false。
     * 桥接必须用「调用方实际调用的那个重载」去委托，否则会出现
     * 「正版账号也被判为未通过」的现象（真机上就是这样）。
     */
    SignatureValidator OVERLOAD_MISLEADING = new SignatureValidator() {
        @Override
        public boolean validate(SignatureUpdater updater, byte[] signature) {
            return true;
        }

        @Override
        public boolean validate(byte[] payload, byte[] signature) {
            return false;
        }
    };

    boolean validate(SignatureUpdater updater, byte[] signature);

    /** 真实接口里的默认重载：把字节数组包成 SignatureUpdater 再走抽象方法。 */
    default boolean validate(byte[] payload, byte[] signature) {
        return validate(output -> {
            try {
                output.update(payload);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, signature);
    }

    static SignatureValidator from(PublicKey key, String algorithm) {
        return (updater, signature) -> {
            try {
                java.security.Signature verifier = java.security.Signature.getInstance(algorithm);
                verifier.initVerify(key);
                updater.update(bytes -> {
                    try {
                        verifier.update(bytes);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                return verifier.verify(signature);
            } catch (Throwable t) {
                return false;
            }
        };
    }
}
