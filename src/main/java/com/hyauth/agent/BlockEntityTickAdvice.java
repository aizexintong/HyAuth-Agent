package com.hyauth.agent;

import com.hyauth.agent.util.ChunkLagSampler;
import net.bytebuddy.asm.Advice;

/**
 * 方块实体 tick 计时切面：{@code LevelChunk#tickBlockEntities()}。
 *
 * <p>熔炉、漏斗、刷怪笼、村民工作站……都在这里 tick。原版是<b>按区块</b>遍历方块实体列表的，
 * 所以这个切点天然就是"按区块归因"，不用再从方块坐标反推。
 *
 * <p><b>重复计问题</b>：不同版本里这个方法可能被 {@code ServerLevel#tickChunk} 包在里面调用
 * （那就是"区块 tick 的子集"）。这里不猜版本：{@link ChunkLagSampler} 会在运行期探测调用栈，
 * 若确实嵌套就把它的耗时从"合计"里去掉，并在报告里写明口径。
 *
 * <p>维度信息从区块自身取（{@code LevelChunk#getLevel()} / {@code level} 字段），
 * 因此切面不需要额外参数，也就更不容易受版本差异影响。
 */
public class BlockEntityTickAdvice {

    @Advice.OnMethodEnter
    public static long enter() {
        return ChunkLagSampler.sampling() ? ChunkLagSampler.beginBlockEntityTick() : 0L;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Enter long start, @Advice.This Object chunk) {
        if (start != 0L) {
            ChunkLagSampler.endBlockEntityTick(start, chunk);
        }
    }
}
