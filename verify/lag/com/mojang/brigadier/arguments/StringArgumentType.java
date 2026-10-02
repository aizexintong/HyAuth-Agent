package com.mojang.brigadier.arguments;

/** 替身：字符串参数类型（提供 greedyString，用于注册 "args" 子节点）。 */
public class StringArgumentType implements ArgumentType {
    private final boolean greedy;

    private StringArgumentType(boolean greedy) {
        this.greedy = greedy;
    }

    public static StringArgumentType greedyString() {
        return new StringArgumentType(true);
    }

    public boolean isGreedy() {
        return greedy;
    }
}
