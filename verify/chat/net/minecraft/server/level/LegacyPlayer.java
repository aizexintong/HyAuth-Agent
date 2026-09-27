package net.minecraft.server.level;

import java.util.UUID;

/**
 * 测试替身：旧版（Authlib ≤ 5.x 时代）形状的玩家对象 —— 只有
 * {@code getUUID()} 与 {@code getGameProfile().getName()}，没有 {@code nameAndId()}。
 *
 * <p>用来验证 Agent 的取名字逻辑两种写法都能吃。
 */
public class LegacyPlayer {

    private final UUID uuid;
    private final String name;

    public LegacyPlayer(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID getUUID() {
        return uuid;
    }

    public LegacyGameProfile getGameProfile() {
        return new LegacyGameProfile(name);
    }
}
