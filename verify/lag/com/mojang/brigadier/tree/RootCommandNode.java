package com.mojang.brigadier.tree;

/** 替身：根节点（原版命令挂这里，我们的节点也加到这里）。 */
public class RootCommandNode extends CommandNode {
    public RootCommandNode() {
        super("");
    }
}
