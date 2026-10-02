package com.hyauth.agent;

import com.hyauth.agent.util.ChunkLagSampler;
import com.hyauth.agent.util.ClearJob;
import net.bytebuddy.asm.Advice;

/**
 * 服务端整 tick 计时切面：{@code MinecraftServer#tickServer(BooleanSupplier)}。
 *
 * <p>它给出<b>全服 MSPT 基线</b>（大家平时说的"服务端多少 ms 一跳"），
 * 用来跟"区块 tick 中位数"对比：区块 tick 都很小、MSPT 却很高，说明卡在别的环节
 * （实体、网络、存档），这时候单看区块榜会误导人。
 *
 * <p><b>注意</b>：即使采样当前关着（{@code start == 0}）也照样要回调一次
 * {@link ChunkLagSampler#serverTick(long)} —— 它同时承担两件事：
 * <ol>
 *     <li>每个 tick 结束把"区块 tick 嵌套深度"清零（切面万一漏配也不会永久误判）；</li>
 *     <li>检查按需扫描是否到点，到点就自动出报告。</li>
 * </ol>
 */
public class ServerTickAdvice {

    @Advice.OnMethodEnter
    public static long enter() {
        return ChunkLagSampler.sampling() ? ChunkLagSampler.begin() : 0L;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Enter long start) {
        ChunkLagSampler.serverTick(start);
        // 空置域挖掘任务的 tick 心跳（内部自带时间闸，与 ServerLevel#tick 那条心跳互相兜底）
        ClearJob.onServerTick();
    }
}
