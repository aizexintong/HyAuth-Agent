package com.mojang.brigadier.builder;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;

import java.util.ArrayList;
import java.util.List;

/** 替身：字面量构建器（literal / executes / then / build）。 */
public class LiteralArgumentBuilder {
    private final String name;
    private final List<CommandNode> children = new ArrayList<CommandNode>();
    private Object command;
    private java.util.function.Predicate requirement;

    private LiteralArgumentBuilder(String name) {
        this.name = name;
    }

    public static LiteralArgumentBuilder literal(String name) {
        return new LiteralArgumentBuilder(name);
    }

    /** 权限门槛（真实 Brigadier 的 ArgumentBuilder#requires）。 */
    public LiteralArgumentBuilder requires(java.util.function.Predicate requirement) {
        this.requirement = requirement;
        return this;
    }

    public java.util.function.Predicate getRequirement() {
        return requirement;
    }

    public LiteralArgumentBuilder executes(Command command) {
        this.command = command;
        return this;
    }

    public LiteralArgumentBuilder then(Object child) {
        if (child instanceof RequiredArgumentBuilder) {
            children.add(((RequiredArgumentBuilder) child).build());
        } else if (child instanceof LiteralArgumentBuilder) {
            children.add(((LiteralArgumentBuilder) child).build());
        }
        return this;
    }

    public LiteralCommandNode build() {
        LiteralCommandNode node = new LiteralCommandNode(name);
        node.setCommand(command);
        node.setRequirement(requirement);
        for (CommandNode child : children) {
            node.addChild(child);
        }
        return node;
    }
}
