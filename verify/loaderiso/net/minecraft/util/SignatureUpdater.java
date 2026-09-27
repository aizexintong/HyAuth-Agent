package net.minecraft.util;

/**
 * 测试替身：真实 {@code net.minecraft.util.SignatureUpdater} 的最小等价物。
 *
 * <p>真实实现由调用方提供一个「把待签名内容写进 Signature」的回调，
 * 校验器再决定用什么公钥/算法去验。这里保留同样的形状，
 * 以便验证 {@code ChatKeyBridge} 的反射调用链。
 */
public interface SignatureUpdater {

    interface Output {
        void update(byte[] bytes) throws Exception;
    }

    void update(Output output) throws Exception;
}
