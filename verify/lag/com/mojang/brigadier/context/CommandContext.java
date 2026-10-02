package com.mojang.brigadier.context;

/** 替身：命令上下文（能给出命令来源与原始输入，供兜底执行器回调）。 */
public class CommandContext {
    private final Object source;
    private final String input;

    public CommandContext(Object source, String input) {
        this.source = source;
        this.input = input;
    }

    public Object getSource() {
        return source;
    }

    public String getInput() {
        return input;
    }
}
