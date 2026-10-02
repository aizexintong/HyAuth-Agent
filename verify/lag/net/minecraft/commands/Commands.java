package net.minecraft.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;

import java.util.ArrayList;
import java.util.List;

/**
 * 替身：命令系统（<b>与真实 26.3 形状一致</b>）。
 *
 * <p>26.3 实测（用真实 server.jar 反编译确认）：
 * <pre>
 *   public void performPrefixedCommand(net.minecraft.commands.CommandSourceStack, java.lang.String);  // 控制台/RCON
 *   public void performCommand(com.mojang.brigadier.ParseResults&lt;CommandSourceStack&gt;, java.lang.String); // 游戏内
 *   public com.mojang.brigadier.CommandDispatcher&lt;CommandSourceStack&gt; getDispatcher();
 * </pre>
 * 两个方法都返回 <b>void</b>（更早的版本返回 int），所以这里也声明成 void ——
 * v1.0.6 的替身写成 int 而切面要求 returns(int.class)，于是"离线自检全绿、真机命令全废"。
 *
 * <p>游戏内那条路的第一个参数是 <b>ParseResults</b> 而不是 CommandSourceStack：
 * 真机上因此出现过"命令被接管了、但聊天里什么都看不到"（权限与聊天都对着一份 ParseResults 找方法）。
 * 替身保留这个形状，专门锁住这个坑。
 */
public class Commands {

    /** 真正落到"原版派发"的命令轨迹。 */
    public static final List<String> vanilla = new ArrayList<String>();

    private final CommandDispatcher dispatcher = new CommandDispatcher();

    public Commands() {
        // 原版命令树里先放一个原版命令（/tp），用来断言我们没有打扰原版
        com.mojang.brigadier.tree.LiteralCommandNode tp =
                (com.mojang.brigadier.tree.LiteralCommandNode)
                        com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("tp").build();
        dispatcher.getRoot().addChild(tp);
    }

    public CommandDispatcher getDispatcher() {
        return dispatcher;
    }

    public void performPrefixedCommand(CommandSourceStack source, String command) {
        String normalized = command.startsWith("/") ? command.substring(1) : command;
        vanilla.add("prefixed:" + normalized);
        performCommand(source, normalized);
    }

    /** 旧版路径：第一个参数就是来源。 */
    public void performCommand(CommandSourceStack source, String command) {
        vanilla.add("plain:" + command);
    }

    /** 26.3 游戏内路径：第一个参数是 ParseResults，来源要从它的 context 里取。 */
    public void performCommand(ParseResults parseResults, String command) {
        vanilla.add("parsed:" + command);
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