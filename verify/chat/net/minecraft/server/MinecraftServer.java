package net.minecraft.server;

import net.minecraft.server.players.PlayerList;

/** 测试替身：{@code MinecraftServer}（只需要提供在线玩家表）。 */
public class MinecraftServer {

    private final PlayerList playerList;

    public MinecraftServer(PlayerList playerList) {
        this.playerList = playerList;
    }

    public PlayerList getPlayerList() {
        return playerList;
    }
}
