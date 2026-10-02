package com.hyauth.agent;

import com.hyauth.agent.util.ClearJob;
import net.bytebuddy.asm.Advice;

/**
 * 世界 tick 驱动切面：{@code ServerLevel#tick(BooleanSupplier)}。
 *
 * <p>用途只有一个：给<b>空置域挖掘任务</b>（{@code /hy clear}）提供 tick 心跳。
 * 这条心跳本来可以由 {@code MinecraftServer#tickServer} 提供，但那个方法名在版本间改过好几次
 * （早期 `tick`、后来 `tickServer`），只靠它就可能在某个版本上"任务永远不推进"。
 * 这里额外挂一条更稳定的世界级 tick，两条心跳互相兜底；
 * {@link ClearJob#onServerTick()} 内部有 45ms 时间闸，重复调用不会让任务跑得更快。
 *
 * <p>字节码约束同其它切面（README §8.4）：只调用注入到服务端加载器里的辅助类，
 * 自己字节码里不出现任何 {@code net.minecraft.*} 类型。
 */
public class LevelTickAdvice {

    @Advice.OnMethodEnter
    public static void enter() {
        ClearJob.onServerTick();
    }
}
