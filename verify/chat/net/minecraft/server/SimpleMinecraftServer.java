package net.minecraft.server;

import net.minecraft.server.players.SimplePlayerList;

/** 测试替身：只提供「没有 getPlayer(UUID) 的在线玩家表」的服务端。 */
public class SimpleMinecraftServer {

    private final SimplePlayerList playerList;

    public SimpleMinecraftServer(SimplePlayerList playerList) {
        this.playerList = playerList;
    }

    public SimplePlayerList getPlayerList() {
        return playerList;
    }
}
