package net.minecraft.commands;

import java.util.ArrayList;
import java.util.List;

/**
 * 替身：命令系统。
 *
 * <p>复现原版形状：控制台 / 游戏内 / RCON 最终都走
 * {@code performPrefixedCommand(CommandSourceStack, String)}，它内部再调
 * {@code performCommand(CommandSourceStack, String)}（26.x 就是这么分层）。
 *
 * <p>两个方法都<b>返回 int</b>（执行了几条命令）——这正是 {@code CommandAdvice} 的匹配条件
 * （{@code takesArguments(2) && returns(int.class)}），因此替身能真实触发"拦截并跳过原方法"。
 * 被真正派发到原版的命令会记进 {@link #vanilla}，供断言"我们的命令没进原版"以及
 * "传送确实交给了原版 /tp"。
 */
public class Commands {

    /** 真正落到"原版派发"的命令轨迹。 */
    public static final List<String> vanilla = new ArrayList<String>();

    public int performPrefixedCommand(CommandSourceStack source, String command) {
        String normalized = command.startsWith("/") ? command.substring(1) : command;
        vanilla.add("prefixed:" + normalized);
        return performCommand(source, normalized);
    }

    public int performCommand(CommandSourceStack source, String command) {
        vanilla.add("plain:" + command);
        return 1;
    }

    public static void reset() {
        vanilla.clear();
    }

    public static boolean dispatched(String exact) {
        return vanilla.contains(exact);
    }

    public static boolean dispatchedAny(String contains) {
        for (String command : vanilla) {
            if (command.contains(contains)) {
                return true;
            }
        }
        return false;
    }

    public static String trace() {
        return vanilla.toString();
    }
}
