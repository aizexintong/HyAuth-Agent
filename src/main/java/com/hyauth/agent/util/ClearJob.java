package com.hyauth.agent.util;

import com.hyauth.agent.config.ListManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 空置域挖掘（"命令版世吞"）：管理员对角选两个角点，服务端用原版 {@code /fill}
 * 分三阶段把这片区域挖成空置域（perimeter）。
 *
 * <p><b>为什么要做这个</b>：小服务器上真造一台世界吞噬者（世吞）要铺几万格 TNT、
 * 上万个实体同时 tick，服务端基本会被拖死甚至崩掉。与其硬造世吞，
 * 不如让管理员"接受一个空置域"：用原版 {@code /fill}（批量置块、不逐格触发光照与实体更新）
 * 把区域直接挖空，代价只是"没有掉落物、没有爆炸特效"。
 *
 * <p><b>三阶段配方</b>（沿用社区世吞的作业顺序，本项目的实现是纯命令版）：
 * <ol>
 *     <li><b>外圈上方清空</b>：外扩一圈的矩形里，y = {@code top_y} 到建筑上限全部清成空气
 *         —— 先清高处，避免上面的方块掉进后面要挖的沟里；</li>
 *     <li><b>防爆沟</b>：沿外圈矩形的四条边，y = {@code min_y+1} 到 {@code top_y-1} 填
 *         {@code trench_block}（默认沙子）—— 世吞作业时用来兜住爆炸与落沙的边界；</li>
 *     <li><b>内圈清空</b>：内圈矩形 y = {@code min_y} 到 {@code top_y-1} 全部清成空气。</li>
 * </ol>
 *
 * <p><b>为什么是自己排程而不是像脚本那样生成数据包</b>：数据包（{@code /schedule} + {@code /function}）
 * 也能做到，但需要落盘、{@code /reload}、无法中途停、进度只能靠 {@code /say}，
 * 而且那种写法里常见的一步是全局 {@code kill @e[type=item]}（把全服掉落物一起扬了）。本实现：
 * <ul>
 *     <li>任务在内存里排队，由服务端 tick 驱动，<b>可按可配置节拍限速</b>（默认每 4 tick 做一步）；</li>
 *     <li>{@code /hy clear preview} <b>先预演</b>（体积、批次数、预计耗时），{@code start confirm} 才动手；</li>
 *     <li>{@code /hy clear stop} 立刻停，{@code status} 随时看进度；</li>
 *     <li>掉落物/实体清理是<b>按本区域范围</b>的（{@code @e[x=…,dx=…]}），不碰区域外的东西；</li>
 *     <li>重启即忘（不落盘）：一个能删掉几百万方块的破坏性任务，持久化反而是风险。</li>
 * </ul>
 *
 * <p><b>安全设计</b>：更高的权限门槛（默认等级 3）+ 必须 {@code confirm} + 边长/体积上限
 * （{@code clear.max_side} / {@code clear.max_volume}）+ 全程控制台审计日志。
 */
public final class ClearJob {

    /** 原版 {@code /fill} 单条命令的方块上限。 */
    private static final int FILL_LIMIT = 32768;

    /** 多个 tick 钩子都会调用 {@link #onServerTick()}，用 45ms 时间闸保证"每个 MC tick 只推进一次"。 */
    private static final long TICK_GATE_NANOS = 45_000_000L;

    /** 执行上下文。 */
    private static volatile Object commands;
    private static volatile Object consoleSource;
    private static volatile Object notifySource;
    private static volatile String dimension;
    private static volatile String dimensionOverride;
    private static volatile String who = "";

    /** 对角选择。 */
    private static volatile boolean hasFirst;
    private static volatile boolean hasSecond;
    private static volatile int firstX;
    private static volatile int firstZ;
    private static volatile int secondX;
    private static volatile int secondZ;

    /** 任务状态。 */
    private static volatile Plan plan;
    private static volatile int cursor;
    private static volatile boolean running;
    private static volatile int waitTicks;
    private static volatile int stepsPerCycle;
    private static volatile long blocksDone;
    private static volatile long startedMillis;
    private static volatile long lastTickNanos;
    private static volatile int announcedPercent;
    private static volatile int phaseOfStep;
    private static volatile boolean failed;

    private ClearJob() {
    }

    /** 计划里的一步：一条原版命令 + 执行后要等多少 tick。 */
    public static final class Step {

        final String command;
        final int waitTicks;
        final int phase;
        final long blocks;

        Step(String command, int waitTicks, int phase, long blocks) {
            this.command = command;
            this.waitTicks = waitTicks;
            this.phase = phase;
            this.blocks = blocks;
        }
    }

