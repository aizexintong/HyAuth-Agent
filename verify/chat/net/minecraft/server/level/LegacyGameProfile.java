package net.minecraft.server.level;

/** 测试替身：旧版 {@code GameProfile}（{@code getName()} 取名字）。 */
public class LegacyGameProfile {

    private final String name;

    public LegacyGameProfile(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
