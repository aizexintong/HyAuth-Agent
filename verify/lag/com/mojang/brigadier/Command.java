package com.mojang.brigadier;

/** 替身：Brigadier 的 Command（注册进命令树的执行器要实现它）。 */
public interface Command {
    int run(com.mojang.brigadier.context.CommandContext context) throws Exception;
}
