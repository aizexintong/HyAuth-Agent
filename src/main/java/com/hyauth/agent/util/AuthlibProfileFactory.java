package com.hyauth.agent.util;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * 按当前服务端 Authlib 版本构造 {@code hasJoinedServer} 的返回值。
 *
 * <p>Authlib 的 API 在近几个大版本里改动很大，本类用反射把差异全部吃掉：
 *
 * <table border="1">
 *     <caption>版本差异</caption>
 *     <tr><th>版本</th><th>方法签名</th><th>GameProfile</th><th>返回值</th></tr>
 *     <tr>
 *         <td>Authlib ≤ 5.x（MC ≤ 1.20.4）</td>
 *         <td>{@code GameProfile hasJoinedServer(GameProfile, String, InetAddress)}</td>
 *         <td>{@code getProperties()} 可写 Multimap</td>
 *         <td>{@link GameProfile}</td>
 *     </tr>
 *     <tr>
 *         <td>Authlib 6.x ~ 9.x（MC 1.20.5 ~ 1.21.x）</td>
 *         <td>{@code ProfileResult hasJoinedServer(String, String, InetAddress)}</td>
 *         <td>{@code getProperties()} 可写 Multimap</td>
 *         <td>{@code com.mojang.authlib.yggdrasil.ProfileResult}</td>
 *     </tr>
 *     <tr>
 *         <td>Authlib 10.x（MC 26.x）</td>
 *         <td>{@code ProfileResult hasJoinedServer(String, String, InetAddress)}</td>
 *         <td>record，属性必须在构造时传入（{@code GameProfile(UUID, String, PropertyMap)}）</td>
 *         <td>{@code com.mojang.authlib.services.ProfileResult}</td>
 *     </tr>
 * </table>
 *
 * <p>判定方式：原方法第一个参数是 {@code GameProfile} 时说明是 Authlib ≤ 5.x 的老签名，
 * 直接返回 {@link GameProfile}；否则按新签名包装成对应版本的 {@code ProfileResult}。
 */
public final class AuthlibProfileFactory {

    /** Authlib 10.x（Minecraft 26.x）的返回类型。 */
    private static final String PROFILE_RESULT_SERVICES = "com.mojang.authlib.services.ProfileResult";

    /** Authlib 6.x ~ 9.x（Minecraft 1.20.5 ~ 1.21.x）的返回类型。 */
    private static final String PROFILE_RESULT_YGGDRASIL = "com.mojang.authlib.yggdrasil.ProfileResult";

    private static final String PROPERTY_MAP_CLASS = "com.mojang.authlib.properties.PropertyMap";

    private static final String GUAVA_MULTIMAP_FACTORY = "com.google.common.collect.LinkedHashMultimap";

    private AuthlibProfileFactory() {
    }

    /**
     * 构造鉴权成功后的返回值。
     *
     * @param profileOrName 原方法第一个参数，用于判定当前 Authlib 的方法形态
     * @param verified      已验签的 LittleSkin / 名单角色信息
     * @return 与当前方法返回类型匹配的对象；无法构造时返回 {@code null}（调用方按失败处理）
     */
    public static Object createResult(Object profileOrName, VerifiedProfile verified) {
        GameProfile profile = buildGameProfile(verified);

        if (profileOrName instanceof GameProfile) {
            // Authlib ≤ 5.x：原方法直接返回 GameProfile
            return profile;
        }
        // Authlib ≥ 6.x：第一个参数是 String 玩家名，需要返回 ProfileResult
        return wrapProfileResult(profile, profileOrName);
    }

    // ------------------------------------------------------------------
    // GameProfile 构造
    // ------------------------------------------------------------------

    private static GameProfile buildGameProfile(VerifiedProfile verified) {
        Object propertyMap = buildPropertyMap(verified);

        // Authlib 10.x：GameProfile 是 record，属性必须在构造时传入
        if (propertyMap != null) {
            Constructor<?> constructor =
                    findConstructor(GameProfile.class, UUID.class, String.class, propertyMap.getClass());
            if (constructor != null) {
                try {
                    return (GameProfile) constructor.newInstance(verified.getId(), verified.getName(), propertyMap);
                } catch (Throwable t) {
                    System.err.println("[HyAuth] 使用 GameProfile(UUID, String, PropertyMap) 构造失败，回退旧构造方式: " + t);
                }
            }
        }

        // Authlib ≤ 9.x：先构造空属性 Profile，再写入属性
        GameProfile profile = new GameProfile(verified.getId(), verified.getName());
        attachProperties(profile, verified);
        return profile;
    }

    /** 反射获取 profile 的属性容器并写入验签通过的属性（兼容 getProperties() 与 record 的 properties()）。 */
    private static void attachProperties(GameProfile profile, VerifiedProfile verified) {
        if (verified.getProperties().isEmpty()) {
            return;
        }
        Object propertyMap = null;
        try {
            Method accessor = findNoArgMethod(GameProfile.class, "getProperties", "properties");
            if (accessor != null) {
                accessor.setAccessible(true);
                propertyMap = accessor.invoke(profile);
            }
        } catch (Throwable t) {
            System.err.println("[HyAuth] 读取 GameProfile 属性容器失败: " + t);
        }
        if (propertyMap == null) {
            System.err.println("[HyAuth] 当前 Authlib 版本的 GameProfile 不支持写入属性，皮肤属性将被忽略。");
            return;
        }
        putProperties(propertyMap, verified);
    }

