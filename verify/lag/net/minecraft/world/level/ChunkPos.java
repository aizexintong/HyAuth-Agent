package net.minecraft.world.level;

/** 替身：区块坐标（真实 ChunkPos 就是 public final int x, z）。 */
public class ChunkPos {

    public final int x;
    public final int z;

    public ChunkPos(int x, int z) {
        this.x = x;
        this.z = z;
    }
}
