package com.hyauth.agent.util;

import java.util.function.Supplier;

/**
 * 管理员命令的输出通道：<b>控制台 + 聊天</b>，并且聊天那一份可以带
 * 「点一下就执行」的点击事件（用于 {@code /hy lag tp 1} 这种快速传送）。
 *
 * <p>为什么两路都要：
 * <ul>
 *     <li>控制台/RCON 执行时没有玩家，点击事件没有意义 → 控制台那一份是纯文本，
 *         并把「等价命令」跟在后面，方便复制；</li>
 *     <li>游戏内管理员执行时，聊天那一份带 {@code ClickEvent(run_command)} 与
 *         {@code HoverEvent(show_text)}，点一下就 tp 到现场。</li>
 * </ul>
 *
 * <p>版本差异全部由反射吃掉（26.x 之外都能退化）：
 * <ul>
 *     <li>点击事件：{@code ClickEvent$RunCommand(String)}（1.21.5+ 的 record 形）
 *         → 退化为 {@code ClickEvent(Action.RUN_COMMAND, String)}；</li>
 *     <li>悬停事件：{@code HoverEvent$ShowText(Component)} → 退化为 {@code HoverEvent(Action.SHOW_TEXT, Component)}；</li>
 *     <li>发消息：{@code sendSuccess(Supplier,boolean)} → {@code sendSystemMessage(Component)}
 *         → {@code sendMessage(Component)}；</li>
 *     <li>颜色：{@code ChatFormatting} → {@code EnumChatFormatting}，都拿不到就纯文本。</li>
 * </ul>
 * 任何一步失败都只降级、不抛异常：命令输出坏掉不能拖累服务端主流程。
 */
public final class ChatOut {

    /** 颜色常量名（ChatFormatting 的枚举名）。 */
    public static final String AQUA = "AQUA";
    public static final String GOLD = "GOLD";
    public static final String GREEN = "GREEN";
    public static final String RED = "RED";
    public static final String GRAY = "GRAY";
    public static final String YELLOW = "YELLOW";
    public static final String WHITE = "WHITE";

    private static final String COMPONENT = "net.minecraft.network.chat.Component";
    private static final String STYLE = "net.minecraft.network.chat.Style";
    private static final String CLICK_EVENT = "net.minecraft.network.chat.ClickEvent";
    private static final String HOVER_EVENT = "net.minecraft.network.chat.HoverEvent";
    private static final String CHAT_FORMATTING = "net.minecraft.ChatFormatting";
    private static final String LEGACY_CHAT_FORMATTING = "net.minecraft.EnumChatFormatting";

    /** 1 = sendSuccess(Supplier,boolean)，2 = sendSystemMessage(Component)，3 = sendMessage(Component)，0 = 未探测。 */
    private static volatile int sendMode = 0;

    private static volatile String sendPath = "未探测";

    private ChatOut() {
    }

    // ------------------------------------------------------------------
    // 对外输出
    // ------------------------------------------------------------------

    /** 普通信息行。 */
    public static void line(Object source, String text) {
        emit(source, text, null, null, null);
    }

    /** 标题 / 分组行（青色）。 */
    public static void head(Object source, String text) {
        emit(source, text, AQUA, null, null);
    }

    /** 成功 / 通过（绿色）。 */
    public static void ok(Object source, String text) {
        emit(source, text, GREEN, null, null);
    }

    /** 提示 / 需要注意（金色）。 */
    public static void warn(Object source, String text) {
        emit(source, text, GOLD, null, null);
    }

    /** 错误 / 拒绝（红色）。 */
    public static void error(Object source, String text) {
        emit(source, text, RED, null, null);
    }

    /** 次要信息（灰色）。 */
    public static void note(Object source, String text) {
        emit(source, text, GRAY, null, null);
    }

    /**
     * 可点击行：游戏内点一下就会以<b>执行者自己</b>的身份运行 {@code command}。
     *
     * @param text    显示文本
     * @param color   颜色常量名（可为 {@code null}）
     * @param command 点击执行的命令（带前导斜杠）
     * @param hover   悬停提示（可为 {@code null}）
     */
    public static void clickable(Object source, String text, String color, String command, String hover) {
        emit(source, text, color, command, hover);
    }

