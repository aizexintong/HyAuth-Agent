package com.mojang.brigadier;

/** 替身：解析结果（游戏内命令走 performCommand(ParseResults, String)，来源要从中取）。 */
public class ParseResults {
    private final com.mojang.brigadier.context.CommandContext context;

    public ParseResults(com.mojang.brigadier.context.CommandContext context) {
        this.context = context;
    }

    public com.mojang.brigadier.context.CommandContext getContext() {
        return context;
    }
}