    /** 计划：步骤列表 + 统计 + 实际使用的 y 范围（已按维度建筑高度夹取）。 */
    private static final class Plan {
        final List<Step> steps = new ArrayList<Step>();
        long blocks;
        int fills;
        int batches;
        int minY;
        int topY;
        int maxY;
        boolean clamped;
        boolean bedrockKept;
    }

    // ==================================================================
    // 一、对角选择
    // ==================================================================

    /** 用执行者当前所站的方块坐标作为第 1/2 个角点。 */
    public static void selectHere(int index, Object source, String root) {
        int[] block = hereBlock(source, root);
        if (block == null) {
            return;
        }
        // 站在哪里就在哪个维度选点（显式 dim 覆盖在这里失效，避免"人在主世界、坐标却记到下界"）
        dimensionOverride = null;
        Object level = VanillaReflect.call(source, "getLevel");
        applyCorner(index, block[0], block[1], ChunkLagSampler.dimensionId(level), source, root);
    }

    /** 取执行者当前所站的方块坐标；取不到会打印提示并返回 {@code null}。 */
    private static int[] hereBlock(Object source, String root) {
        Object entity = VanillaReflect.call(source, "getEntity");
        if (entity == null) {
            ChatOut.warn(source, "控制台没有坐标：直接给数字即可，例如 /" + root
                    + " clear <x1> <z1> <x2> <z2>。");
            return null;
        }
        int[] block = ChunkLagSampler.entityBlock(entity);
        if (block == null) {
            ChatOut.error(source, "解析你所在坐标失败（版本差异），请改用四个数字：/" + root
                    + " clear <x1> <z1> <x2> <z2>");
            return null;
        }
        return block;
    }

    /** 用显式坐标作为第 1/2 个角点（控制台 / RCON 用）。 */
    public static void selectAt(int index, int blockX, int blockZ, Object source, String root) {
        String id = dimensionOverride != null ? dimensionOverride : dimensionOf(source);
        applyCorner(index, blockX, blockZ, id, source, root);
    }

    /**
     * 便捷选点（命令里最常用的形式），选完<b>立刻预演</b>：
     * <ul>
     *     <li>4 个数字 {@code x1 z1 x2 z2}：两个对角点都显式给；</li>
     *     <li>2 个数字 {@code x2 z2}：角点 1 = 执行者当前所站位置；</li>
     *     <li>1 个数字 {@code 边长}：以执行者当前所站位置为中心的方形（例如 {@code /hy clear 100} 挖 100x100）。</li>
     * </ul>
     */
    public static void quickSelect(Object source, String root, int[] numbers) {
        if (running) {
            ChatOut.warn(source, "有任务正在执行，先 /" + root + " clear stop 再改选区。");
            return;
        }
        if (numbers.length == 4) {
            selectAt(1, numbers[0], numbers[1], source, root);
            selectAt(2, numbers[2], numbers[3], source, root);
        } else if (numbers.length == 2) {
            int[] here = hereBlock(source, root);
            if (here == null) {
                ChatOut.note(source, "（两个数字的形式是「以你的站位为角点 1」；控制台请给四个数字）");
                return;
            }
            dimensionOverride = null;
            applyCorner(1, here[0], here[1], ChunkLagSampler.dimensionId(VanillaReflect.call(source, "getLevel")),
                    source, root);
            selectAt(2, numbers[0], numbers[1], source, root);
        } else if (numbers.length == 1) {
            int side = Math.max(1, numbers[0]);
            int[] here = hereBlock(source, root);
            if (here == null) {
                ChatOut.note(source, "（一个数字的形式是「以你为中心的边长」；控制台请给四个数字）");
                return;
            }
            dimensionOverride = null;
            int half = (side - 1) / 2;
            String id = ChunkLagSampler.dimensionId(VanillaReflect.call(source, "getLevel"));
            applyCorner(1, here[0] - half, here[1] - half, id, source, root);
            applyCorner(2, here[0] - half + side - 1, here[1] - half + side - 1, id, source, root);
            ChatOut.note(source, "以你为中心，边长 " + side + " 的方形选区。");
        }
        if (hasFirst && hasSecond) {
            preview(source, root); // 方便：选完直接给预演，确认后 /hy clear go
        }
    }

