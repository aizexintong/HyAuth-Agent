package net.minecraft.core;

/** 替身：方块坐标（真实 BlockPos 的 x/z 是 private final 字段，因此这里也只放字段 + getter）。 */
public class BlockPos {

    private final int x;
    private final int z;

    public BlockPos(int x, int z) {
        this.x = x;
        this.z = z;
    }

    public int getX() {
        return x;
    }

    public int getZ() {
        return z;
    }
}
