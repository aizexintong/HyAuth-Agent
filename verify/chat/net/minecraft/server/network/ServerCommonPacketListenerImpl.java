package net.minecraft.server.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;

/**
 * 测试替身：连接监听器的<b>父类</b>，把「聊天发送方法」声明在这里（{@code server} 字段也是）。
 *
 * <p>有的版本把 {@code sendPlayerChatMessage} / {@code sendDisguisedChatMessage} 放在子类里，
 * 有的放在这个公共父类里。Agent 两个类都挂了切面，本替身用来验证
 * 「方法声明在父类、实例是子类」时同样能接管（并顺带验证字段取的是继承来的）。
 */
public class ServerCommonPacketListenerImpl {

    /** 真实类里这个字段不在 public（protected/private），这里故意用 private 验证反射取值。 */
    private final MinecraftServer server;
    public final List<String> sent = new ArrayList<String>();

    public ServerCommonPacketListenerImpl(MinecraftServer server) {
        this.server = server;
    }

    public void sendPlayerChatMessage(PlayerChatMessage message, ChatType.Bound chatType) {
        sent.add("PLAYER:" + message.decoratedContent());
    }

    public void sendDisguisedChatMessage(Component content, ChatType.Bound chatType) {
        sent.add("DISGUISED:" + content);
    }

    public List<String> sent() {
        return sent;
    }
}
