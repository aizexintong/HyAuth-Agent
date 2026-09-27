package net.minecraft.network.chat;

import java.util.UUID;

/**
 * 测试替身：{@code PlayerChatMessage}。
 *
 * <p>形状对齐 26.3 真实类（Paper 26.3 补丁里的源码）：
 * {@code sender() → UUID}、{@code decoratedContent() → Component}、{@code withUnsignedContent(Component)}。
 */
public class PlayerChatMessage {

    private final UUID sender;
    private final Component content;

    public PlayerChatMessage(UUID sender, Component content) {
        this.sender = sender;
        this.content = content;
    }

    public UUID sender() {
        return sender;
    }

    public Component decoratedContent() {
        return content;
    }
}
