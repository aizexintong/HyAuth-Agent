package net.minecraft.world.level.chunk;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * 替身：区块（26.x 形状）。
 *
 * <p>{@code tickBlockEntities()} 是勘探"方块实体"那一类的切点；
 * {@code getPos()} / {@code getLevel()} 分别供坐标归因与维度归因使用。
 *
 * <p>{@link #nestedInChunkTick} 用来模拟"方块实体 tick 被包在区块 tick 内部"的版本差异：
 * 打开后，{@code ServerLevel#tickChunk} 会在这个方法里再调一次 {@code tickBlockEntities()}，
 * 于是勘探器应当探测到嵌套、把方块实体耗时从"合计"里去掉。
 */
public class LevelChunk {

    /** 测试用：模拟"区块 tick 内部会 tick 方块实体"。 */
    public static boolean nestedInChunkTick;

    /** 测试用：该区块每次 tickBlockEntities 的耗时（毫秒）。 */
    public long blockEntitySleepMs;

    private final ServerLevel level;
    private final ChunkPos pos;

    public LevelChunk(ServerLevel level, int x, int z) {
        this.level = level;
        this.pos = new ChunkPos(x, z);
    }

    public ChunkPos getPos() {
        return pos;
    }

    public ServerLevel getLevel() {
        return level;
    }

    public void tickBlockEntities() {
        ServerLevel.sleep(blockEntitySleepMs);
    }
}
