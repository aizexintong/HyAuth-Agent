package com.hyauth.agent;

import com.hyauth.agent.util.ChunkLagSampler;
import net.bytebuddy.asm.Advice;

/**
 * 乘客实体 tick 计时切面：{@code ServerLevel#tickPassenger(Entity, Entity)}。
 *
 * <p>与 {@link EntityTickAdvice} 是一对：原版把"非乘客实体"和"乘客实体"分成两条路 tick，
 * 只挂一条会漏掉船/矿车/骑乘生物上的乘客（一群人骑着同一批船时，恰好就是最卡的那一类）。
 * 这里真正被 tick 的是<b>第二个参数</b>（乘客），所以归因按它所在区块算。
 */
public class PassengerTickAdvice {

    @Advice.OnMethodEnter
    public static long enter() {
        return ChunkLagSampler.sampling() ? ChunkLagSampler.begin() : 0L;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Enter long start,
                            @Advice.This Object level,
                            @Advice.Argument(1) Object passenger) {
        if (start != 0L) {
            ChunkLagSampler.entityTick(start, level, passenger);
        }
    }
}
