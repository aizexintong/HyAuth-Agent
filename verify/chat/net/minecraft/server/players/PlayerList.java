package net.minecraft.server.players;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 测试替身：{@code PlayerList}（在线玩家表，按 UUID 找人）。 */
public class PlayerList {

    private final List<Object> players = new ArrayList<Object>();

    public void add(Object player) {
        players.add(player);
    }

    public Object getPlayer(UUID uuid) {
        for (Object player : players) {
            if (uuid.equals(uuidOf(player))) {
                return player;
            }
        }
        return null;
    }

    public List<Object> getPlayers() {
        return players;
    }

    private static UUID uuidOf(Object player) {
        try {
            Method method = player.getClass().getMethod("getUUID");
            Object value = method.invoke(player);
            return value instanceof UUID ? (UUID) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
