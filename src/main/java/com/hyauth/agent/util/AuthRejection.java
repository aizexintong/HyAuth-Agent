package com.hyauth.agent.util;

/**
 * 「拒绝登录」工具类 —— 专门用来把 authlib 的异常类型从切面类的<b>字节码</b>里挪走。
 *
 * <p><b>为什么需要这个类（改动前务必读完，这里踩过一个致命坑）：</b>
 *
 * <p>Byte Buddy 的 {@code Advice.to(切面类)} 会对切面类做反射
 * （{@code Class#getDeclaredMethods()}）。而反射会触发 JVM 对切面类做<b>类校验</b>，
 * 校验时会解析「该类的字节码直接引用到的所有类型」。切面类是用
 * {@code -javaagent} 加载的，属于 system（应用）加载器；
 * 而原版 {@code server.jar}（Mojang Bundler）把 authlib 放在**子类加载器**里，
 * 应用加载器看不见它。于是切面类字节码里只要出现
 * {@code new AuthenticationUnavailableException(...)}、{@code instanceof GameProfile}、
 * {@code GameProfile.class} 这类直接引用，挂载阶段就会抛：
 *
 * <pre>
 * java.lang.NoClassDefFoundError: com/mojang/authlib/exceptions/AuthenticationUnavailableException
 *     at net.bytebuddy.asm.Advice.to(Advice.java:367)
 * </pre>
 *
 * <p>结果是<b>目标类完全不被改写、Agent 静默失效</b>：
 * 服务端照常开服，但名单一个都不生效，且只在控制台留下一段 Byte Buddy ERROR。
 *
 * <p>实测结论（见 {@code verify/loaderiso}）：
 * <ul>
 *   <li>切面类<b>自己</b>的字节码引用 authlib → 校验失败（签名引用同样失败）；</li>
 *   <li>切面类只是<b>调用</b>了别的类（哪怕那个类引用了 authlib / gson）→ 没问题。</li>
 * </ul>
 *
 * <p>所以：切面类里凡是需要 authlib 类型的地方，一律走本类这种「纯 JDK 反射」的中转，
 * 真正的类型解析推迟到运行期——那时代码已经内联进目标类，
 * 由目标类的加载器解析，它当然能看到 authlib。
 */
public final class AuthRejection {

    /** 拒绝登录时抛出的 authlib 异常（运行期按目标类加载器解析）。 */
    private static final String REJECTION_TYPE =
            "com.mojang.authlib.exceptions.AuthenticationUnavailableException";

    private AuthRejection() {
    }

    /**
     * 以「鉴权不可用」为由中断登录。
     *
     * <p>方法体只用 JDK 类型（反射），因此本类在应用加载器与目标加载器里都能通过校验。
     * 该方法会被 Byte Buddy 内联进目标类，所以必须是 {@code public static}。
     *
     * <p>失败关闭语义：无论如何都会抛出异常，绝不返回（即绝不放行）。
     */
    public static void reject(String message) {
        Throwable failure;
        try {
            ClassLoader loader = AuthRejection.class.getClassLoader();
            Class<?> type = Class.forName(REJECTION_TYPE, false, loader);
            failure = (Throwable) type.getConstructor(String.class).newInstance(message);
        } catch (Throwable t) {
            // 理论上不会走到：有 hasJoinedServer 就一定有 authlib。
            // 即便如此也要拒绝登录（fail-closed），只是换一种异常类型。
            failure = new IllegalStateException(
                    "HyAuth: 拒绝登录（未能构造 authlib 异常，请把该日志发给维护者）: " + message, t);
        }
        AuthRejection.<RuntimeException>sneakyThrow(failure);
    }

    /**
     * 抛出异常但不在签名里声明它（{@code throws T} 会被擦除成 {@code Throwable}）。
     * <p>public static：会被内联进目标类的字节码调用。
     */
    @SuppressWarnings("unchecked")
    public static <T extends Throwable> void sneakyThrow(Throwable throwable) throws T {
        throw (T) throwable;
    }
}
