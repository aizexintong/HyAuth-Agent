package net.minecraft.server.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 测试替身：{@code ServerGamePacketListenerImpl}。
 *
 * <p>形状对齐 26.3 真实类（Paper 26.3 补丁里的原版代码）：
 * <pre>
 *   ServerPlayer player;                   // 接收者（真实类里未必是 public，这里故意用 private 验证反射取值）
 *   MinecraftServer server;                // 用来按 UUID 查发送者名字
 *   public void sendPlayerChatMessage(PlayerChatMessage message, ChatType.Bound chatType)
 *   public void sendDisguisedChatMessage(Component content, ChatType.Bound chatType)
 * </pre>
 * 两个发送方法都把调用记进 {@link #sent}，方便断言"这条消息到底走了哪条路"。
 */
public class ServerGamePacketListenerImpl {

    private final ServerPlayer player;
    private final MinecraftServer server;
    public final List<String> sent = new ArrayList<String>();

    public ServerGamePacketListenerImpl(ServerPlayer player, MinecraftServer server) {
        this.player = player;
        this.server = server;
    }

    /** 原版路径：带签名的玩家聊天包（会进签名账本）。 */
    public void sendPlayerChatMessage(PlayerChatMessage message, ChatType.Bound chatType) {
        sent.add("PLAYER:" + message.decoratedContent());
    }

    /** 原版路径：伪装聊天（不参与签名账本，客户端不做签名校验）。 */
    public void sendDisguisedChatMessage(Component content, ChatType.Bound chatType) {
        sent.add("DISGUISED:" + content);
    }

    public List<String> sent() {
        return sent;
    }
}
