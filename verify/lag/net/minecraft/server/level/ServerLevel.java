package net.minecraft.server.level;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 替身：服务端世界（26.x 形状）。
 *
 * <p>三个被勘探切面挂钩的方法与真实版本同名同签名：
 * {@code tickChunk(LevelChunk,int)}、{@code tickNonPassenger(Entity)}、{@code tickPassenger(Entity,Entity)}；
 * 另有 {@code dimension()} 与 {@code getHeight(Heightmap.Types,int,int)} 分别供"维度归因"和"传送落点"使用。
 *
 * <p>每个区块的"耗时"由 {@link #chunkSleepMs} 配置：睡在<b>被挂钩的方法内部</b>，
 * 这样切面量到的就是确定的毫秒数，断言可以精确对数字。
 */
public class ServerLevel {

    /** 每个区块的区块 tick 耗时（毫秒）。 */
    public static final Map<Long, Long> chunkSleepMs = new HashMap<Long, Long>();

    /** 落点查询记录（断言用）。 */
    public static String lastHeightQuery;

    /** 建筑高度范围（替身里可调，用来验证空置域挖掘的 y 范围会被夹取）。 */
    public int minBuildHeight = -64;
    public int maxBuildHeight = 320;

    /** 世界 tick 次数（空置域挖掘任务的心跳来源之一）。 */
    public static int tickCalls;

    public int getMinBuildHeight() {
        return minBuildHeight;
    }

    public int getMaxBuildHeight() {
        return maxBuildHeight;
    }

    public void tick(java.util.function.BooleanSupplier hasTimeLeft) {
        tickCalls++;
    }

    /** 区块 tick 轨迹（断言归因用了哪些区块）。 */
    public static final List<String> tickTrace = new ArrayList<String>();

    private final ResourceKey dimensionKey;

    public ServerLevel(String dimension) {
        this.dimensionKey = ResourceKey.create(ResourceLocation.of(dimension));
    }

    public ResourceKey dimension() {
        return dimensionKey;
    }

    public void tickChunk(LevelChunk chunk, int randomTickSpeed) {
        if (LevelChunk.nestedInChunkTick) {
            // 模拟"方块实体 tick 被包在区块 tick 内部"的版本形态：
            // 勘探器应当据此把方块实体耗时从"合计"里去掉（运行期探测，不靠版本假设）
            chunk.tickBlockEntities();
        }
        long sleep = sleepOf(chunk);
        sleep(sleep);
        if (sleep > 0) {
            tickTrace.add("tickChunk:" + chunk.getPos().x + "," + chunk.getPos().z);
        }
    }

    public void tickNonPassenger(Entity entity) {
        sleep(entity.tickSleepMs);
    }

    public void tickPassenger(Entity vehicle, Entity passenger) {
        sleep(passenger.tickSleepMs);
    }

    public int getHeight(Heightmap.Types type, int x, int z) {
        lastHeightQuery = type + ":" + x + "," + z;
        return 70;
    }

    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static long sleepOf(LevelChunk chunk) {
        Long value = chunkSleepMs.get(key(chunk.getPos().x, chunk.getPos().z));
        return value == null ? 0L : value.longValue();
    }

    public static void sleep(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
