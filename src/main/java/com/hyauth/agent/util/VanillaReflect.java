package com.hyauth.agent.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端类（{@code net.minecraft.*}）的反射桥。
 *
 * <p><b>为什么勘探/管理功能全走反射</b>：
 * <ol>
 *     <li>本 Agent 的编译期 classpath 里<b>没有服务端 jar</b>：原版 {@code server.jar} 是 Bundler，
 *         {@code net.minecraft.*} 在解包后的子加载器里，类名与方法名随版本变动，
 *         唯一稳的做法是运行期按名字找；</li>
 *     <li>切面类（{@code *Advice}）由 {@code -javaagent} 的 system 加载器加载，
 *         字节码里<b>不允许</b>出现服务端类型（见 README §8.4），
 *         所以切面只传 {@code Object}，真正的取数逻辑放在<b>注入到服务端加载器</b>的辅助类里，
 *         由辅助类反射访问 —— 本类就是那个反射入口。</li>
 * </ol>
 *
 * <p><b>缓存策略</b>：类、方法、字段、构造器一律按「名字 + 加载器身份 + 参数形状」缓存；
 * <b>未命中也会缓存</b>（{@link #MISSING} / {@link #MISSING_CLASSES}），
 * 避免每秒上万次的区块/实体 tick 热路径上反复抛 {@code ClassNotFoundException}。
 *
 * <p><b>失败语义</b>：所有取数方法在失败时返回 {@code null}（或调用方给的默认值），
 * 并只在第一次失败时打一行告警（{@link #warnOnce}）——勘探功能坏掉不能拖累服务端主流程。
 */
public final class VanillaReflect {

    /** 成员缓存里"查过但没有"的哨兵值。 */
    private static final Object MISSING = new Object();

    /** 成员缓存：key = 类名 + 加载器身份 + 成员名 + 参数形状。 */
    private static final Map<String, Object> MEMBERS = new ConcurrentHashMap<String, Object>();

    /** 已解析到的类。 */
    private static final Map<String, Class<?>> CLASSES = new ConcurrentHashMap<String, Class<?>>();

    /** 已确认不存在的类（key 同 {@link #CLASSES}）。 */
    private static final Set<String> MISSING_CLASSES =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** 已经告警过的失败点，避免刷屏。 */
    private static final Set<String> WARNED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private VanillaReflect() {
    }

    // ------------------------------------------------------------------
    // 类与加载器
    // ------------------------------------------------------------------

    /**
     * 取"能看见 {@code net.minecraft.*} 的加载器"。
     *
     * <p>优先用传入对象的加载器：注入副本（辅助类在服务端加载器里）拿到的是服务端加载器，
     * 自检替身（mock 类在应用加载器里）拿到的是应用加载器，两种拓扑都能正确解析。
     */
    public static ClassLoader loaderFor(Object instance) {
        if (instance != null) {
            ClassLoader loader = instance.getClass().getClassLoader();
            if (loader != null) {
                return loader;
            }
        }
        ClassLoader own = VanillaReflect.class.getClassLoader();
        return own != null ? own : ClassLoader.getSystemClassLoader();
    }

    /** 按名字找类（找不到返回 {@code null}，结果会被缓存）。 */
    public static Class<?> findClass(String name, ClassLoader loader) {
        ClassLoader actual = loader != null ? loader : loaderFor(null);
        String key = name + '@' + Integer.toHexString(System.identityHashCode(actual));
        Class<?> cached = CLASSES.get(key);
        if (cached != null) {
            return cached;
        }
        if (MISSING_CLASSES.contains(key)) {
            return null;
        }
        try {
            Class<?> type = Class.forName(name, false, actual);
            CLASSES.put(key, type);
            return type;
        } catch (Throwable t) {
            MISSING_CLASSES.add(key);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 方法查找
    // ------------------------------------------------------------------

    /** 按「名字 + 参数个数」找方法（含父类与接口）。 */
    public static Method method(Class<?> owner, String name, int arity) {
        if (owner == null) {
            return null;
        }
        String key = owner.getName() + '@' + Integer.toHexString(System.identityHashCode(owner.getClassLoader()))
                + '#' + name + '/' + arity;
        Object cached = MEMBERS.get(key);
        if (cached != null) {
            return cached == MISSING ? null : (Method) cached;
        }
        Method found = search(owner, name, arity);
        MEMBERS.put(key, found == null ? MISSING : found);
        return found;
    }

    /** 按「名字 + 精确参数类型」找方法（含父类与接口），用于重载消歧。 */
    public static Method method(Class<?> owner, String name, Class<?>[] signature) {
        if (owner == null) {
            return null;
        }
        StringBuilder shape = new StringBuilder();
        for (Class<?> parameter : signature) {
            shape.append(parameter == null ? "?" : parameter.getName()).append(',');
        }
        String key = owner.getName() + '@' + Integer.toHexString(System.identityHashCode(owner.getClassLoader()))
                + '#' + name + '(' + shape + ')';
        Object cached = MEMBERS.get(key);
        if (cached != null) {
            return cached == MISSING ? null : (Method) cached;
        }
        Method found = null;
        for (Class<?> type = owner; type != null && found == null; type = type.getSuperclass()) {
            try {
                found = type.getDeclaredMethod(name, signature);
            } catch (NoSuchMethodException ignored) {
                // 沿父类继续找
            } catch (Throwable ignored) {
                // 某些加载器下 getDeclaredMethod 可能抛其它异常，按"没找到"处理
            }
        }
        if (found == null) {
            found = searchBySignatureInInterfaces(owner, name, signature);
        }
        if (found != null) {
            try {
                found.setAccessible(true);
            } catch (Throwable ignored) {
                // 未命名模块下一般都能放开；放不开就按默认可见性调用
            }
        }
        MEMBERS.put(key, found == null ? MISSING : found);
        return found;
    }

    private static Method search(Class<?> owner, String name, int arity) {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            for (Method candidate : declaredMethods(type)) {
                if (candidate.getName().equals(name) && candidate.getParameterTypes().length == arity) {
                    try {
                        candidate.setAccessible(true);
                    } catch (Throwable ignored) {
                        // 同上
                    }
                    return candidate;
                }
            }
        }
        for (Class<?> iface : owner.getInterfaces()) {
            Method found = search(iface, name, arity);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Method searchBySignatureInInterfaces(Class<?> owner, String name, Class<?>[] signature) {
        for (Class<?> iface : owner.getInterfaces()) {
            try {
                Method found = iface.getDeclaredMethod(name, signature);
                found.setAccessible(true);
                return found;
            } catch (Throwable ignored) {
                Method nested = searchBySignatureInInterfaces(iface, name, signature);
                if (nested != null) {
                    return nested;
                }
            }
        }
        Class<?> parent = owner.getSuperclass();
        return parent == null ? null : method(parent, name, signature);
    }

    private static Method[] declaredMethods(Class<?> type) {
        try {
            return type.getDeclaredMethods();
        } catch (Throwable t) {
            return new Method[0];
        }
    }

    /**
     * 按「参数个数 + 运行期类型可赋值」调用实例方法，<b>用于重载消歧</b>（失败返回 {@code null}）。
     *
     * <p>为什么需要它：原版很多方法是重载的，而按参数个数取会撞错重载，例如
     * <ul>
     *     <li>{@code CommandSourceStack#sendSuccess(Supplier,boolean)} 与 {@code sendSuccess(Component,boolean)}；</li>
     *     <li>{@code Style#withColor(ChatFormatting)} 与 {@code withColor(TextColor)}；</li>
     *     <li>{@code MutableComponent#withStyle(Style)} 与 {@code withStyle(ChatFormatting)}。</li>
     * </ul>
     * 本方法按「参数类型可从实参类型赋值」筛选，并在多个候选里优先选参数类型最贴近的那个，
     * 因此 {@code withClickEvent(ClickEvent)} 这种「接口参数 + record 实参」的情况也能命中。
     */
    public static Object callMatching(Object target, String name, Object... args) {
        if (target == null) {
            return null;
        }
        Method target0 = matchMethod(target.getClass(), name, args);
        return target0 == null ? null : invoke(target0, target, args);
    }

    /** 该对象是否存在「参数个数 + 类型」都匹配的方法（能力探测用，不做调用）。 */
    public static boolean hasMatching(Object target, String name, Object... args) {
        return target != null && matchMethod(target.getClass(), name, args) != null;
    }

    /**
     * 调用并回答"到底调成了没有" —— 专门解决 <b>void 方法</b>的判断问题。
     *
     * <p>为什么需要：26.x 的 {@code Commands#performPrefixedCommand} 返回 {@code void}，
     * 反射调用成功拿到的也是 {@code null}，所以 {@code result != null} 这种写法会把
     * "调用成功"误判成"失败"。v1.0.6 因此让空置域挖掘在真机上刚开就报"命令派发失败"。
     *
     * @return 找到方法且调用没有抛异常 = {@code true}；找不到方法或抛异常 = {@code false}
     */
    public static boolean callMatchingQuietly(Object target, String name, Object... args) {
        if (target == null) {
            lastCallError = new IllegalStateException("target 为 null（" + name + "）");
            return false;
        }
        Method method = matchMethod(target.getClass(), name, args);
        if (method == null) {
            lastCallError = new NoSuchMethodException(target.getClass().getName() + "#" + name);
            return false;
        }
        try {
            if (!method.isAccessible()) {
                method.setAccessible(true);
            }
            method.invoke(target, args);
            return true;
        } catch (Throwable t) {
            lastCallError = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null
                    ? t.getCause() : t;
            return false;
        }
    }

    /** 上一次 {@link #callMatchingQuietly} 失败的原因（诊断用，成功时为 null）。 */
    public static Throwable lastCallError() {
        return lastCallError;
    }

    private static volatile Throwable lastCallError;

    private static Method matchMethod(Class<?> owner, String name, Object[] args) {
        Class<?>[] argTypes = new Class<?>[args == null ? 0 : args.length];
        for (int i = 0; i < argTypes.length; i++) {
            Object arg = args[i];
            argTypes[i] = arg == null ? null : (arg instanceof Class ? (Class<?>) arg : arg.getClass());
        }
        Class<?>[] shape = new Class<?>[argTypes.length];
        for (int i = 0; i < argTypes.length; i++) {
            shape[i] = argTypes[i] == null ? Object.class : argTypes[i];
        }
        String key = owner.getName() + '@' + Integer.toHexString(System.identityHashCode(owner.getClassLoader()))
                + "#match:" + name + "(" + join(shape) + ")";
        Object cached = MEMBERS.get(key);
        if (cached != null) {
            return cached == MISSING ? null : (Method) cached;
        }

        Method best = null;
        int bestScore = -1;
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            for (Method candidate : declaredMethods(type)) {
                if (!candidate.getName().equals(name)) {
                    continue;
                }
                Class<?>[] parameters = candidate.getParameterTypes();
                if (parameters.length != argTypes.length) {
                    continue;
                }
                int score = 0;
                boolean compatible = true;
                for (int i = 0; i < parameters.length && compatible; i++) {
                    if (argTypes[i] == null) {
                        if (parameters[i].isPrimitive()) {
                            compatible = false; // 实参为 null 无法赋给基本类型
                        }
                        continue;
                    }
                    if (parameters[i] == argTypes[i]) {
                        score += 2; // 精确命中优先级最高
                    } else if (!assignable(parameters[i], argTypes[i])) {
                        compatible = false;
                    } else {
                        score += 1;
                    }
                }
                if (compatible && score > bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }
        if (best != null) {
            try {
                best.setAccessible(true);
            } catch (Throwable ignored) {
                // 同上：未命名模块一般都能放开
            }
        }
        MEMBERS.put(key, best == null ? MISSING : best);
        return best;
    }

    /** 装箱感知的可赋值判断（基本类型参数可接受对应包装类实参）。 */
    private static boolean assignable(Class<?> parameter, Class<?> argument) {
        if (parameter.isAssignableFrom(argument)) {
            return true;
        }
        return (parameter == boolean.class && argument == Boolean.class)
                || (parameter == int.class && argument == Integer.class)
                || (parameter == long.class && argument == Long.class)
                || (parameter == double.class && argument == Double.class)
                || (parameter == float.class && argument == Float.class)
                || (parameter == short.class && argument == Short.class)
                || (parameter == byte.class && argument == Byte.class)
                || (parameter == char.class && argument == Character.class);
    }

    private static String join(Class<?>[] types) {
        StringBuilder builder = new StringBuilder();
        for (Class<?> type : types) {
            builder.append(type.getName()).append(',');
        }
        return builder.toString();
    }

    /** 该对象是否能响应「名字 + 参数个数」的调用（能力探测用，不做调用）。 */
    public static boolean canCall(Object target, String name, int arity) {
        return target != null && method(target.getClass(), name, arity) != null;
    }

    /** 该类是否能响应「名字 + 精确参数类型」的调用。 */
    public static boolean canCall(Class<?> owner, String name, Class<?>[] signature) {
        return method(owner, name, signature) != null;
    }

    // ------------------------------------------------------------------
    // 调用
    // ------------------------------------------------------------------

    /** 按参数个数调用实例方法；失败返回 {@code null}。 */
    public static Object call(Object target, String name, Object... args) {
        if (target == null) {
            return null;
        }
        int arity = args == null ? 0 : args.length;
        Method target0 = method(target.getClass(), name, arity);
        return target0 == null ? null : invoke(target0, target, args);
    }

    /** 按精确参数类型调用实例方法（重载消歧）；失败返回 {@code null}。 */
    public static Object callExact(Object target, String name, Class<?>[] signature, Object... args) {
        if (target == null) {
            return null;
        }
        Method target0 = method(target.getClass(), name, signature);
        return target0 == null ? null : invoke(target0, target, args);
    }

    /** 按参数个数调用静态方法；失败返回 {@code null}。 */
    public static Object callStatic(Class<?> owner, String name, Object... args) {
        if (owner == null) {
            return null;
        }
        int arity = args == null ? 0 : args.length;
        Method target = method(owner, name, arity);
        return target == null ? null : invoke(target, null, args);
    }

    /** 按精确参数类型调用静态方法；失败返回 {@code null}。 */
    public static Object callStaticExact(Class<?> owner, String name, Class<?>[] signature, Object... args) {
        if (owner == null) {
            return null;
        }
        Method target = method(owner, name, signature);
        return target == null ? null : invoke(target, null, args);
    }

    /** 构造实例；失败返回 {@code null}。 */
    public static Object construct(Class<?> owner, Class<?>[] signature, Object... args) {
        if (owner == null) {
            return null;
        }
        try {
            Constructor<?> constructor = owner.getDeclaredConstructor(signature);
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (InvocationTargetException e) {
            warnOnce(owner, "<init>", e.getCause() == null ? e : e.getCause());
        } catch (Throwable t) {
            warnOnce(owner, "<init>", t);
        }
        return null;
    }

    /** 取枚举常量；失败返回 {@code null}。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object enumConstant(Class<?> enumType, String constant) {
        if (enumType == null || !enumType.isEnum()) {
            return null;
        }
        try {
            return Enum.valueOf((Class<? extends Enum>) enumType, constant);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invoke(Method target, Object receiver, Object[] args) {
        try {
            return target.invoke(receiver, args);
        } catch (InvocationTargetException e) {
            warnOnce(target.getDeclaringClass(), target.getName(), e.getCause() == null ? e : e.getCause());
        } catch (Throwable t) {
            warnOnce(target.getDeclaringClass(), target.getName(), t);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 便捷取数（失败给默认值，绝不抛）
    // ------------------------------------------------------------------

    public static int callInt(Object target, String name, int fallback, Object... args) {
        Object value = call(target, name, args);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    public static long callLong(Object target, String name, long fallback, Object... args) {
        Object value = call(target, name, args);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    public static double callDouble(Object target, String name, double fallback, Object... args) {
        Object value = call(target, name, args);
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    public static boolean callBoolean(Object target, String name, boolean fallback, Object... args) {
        Object value = call(target, name, args);
        return value instanceof Boolean ? ((Boolean) value).booleanValue() : fallback;
    }

    // ------------------------------------------------------------------
    // 字段
    // ------------------------------------------------------------------

    /** 读静态字段；失败返回 {@code null}。 */
    public static Object staticField(Class<?> owner, String name) {
        if (owner == null) {
            return null;
        }
        Field field = field(owner, name);
        if (field == null) {
            return null;
        }
        try {
            return field.get(null);
        } catch (Throwable t) {
            warnOnce(owner, name, t);
            return null;
        }
    }

    /** 读实例字段（沿类层次查）；失败返回 {@code null}。 */
    public static Object fieldValue(Object target, String name) {
        if (target == null) {
            return null;
        }
        Field field = field(target.getClass(), name);
        if (field == null) {
            return null;
        }
        try {
            return field.get(target);
        } catch (Throwable t) {
            warnOnce(target.getClass(), name, t);
            return null;
        }
    }

    private static Field field(Class<?> owner, String name) {
        String key = owner.getName() + '@' + Integer.toHexString(System.identityHashCode(owner.getClassLoader()))
                + "." + name;
        Object cached = MEMBERS.get(key);
        if (cached != null) {
            return cached == MISSING ? null : (Field) cached;
        }
        Field found = null;
        for (Class<?> type = owner; type != null && found == null; type = type.getSuperclass()) {
            try {
                found = type.getDeclaredField(name);
            } catch (Throwable ignored) {
                // 沿父类继续找
            }
        }
        if (found != null) {
            try {
                found.setAccessible(true);
            } catch (Throwable ignored) {
                // 同上
            }
        }
        MEMBERS.put(key, found == null ? MISSING : found);
        return found;
    }

    // ------------------------------------------------------------------
    // 诊断
    // ------------------------------------------------------------------

    /** 首次失败打一行告警（后续静默，避免热路径刷屏）。 */
    public static void warnOnce(Class<?> owner, String member, Throwable cause) {
        String name = (owner == null ? "?" : owner.getName()) + "#" + member;
        if (WARNED.add(name)) {
            System.err.println("[HyAuth] 反射调用失败（只提示一次）: " + name + " -> " + cause);
        }
    }

    /** 供 /hy lag status 输出：某能力是否可用。 */
    public static String available(boolean value) {
        return value ? "可用" : "不可用";
    }
}
