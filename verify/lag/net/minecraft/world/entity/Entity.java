package net.minecraft.world.entity;

import net.minecraft.core.BlockPos;

/** 替身：实体（只需 {@code chunkPosition()} 供勘探归因，以及一个"实体 tick 耗时"开关）。 */
public class Entity {

    private final int blockX;
    private final int blockZ;

    /** 测试用：这个实体每次 tick 要"耗时"多少毫秒。 */
    public long tickSleepMs;

    public Entity(int blockX, int blockZ, long tickSleepMs) {
        this.blockX = blockX;
        this.blockZ = blockZ;
        this.tickSleepMs = tickSleepMs;
    }

    public BlockPos chunkPosition() {
        return new BlockPos(blockX, blockZ);
    }
}