    /**
     * 手动指定目标维度（控制台 / RCON 挖下界、末地的空置域时用）：
     * {@code /hy clear dim the_nether}。
     */
    public static void selectDimension(Object source, String rawId, String root) {
        if (running) {
            ChatOut.warn(source, "任务执行中，先 /" + root + " clear stop。");
            return;
        }
        if (rawId == null || rawId.trim().isEmpty()) {
            ChatOut.line(source, "当前目标维度: " + (dimensionOverride == null
                    ? "跟随命令来源（控制台 = 主世界）" : dimensionOverride));
            ChatOut.note(source, "用法: /" + root + " clear dim the_nether（或 minecraft:the_end）");
            return;
        }
        resolveLevels(source);
        String id = rawId.trim().toLowerCase(Locale.ROOT);
        if (id.indexOf(':') < 0) {
            id = "minecraft:" + id;
        }
        if (ChunkLagSampler.levelForDimension(id) == null) {
            ChatOut.error(source, "找不到维度 " + rawId + "；当前能识别的维度: " + ChunkLagSampler.knownDimensions());
            return;
        }
        dimensionOverride = id;
        hasFirst = false;
        hasSecond = false;
        plan = null;
        ChatOut.ok(source, "已把目标维度固定为 " + ChunkLagSampler.dimensionLabel(id) + "（" + id
                + "）：接下来用坐标选点会落在该维度。");
    }

    /** 通过 {@code MinecraftServer#getAllLevels()} 把所有已加载维度登记进缓存（好按 id 校验）。 */
    private static void resolveLevels(Object source) {
        Object server = VanillaReflect.call(source, "getServer");
        Object levels = VanillaReflect.call(server, "getAllLevels");
        if (levels instanceof Iterable) {
            for (Object level : (Iterable<?>) levels) {
                if (level != null) {
                    ChunkLagSampler.dimensionId(level); // 顺手缓存 id → level
                }
            }
        }
    }

    private static void applyCorner(int index, int blockX, int blockZ, String dimensionId,
                                    Object source, String root) {
        if (running) {
            ChatOut.warn(source, "有任务正在执行，先 /" + root + " clear stop 再改选区。");
            return;
        }
        if ((hasFirst || hasSecond) && dimension != null && dimensionId != null && !dimension.equals(dimensionId)) {
            ChatOut.warn(source, "两个角点必须在同一个维度：当前选区在 " + dimension + "，你这次在 " + dimensionId
                    + "。先 /" + root + " clear reset 清掉选区。");
            return;
        }
        dimension = dimensionId;
        if (index == 1) {
            firstX = blockX;
            firstZ = blockZ;
            hasFirst = true;
        } else {
            secondX = blockX;
            secondZ = blockZ;
            hasSecond = true;
        }
        ChatOut.ok(source, "已选角点 " + index + ": x=" + blockX + " z=" + blockZ
                + "（维度 " + ChunkLagSampler.dimensionLabel(dimensionId) + "）");
        if (hasFirst && hasSecond) {
            ChatOut.line(source, "选区: " + describeSelection());
            ChatOut.note(source, "下一步: /" + root + " clear preview 预演（体积 / 批次 / 预计耗时）");
        } else {
            ChatOut.note(source, "再选另一个对角点：/" + root + " clear pos" + (hasFirst ? "2" : "1")
                    + "（站在那一点上执行即取当前坐标，也可以直接带坐标）");
        }
    }

    /** 清空选区与计划。 */
    public static void reset(Object source, String root) {
        if (running) {
            ChatOut.warn(source, "任务执行中，先 /" + root + " clear stop。");
            return;
        }
        hasFirst = false;
        hasSecond = false;
        plan = null;
        cursor = 0;
        blocksDone = 0;
        dimensionOverride = null;
        ChatOut.ok(source, "已清空选区、维度指定与预演计划。");
    }

    private static String describeSelection() {
        int[] b = bounds();
        return "内圈 x[" + b[0] + ".." + b[1] + "] z[" + b[2] + ".." + b[3] + "]（外圈各扩 1 格）· 维度 "
                + ChunkLagSampler.dimensionLabel(dimension);
    }

    /** {@code [X1, X2, Z1, Z2]}（已排序）。 */
    private static int[] bounds() {
        return new int[]{Math.min(firstX, secondX), Math.max(firstX, secondX),
                Math.min(firstZ, secondZ), Math.max(firstZ, secondZ)};
    }

    // ==================================================================
    // 二、预演 / 启动 / 停止 / 状态
    // ==================================================================

    /** 预演：只生成计划并打印统计，不动世界。 */
    public static void preview(Object source, String root) {
        if (running) {
            ChatOut.warn(source, "任务正在执行中，用 /" + root + " clear status 看进度。");
            return;
        }
        Plan built = build(source, root);
        if (built == null) {
            return;
        }
        plan = built;
        cursor = 0;
        blocksDone = 0;
        printPlan(source, built, root);
    }

