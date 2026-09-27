package harness;

import com.mojang.authlib.GameProfile;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.UUID;

/**
 * HyAuth-Agent 端到端验证脚手架。
 *
 * <p>运行方式（由 harness.ps1 调用）：
 * {@code java -javaagent:HyAuth-Agent-1.0.0.jar -cp ... harness.HarnessMain <mode> <port>}
 *
 * <p>mode:
 * <ul>
 *     <li>{@code mock-modern}  —— 使用模拟的 Authlib 6.x 签名（String → ProfileResult）</li>
 *     <li>{@code mock-legacy}  —— 使用模拟的 Authlib ≤5.x 签名（GameProfile → GameProfile）</li>
 *     <li>{@code real-modern}  —— 使用真实 authlib 6.0.54</li>
 *     <li>{@code real-legacy}  —— 使用真实 authlib 3.11.49</li>
 * </ul>
 */
public final class HarnessMain {

    /** Authlib 的 Environment host 需要包含协议头；指向不可达端口以便透传用例快速失败。 */
    private static final String UNREACHABLE_HOST = "https://127.0.0.1:1";
    private static final String MOJANG_MARKER = "MOJANG_SIGNED_VALUE";

    private static int failures = 0;
    private static int checks = 0;

    public static void main(String[] args) throws Throwable {
        String mode = args.length > 0 ? args[0] : "mock-modern";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 25588;
        boolean mock = mode.startsWith("mock");

        MockLittleSkin server = new MockLittleSkin(port);
        server.start();
        try {
            Class<?> serviceClass = findServiceClass();
            Object service = instantiate(serviceClass, mode);
            Method hasJoined = findHasJoined(serviceClass);
            System.out.println("[HARNESS] mode=" + mode + " 目标方法: " + hasJoined);

            // ---------- 用例 1：非名单玩家必须透传原版逻辑 ----------
            VanillaProbe.vanillaCalled = false;
            Object premium = null;
            Throwable premiumError = null;
            try {
                premium = invoke(hasJoined, service, "PremiumPlayer");
            } catch (Throwable t) {
                premiumError = t;
            }
            check("透传: 未请求 LittleSkin", server.requestCount("PremiumPlayer") == 0);
            check("透传: 返回值不是 LittleSkin 资料",
                    !MockLittleSkin.LITTLESKIN_ID_DASHED.equalsIgnoreCase(String.valueOf(uuidOf(premium))));
            if (mock) {
                check("透传: 原版实现已被执行", VanillaProbe.vanillaCalled);
                check("透传: 返回原版(Mojang)资料", MOJANG_MARKER.equals(texturesValue(premium)));
            } else {
                System.out.println("  [info] 真实 Authlib 透传结果: result=" + describe(premium)
                        + " error=" + (premiumError == null ? "无" : premiumError.getClass().getName()));
            }

            // ---------- 用例 2：名单玩家走 LittleSkin 并替换返回值 ----------
            VanillaProbe.vanillaCalled = false;
            Object profile = invoke(hasJoined, service, "WhitelistPlayer");
            check("拦截: LittleSkin 被请求 1 次", server.requestCount("WhitelistPlayer") == 1);
            check("拦截: 未执行原版实现", !VanillaProbe.vanillaCalled);
            check("拦截: UUID 来自 LittleSkin",
                    MockLittleSkin.LITTLESKIN_ID_DASHED.equalsIgnoreCase(String.valueOf(uuidOf(profile))));
            check("拦截: 玩家名正确", "WhitelistPlayer".equals(nameOf(profile)));
            check("拦截: 返回类型匹配当前 Authlib 版本", resultTypeOk(profile, mode));
            String textures = texturesValue(profile);
            check("拦截: textures 属性验签通过并保留", textures != null && !MOJANG_MARKER.equals(textures));
            String query = server.lastQuery("WhitelistPlayer");
            check("拦截: 请求携带 username 与 serverId",
                    query != null && query.contains("username=WhitelistPlayer") && query.contains("serverId=harness-server-id"));
            check("拦截: 未携带 ip 参数（模拟未开启 prevent-proxy-connections）", query != null && !query.contains("ip="));

            // ---------- 用例 3：名单玩家但没有有效会话 → 必须拒绝连接 ----------
            VanillaProbe.vanillaCalled = false;
            Throwable denied = null;
            try {
                invoke(hasJoined, service, "NoSessionPlayer");
            } catch (Throwable t) {
                denied = t;
            }
            check("失败关闭: 抛出 AuthenticationUnavailableException",
                    denied != null && "com.mojang.authlib.exceptions.AuthenticationUnavailableException"
                            .equals(denied.getClass().getName()));
            check("失败关闭: 未降级回退原版实现", !VanillaProbe.vanillaCalled);

            // ---------- 用例 4：签名被篡改的属性必须丢弃，玩家仍可登录 ----------
            Object tampered = invoke(hasJoined, service, "TamperedPlayer");
            check("验签: 篡改签名的属性被丢弃", texturesValue(tampered) == null);
            check("验签: 玩家本体仍可登录", "TamperedPlayer".equals(nameOf(tampered)));

            // ---------- 用例 5：未签名属性必须丢弃 ----------
            Object unsigned = invoke(hasJoined, service, "UnsignedPlayer");
            check("验签: 未签名属性被丢弃", texturesValue(unsigned) == null);
            check("验签: 玩家本体仍可登录", "UnsignedPlayer".equals(nameOf(unsigned)));

            // ---------- 用例 6：离线名单（管理员手动指定 UUID，优先级最高） ----------
            VanillaProbe.vanillaCalled = false;
            Object offline = invoke(hasJoined, service, "offlineplayer"); // 故意用小写，验证名字写法以配置为准
            check("离线名单: UUID 为管理员指定值",
                    "99998888-7777-6666-5555-444433332222".equalsIgnoreCase(String.valueOf(uuidOf(offline))));
            check("离线名单: 名字统一为管理员写法", "OfflinePlayer".equals(nameOf(offline)));
            check("离线名单: 不请求 LittleSkin", server.requestCount("offlineplayer") == 0);
            check("离线名单: 未执行原版实现", !VanillaProbe.vanillaCalled);

            System.out.println("[HARNESS] " + (failures == 0 ? "全部通过" : "存在失败") 
                    + "：共 " + checks + " 项断言，失败 " + failures + " 项");
        } finally {
            server.stop();
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------
    // 断言与描述
    // ------------------------------------------------------------------

    private static void check(String name, boolean ok) {
        checks++;
        if (ok) {
            System.out.println("  [PASS] " + name);
        } else {
            failures++;
            System.out.println("  [FAIL] " + name);
        }
    }

    private static boolean resultTypeOk(Object result, String mode) {
        if (result == null) {
            return false;
        }
        boolean modern = mode.contains("modern") || mode.contains("10");
        return modern ? "ProfileResult".equals(result.getClass().getSimpleName()) : result instanceof GameProfile;
    }

    private static String describe(Object result) {
        if (result == null) {
            return "null";
        }
        return result.getClass().getName() + "{name=" + nameOf(result) + ", id=" + uuidOf(result)
                + ", textures=" + (texturesValue(result) != null) + "}";
    }

    // ------------------------------------------------------------------
    // 反射调用工具（同时兼容新旧 Authlib API）
    // ------------------------------------------------------------------

    /** 兼容不同 Authlib 大版本的会话服务实现类名。 */
    private static Class<?> findServiceClass() throws ClassNotFoundException {
        String[] candidates = {
                "com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService", // Authlib ≤ 9.x
                "com.mojang.authlib.services.MinecraftServicesSessionService"    // Authlib 10.x（MC 26.x）
        };
        ClassNotFoundException failure = null;
        for (String name : candidates) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException e) {
                failure = e;
            }
        }
        throw failure;
    }

