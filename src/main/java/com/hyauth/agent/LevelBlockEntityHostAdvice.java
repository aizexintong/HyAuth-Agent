package com.hyauth.agent;

import com.hyauth.agent.util.ChunkLagSampler;
import net.bytebuddy.asm.Advice;

/**
 * 维度上下文切面：{@code Level#tickBlockEntities()}。
 *
 * <p>26.x 的方块实体是"维度级遍历 ticker"的（{@code Level#tickBlockEntities} → 逐个
 * {@code TickingBlockEntity#tick()}），而 ticker 自己不带 level 引用，
 * 所以只靠 ticker 那个切面拿不到维度 —— 真机报告里出现过 {@code unknown x[..] z[..]} 的行
 * （那些行只有"方块实体"有值，区块/实体都是 0，正是只走了这条路）。
 *
 * <p>这里在入口/出口记一下"当前维度"，ticker 切面取不到时就取它，报告的维度列就正常了。
 * 老版本（1.18~1.21.x）这个方法不存在，匹配为空、无副作用。
 */
public class LevelBlockEntityHostAdvice {

    @Advice.OnMethodEnter
    public static void enter(@Advice.This Object level) {
        try {
            ChunkLagSampler.beginLevelBlockEntities(level);
        } catch (Throwable ignored) {
            // 采样相关的任何异常都不该影响服务端 tick
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit() {
        try {
            ChunkLagSampler.endLevelBlockEntities();
        } catch (Throwable ignored) {
            // 同上
        }
    }
}