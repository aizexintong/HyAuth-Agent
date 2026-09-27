package com.hyauth.agent;

/**
 * 登录握手期间的请求上下文（ThreadLocal）。
 *
 * <p>用途：服务端 {@code ServerLoginPacketListenerImpl.handleHello} 与紧接着构造的
 * {@code ClientboundHelloPacket} 在同一个线程上顺序执行，前者知道玩家名、后者需要
 * 根据玩家名决定是否让客户端跳过会话校验，两者之间用本类传递。
 *
 * <p>名字在 {@code handleHello} 退出时清除，不会泄漏到其它连接。
 */
public final class LoginFlowContext {

    private static final ThreadLocal<String> REQUESTED_NAME = new ThreadLocal<String>();

    private LoginFlowContext() {
    }

    public static void set(String name) {
        if (name == null || name.isEmpty()) {
            REQUESTED_NAME.remove();
        } else {
            REQUESTED_NAME.set(name);
        }
    }

    /** 取出并清除（一次性使用，避免影响后续连接）。 */
    public static String consume() {
        String name = REQUESTED_NAME.get();
        REQUESTED_NAME.remove();
        return name;
    }

    public static void clear() {
        REQUESTED_NAME.remove();
    }

    /** 仅供自检使用。 */
    public static String peek() {
        return REQUESTED_NAME.get();
    }

    /**
     * 从 LoginStart 包里取出玩家名，兼容 record 的 {@code name()} 与旧版 {@code getName()}。
     * <p>纯反射实现：本类会被注入服务端类加载器，不能对 MC 类产生编译期依赖。
     */
    public static String nameOfLoginStart(Object packet) {
        if (packet == null) {
            return null;
        }
        if (packet instanceof String) {
            return (String) packet;
        }
        Object value = invokeAny(packet, "name", "getName");
        return value == null ? null : String.valueOf(value);
    }

    private static Object invokeAny(Object target, String... methodNames) {
        for (String name : methodNames) {
            try {
                java.lang.reflect.Method method = target.getClass().getMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (Throwable ignored) {
                // 尝试下一个访问器名字
            }
        }
        return null;
    }
}
