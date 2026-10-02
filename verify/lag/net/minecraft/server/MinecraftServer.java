package net.minecraft.server;

import net.minecraft.commands.Commands;

import java.util.function.BooleanSupplier;

/** 替身：服务端主类（{@code tickServer} 是整服 MSPT 的切点）。 */
public class MinecraftServer {

    /** 每次 tickServer 的"耗时"（毫秒），测试可调。 */
    public long tickSleepMs;

    public static int tickServerCalls;

    /** 给 {@code createCommandSourceStack()} 用的世界（替身里由测试注入）。 */
    public net.minecraft.server.level.ServerLevel consoleLevel;

    private final Commands commands = new Commands();

    public Commands getCommands() {
        return commands;
    }

    /** 原版的"控制台命令源"（权限 4）：空置域挖掘用它执行 fill/forceload。 */
    public net.minecraft.commands.CommandSourceStack createCommandSourceStack() {
        return new net.minecraft.commands.CommandSourceStack(4, null, consoleLevel);
    }

    public void tickServer(BooleanSupplier hasTimeLeft) {
        tickServerCalls++;
        net.minecraft.server.level.ServerLevel.sleep(tickSleepMs);
    }
}
