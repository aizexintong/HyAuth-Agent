package com.hyauth.agent;

import com.hyauth.agent.util.AdminCommands;
import net.bytebuddy.asm.Advice;

/**
 * 管理员命令接管切面：<b>返回 {@code boolean} 的版本</b>（部分版本/分支会这样声明）。
 *
 * <p>与被我们处理掉时把返回值置为 {@code true}（原版语义：这条命令已执行）。
 * 详见 {@link CommandAdviceVoid} 里关于"按返回类型分别挂载"的说明。
 */
public class CommandAdviceBoolean {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean enter(@Advice.This Object commands,
                               @Advice.Argument(0) Object source,
                               @Advice.Argument(1) String commandLine) {
        try {
            return AdminCommands.tryHandle(commands, source, commandLine);
        } catch (Throwable t) {
            System.err.println("[HyAuth] 管理员命令处理异常，已交还原版命令系统: " + t);
            t.printStackTrace();
            return false;
        }
    }

    @Advice.OnMethodExit
    public static void exit(@Advice.Enter boolean handled,
                            @Advice.Return(readOnly = false) boolean result) {
        if (handled) {
            result = true;
        }
    }
}