    /** 开始执行（必须带 confirm）。 */
    public static void start(Object source, Object commandSystem, String root, boolean confirmed) {
        if (running) {
            ChatOut.warn(source, "已经有挖掘任务在跑了：/" + root + " clear status 看进度，/"
                    + root + " clear stop 可以停。");
            return;
        }
        if (plan == null || cursor >= plan.steps.size()) {
            Plan built = build(source, root);
            if (built == null) {
                return;
            }
            plan = built;
            cursor = 0;
            blocksDone = 0;
            printPlan(source, built, root);
        }
        if (!confirmed) {
            ChatOut.warn(source, "这是**不可撤销**的大范围删方块操作：确认无误后执行 /"
                    + root + " clear start confirm");
            return;
        }
        Object server = VanillaReflect.call(source, "getServer");
        Object console = server == null ? null : VanillaReflect.call(server, "createCommandSourceStack");
        if (console == null) {
            console = source; // 退路：直接用发起者（游戏内玩家也能跑，但玩家下线后命令源会失效）
        }
        commands = commandSystem;
        consoleSource = console;
        notifySource = source;
        who = String.valueOf(VanillaReflect.call(source, "getTextName"));
        running = true;
        failed = false;
        waitTicks = 1;
        stepsPerCycle = Math.max(1, ListManager.getClearFillsPerStep());
        announcedPercent = 0;
        phaseOfStep = 1;
        startedMillis = System.currentTimeMillis();

        ChatOut.head(source, "===== 开始挖掘空置域 =====");
        ChatOut.line(source, "选区: " + describeSelection());
        ChatOut.line(source, "共 " + plan.steps.size() + " 步（fill " + plan.fills + " 条）· 预计清理约 "
                + format(plan.blocks) + " 方块 · 节流 每 " + ListManager.getClearIntervalTicks() + " tick "
                + stepsPerCycle + " 步 → 预计约 " + estimatedSeconds(plan) + " 秒");
        ChatOut.note(source, "随时: /" + root + " clear status 看进度 · /" + root
                + " clear stop 中止（已挖的不会回滚）");
        ChatOut.clickable(source, "  ▶ 点击中止挖掘", ChatOut.GOLD, "/" + root + " clear stop",
                "立即停止任务（已挖掉的方块不会回滚）");
        System.out.println("[HyAuth] 空置域挖掘开始: " + describeSelection() + "，计划 " + plan.steps.size()
                + " 步 / 约 " + format(plan.blocks) + " 方块（发起者: " + who + "）");
    }

    /** 中止任务（已挖掉的不回滚）。 */
    public static void stop(Object source, String root) {
        if (!running) {
            ChatOut.warn(source, "当前没有正在执行的挖掘任务。");
            return;
        }
        running = false;
        int percent = progressPercent();
        ChatOut.warn(source, "已中止空置域挖掘（进度 " + percent + "%，约清理 " + format(blocksDone)
                + " 方块）。已挖掉的方块不会回滚。");
        System.out.println("[HyAuth] 空置域挖掘被中止: " + describeSelection() + "，进度 " + percent
                + "%，已清理约 " + format(blocksDone) + " 方块");
        if (notifySource != null && notifySource != source) {
            ChatOut.warn(notifySource, "空置域挖掘已被中止（进度 " + percent + "%）。");
        }
    }

    /** 进度与状态。 */
    public static void status(Object source, String root) {
        ChatOut.head(source, "===== 空置域挖掘状态 =====");
        if (hasFirst && hasSecond) {
            ChatOut.line(source, "选区: " + describeSelection());
        } else {
            ChatOut.line(source, "选区: 未完成（/" + root + " clear pos1 / pos2，站在对角两点上执行）");
        }
        if (plan == null) {
            ChatOut.line(source, "计划: 尚未预演（/" + root + " clear preview）");
            return;
        }
        ChatOut.line(source, "计划: " + plan.steps.size() + " 步（fill " + plan.fills + " 条）· " + plan.batches
                + " 批次 · 约 " + format(plan.blocks) + " 方块 · 预计 " + estimatedSeconds(plan) + " 秒 · y 范围 "
                + plan.minY + ".." + plan.maxY);
        if (running) {
            ChatOut.line(source, "执行中: " + cursor + "/" + plan.steps.size() + " 步 · " + phaseName(phaseOfStep)
                    + " · 已清理约 " + format(blocksDone) + " 方块（" + progressPercent() + "%）· 已跑 "
                    + ((System.currentTimeMillis() - startedMillis) / 1000) + " 秒");
        } else {
            ChatOut.line(source, failed ? "状态: 执行中断（见上面的错误日志）" : "状态: 未在执行");
        }
    }

    // ==================================================================
    // 三、tick 驱动（由切面调用；自带时间闸，多钩子重复调用无副作用）
    // ==================================================================

