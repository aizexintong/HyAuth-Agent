package loaderiso;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Bundler 类加载器隔离复现 / 回归测试。
 *
 * <p>真实原版服务端的加载器形态是：
 * <pre>
 *   platform 加载器
 *        └── URLClassLoader（服务端类、authlib、gson、guava…）
 *   system（应用）加载器：-javaagent 的类（Agent + Byte Buddy），<b>看不到 authlib</b>
 * </pre>
 *
 * <p>此前所有自检都把 authlib 放在应用类路径上，掩盖了一个致命问题：
 * {@code Advice.to(HasJoinedAdvice.class)} 会对切面类做反射，解析其方法签名中的
 * {@code AuthenticationUnavailableException}；由于应用加载器看不到 authlib，
 * 直接抛 {@code NoClassDefFoundError}，目标类**根本不会被改写**。
 *
 * <p>本测试把拓扑还原出来，并断言：
 * <ol>
 *   <li>应用加载器确实看不到 authlib（否则测试无意义）；</li>
 *   <li>{@code Advice.to(...)} 不再抛异常；</li>
 *   <li>能在 platform 父加载器下完成字节码改写并注入；</li>
 *   <li>离线名单玩家被切面接管、UUID 等于配置值；</li>
 *   <li>非名单玩家原样透传（返回 "ORIGINAL"）。</li>
 * </ol>
 */
public class LoaderIsoMain {

    private static int failures = 0;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        String libDir = args[0];
        String childDir = args[1];
        final String expectedUuid = "99998888-7777-6666-5555-444433332222";
        ClassLoader app = LoaderIsoMain.class.getClassLoader();

        // ---------- 0) 诊断：反射为何会失败 ----------
        diagnose(app, "loaderiso.MissingTypeUser",
                "本类方法体 new 了缺失类型（无签名引用）");
        diagnose(app, "loaderiso.Caller",
                "本类不引用缺失类型，只调用上面那个类");

        // ---------- 1) 前提：应用加载器必须看不到 authlib ----------
        boolean appSeesAuthlib = true;
        try {
            Class.forName("com.mojang.authlib.GameProfile", false, app);
        } catch (Throwable t) {
            appSeesAuthlib = false;
        }
        check(!appSeesAuthlib, "前提：应用加载器看不到 authlib（复现 Bundler 隔离）");
        if (appSeesAuthlib) {
            System.out.println("[loaderiso] 测试环境不成立：请勿把 authlib 放到应用类路径上。");
            System.exit(2);
        }

        // ---------- 2) 阶段一：对 Agent 加载器里的切面类执行 Advice.to ----------
        Class<?> adviceClass = Class.forName("com.hyauth.agent.HasJoinedAdvice", false, app);
        boolean stage1 = false;
        try {
            Advice.to(adviceClass);
            stage1 = true;
        } catch (Throwable t) {
            System.out.println("       异常: " + t);
        }
        check(stage1, "阶段一：Advice.to(Agent 加载器中的 HasJoinedAdvice) 不抛异常");

        // ---------- 3) 构造父加载器为 platform 的子加载器 ----------
        List<URL> urls = new ArrayList<URL>();
        String[] libs = {
                "authlib-10.0.77.jar", "gson-2.10.1.jar", "guava-33.4.8-jre.jar",
                "slf4j-api-2.0.16.jar", "commons-lang3-3.12.0.jar", "commons-io-2.11.0.jar"
        };
        for (String lib : libs) {
            urls.add(new File(libDir, lib).toURI().toURL());
        }
        urls.add(new File(childDir).toURI().toURL());
        URLClassLoader child = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
        System.out.println("[loaderiso] 服务端类加载器: " + child);

        // ---------- 4) 注入 Agent 辅助类（与真实运行路径一致） ----------
        Class<?> bridge = Class.forName("com.hyauth.agent.LoaderBridge", false, app);
        bridge.getMethod("ensureInjected", ClassLoader.class).invoke(null, child);
        Class<?> listManager = child.loadClass("com.hyauth.agent.config.ListManager");
        int offlineCount = (Integer) listManager.getMethod("offlinePlayerCount").invoke(null);
        check(offlineCount == 1,
                "子加载器里的 ListManager 读到 1 个离线名单玩家（实际 " + offlineCount + "）");

        // ---------- 5) 阶段二：改写目标类并注入子加载器 ----------
        ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(child);
        TypeDescription targetType = TypePool.Default.WithLazyResolution.of(locator)
                .describe("loaderiso.IsoTarget").resolve();

