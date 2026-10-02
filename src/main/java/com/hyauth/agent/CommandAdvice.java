package com.hyauth.agent;

import com.hyauth.agent.util.AdminCommands;
import net.bytebuddy.asm.Advice;

/**
 * 管理员命令接管切面：{@code Commands#performPrefixedCommand(CommandSourceStack, String)}。
 *
 * <p><b>为什么切这里</b>：这是原版"一条命令字符串"的唯一汇聚点 ——
 * 控制台输入、游戏内 {@code /xxx}（签名版/未签名版指令包最终都汇到这里）、RCON 全都会经过它。
 * 在这里拦一次，就等于三种来源同时支持，且不需要往 Brigadier 里注册节点
 * （不注册节点 = 不可能与任何原版/插件命令撞名）。
 *
 * <p><b>为什么不注册真正的 Brigadier 命令节点</b>：那要求切面字节码引用
 * {@code com.mojang.brigadier.*} 与服务端类型（README §8.4 明令禁止），
 * 且要把命令树同步给每个客户端（登录时的命令树包），版本差异面大得多。
 * 代价是<b>没有 Tab 补全</b>——本项目选择"绝不让 Agent 自己成为服务端起不来的原因"。
 *
 * <p>处理逻辑：{@code AdminCommands.tryHandle} 只在命令首词命中我们配置的根命令
 * （默认 {@code hy} / {@code ha} / {@code hyauth} / {@code lag}）且权限够时才返回 {@code true}，
 * 此时<b>跳过原方法</b>（{@code skipOn = OnNonDefaultValue}）并让返回值保持"执行了 1 条命令"。
 * 其它命令一律返回 {@code false}，原版逻辑分毫不动。
 */
public class CommandAdvice {

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

    @Advice.OnMethodExit
    public static void exit(@Advice.Enter boolean handled,
                            @Advice.Return(readOnly = false) int result) {
        if (handled) {
            result = 1; // 原版语义：成功执行了 1 条命令
        }
    }
}
