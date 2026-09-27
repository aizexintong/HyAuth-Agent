package net.minecraft.server.players;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试替身：<b>没有</b> {@code getPlayer(UUID)} 的在线玩家表 ——
 * 用来验证 Agent 的退化路线（遍历 {@code getPlayers()} 按 UUID 找人）也能工作。
 */
public class SimplePlayerList {

    private final List<Object> players = new ArrayList<Object>();

    public void add(Object player) {
        players.add(player);
    }

    public List<Object> getPlayers() {
        return players;
    }
}
