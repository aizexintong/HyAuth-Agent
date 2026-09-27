package net.minecraft.server.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;

/**
 * 测试替身：连接对象（字段类型放宽为 {@code Object}，方便塞进不同形状的玩家 / 服务端替身）。
 *
 * <p>用来覆盖两条退化路线：
 * <ul>
 *     <li>发送者是旧版形状（只有 {@code getGameProfile().getName()}，没有 {@code nameAndId()}）；</li>
 *     <li>在线玩家表没有 {@code getPlayer(UUID)}（按 UUID 找不到时退化为遍历 {@code getPlayers()}）。</li>
 * </ul>
 */
public class LegacyServerGamePacketListenerImpl {

    public final Object player;
    public final Object server;
    public final List<String> sent = new ArrayList<String>();

    public LegacyServerGamePacketListenerImpl(Object player, Object server) {
        this.player = player;
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