    /** 把验签通过的属性写入属性容器。 */
    private static void putProperties(Object propertyMap, VerifiedProfile verified) {
        if (verified.getProperties().isEmpty()) {
            return;
        }
        Method put = findTwoArgMethod(propertyMap.getClass(), "put");
        if (put == null) {
            System.err.println("[HyAuth] 属性容器 " + propertyMap.getClass().getName() + " 没有可用的 put 方法。");
            return;
        }
        put.setAccessible(true);
        for (VerifiedProperty property : verified.getProperties()) {
            try {
                put.invoke(propertyMap, property.getName(),
                        new Property(property.getName(), property.getValue(), property.getSignature()));
            } catch (Throwable t) {
                System.err.println("[HyAuth] 写入属性 " + property.getName() + " 失败: " + t);
            }
        }
    }

    /**
     * 构造并填充属性容器。
     * <ul>
     *     <li>Authlib ≤ 9.x：{@code new PropertyMap()}（可变），随后直接写入属性</li>
     *     <li>Authlib 10.x：先把属性写进 Guava {@code LinkedHashMultimap}，再
     *         {@code new PropertyMap(multimap)} —— 该构造器内部执行
     *         {@code ImmutableMultimap.copyOf(...)}，得到的是<b>不可变</b>容器，
     *         因此必须在包装之前写好数据</li>
     * </ul>
     */
    private static Object buildPropertyMap(VerifiedProfile verified) {
        Class<?> propertyMapType = loadClass(PROPERTY_MAP_CLASS, resolveLoader(null));
        if (propertyMapType == null) {
            return null;
        }

        // Authlib ≤ 9.x：可变容器，直接写入
        try {
            Constructor<?> constructor = propertyMapType.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object propertyMap = constructor.newInstance();
            putProperties(propertyMap, verified);
            return propertyMap;
        } catch (Throwable ignored) {
            // Authlib 10.x 没有无参构造器，走 Multimap 版本
        }

        Object multimap = newGuavaMultimap();
        if (multimap == null) {
            return null;
        }
        putProperties(multimap, verified);
        for (Constructor<?> constructor : propertyMapType.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 1 && parameters[0].isInstance(multimap)) {
                try {
                    return constructor.newInstance(multimap);
                } catch (Throwable t) {
                    System.err.println("[HyAuth] 构造 PropertyMap 失败: " + t);
                    return null;
                }
            }
        }
        return null;
    }

    private static Object newGuavaMultimap() {
        Class<?> type = loadClass(GUAVA_MULTIMAP_FACTORY, resolveLoader(null));
        if (type == null) {
            return null;
        }
        try {
            Method create = type.getMethod("create");
            return create.invoke(null);
        } catch (Throwable t) {
            System.err.println("[HyAuth] 创建 Guava Multimap 失败: " + t);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // ProfileResult 包装
    // ------------------------------------------------------------------

    private static Object wrapProfileResult(GameProfile profile, Object context) {
        ClassLoader loader = resolveLoader(context);
        for (String className : new String[]{PROFILE_RESULT_SERVICES, PROFILE_RESULT_YGGDRASIL}) {
            Class<?> type = loadClass(className, loader);
            if (type == null) {
                continue;
            }
            Constructor<?> single = findConstructor(type, GameProfile.class);
            if (single != null) {
                try {
                    return single.newInstance(profile);
                } catch (Throwable t) {
                    System.err.println("[HyAuth] 构造 " + className + " 失败: " + t);
                }
            }
            for (Constructor<?> constructor : type.getConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length == 2 && parameters[0].isInstance(profile)
                        && Set.class.isAssignableFrom(parameters[1])) {
                    try {
                        return constructor.newInstance(profile, Collections.emptySet());
                    } catch (Throwable t) {
                        System.err.println("[HyAuth] 构造 " + className + " 失败: " + t);
                    }
                }
            }
        }
        System.err.println("[HyAuth] 未找到可用的 ProfileResult 类型（已尝试 "
                + PROFILE_RESULT_SERVICES + " / " + PROFILE_RESULT_YGGDRASIL + "）。");
        return null;
    }

    // ------------------------------------------------------------------
    // 反射小工具
    // ------------------------------------------------------------------

    private static Constructor<?> findConstructor(Class<?> type, Class<?>... parameterTypes) {
        try {
            return type.getConstructor(parameterTypes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findNoArgMethod(Class<?> type, String... names) {
        for (String name : names) {
            try {
                return type.getMethod(name);
            } catch (Throwable ignored) {
                // 继续尝试下一个名字
            }
        }
        return null;
    }

    private static Method findTwoArgMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == 2) {
                return method;
            }
        }
        return null;
    }

    private static Class<?> loadClass(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 依次尝试：上下文对象的加载器 → Authlib 所在加载器 → 线程上下文加载器 → 系统加载器。 */
    private static ClassLoader resolveLoader(Object context) {
        ClassLoader loader = null;
        if (context != null) {
            loader = context.getClass().getClassLoader();
        }
        if (loader == null) {
            loader = GameProfile.class.getClassLoader();
        }
        if (loader == null) {
            loader = Thread.currentThread().getContextClassLoader();
        }
        if (loader == null) {
            loader = ClassLoader.getSystemClassLoader();
        }
        return loader;
    }
}
