package net.minecraft.server.network;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 测试替身：<b>子类</b>（{@code player} 字段声明在这里，聊天发送方法继承自父类）。
 *
 * <p>用来验证「切面挂在声明方法的父类上、实例是子类」这条路径。
 */
public class InheritedGamePacketListenerImpl extends ServerCommonPacketListenerImpl {

    public final ServerPlayer player;

    public InheritedGamePacketListenerImpl(ServerPlayer player, MinecraftServer server) {
        super(server);
        this.player = player;
    }
}
