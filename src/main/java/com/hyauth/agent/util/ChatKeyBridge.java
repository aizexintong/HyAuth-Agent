package com.hyauth.agent.util;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.PublicKey;

/**
 * 聊天签名密钥「桥接」校验器。
 *
 * <p>背景见 {@link ChatKeyPolicy}：原版只认 Mojang 服务密钥，外置账号（LittleSkin）的
 * 聊天密钥必然验不过，玩家会被直接踢下线。
 *
 * <p>本类不再"一律放行"，而是在中间插一层<b>真实的多级验签</b>：
 * <ol>
 *     <li><b>Mojang 官方校验器</b>先验（正版玩家命中此级，且不会产生任何额外网络请求）；</li>
 *     <li>失败则用 <b>LittleSkin 的公钥</b>（{@code signaturePublickey}，与属性验签用的是同一把）
 *         依次尝试 {@code SHA1withRSA / SHA256withRSA / SHA512withRSA} 验签 ——
 *         命中即说明该密钥确实由皮肤站签发；</li>
 *     <li>仍然失败时，按 {@code chat_key_strict} 决定：放行（默认）或拒绝（等同原版严格行为）。</li>
 * </ol>
 *
 * <p>每一级的命中情况都会打印一次日志，因此"LittleSkin 到底用什么算法签的"这一点
 * 可以在真实客户端上直接观测，无需事先假设。
 *
 * <p>实现要点：本类字节码里不能出现服务端类型（否则 Bundler 环境下切面挂载会失败，
 * 见 {@link AuthRejection}），因此校验器接口用类名反射获取，组合结果用
 * {@link Proxy} 动态实现。
 */
public final class ChatKeyBridge {

    /** 外部（皮肤站）公钥来源；延迟到 Mojang 校验失败后才调用。 */
    public interface ExternalKeySource {
        PublicKey get();
    }

    private static final String VALIDATOR_TYPE = "net.minecraft.util.SignatureValidator";

    /** 皮肤站可能使用的签名算法（逐个尝试，命中即通过）。 */
    private static final String[] EXTERNAL_ALGORITHMS = {
            "SHA1withRSA", "SHA256withRSA", "SHA512withRSA"
    };

    private static volatile boolean reportedMojang = false;
    private static volatile boolean reportedExternal = false;
    private static volatile boolean reportedNoMatch = false;

    private ChatKeyBridge() {
    }

    /**
     * 把原版校验器包装成「Mojang → LittleSkin → 策略」三级桥接校验器。
     *
     * @param original     原版（Mojang 服务密钥）校验器，可能为 null
     * @param externalKeys 皮肤站公钥来源，可为 null
     * @param strict       true = 全部验不过时拒绝（玩家会被踢下线）；false = 放行
     * @return 包装后的校验器；无法构造时原样返回 {@code original}
     */
    public static Object wrap(final Object original, final ExternalKeySource externalKeys, final boolean strict) {
        final Class<?> validatorType;
        final Method validateMethod;
        try {
            validatorType = Class.forName(VALIDATOR_TYPE, false, ChatKeyBridge.class.getClassLoader());
            validateMethod = findValidateMethod(validatorType);
            if (validateMethod == null) {
                System.err.println("[HyAuth] 聊天密钥桥接：未找到 validate 方法，保持原版校验器。");
                return original;
            }
        } catch (Throwable t) {
            System.err.println("[HyAuth] 聊天密钥桥接：无法解析 " + VALIDATOR_TYPE + "（" + t + "），保持原版校验器。");
            return original;
        }

        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if ("validate".equals(name) && args != null && args.length == 2) {
                    // ★ 用「调用方实际用的那个重载」去委托：
                    //   SignatureValidator 有两个 validate（SignatureUpdater 版与 byte[] 默认版），
                    //   调用方走哪个就必须用哪个去验，否则参数类型不匹配 → 恒为未通过。
                    if (original != null && passes(method, original, args)) {
                        reportMojang();
                        return Boolean.TRUE;
                    }
                    PublicKey key = externalKey(externalKeys);
                    if (key != null) {
                        for (String algorithm : EXTERNAL_ALGORITHMS) {
                            Object validator = create(validatorType, key, algorithm);
                            if (validator != null && passes(method, validator, args)) {
                                reportExternal(algorithm);
                                return Boolean.TRUE;
                            }
                        }
                    }
                    // 第 3 级：策略
                    reportNoMatch(strict);
                    return strict ? Boolean.FALSE : Boolean.TRUE;
                }
                // equals / hashCode / toString 等：不委托给 original（它可能为 null）
                if ("equals".equals(name)) {
                    return proxy == (args == null || args.length == 0 ? null : args[0]);
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("toString".equals(name)) {
                    return "HyAuthChatKeyBridge[" + (original == null ? "no-mojang-validator" : original) + "]";
                }
                return original == null ? null : invokeSafely(method, original, args);
            }
        };

