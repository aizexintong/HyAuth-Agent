package net.minecraft.commands;

import java.util.ArrayList;
import java.util.List;

/**
 * 替身：命令系统（<b>与真实 26.3 形状一致</b>）。
 *
 * <p>26.3 实测（用真实 server.jar 反编译确认）：
 * <pre>
 *   public void performPrefixedCommand(net.minecraft.commands.CommandSourceStack, java.lang.String);
 *   public void performCommand(com.mojang.brigadier.ParseResults&lt;CommandSourceStack&gt;, java.lang.String);
 * </pre>
 * 两个方法都返回 <b>void</b>（更早的版本返回 int）。所以这里也声明成 void ——
 * v1.0.6 的替身写成了 int，而切面匹配条件是 {@code returns(int.class)}，
 * 于是"离线自检全绿、真机命令全落到原版"。替身与真实形状必须一致，这类假绿才不会再出现。
 *
 * <p>因为返回值不再承载信息，"有没有被我们接管"改看派发轨迹：被 Agent 处理掉的命令
 * <b>不会</b>出现在 {@link #vanilla} 里（原方法被跳过）；交给原版的命令会出现在里面。
 */
public class Commands {

    /** 真正落到"原版派发"的命令轨迹。 */
    public static final List<String> vanilla = new ArrayList<String>();

    public void performPrefixedCommand(CommandSourceStack source, String command) {
        String normalized = command.startsWith("/") ? command.substring(1) : command;
        vanilla.add("prefixed:" + normalized);
        performCommand(source, normalized);
    }

    public void performCommand(CommandSourceStack source, String command) {
        vanilla.add("plain:" + command);
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
