package net.minecraft.server.level;

import java.util.UUID;

import net.minecraft.server.players.NameAndId;

/** 测试替身：26.3 形状的 {@code ServerPlayer}（玩家名走 {@code nameAndId().name()}）。 */
public class ServerPlayer {

    private final UUID uuid;
    private final String name;

    public ServerPlayer(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID getUUID() {
        return uuid;
    }

    public NameAndId nameAndId() {
        return new NameAndId(name);
    }

    @Override
    public String toString() {
        return "ServerPlayer[" + name + "]";
    }
}