        Class<?> redefined = null;
        boolean stage2 = false;
        try {
            DynamicType.Unloaded<?> unloaded = new ByteBuddy()
                    .redefine(targetType, locator)
                    .visit(Advice.to(adviceClass)
                            .on(ElementMatchers.named("hasJoinedServer")
                                    .and(ElementMatchers.takesArguments(3))))
                    .make();
            redefined = unloaded.load(child, ClassLoadingStrategy.Default.INJECTION).getLoaded();
            stage2 = true;
        } catch (Throwable t) {
            System.out.println("       异常: " + t);
        }
        check(stage2, "阶段二：在 platform 父加载器下完成切面内联并注入");

        if (!stage2) {
            System.out.println("[loaderiso] 失败项: " + failures);
            System.exit(1);
        }

        // ---------- 6) 行为断言 ----------
        Object instance = redefined.getDeclaredConstructor().newInstance();
        Method hasJoined = redefined.getMethod(
                "hasJoinedServer", Object.class, String.class, java.net.InetAddress.class);

        Object offlineResult = hasJoined.invoke(instance, "OfflinePlayer", "serverid", null);
        check(offlineResult != null && !"ORIGINAL".equals(offlineResult),
                "离线名单玩家被切面接管，未落入原方法（返回 " + typeOf(offlineResult) + "）");
        String uuid = extractUuid(offlineResult);
        check(expectedUuid.equalsIgnoreCase(uuid),
                "返回的 UUID 等于配置中的管理员指定值（实际 " + uuid + "）");

        Object pass = hasJoined.invoke(instance, "NotListed", "serverid", null);
        check("ORIGINAL".equals(pass), "非名单玩家原样透传（返回 " + pass + "）");

        // ---------- 7) 聊天签名密钥策略（外置账号不被踢） ----------
        checkChatKeyPolicy(child, listManager);

        // ---------- 8) 无聊天公钥的离线玩家：指令按未签名处理 ----------
        checkKeylessCommandBypass(child);

        // ---------- 9) 离线玩家按玩家豁免 enforce-secure-profile ----------
        checkOfflineChatExempt(child);