    /** 服务端每个 tick 会被调用多次（MinecraftServer#tickServer 与 ServerLevel#tick 两个钩子）。 */
    public static void onServerTick() {
        long now = System.nanoTime();
        if (now - lastTickNanos < TICK_GATE_NANOS) {
            return;
        }
        lastTickNanos = now;
        if (!running || plan == null) {
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

        int budget = stepsPerCycle;
        while (budget-- > 0 && cursor < plan.steps.size()) {
            Step step = plan.steps.get(cursor++);
            phaseOfStep = step.phase;
            if (!dispatch(step.command)) {
                failed = true;
                running = false;
                ChatOut.error(notifySource, "命令派发失败，任务已停止: /" + step.command);
                System.out.println("[HyAuth] 空置域挖掘中断（命令派发失败）: " + step.command);
                return;
            }
            blocksDone += step.blocks;
            waitTicks = Math.max(step.waitTicks, ListManager.getClearIntervalTicks());
            if (step.waitTicks > 0) {
                break; // 需要等待的步骤（如 forceload 之后等区块加载）单独占一个节拍
            }
        }

        announceIfDue();
        if (cursor >= plan.steps.size()) {
            finish();
        }
    }

    private static void finish() {
        running = false;
        long seconds = (System.currentTimeMillis() - startedMillis) / 1000L;
        ChatOut.head(notifySource, "===== 空置域挖掘完成 =====");
        ChatOut.ok(notifySource, "选区 " + describeSelection() + " 已挖空，用时 " + seconds + " 秒，累计清理约 "
                + format(blocksDone) + " 方块。");
        ChatOut.note(notifySource, "提示: 残留实体清理模式为 clear.kill=" + ListManager.getClearKill()
                + "（只清理本区域范围内）；空域不需要时用备份回滚或原版 /fill 处理。");
        System.out.println("[HyAuth] 空置域挖掘完成: " + describeSelection() + "，用时 " + seconds + " 秒，清理约 "
                + format(blocksDone) + " 方块（发起者: " + who + "）");
    }

    private static void announceIfDue() {
        int percent = progressPercent();
        int step = Math.max(1, ListManager.getClearAnnouncePercent());
        boolean finished = cursor >= plan.steps.size();
        if (percent >= announcedPercent + step || (finished && announcedPercent < 100)) {
            announcedPercent = percent - (percent % step);
            String line = "[HyAuth] 空置域挖掘 · " + phaseName(phaseOfStep) + " · 进度 " + percent + "%（" + cursor
                    + "/" + plan.steps.size() + " 步，约 " + format(blocksDone) + " 方块）";
            System.out.println(line);
            if (notifySource != null) {
                ChatOut.line(notifySource, line);
            }
        }
    }

    private static int progressPercent() {
        if (plan == null || plan.steps.isEmpty()) {
            return 100;
        }
        return (int) Math.min(100L, cursor * 100L / plan.steps.size());
    }

    /** 把一条命令交给原版命令系统执行（总是带 {@code execute in <维度>}，保证在目标维度生效）。 */
    private static boolean dispatch(String command) {
        if (commands == null || consoleSource == null) {
            return false;
        }
        String full = dimension == null ? "/" + command : "/execute in " + dimension + " run " + command;
        // ★ 不能用"返回值 != null"判断成功：26.3 里 performPrefixedCommand 返回 void，
        //   反射调用成功同样是 null（v1.0.6 因此在真机上刚开就报"命令派发失败"）。
        if (VanillaReflect.callMatchingQuietly(commands, "performPrefixedCommand", consoleSource, full)) {
            return true;
        }
        return VanillaReflect.callMatchingQuietly(commands, "performCommand", consoleSource, full);
    }

    // ==================================================================
    // 四、计划生成（纯几何，先预演后执行）
    // ==================================================================

    private static Plan build(Object source, String root) {
        if (!hasFirst || !hasSecond) {
            ChatOut.warn(source, "还没选齐两个角点：/" + root + " clear pos1 与 /" + root
                    + " clear pos2（站在对角两点上执行即取当前坐标）");
            return null;
        }
        int[] b = bounds();
        int x1 = b[0];
        int x2 = b[1];
        int z1 = b[2];
        int z2 = b[3];
        int sideX = x2 - x1 + 1;
        int sideZ = z2 - z1 + 1;
        int maxSide = ListManager.getClearMaxSide();
        if (sideX > maxSide || sideZ > maxSide) {
            ChatOut.error(source, "选区太大（" + sideX + " x " + sideZ + " 方块），超过上限 " + maxSide
                    + "（配置 clear.max_side）。真要挖请先调大上限。");
            return null;
        }

        Plan plan = new Plan();
        // y 范围：配置给默认，再按该维度真实建筑高度夹取（下界 0..255、末地 0..255 与主世界不同）
        plan.minY = ListManager.getClearMinY();
        plan.topY = ListManager.getClearTopY();
        plan.maxY = plan.topY + Math.max(1, ListManager.getClearAboveHeight());
        // 基岩开关：false = 保留基岩层，把挖掘下界抬到"基岩顶面的上一格"
        if (!ListManager.isClearBreakBedrock()) {
            int keepFrom = ListManager.getClearBedrockTopY() + 1;
            if (keepFrom > plan.minY) {
                plan.minY = keepFrom;
                plan.bedrockKept = true;
            }
        }
        int levelMin = levelMinY(plan.minY);
        int levelMax = levelMaxY(plan.maxY);
        int rawMin = plan.minY;
        int rawTop = plan.topY;
        int rawMax = plan.maxY;
        plan.minY = Math.max(plan.minY, levelMin);
        if (plan.topY <= plan.minY) {
            plan.topY = Math.min(levelMax, plan.minY + 1);
        }
        if (plan.topY > levelMax) {
            plan.topY = levelMax;
        }
        plan.maxY = Math.min(plan.maxY, levelMax);
        plan.clamped = plan.minY != rawMin || plan.topY != rawTop || plan.maxY != rawMax;
        if (plan.maxY <= plan.topY) {
            ChatOut.error(source, "y 范围不合法（min_y=" + plan.minY + " top_y=" + plan.topY + " max_y="
                    + plan.maxY + "）：检查 clear.min_y / clear.top_y / clear.above_height 配置。");
            return null;
        }

        int interval = ListManager.getClearIntervalTicks();
        int loadWait = ListManager.getClearLoadWaitTicks();
        int batchBlocks = Math.max(1, ListManager.getClearBatchChunks()) * 16;
        String kill = killSelector(ListManager.getClearKill());
        String trenchBlock = ListManager.getClearTrenchBlock();
        int ox1 = x1 - 1;
        int ox2 = x2 + 1;
        int oz1 = z1 - 1;
        int oz2 = z2 + 1;

        // ---------- 阶段 1：外圈上方清空（y = topY .. maxY） ----------
        for (int bx = ox1; bx <= ox2; bx += batchBlocks) {
            for (int bz = oz1; bz <= oz2; bz += batchBlocks) {
                int ex = Math.min(bx + batchBlocks - 1, ox2);
                int ez = Math.min(bz + batchBlocks - 1, oz2);
                plan.batches++;
                plan.steps.add(new Step("forceload add " + bx + " " + bz + " " + ex + " " + ez, loadWait, 1, 0));
                for (int cx = bx; cx <= ex; cx += 16) {
                    for (int cz = bz; cz <= ez; cz += 16) {
                        addFillSlabs(plan, 1, cx, plan.topY, cz, Math.min(cx + 15, ex), plan.maxY,
                                Math.min(cz + 15, ez), "air", true);
                    }
                }
                if (kill != null) {
                    plan.steps.add(new Step(killBox(kill, bx, plan.topY, bz, ex, plan.maxY, ez), 0, 1, 0));
                }
                plan.steps.add(new Step("forceload remove " + bx + " " + bz + " " + ex + " " + ez, interval, 1, 0));
            }
        }

        // ---------- 阶段 2：四边防爆沟（外圈四条边，1 格宽） ----------
        if (ListManager.isClearTrench() && plan.topY - 1 >= plan.minY + 1) {
            List<int[]> edges = new ArrayList<int[]>();
            for (int x = ox1; x <= ox2; x += 256) {
                edges.add(new int[]{x, oz1, Math.min(x + 255, ox2), oz1}); // 北
            }
            for (int x = ox1; x <= ox2; x += 256) {
                edges.add(new int[]{x, oz2, Math.min(x + 255, ox2), oz2}); // 南
            }
            for (int z = oz1; z <= oz2; z += 256) {
                edges.add(new int[]{ox1, z, ox1, Math.min(z + 255, oz2)}); // 西
            }
            for (int z = oz1; z <= oz2; z += 256) {
                edges.add(new int[]{ox2, z, ox2, Math.min(z + 255, oz2)}); // 东
            }
            for (int[] edge : edges) {
                int ex1 = edge[0];
                int ez1 = edge[1];
                int ex2 = edge[2];
                int ez2 = edge[3];
                plan.batches++;
                plan.steps.add(new Step("forceload add " + ex1 + " " + ez1 + " " + ex2 + " " + ez2, loadWait, 2, 0));
                // 填沟是"加方块"，不算进"已清理体积"
                addFillSlabs(plan, 2, ex1, plan.minY + 1, ez1, ex2, plan.topY - 1, ez2, trenchBlock, false);
                plan.steps.add(new Step("forceload remove " + ex1 + " " + ez1 + " " + ex2 + " " + ez2, interval, 2, 0));
            }
        }

        // ---------- 阶段 3：内圈清空（y = minY .. topY-1） ----------
        for (int bx = x1; bx <= x2; bx += batchBlocks) {
            for (int bz = z1; bz <= z2; bz += batchBlocks) {
                int ex = Math.min(bx + batchBlocks - 1, x2);
                int ez = Math.min(bz + batchBlocks - 1, z2);
                plan.batches++;
                plan.steps.add(new Step("forceload add " + bx + " " + bz + " " + ex + " " + ez, loadWait, 3, 0));
                for (int cx = bx; cx <= ex; cx += 16) {
                    for (int cz = bz; cz <= ez; cz += 16) {
                        addFillSlabs(plan, 3, cx, plan.minY, cz, Math.min(cx + 15, ex), plan.topY - 1,
                                Math.min(cz + 15, ez), "air", true);
                    }
                }
                if (kill != null) {
                    plan.steps.add(new Step(killBox(kill, bx, plan.minY, bz, ex, plan.topY - 1, ez), 0, 3, 0));
                }
                plan.steps.add(new Step("forceload remove " + bx + " " + bz + " " + ex + " " + ez, interval, 3, 0));
            }
        }

        long maxVolume = ListManager.getClearMaxVolume();
        if (plan.blocks > maxVolume) {
            ChatOut.error(source, "这次要清理约 " + format(plan.blocks) + " 方块，超过上限 " + format(maxVolume)
                    + "（配置 clear.max_volume）。确认要挖请先调大上限。");
            return null;
        }
        return plan;
    }

    /**
     * 通用的分层 fill：把 y 区间切成若干条命令，保证每条 ≤ {@link #FILL_LIMIT} 方块。
     *
     * @param block       填充方块（{@code air} = 清空）
     * @param countVolume 是否把体积计入"清理量"（清空才算，填沟不算）
     */
    private static void addFillSlabs(Plan plan, int phase, int x1, int y1, int z1,
                                     int x2, int y2, int z2, String block, boolean countVolume) {
        if (y2 < y1) {
            return;
        }
        int perLayer = (x2 - x1 + 1) * (z2 - z1 + 1);
        int layersPerFill = Math.max(1, FILL_LIMIT / Math.max(1, perLayer));
        for (int y = y1; y <= y2; y += layersPerFill) {
            int sliceEnd = Math.min(y + layersPerFill - 1, y2);
            long volume = (long) perLayer * (sliceEnd - y + 1);
            plan.steps.add(new Step("fill " + x1 + " " + y + " " + z1 + " " + x2 + " " + sliceEnd + " " + z2
                    + " " + block, 0, phase, countVolume ? volume : 0));
            plan.fills++;
            if (countVolume) {
                plan.blocks += volume;
            }
        }
    }

    /** 区域内的实体清理命令（体积选择器，绝不越界）。 */
    private static String killBox(String selector, int x1, int y1, int z1, int x2, int y2, int z2) {
        return "kill @e[" + selector + ",x=" + x1 + ",y=" + y1 + ",z=" + z1
                + ",dx=" + Math.max(1, x2 - x1 + 1) + ",dy=" + Math.max(1, y2 - y1 + 1)
                + ",dz=" + Math.max(1, z2 - z1 + 1) + "]";
    }

    /** 配置里的 kill 模式 → 选择器条件（{@code null} = 不清理）。 */
    private static String killSelector(String mode) {
        if (mode == null) {
            return null;
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        if ("all".equals(normalized) || "entities".equals(normalized)) {
            return "type=!player";
        }
        if ("items".equals(normalized) || "item".equals(normalized)) {
            return "type=item";
        }
        return null;
    }

    private static long estimatedSeconds(Plan plan) {
        long ticks = 0;
        for (Step step : plan.steps) {
            ticks += Math.max(step.waitTicks, ListManager.getClearIntervalTicks());
        }
        return ticks / 20L;
    }

    private static void printPlan(Object source, Plan plan, String root) {
        int[] b = bounds();
        ChatOut.head(source, "===== 空置域挖掘预演（还没有动世界）=====");
        ChatOut.line(source, "内圈: x[" + b[0] + ".." + b[1] + "] z[" + b[2] + ".." + b[3] + "]（"
                + (b[1] - b[0] + 1) + " x " + (b[3] - b[2] + 1) + " 方块）· 外圈各扩 1 格 · 维度 "
                + ChunkLagSampler.dimensionLabel(dimension));
        ChatOut.line(source, "阶段 1 外圈上方清空: y " + plan.topY + " .. " + plan.maxY);
        ChatOut.line(source, "阶段 2 防爆沟: " + (ListManager.isClearTrench()
                ? "四边填 " + ListManager.getClearTrenchBlock() + "（y " + (plan.minY + 1) + " .. " + (plan.topY - 1) + "）"
                : "已关闭"));
        ChatOut.line(source, "阶段 3 内圈清空: y " + plan.minY + " .. " + (plan.topY - 1));
        ChatOut.line(source, "计划: " + plan.steps.size() + " 步（fill " + plan.fills + " 条）· " + plan.batches
                + " 批次 · 预计清理约 " + format(plan.blocks) + " 方块");
        ChatOut.line(source, "节流: 每 " + ListManager.getClearIntervalTicks() + " tick 做 "
                + ListManager.getClearFillsPerStep() + " 步 → 预计约 " + estimatedSeconds(plan)
                + " 秒（已计入 forceload 等待，实际取决于机器与磁盘）");
        ChatOut.line(source, "实体清理: " + ListManager.getClearKill() + "（仅限本区域范围）");
        ChatOut.line(source, "基岩层: " + (plan.bedrockKept
                ? "保留（break_bedrock=false，从 y=" + plan.minY + " 开始清，底板不动）"
                : "一起挖掉（break_bedrock=true，底部是虚空 —— 东西会掉下去）"));
        if (plan.clamped) {
            ChatOut.warn(source, "注意: y 范围已按该维度的建筑高度夹取 → min_y=" + plan.minY + " top_y="
                    + plan.topY + " max_y=" + plan.maxY);
        }
        // 就地给一个可点击的"确认开始"，省得再背命令（控制台那一份会附带等价命令）
        ChatOut.clickable(source, "  ▶ 点击确认开始挖掘（不可撤销）", ChatOut.RED,
                "/" + root + " clear start confirm",
                "确认后立刻开始挖空置域\n选区: " + describeSelection() + "\n预计清理约 " + format(plan.blocks)
                        + " 方块\n共 " + plan.steps.size() + " 步\n中止用 /" + root + " clear stop");
        ChatOut.note(source, "手打也行: /" + root + " clear go（= start confirm）· 进度: /"
                + root + " clear status");
    }

    private static String phaseName(int phase) {
        if (phase == 1) {
            return "阶段1/3 外圈上方清空";
        }
        if (phase == 2) {
            return "阶段2/3 防爆沟";
        }
        if (phase == 3) {
            return "阶段3/3 内圈清空";
        }
        return "准备";
    }

    // ==================================================================
    // 五、工具
    // ==================================================================

    private static String dimensionOf(Object source) {
        return ChunkLagSampler.dimensionId(VanillaReflect.call(source, "getLevel"));
    }

    /** 目标维度里的 ServerLevel（选择角点时已被缓存）；拿不到返回 {@code null}。 */
    private static Object level() {
        return ChunkLagSampler.levelForDimension(dimension);
    }

    private static int levelMinY(int fallback) {
        Object level = level();
        int value = VanillaReflect.callInt(level, "getMinBuildHeight", Integer.MIN_VALUE);
        if (value == Integer.MIN_VALUE) {
            // 1.21.2+ 把 getMinBuildHeight 改名为 getMinY
            value = VanillaReflect.callInt(level, "getMinY", Integer.MIN_VALUE);
        }
        return value == Integer.MIN_VALUE ? fallback : value;
    }

    private static int levelMaxY(int fallback) {
        Object level = level();
        int value = VanillaReflect.callInt(level, "getMaxBuildHeight", Integer.MIN_VALUE);
        if (value == Integer.MIN_VALUE) {
            // 1.21.2+ 把 getMaxBuildHeight 改名为 getMaxY（返回的都是"上界 + 1"）
            value = VanillaReflect.callInt(level, "getMaxY", Integer.MIN_VALUE);
        }
        return value == Integer.MIN_VALUE ? fallback : value - 1;
    }

    private static String format(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    // ------------------------------------------------------------------
    // 供自检 / 诊断读取
    // ------------------------------------------------------------------

    public static boolean isRunning() {
        return running;
    }

    public static int planSize() {
        return plan == null ? 0 : plan.steps.size();
    }

    public static int planFills() {
        return plan == null ? 0 : plan.fills;
    }

    public static long plannedBlocks() {
        return plan == null ? 0L : plan.blocks;
    }

    public static int getCursor() {
        return cursor;
    }

    public static long blocksDone() {
        return blocksDone;
    }
}
