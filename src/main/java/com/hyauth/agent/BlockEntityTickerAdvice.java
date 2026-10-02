package com.hyauth.agent;

import com.hyauth.agent.util.ChunkLagSampler;
import net.bytebuddy.asm.Advice;

/**
 * 方块实体 tick 计时切面（<b>26.x 的真实形状</b>）。
 *
 * <p>26.3 实测：{@code LevelChunk#tickBlockEntities()} 已经不存在了，方块实体改由
 * {@code Level#tickBlockEntities()} 做<b>维度级</b>遍历 —— 遍历的对象是
 * {@code net.minecraft.world.level.block.entity.TickingBlockEntity} 的实现，
 * 逐个调用它的 {@code tick()}。所以这里直接挂 {@code TickingBlockEntity} 各实现的
 * {@code tick()}，再用 ticker 的 {@code getPos()} 反推区块坐标，归因粒度仍是"按区块"。
 *
 * <p>为什么不用 {@code Level#tickBlockEntities()}：那是维度级的，拿不到区块上下文，
 * 只能给出"这个维度一共花了多少"，精度差一个量级。ticker 级虽然调用更频繁
 * （每个方块实体每 tick 一次），但开销只有一次 {@code System.nanoTime()} 与一次
 * 缓存过的反射取坐标，实测可接受。
 *
 * <p>老版本（1.18 ~ 1.21.x）里 {@code LevelChunk#tickBlockEntities()} 也在，但那些版本的
 * 方块实体同样走 ticker；只挂这一个切点就够，避免同一份耗时被记两次。
 */
public class BlockEntityTickerAdvice {

    @Advice.OnMethodEnter
    public static long enter() {
        return ChunkLagSampler.sampling() ? ChunkLagSampler.beginBlockEntityTick() : 0L;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Enter long start, @Advice.This Object ticker) {
        if (start != 0L) {
            ChunkLagSampler.endBlockEntityTickerTick(start, ticker);
        }
    }
}