        try {
            return Proxy.newProxyInstance(validatorType.getClassLoader(), new Class<?>[]{validatorType}, handler);
        } catch (Throwable t) {
            System.err.println("[HyAuth] 聊天密钥桥接：构造桥接校验器失败（" + t + "），保持原版校验器。");
            return original;
        }
    }

    /**
     * 找出真正的校验入口：{@code boolean validate(SignatureUpdater, byte[])}。
     *
     * <p>{@code SignatureValidator} 里有两个同名、参数个数也一样的 {@code validate}
     * （另一个是 {@code default boolean validate(byte[], byte[])}），
     * 这里只用来确认接口形状（抽象方法存在），<b>委托时必须用调用方实际调用的那个重载</b>。
     */
    private static Method findValidateMethod(Class<?> validatorType) {
        Method fallback = null;
        for (Method method : validatorType.getMethods()) {
            if (!"validate".equals(method.getName()) || method.getParameterTypes().length != 2
                    || method.getReturnType() != boolean.class) {
                continue;
            }
            Class<?> first = method.getParameterTypes()[0];
            if (first == byte[].class) {
                continue; // validate(byte[], byte[])：待签名内容已是字节数组，不是我们要调的那个
            }
            if (method.isDefault()) {
                fallback = method;
                continue;
            }
            return method; // 抽象方法 = 真正的校验入口 validate(SignatureUpdater, byte[])
        }
        return fallback;
    }

    private static boolean passes(Method method, Object validator, Object[] args) {
        try {
            return Boolean.TRUE.equals(method.invoke(validator, args));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 用皮肤站公钥构造一个原版校验器：{@code SignatureValidator.from(PublicKey, String)}。 */
    private static Object create(Class<?> validatorType, PublicKey key, String algorithm) {
        try {
            Method from = validatorType.getMethod("from", PublicKey.class, String.class);
            return from.invoke(null, key, algorithm);
        } catch (Throwable t) {
            return null;
        }
    }

    private static PublicKey externalKey(ExternalKeySource source) {
        if (source == null) {
            return null;
        }
        try {
            return source.get();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invokeSafely(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static void reportMojang() {
        if (!reportedMojang) {
            reportedMojang = true;
            System.out.println("[HyAuth] 聊天密钥桥接：Mojang 官方验签通过（正版账号，走原版校验）。");
        }
    }

    private static void reportExternal(String algorithm) {
        if (!reportedExternal) {
            reportedExternal = true;
            System.out.println("[HyAuth] 聊天密钥桥接：皮肤站公钥验签通过（算法 " + algorithm
                    + "）—— 外置账号的密钥确实是皮肤站签发的。");
        }
    }

    private static void reportNoMatch(boolean strict) {
        if (reportedNoMatch) {
            return;
        }
        reportedNoMatch = true;
        if (strict) {
            System.err.println("[HyAuth] 聊天密钥桥接：Mojang 与皮肤站公钥均未通过验签，"
                    + "chat_key_strict=true → 拒绝该密钥（该玩家会被踢下线）。");
        } else {
            System.out.println("[HyAuth] 聊天密钥桥接：Mojang 与皮肤站公钥均未通过验签，"
                    + "按 relax 策略放行（想改为拒绝请设 chat_key_strict=true）。");
        }
    }
}
