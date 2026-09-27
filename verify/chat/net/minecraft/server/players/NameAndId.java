package net.minecraft.server.players;

/** 测试替身：26.3 的 {@code NameAndId}（{@code nameAndId().name()} 是取玩家名的首选路径）。 */
public class NameAndId {

    private final String name;

    public NameAndId(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }
}
