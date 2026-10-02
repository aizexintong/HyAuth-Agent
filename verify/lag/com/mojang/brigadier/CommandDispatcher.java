package com.mojang.brigadier;

import com.mojang.brigadier.tree.RootCommandNode;

/** 替身：命令分发器（只需要 getRoot，原版命令树由它持有）。 */
public class CommandDispatcher {
    private final RootCommandNode root = new RootCommandNode();

    public RootCommandNode getRoot() {
        return root;
    }
}