    private static Method findHasJoined(Class<?> serviceClass) {        for (Method method : serviceClass.getMethods()) {
            if ("hasJoinedServer".equals(method.getName()) && method.getParameterTypes().length == 3) {
                return method;
            }
        }
        throw new IllegalStateException("目标类中找不到 hasJoinedServer(String/String..., 3 参数) 方法");
    }

    private static Object invoke(Method method, Object service, String username) throws Throwable {
        Object[] args = new Object[3];
        args[0] = method.getParameterTypes()[0] == String.class
                ? username
                : new GameProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8)),
                        username);
        args[1] = "harness-server-id";
        args[2] = null;
        try {
            return method.invoke(service, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Object instantiate(Class<?> serviceClass, String mode) throws Exception {
        if (mode.startsWith("mock")) {
            return serviceClass.getDeclaredConstructor().newInstance();
        }
        for (Constructor<?> constructor : serviceClass.getDeclaredConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            // Authlib ≤ 5.x: (YggdrasilAuthenticationService, Environment)
            if (types.length == 2 && types[0].getName().endsWith("AuthenticationService")) {
                constructor.setAccessible(true);
                Object environment = legacyEnvironment();
                Object authenticationService = Class.forName("com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService")
                        .getConstructor(java.net.Proxy.class, Class.forName("com.mojang.authlib.Environment"))
                        .newInstance(java.net.Proxy.NO_PROXY, environment);
                return constructor.newInstance(authenticationService, environment);
            }
            // Authlib ≥ 6.x: (ServicesKeySet, Proxy, Environment)  /  (ServicesKeySet, Proxy, DiscoveryService)
            if (types.length == 3 && types[1] == java.net.Proxy.class) {
                constructor.setAccessible(true);
                Object[] arguments = new Object[3];
                arguments[1] = java.net.Proxy.NO_PROXY;
                for (int i = 0; i < 3; i++) {
                    String typeName = types[i].getName();
                    if (i == 1) {
                        continue;
                    }
                    if (typeName.endsWith("ServicesKeySet")) {
                        arguments[i] = servicesKeySetStub(typeName);
                    } else if (typeName.endsWith("Environment")) {
                        arguments[i] = modernEnvironment();
                    }
                    // 其余参数（如 MinecraftServicesDiscoveryService）留空：被拦截的调用路径不会用到
                }
                return constructor.newInstance(arguments);
            }
        }
        throw new IllegalStateException("未能找到可用的构造器（mode=" + mode + "）");
    }

    private static Object modernEnvironment() throws Exception {
        Class<?> environment = Class.forName("com.mojang.authlib.Environment");
        return environment.getConstructor(String.class, String.class, String.class)
                .newInstance(UNREACHABLE_HOST, UNREACHABLE_HOST, "harness");
    }

    private static Object legacyEnvironment() throws Exception {
        Class<?> environment = Class.forName("com.mojang.authlib.Environment");
        return environment.getMethod("create", String.class, String.class, String.class, String.class, String.class)
                .invoke(null, UNREACHABLE_HOST, UNREACHABLE_HOST, UNREACHABLE_HOST, UNREACHABLE_HOST, "harness");
    }

    private static Object servicesKeySetStub(String typeName) {
        try {
            Class<?> type = Class.forName(typeName);
            if (type.isInterface()) {
                return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) {
                        return false;
                    }
                    if (returnType == int.class || returnType == long.class || returnType == short.class
                            || returnType == byte.class) {
                        return 0;
                    }
                    return null;
                });
            }
        } catch (Throwable ignored) {
            // 忽略：部分版本不需要该参数
        }
        return null;
    }

    private static GameProfile profileOf(Object result) throws Exception {
        if (result == null) {
            return null;
        }
        if (result instanceof GameProfile) {
            return (GameProfile) result;
        }
        Method accessor = result.getClass().getMethod("profile");
        return (GameProfile) accessor.invoke(result);
    }

    private static UUID uuidOf(Object result) {
        try {
            Object profile = profileOf(result);
            if (profile == null) {
                return null;
            }
            // Authlib ≤ 9.x: getId()；Authlib 10.x（record）: id()
            Object value = invokeAny(profile, "getId", "id");
            return (UUID) value;
        } catch (Exception e) {
            return null;
        }
    }

    private static String nameOf(Object result) {
        try {
            Object profile = profileOf(result);
            if (profile == null) {
                return null;
            }
            // Authlib ≤ 9.x: getName()；Authlib 10.x（record）: name()
            Object value = invokeAny(profile, "getName", "name");
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static String texturesValue(Object result) {
        try {
            Object profile = profileOf(result);
            if (profile == null) {
                return null;
            }
            // Authlib ≤ 9.x: getProperties()；Authlib 10.x（record）: properties()
            Object propertyMap = invokeAny(profile, "getProperties", "properties");
            if (propertyMap == null) {
                return null;
            }
            Object collection = propertyMap.getClass().getMethod("get", Object.class).invoke(propertyMap, "textures");
            if (!(collection instanceof Collection)) {
                return null;
            }
            for (Object property : (Collection<?>) collection) {
                return String.valueOf(propertyValue(property));
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static Object invokeAny(Object target, String... methodNames) throws Exception {
        for (String name : methodNames) {
            try {
                Method method = target.getClass().getMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                // 尝试下一个名字
            }
        }
        return null;
    }

    /** Authlib ≤ 9.x 为 getValue()，10.x 的 record 为 value()。 */
    private static Object propertyValue(Object property) throws Exception {
        return invokeAny(property, "getValue", "value");
    }
}
