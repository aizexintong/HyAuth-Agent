package com.mojang.brigadier.tree;

import java.util.LinkedHashMap;
import java.util.Map;

/** 替身：命令树节点。 */
public class CommandNode {
    private final String name;
    private final Map<String, CommandNode> children = new LinkedHashMap<String, CommandNode>();
    private Object command;
    private java.util.function.Predicate requirement;

    public CommandNode(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void addChild(CommandNode child) {
        children.put(child.getName(), child);
    }

    public Map<String, CommandNode> getChildren() {
        return children;
    }

    public CommandNode getChild(String childName) {
        return children.get(childName);
    }

    public void setCommand(Object command) {
        this.command = command;
    }

    public Object getCommand() {
        return command;
    }

    public void setRequirement(java.util.function.Predicate requirement) {
        this.requirement = requirement;
    }

    public java.util.function.Predicate getRequirement() {
        return requirement;
    }
}
