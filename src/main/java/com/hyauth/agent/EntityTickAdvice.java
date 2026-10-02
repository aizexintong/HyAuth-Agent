package com.hyauth.agent;

import com.hyauth.agent.util.ChunkLagSampler;
import net.bytebuddy.asm.Advice;

/**
 * 实体 tick 计时切面：{@code ServerLevel#tickNonPassenger(Entity)}。
 *
 * <p>原版在 {@code ServerLevel#tick} 里对每个非乘客实体各调一次，
 * 因此这里量到的耗时可以按"实体所在区块"归因 ——
 * 这是"某个区块养了 300 只怪"这类卡顿唯一能被看见的地方
 * （实体 tick 不在 {@code tickChunk} 里，只量区块 tick 是看不到的）。
 *
 * <p>字节码约束同 {@link ChunkTickAdvice}：全部用 {@link Object} 承载。
 */
public class EntityTickAdvice {

    @Advice.OnMethodEnter
    public static long enter() {
        return ChunkLagSampler.sampling() ? ChunkLagSampler.begin() : 0L;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Enter long start,
                            @Advice.This Object level,
                            @Advice.Argument(0) Object entity) {
        if (start != 0L) {
            ChunkLagSampler.entityTick(start, level, entity);
        }
    }
}
