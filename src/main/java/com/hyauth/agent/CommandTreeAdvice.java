package com.hyauth.agent;

import com.hyauth.agent.config.ListManager;
import com.hyauth.agent.util.AdminCommands;
import net.bytebuddy.asm.Advice;

/**
 * 命令树注册切面：在 {@code Commands} 的<b>构造结束</b>时，把我们的命令节点注册进真实的
 * Brigadier 命令树。
 *
 * <p>这样做解决三件事（只做"入口拦截"时它们都不成立）：
 * <ol>
 *   <li><b>Tab 补全</b>：客户端命令树里有了 {@code hy} / {@code lag} 与它们的一级子命令；</li>
 *   <li><b>聊天里点一下就能传送</b>：{@code ClickEvent.RunCommand} 会被客户端本地校验，
 *       命令不在树里就直接报"未知或不完整的命令"（真机上点传送就是这么失效的）；</li>
 *   <li>输入插件的命令不再显示为"未知命令"。</li>
 * </ol>
 *
 * <p>执行依旧由分发入口的切面（{@link CommandAdviceVoid} 等）负责，注册的节点只是让命令"存在"，
 * 带一个兜底执行器以防入口切面在某个版本上匹配不到。
 *
 * <p>时机：构造结束时玩家尚未登录，登录下发的命令树就已包含我们；{@code /hy reload} 会再注册一次。
 */
public class CommandTreeAdvice {

    @Advice.OnMethodExit
    public static void exit(@Advice.This Object commands) {
        try {
            AdminCommands.registerCommandTree(commands, ListManager.getCommandRoots());
        } catch (Throwable t) {
            System.err.println("[HyAuth] 注册命令树时出错（只影响补全与点击执行）: " + t);
        }
    }
}
