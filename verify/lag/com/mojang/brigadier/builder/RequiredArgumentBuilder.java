package com.mojang.brigadier.builder;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;

/** 替身：必选参数构建器（argument / executes / build）。 */
public class RequiredArgumentBuilder {
    private final String name;
    private final ArgumentType type;
    private Object command;

    private RequiredArgumentBuilder(String name, ArgumentType type) {
        this.name = name;
        this.type = type;
    }

    public static RequiredArgumentBuilder argument(String name, ArgumentType type) {
        return new RequiredArgumentBuilder(name, type);
    }

    public RequiredArgumentBuilder executes(Command command) {
        this.command = command;
        return this;
    }

    public CommandNode build() {
        CommandNode node = new CommandNode(name + "(" + type.getClass().getSimpleName() + ")");
        node.setCommand(command);
        return node;
    }
}
