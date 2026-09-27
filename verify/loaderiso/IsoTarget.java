package loaderiso;

import java.net.InetAddress;

/**
 * 模拟“服务端类加载器里的目标类”。
 *
 * <p>它由「父加载器为 platform 的 URLClassLoader」加载（与原版 Bundler 一致），
 * 声明的方法签名与 authlib 的 {@code hasJoinedServer} 完全一致，
 * 因此可以用它来复现真实服务端上切面挂载的每一步。
 *
 * <p>方法体返回 {@code "ORIGINAL"}：只要切面没有接管，调用者就能看到这个值。
 */
public class IsoTarget {

    public Object hasJoinedServer(Object profileOrName, String serverId, InetAddress address) {
        return "ORIGINAL";
    }
}
