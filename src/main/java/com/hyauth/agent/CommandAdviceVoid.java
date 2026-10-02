package com.hyauth.agent;

import com.hyauth.agent.util.AdminCommands;
import net.bytebuddy.asm.Advice;

/**
 * 管理员命令接管切面：<b>返回 {@code void} 的版本</b>。
 *
 * <p>为什么单独一个类：不同版本的服务端方法返回类型不一样 ——
 * MC 26.3 实测 {@code Commands#performPrefixedCommand(CommandSourceStack, String)} 与
 * {@code performCommand(ParseResults, String)} 都是 {@code void}；
 * 更早的版本里它们是 {@code int}（见 {@link CommandAdvice}）。
 * Byte Buddy 的 {@code @Advice.Return} 必须与被插桩方法的返回类型完全一致，
 * 所以这里按返回类型各写一个切面类，由 {@code AgentMain} 用
 * {@code returns(void.class) / returns(int.class) / returns(boolean.class)} 分别挂载。
 *
 * <p>⚠️ 教训（v1.0.6 → v1.0.7）：v1.0.6 只挂了 {@code int} 版本，而真机是 {@code void}，
 * 结果是"日志说已改写、命令却全落到原版"—— 而且日志在方法匹配为空时照样打印
 * "命中命令系统 / 已改写目标类字节码"。现在 {@code verify/realjar.ps1} 会拿真实的
 * server.jar 逐个核对切点的方法名/参数个数/返回类型，避免这类"静默不匹配"再发生。
 */
public class CommandAdviceVoid {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean enter(@Advice.This Object commands,
                               @Advice.Argument(0) Object source,
                               @Advice.Argument(1) String commandLine) {
        try {
            return AdminCommands.tryHandle(commands, source, commandLine);
        } catch (Throwable t) {
            // 命令处理出任何意外都必须放行给原版，绝不能让 /tp、/list 这类命令被我们吞掉
            System.err.println("[HyAuth] 管理员命令处理异常，已交还原版命令系统: " + t);
            t.printStackTrace();
            return false;
        }
    }
}