        System.out.println(failures == 0 ? "[loaderiso] 全部通过" : "[loaderiso] 失败项: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * 切 {@code Decoder.unsigned(UUID, BooleanSupplier)} 的第二个参数：
     * 离线名单玩家 → 判断被换成恒 false；其他玩家 → 原样保留服务端设置。
     *
     * <p>这里顺带验证「切面能否作用于接口的静态方法」。
     */
    private static void checkOfflineChatExempt(URLClassLoader child) throws Exception {
        Class<?> advice = Class.forName("com.hyauth.agent.OfflineDecoderAdvice", false,
                LoaderIsoMain.class.getClassLoader());
        ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(child);
        TypeDescription target = TypePool.Default.WithLazyResolution.of(locator)
                .describe("net.minecraft.network.chat.SignedMessageChain$Decoder").resolve();

        Class<?> decoder = new ByteBuddy()
                .redefine(target, locator)
                .visit(Advice.to(advice)
                        .on(ElementMatchers.named("unsigned").and(ElementMatchers.takesArguments(2))))
                .make()
                .load(child, ClassLoadingStrategy.Default.INJECTION)
                .getLoaded();

        // AgentMain 用的是字符串类名，这里用同一串去匹配替身，确认 `$` 内部类写法能被 Byte Buddy 命中
        check(ElementMatchers.named("net.minecraft.network.chat.SignedMessageChain$Decoder").matches(target),
                "AgentMain 的类名匹配（含 $ 内部类）能命中聊天解码器工厂");

        Method unsigned = decoder.getMethod("unsigned", java.util.UUID.class,
                java.util.function.BooleanSupplier.class);
        Method unpack = decoder.getMethod("unpack", Object.class, Object.class);
        java.util.function.BooleanSupplier enforceTrue = () -> true;

        Object offline = unsigned.invoke(null,
                java.util.UUID.fromString("99998888-7777-6666-5555-444433332222"), enforceTrue);
        check("UNSIGNED_OK".equals(unpack.invoke(offline, null, null)),
                "离线名单玩家：解码器被豁免（可发未签名聊天），无需关闭全局开关");

        Object other = unsigned.invoke(null,
                java.util.UUID.fromString("11111111-2222-3333-4444-555555555555"), enforceTrue);
        check("MISSING_PROFILE_KEY".equals(unpack.invoke(other, null, null)),
                "其他玩家：仍按服务端 enforce-secure-profile 强制（行为与原版一致）");
    }

    /**
     * 离线名单玩家没有聊天公钥时，应改走原版的未签名分支；
     * 其他人（非名单 / 已有聊天公钥）必须完全不受影响。
     *
     * <p>这里直接调 {@code KeylessChatBypass}（子加载器里的注入副本），
     * 切面内联本身已由阶段二/阶段六覆盖。
     */
    private static void checkKeylessCommandBypass(URLClassLoader child) throws Exception {
        Class<?> listenerType = child.loadClass("loaderiso.IsoChatListener");
        Class<?> signableType = child.loadClass("loaderiso.IsoSignableCommand");
        Class<?> bypass = child.loadClass("com.hyauth.agent.util.KeylessChatBypass");

        Method shouldBypass = bypass.getMethod("shouldBypass", Object.class);
        Method collectUnsigned = bypass.getMethod("collectUnsigned", Object.class, Object.class);
        Object signable = signableType.getConstructor().newInstance();

        Object offline = listenerType.getConstructor(String.class).newInstance("OfflinePlayer");
        check(Boolean.TRUE.equals(shouldBypass.invoke(null, offline)),
                "离线名单玩家（无聊天公钥）：判定为需要回退");
        Object result = collectUnsigned.invoke(null, offline, signable);
        check(result instanceof java.util.Map
                        && Boolean.TRUE.equals(((java.util.Map<?, ?>) result).get("UNSIGNED")),
                "离线名单玩家：指令改走原版「未签名」分支，不再抛 DecodeException（返回 " + result + "）");

        Object stranger = listenerType.getConstructor(String.class).newInstance("NotListed");
        check(Boolean.FALSE.equals(shouldBypass.invoke(null, stranger)), "非名单玩家：不干预（走原版）");

        // 离线名单玩家即使 client 上报过密钥（chatSession 非空）也照旧回退：
        // 真机上出现过「有 chatSession 但服务端解码仍报 missing public key」的情况
        Object withKey = listenerType.getConstructor(String.class, boolean.class)
                .newInstance("OfflinePlayer", Boolean.TRUE);
        check(Boolean.TRUE.equals(shouldBypass.invoke(null, withKey)),
                "离线名单玩家（即便上报过密钥）：仍回退到未签名分支，保证指令可用");
    }

    /** 验证 relax_chat_keys 开/关两种行为，以及三级桥接验签。 */
    private static void checkChatKeyPolicy(URLClassLoader child, Class<?> listManager) throws Exception {
        Class<?> policy = child.loadClass("com.hyauth.agent.util.ChatKeyPolicy");
        Class<?> validatorType = child.loadClass("net.minecraft.util.SignatureValidator");
        Object noValidation = validatorType.getField("NO_VALIDATION").get(null);

        // 一个「严格校验、永远返回 false」的替身校验器（模拟 Mojang 校验器对外置密钥验不过）
        Object strict = java.lang.reflect.Proxy.newProxyInstance(child, new Class<?>[]{validatorType},
                (proxy, method, methodArgs) -> {
                    if ("validate".equals(method.getName())) {
                        return Boolean.FALSE;
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == methodArgs[0];
                    }
                    if ("toString".equals(method.getName())) {
                        return "strict-validator";
                    }
                    return null;
                });

        Method relax = policy.getMethod("relax", Object.class);

        Object relaxed = relax.invoke(null, strict);
        check(relaxed != strict && validatorType.isInstance(relaxed),
                "relax_chat_keys 默认开启：返回桥接校验器（不再是原版校验器）");

        Object relaxedNull = relax.invoke(null, new Object[]{null});
        check(relaxedNull == null, "原版本就没有校验器时保持 null（不干预）");

        // 关掉开关 → 必须完全恢复原版行为
        writeConfig(false);
        listManager.getMethod("reload").invoke(null);
        Object kept = relax.invoke(null, strict);
        check(kept == strict, "relax_chat_keys=false：原样返回，恢复原版严格校验");

        writeConfig(true);
        listManager.getMethod("reload").invoke(null);
        Object again = relax.invoke(null, strict);
        check(again != strict && validatorType.isInstance(again), "改回 true 后（热重载）桥接重新生效");

        checkChatKeyBridge(child, validatorType);
    }

    /**
     * 三级桥接的核心行为：Mojang 官方 → 皮肤站公钥（真 RSA 验签）→ 策略。
     */
    private static void checkChatKeyBridge(URLClassLoader child, Class<?> validatorType)
            throws Exception {
        Class<?> bridge = child.loadClass("com.hyauth.agent.util.ChatKeyBridge");
        Class<?> updaterType = child.loadClass("net.minecraft.util.SignatureUpdater");
        Class<?> sourceType = child.loadClass("com.hyauth.agent.util.ChatKeyBridge$ExternalKeySource");

        // 造一对“皮肤站密钥”，并生成一段内容 + 用私钥签名
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair pair = generator.generateKeyPair();
        byte[] payload = "hy-auth-chat-key-bridge".getBytes("UTF-8");
        byte[] goodSignature = sign(pair.getPrivate(), "SHA1withRSA", payload);
        byte[] badSignature = new byte[goodSignature.length];

        Object updater = updater(child, updaterType, payload);
        Method validate = validateMethod(validatorType);
        Method wrap = bridge.getMethod("wrap", Object.class, sourceType, boolean.class);

        // 一个「Mojang 校验器」：永远 false（模拟外置账号验不过）
        Object mojangStrict = java.lang.reflect.Proxy.newProxyInstance(child, new Class<?>[]{validatorType},
                (proxy, method, args) -> "validate".equals(method.getName()) ? Boolean.FALSE
                        : ("hashCode".equals(method.getName()) ? System.identityHashCode(proxy)
                        : ("equals".equals(method.getName()) ? proxy == args[0] : "mojang-strict")));

        Object withExternal = wrap.invoke(null, mojangStrict, keySource(child, sourceType, pair.getPublic()), false);
        check(Boolean.TRUE.equals(validate.invoke(withExternal, updater, goodSignature)),
                "桥接第 2 级：皮肤站公钥对真实 RSA 签名验签通过");

        check(Boolean.TRUE.equals(validate.invoke(withExternal, updater, badSignature)),
                "桥接第 3 级：验不过时默认放行（relax 策略，不会把玩家踢下线）");

        Object strictBridge = wrap.invoke(null, mojangStrict, keySource(child, sourceType, pair.getPublic()), true);
        check(Boolean.FALSE.equals(validate.invoke(strictBridge, updater, badSignature)),
                "chat_key_strict=true：验不过时拒绝该密钥");

        // 第 1 级：Mojang 校验器通过时，直接用它的结论（外置公钥根本不参与）
        Object mojangLoose = java.lang.reflect.Proxy.newProxyInstance(child, new Class<?>[]{validatorType},
                (proxy, method, args) -> "validate".equals(method.getName()) ? Boolean.TRUE
                        : ("hashCode".equals(method.getName()) ? System.identityHashCode(proxy)
                        : ("equals".equals(method.getName()) ? proxy == args[0] : "mojang-loose")));
        Object viaMojang = wrap.invoke(null, mojangLoose, null, true);
        check(Boolean.TRUE.equals(validate.invoke(viaMojang, updater, badSignature)),
                "桥接第 1 级：Mojang 官方验签通过即放行（正版账号不受影响）");

        // 没有 Mojang 校验器时，仍然能靠皮肤站公钥验签
        Object noMojang = wrap.invoke(null, null, keySource(child, sourceType, pair.getPublic()), true);
        check(Boolean.TRUE.equals(validate.invoke(noMojang, updater, goodSignature)),
                "没有原版校验器时，皮肤站公钥仍可独立完成验签");

        // 回归：桥接必须用「调用方实际调用的那个重载」去委托。
        // OVERLOAD_MISLEADING 的抽象重载返回 true、byte[] 默认重载返回 false：
        // 通过 byte[] 重载调用桥接时必须得到 false（说明委托用的就是 byte[] 那个）；
        // 若桥接写死用抽象重载去委托，这里会得到 true → 断言失败。
        Object misleading = validatorType.getField("OVERLOAD_MISLEADING").get(null);
        Object viaByteArray = wrap.invoke(null, misleading, null, true);
        Method byteArrayValidate = validatorType.getMethod("validate", byte[].class, byte[].class);
        check(Boolean.FALSE.equals(byteArrayValidate.invoke(viaByteArray, payload, badSignature)),
                "桥接按「调用方实际用的重载」委托（真机 bug：换重载后正版被判未通过）");

        // Mojang 校验器（两个重载都通过）→ 第 1 级命中
        Object mojangLike = validatorType.getField("MOJANG_LIKE").get(null);
        Object viaMojangLike = wrap.invoke(null, mojangLike, null, true);
        check(Boolean.TRUE.equals(validate.invoke(viaMojangLike, updater, badSignature)),
                "桥接第 1 级：Mojang 官方验签通过（正版账号不会被误判为未通过）");
    }

    private static Method validateMethod(Class<?> validatorType) {
        // 与 ChatKeyBridge 同样的挑选规则：排除 validate(byte[], byte[]) 那个默认重载
        Method fallback = null;
        for (Method method : validatorType.getMethods()) {
            if (!"validate".equals(method.getName()) || method.getParameterTypes().length != 2) {
                continue;
            }
            if (method.getParameterTypes()[0] == byte[].class) {
                continue;
            }
            if (method.isDefault()) {
                fallback = method;
                continue;
            }
            return method;
        }
        if (fallback != null) {
            return fallback;
        }
        throw new IllegalStateException("未找到 validate 方法");
    }

    /** 构造一个 SignatureUpdater：把 payload 原样喂给验签方。 */
    private static Object updater(URLClassLoader child, Class<?> updaterType, final byte[] payload) throws Exception {
        final Class<?> outputType = updaterType.getMethods()[0].getParameterTypes()[0];
        final Method outputUpdate = outputType.getMethod("update", byte[].class);
        return java.lang.reflect.Proxy.newProxyInstance(child, new Class<?>[]{updaterType},
                (proxy, method, args) -> {
                    if ("update".equals(method.getName())) {
                        outputUpdate.invoke(args[0], (Object) payload);
                    }
                    return null;
                });
    }

    /** 构造 ChatKeyBridge.ExternalKeySource（返回固定公钥）。 */
    private static Object keySource(URLClassLoader child, Class<?> sourceType, final java.security.PublicKey key)
            throws Exception {
        return java.lang.reflect.Proxy.newProxyInstance(child, new Class<?>[]{sourceType},
                (proxy, method, args) -> "get".equals(method.getName()) ? key : null);
    }

    private static byte[] sign(java.security.PrivateKey key, String algorithm, byte[] payload) throws Exception {
        java.security.Signature signer = java.security.Signature.getInstance(algorithm);
        signer.initSign(key);
        signer.update(payload);
        return signer.sign();
    }

    /** 重写测试配置，用于验证开关的两种行为。 */
    private static void writeConfig(boolean relaxChatKeys) throws Exception {
        String json = "{\n"
                + "  \"config_version\": -1," + "\n"
                + "  \"littleskin_players\": []," + "\n"
                + "  \"offline_players\": [ { \"name\": \"OfflinePlayer\", "
                + "\"uuid\": \"99998888-7777-6666-5555-444433332222\" } ],\n"
                + "  \"api_root\": \"http://127.0.0.1:25588/api/yggdrasil\",\n"
                + "  \"relax_chat_keys\": " + relaxChatKeys + ",\n"
                + "  \"debug\": true\n"
                + "}\n";
        File file = new File("littleskin_config.json");
        try (java.io.Writer writer = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
            writer.write(json);
        }
    }

    private static String typeOf(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }

    /** 反射一个「引用了当前加载器看不见的类型」的类，观察失败点。 */
    private static void diagnose(ClassLoader loader, String className, String label) {
        Class<?> type;
        try {
            type = Class.forName(className, false, loader);
        } catch (Throwable t) {
            System.out.println("[DIAG] " + className + " 加载失败: " + t);
            return;
        }
        try {
            type.getDeclaredMethods();
            System.out.println("[DIAG] " + className + " getDeclaredMethods() 成功（" + label + "）");
        } catch (Throwable t) {
            System.out.println("[DIAG] " + className + " getDeclaredMethods() 失败: " + t);
        }
    }

    /** 兼容 ProfileResult(profile()/getProfile()) 与 GameProfile(id()/getId())。 */
    private static String extractUuid(Object result) {
        if (result == null) {
            return null;
        }
        Object profile = result;
        Object unwrapped = invokeNoArg(result, "profile");
        if (unwrapped == null) {
            unwrapped = invokeNoArg(result, "getProfile");
        }
        if (unwrapped != null) {
            profile = unwrapped;
        }
        Object id = invokeNoArg(profile, "id");
        if (id == null) {
            id = invokeNoArg(profile, "getId");
        }
        return id == null ? null : id.toString();
    }

    private static Object invokeNoArg(Object target, String name) {
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
    /** 写配置时用的版本号 = ListManager.CONFIG_VERSION（跟着编译版本走）。 */
    private static int hyauthConfigVersion() {
        try {
            Class<?> type = Class.forName("com.hyauth.agent.config.ListManager");
            return type.getField("CONFIG_VERSION").getInt(null);
        } catch (Throwable t) {
            return 10000;
        }
    }
}
