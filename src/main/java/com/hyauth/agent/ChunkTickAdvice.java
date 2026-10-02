package com.hyauth.agent;

import com.hyauth.agent.util.ChunkLagSampler;
import net.bytebuddy.asm.Advice;

/**
 * 区块 tick 计时切面：{@code ServerLevel#tickChunk(LevelChunk, int)}。
 *
 * <p>这是勘探的<b>主干</b>：原版每个 tick 会对每个"正在 tick 的区块"调用一次，
 * 里面包含随机刻、冰雪/闪电处理等。量到它，就等于量到了"哪个区块在吃 tick"。
 *
 * <p><b>字节码约束（README §8.4）</b>：本切面由 system 加载器加载，
 * 因此方法签名与字节码里<b>不能出现任何 {@code net.minecraft.*} 类型</b>
 * ——{@code level} / {@code chunk} 一律用 {@link Object} 承载，
 * 真正的解析交给注入到服务端加载器里的 {@link ChunkLagSampler}。
 */
public class ChunkTickAdvice {

    /**
     * 进入计时。采样关着时直接返回 0（退出侧就不再记账），
     * 避免"没在采样还每秒做两万次 nanoTime"。
     */
    @Advice.OnMethodEnter
    public static long enter() {
        return ChunkLagSampler.sampling() ? ChunkLagSampler.beginChunkTick() : 0L;
    }

    /** 退出计时并记账（含异常路径：抛异常的那次 tick 同样是耗时，也要算）。 */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Enter long start,
                            @Advice.This Object level,
                            @Advice.Argument(0) Object chunk) {
        if (start != 0L) {
            ChunkLagSampler.endChunkTick(start, level, chunk);
        }
    }
}