    /** 仅供 /hy lag status 输出：当前用的是哪条发送路径。 */
    public static String describeSendPath() {
        return sendPath;
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private static void emit(Object source, String text, String color, String command, String hover) {
        // 控制台那一份：永远有，且把可执行命令原样写出来（控制台点不了，但能复制）
        if (command == null) {
            System.out.println(text);
        } else {
            System.out.println(text + "   [命令: " + command + "]");
        }
        if (source == null) {
            return;
        }
        Object component = build(source, text, color, command, hover);
        if (component != null) {
            send(source, component);
        }
    }

    private static Object build(Object source, String text, String color, String command, String hover) {
        // ★ 用「命令来源的加载器」而不是本类的加载器：真实 Bundler 环境里两者都指向服务端加载器，
        //   但在子加载器拓扑（自检替身）下，本类可能位于父加载器而 net.minecraft.* 只对子加载器可见。
        ClassLoader loader = VanillaReflect.loaderFor(source);
        Class<?> componentClass = VanillaReflect.findClass(COMPONENT, loader);
        if (componentClass == null) {
            return null;
        }
        Object component = VanillaReflect.callStaticExact(componentClass, "literal",
                new Class<?>[]{String.class}, text);
        if (component == null) {
            return null;
        }
        Object clickEvent = command == null ? null : clickEvent(command, loader);
        Object hoverEvent = hover == null ? null : hoverEvent(hover, loader);
        if (color == null && clickEvent == null && hoverEvent == null) {
            return component;
        }
        return styled(component, color, clickEvent, hoverEvent, loader);
    }

    private static Object styled(Object component, String color, Object clickEvent, Object hoverEvent,
                                 ClassLoader loader) {
        Class<?> styleClass = VanillaReflect.findClass(STYLE, loader);
        Object style = VanillaReflect.staticField(styleClass, "EMPTY");
        if (style == null) {
            style = VanillaReflect.callStatic(styleClass, "empty");
        }
        if (style == null) {
            return component;
        }
        if (color != null) {
            Object formatting = formatting(color, loader);
            if (formatting != null) {
                Object next = VanillaReflect.callMatching(style, "withColor", formatting);
                if (next != null) {
                    style = next;
                }
            }
        }
        if (clickEvent != null) {
            Object next = VanillaReflect.callMatching(style, "withClickEvent", clickEvent);
            if (next != null) {
                style = next;
            }
        }
        if (hoverEvent != null) {
            Object next = VanillaReflect.callMatching(style, "withHoverEvent", hoverEvent);
            if (next != null) {
                style = next;
            }
        }
        Object result = VanillaReflect.callMatching(component, "withStyle", style);
        return result != null ? result : component;
    }

    /** {@code ClickEvent.RunCommand(String)}（新）或 {@code ClickEvent(Action.RUN_COMMAND, String)}（旧）。 */
    private static Object clickEvent(String command, ClassLoader loader) {
        Class<?> modern = VanillaReflect.findClass(CLICK_EVENT + "$RunCommand", loader);
        if (modern != null) {
            Object made = VanillaReflect.construct(modern, new Class<?>[]{String.class}, command);
            if (made != null) {
                return made;
            }
        }
        Class<?> legacy = VanillaReflect.findClass(CLICK_EVENT, loader);
        Class<?> action = VanillaReflect.findClass(CLICK_EVENT + "$Action", loader);
        Object runCommand = VanillaReflect.enumConstant(action, "RUN_COMMAND");
        if (legacy == null || runCommand == null) {
            return null;
        }
        return VanillaReflect.construct(legacy, new Class<?>[]{action, String.class}, runCommand, command);
    }

    /** {@code HoverEvent.ShowText(Component)}（新）或 {@code HoverEvent(Action.SHOW_TEXT, Component)}（旧）。 */
    private static Object hoverEvent(String text, ClassLoader loader) {
        Class<?> componentClass = VanillaReflect.findClass(COMPONENT, loader);
        if (componentClass == null) {
            return null;
        }
        Object body = VanillaReflect.callStaticExact(componentClass, "literal",
                new Class<?>[]{String.class}, text);
        if (body == null) {
            return null;
        }
        Class<?> modern = VanillaReflect.findClass(HOVER_EVENT + "$ShowText", loader);
        if (modern != null) {
            Object made = VanillaReflect.construct(modern, new Class<?>[]{componentClass}, body);
            if (made != null) {
                return made;
            }
        }
        Class<?> legacy = VanillaReflect.findClass(HOVER_EVENT, loader);
        Class<?> action = VanillaReflect.findClass(HOVER_EVENT + "$Action", loader);
        Object showText = VanillaReflect.enumConstant(action, "SHOW_TEXT");
        if (legacy == null || showText == null) {
            return null;
        }
        return VanillaReflect.construct(legacy, new Class<?>[]{action, componentClass}, showText, body);
    }

    private static Object formatting(String name, ClassLoader loader) {
        Class<?> type = VanillaReflect.findClass(CHAT_FORMATTING, loader);
        if (type == null) {
            type = VanillaReflect.findClass(LEGACY_CHAT_FORMATTING, loader);
        }
        return VanillaReflect.enumConstant(type, name);
    }

    /**
     * 把一条组件发给命令来源。
     *
     * <p>探测失败时不再"只打控制台"：直接按优先级**逐个真试**（sendSuccess → sendSystemMessage →
     * sendMessage），哪一个调成了就记住它。真机上出现过"探测说不可用、其实 sendSuccess 完全能用"
     * 的情况（探测依赖 {@code Component.literal} 与加载器解析，早期调用可能解析不到），
     * 所以这里以"实际调通"为准，而不是以探测结论为准。
     */
    private static void send(Object source, Object component) {
        int mode = resolveSendMode(source);
        if (mode <= 0 || mode == 1) {
            if (VanillaReflect.callMatchingQuietly(source, "sendSuccess", new ComponentSupplier(component),
                    Boolean.FALSE)) {
                remember(1, "CommandSourceStack#sendSuccess(Supplier,boolean)");
                return;
            }
            if (mode == 1) {
                sendMode = 0;   // 之前探测到的通道失效了（换加载器/换版本），下次重新探测
            }
        }
        if (mode <= 0 || mode == 2) {
            if (VanillaReflect.callMatchingQuietly(source, "sendSystemMessage", component)) {
                remember(2, "CommandSourceStack#sendSystemMessage(Component)");
                return;
            }
        }
        if (mode <= 0 || mode == 3) {
            if (VanillaReflect.callMatchingQuietly(source, "sendMessage", component)) {
                remember(3, "CommandSourceStack#sendMessage(Component)（旧版）");
                return;
            }
        }
        // 三个通道都调不通：控制台那一份已经在 emit() 里打过了，这里不再重复
    }

    private static void remember(int mode, String path) {
        if (sendMode != mode) {
            sendMode = mode;
            sendPath = path;
        }
    }

    private static int resolveSendMode(Object source) {
        if (sendMode == 0) {
            detect(source);
        }
        return sendMode;
    }

    private static synchronized void detect(Object source) {
        if (sendMode != 0) {
            return;
        }
        ClassLoader loader = VanillaReflect.loaderFor(source);
        Class<?> componentClass = VanillaReflect.findClass(COMPONENT, loader);
        Object probe = componentClass == null ? null
                : VanillaReflect.callStaticExact(componentClass, "literal", new Class<?>[]{String.class}, "probe");
        if (probe != null && VanillaReflect.hasMatching(source, "sendSuccess", new ComponentSupplier(probe),
                Boolean.FALSE)) {
            sendMode = 1;
            sendPath = "CommandSourceStack#sendSuccess(Supplier,boolean)";
        } else if (probe != null && VanillaReflect.hasMatching(source, "sendSystemMessage", probe)) {
            sendMode = 2;
            sendPath = "CommandSourceStack#sendSystemMessage(Component)";
        } else if (probe != null && VanillaReflect.hasMatching(source, "sendMessage", probe)) {
            sendMode = 3;
            sendPath = "CommandSourceStack#sendMessage(Component)（旧版）";
        } else {
            sendMode = -1;
            sendPath = "不可用（只能看控制台输出）";
        }
    }

    /** 是否至少有一个可用的聊天通道（能力探测用）。 */
    public static boolean chatAvailable(Object source) {
        return source != null && resolveSendMode(source) > 0;
    }

    /**
     * {@code sendSuccess} 需要一个 {@link Supplier}。
     *
     * <p>用 JDK 自带的 {@code java.util.function.Supplier}（bootstrap 加载器，两侧同一个类），
     * 因此跨加载器传参不会出问题。
     */
    private static final class ComponentSupplier implements Supplier<Object> {
        private final Object component;

        ComponentSupplier(Object component) {
            this.component = component;
        }

        @Override
        public Object get() {
            return component;
        }
    }

    /** 诊断辅助：Component.literal 是否可用（/hy lag status 用）。 */
    public static String componentAvailability() {
        return componentAvailability(null);
    }

    /** 诊断辅助（带命令来源：用它的加载器解析组件类）。 */
    public static String componentAvailability(Object source) {
        ClassLoader loader = source == null ? VanillaReflect.loaderFor(null) : VanillaReflect.loaderFor(source);
        Class<?> componentClass = VanillaReflect.findClass(COMPONENT, loader);
        if (componentClass == null) {
            return "net.minecraft.network.chat.Component 未找到";
        }
        Object probe = VanillaReflect.callStaticExact(componentClass, "literal", new Class<?>[]{String.class}, "x");
        if (probe == null) {
            return "Component.literal(String) 调用失败";
        }
        Class<?> styleClass = VanillaReflect.findClass(STYLE, loader);
        Object style = VanillaReflect.staticField(styleClass, "EMPTY");
        Object styled = style == null ? null : VanillaReflect.callMatching(probe, "withStyle", style);
        return styled == null ? "可用（literal），withStyle 不可用" : "可用（literal + withStyle）";
    }
}
