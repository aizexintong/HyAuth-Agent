package com.hyauth.agent.util;

import com.hyauth.agent.config.ListManager;

import java.util.List;
import java.util.Locale;

/**
 * 勘探报告：把 {@link ChunkLagSampler.Analysis} 渲染成「控制台文本 + 聊天可点击文本」，
 * 并负责 {@code /hy lag tp} 的传送落点计算。
 *
 * <p>报告刻意做成<b>聊天里点得动</b>的形态：每一行异常范围后面都能点一下直接 tp 到现场，
 * 悬停显示细分耗时。控制台那一份是同样的文字 + 等价命令，方便 RCON / 日志里复制。
 */
public final class LagReport {

    private LagReport() {
    }

    /** 扫描到点自动出报告（由服务端 tick 切面在到点时调用，仍在主线程上）。 */
    public static void autoReport(ChunkLagSampler.Window window) {
        Object source = window.getSource();
        String root = window.getCommandRoot() == null ? ListManager.getPrimaryCommandRoot() : window.getCommandRoot();
        ChunkLagSampler.Analysis analysis = ChunkLagSampler.analyze(
                "按需扫描 " + format(window.getElapsedSeconds()) + "s",
                window, ListManager.getLagReportTop(), ListManager.getLagMaxClusters());
        ChunkLagSampler.setLastAnalysis(analysis);
        ChatOut.head(source, "===== HyAuth 区块卡顿勘探 · 扫描完成 =====");
        render(source, analysis, root);
    }

    /** 渲染一份分析结果。 */
    public static void render(Object source, ChunkLagSampler.Analysis analysis, String root) {
        if (analysis == null) {
            ChatOut.warn(source, "还没有可用的勘探数据：先 /" + root + " lag scan <秒> 采一次，或确认常驻采样是开的（/"
                    + root + " lag status）");
            return;
        }
        ChunkLagSampler.Window window = analysis.window;
        boolean nested = window != null && window.blockEntitiesNested();
        String caliber = nested
                ? "口径：方块实体耗时已包含在区块 tick 里（合计不重复计）"
                : "口径：合计 = 区块 tick + 实体 + 方块实体";

        ChatOut.line(source, String.format(Locale.ROOT,
                "采样 %ss / %d tick · 覆盖 %d 个维度 %d 个区块 · 全服 MSPT 均值 %.2fms（近期 %.2fms，峰值 %.1fms）",
                format(window == null ? 0D : window.getElapsedSeconds()),
                window == null ? 0L : window.getServerTicks(),
                window == null ? 0 : window.getDimensionCount(),
                analysis.sampledChunks,
                window == null ? 0D : window.getServerAvgMs(),
                window == null ? 0D : window.getServerEwmaMs(),
                window == null ? 0D : window.getServerPeakMs()));
        ChatOut.line(source, String.format(Locale.ROOT,
                "区块 tick 耗时分布: 中位 %.3fms · P95 %.3fms · 单次峰值 %.2fms · %s",
                analysis.medianMs, analysis.p95Ms, analysis.maxPeakMs, caliber));
        ChatOut.line(source, String.format(Locale.ROOT,
                "异常判据: 合计 ≥ %.2fms 或 ≥ 中位数 %.1f 倍 → 命中 %d 个区块，合并为 %d 组范围",
                analysis.thresholdMs, analysis.relativeFactor, analysis.flaggedChunks, analysis.clusters.size()));
        if (window != null && window.isCapped()) {
            ChatOut.warn(source, "注意：跟踪区块数已达上限，部分新区块未纳入统计（chunk_lag.max_tracked_chunks）。");
        }

        if (analysis.clusters.isEmpty()) {
            ChatOut.ok(source, "没有发现异常区块：这段时间的区块 tick 都很健康。");
        } else {
            int index = 1;
            for (ChunkLagSampler.Cluster cluster : analysis.clusters) {
                renderCluster(source, analysis, cluster, index++, root);
            }
        }

        ChatOut.note(source, "可用: /" + root + " lag（直接出报告）· /" + root + " lag 30（采样 30 秒）· /"
                + root + " lag top [N] 单区块榜 · /" + root + " lag tp <序号> 传送到某一组 · /" + root
                + " lag here 看脚下这块 · /" + root + " lag clear 清空重来");
    }

    private static void renderCluster(Object source, ChunkLagSampler.Analysis analysis,
                                      ChunkLagSampler.Cluster cluster, int index, String root) {
        String dimension = ChunkLagSampler.dimensionLabel(cluster.dimension);
        double avg = cluster.chunks == 0 ? 0D : cluster.avgMs / cluster.chunks;
        double chunkAvg = cluster.chunks == 0 ? 0D : cluster.chunkAvgMs / cluster.chunks;
        double entityAvg = cluster.chunks == 0 ? 0D : cluster.entityAvgMs / cluster.chunks;
        double blockEntityAvg = cluster.chunks == 0 ? 0D : cluster.blockEntityAvgMs / cluster.chunks;
        double entityCount = cluster.chunks == 0 ? 0D : cluster.entityCount / cluster.chunks;

        String text = String.format(Locale.ROOT,
                "  #%d %s x[%d..%d] z[%d..%d]  %d 区块  合计均值 %.2fms 峰值 %.2fms  [区块 %.2f | 实体 %.2f(≈%.0f只) | 方块实体 %.2f]  点击传送",
                index, dimension, cluster.minBlockX(), cluster.maxBlockX(), cluster.minBlockZ(), cluster.maxBlockZ(),
                cluster.chunks, avg, cluster.peakMs, chunkAvg, entityAvg, entityCount, blockEntityAvg);
        String hover = String.format(Locale.ROOT,
                "点击传送到该范围中心\n维度: %s\n方块范围: x[%d..%d] z[%d..%d]\n中心: %d, %d\n合计均值: %.2fms / 峰值: %.2fms\n区块 tick: %.2fms\n实体: %.2fms（平均约 %.0f 只）\n方块实体: %.2fms\n覆盖区块: %d",
                cluster.dimension, cluster.minBlockX(), cluster.maxBlockX(), cluster.minBlockZ(), cluster.maxBlockZ(),
                cluster.centerBlockX(), cluster.centerBlockZ(), avg, cluster.peakMs,
                chunkAvg, entityAvg, entityCount, blockEntityAvg, cluster.chunks);
        String color = cluster.peakMs >= analysis.thresholdMs * 3D ? ChatOut.RED : ChatOut.GOLD;
        ChatOut.clickable(source, text, color, "/" + root + " lag tp " + index, hover);
    }

    /** 单区块榜（按单次峰值排序）。 */
    public static void renderTop(Object source, ChunkLagSampler.Analysis analysis, int limit, String root) {
        if (analysis == null || analysis.top.isEmpty()) {
            ChatOut.warn(source, "还没有可用的勘探数据：先 /" + root + " lag scan <秒> 采一次。");
            return;
        }
        ChatOut.head(source, "===== HyAuth 区块卡顿勘探 · 单区块 Top " + limit + " =====");
        String currentDimension = null;
        int index = 1;
        for (ChunkLagSampler.ChunkRow row : analysis.top) {
            if (index > limit) {
                break;
            }
            if (!row.dimension.equals(currentDimension)) {
                currentDimension = row.dimension;
                ChatOut.line(source, "· " + ChunkLagSampler.dimensionLabel(currentDimension) + "（" + currentDimension + "）");
            }
            String text = String.format(Locale.ROOT,
                    "  #%-2d 区块(%d,%d) 方块(%d,%d)  峰值 %.2fms 均值 %.2fms  [区块 %.2f | 实体 %.2f(≈%.0f只) | 方块实体 %.2f]  点击传送",
                    index, row.chunkX, row.chunkZ, row.centerBlockX(), row.centerBlockZ(),
                    row.totalPeakMs, row.totalAvgMs, row.chunkAvgMs, row.entityAvgMs, row.entityCount,
                    row.blockEntityAvgMs);
            String hover = String.format(Locale.ROOT,
                    "点击传送到该区块中心\n维度: %s\n区块: (%d, %d)\n方块范围: x[%d..%d] z[%d..%d]\n峰值: %.2fms / 均值: %.2fms\n实体: %.2fms（平均约 %.0f 只）\n方块实体: %.2fms\n采样 tick 数: %d",
                    row.dimension, row.chunkX, row.chunkZ, row.chunkX * 16, row.chunkX * 16 + 15,
                    row.chunkZ * 16, row.chunkZ * 16 + 15, row.totalPeakMs, row.totalAvgMs,
                    row.entityAvgMs, row.entityCount, row.blockEntityAvgMs, row.ticks);
            String color = row.totalPeakMs >= analysis.thresholdMs * 3D ? ChatOut.RED : ChatOut.GOLD;
            ChatOut.clickable(source, text, color, "/" + root + " lag tp " + row.centerBlockX() + " " + row.centerBlockZ(),
                    hover);
            index++;
        }
        ChatOut.note(source, "提示: 两个数字的形式是方块坐标，例如 /" + root + " lag tp " + analysis.top.get(0).centerBlockX()
                + " " + analysis.top.get(0).centerBlockZ());
    }

    /** 「脚下这块」：当前所在区块的采样数据。 */
    public static void renderHere(Object source, String root) {
        Object entity = VanillaReflect.call(source, "getEntity");
        if (entity == null) {
            ChatOut.warn(source, "控制台没有「脚下这块地」：请用 /" + root + " lag list 或 /" + root + " lag top。");
            return;
        }
        int[] chunk = ChunkLagSampler.entityChunk(entity);
        if (chunk == null) {
            ChatOut.warn(source, "解析你所在区块失败（版本差异），请用 /" + root + " lag list。");
            return;
        }
        Object level = VanillaReflect.call(source, "getLevel");
        String dimension = ChunkLagSampler.dimensionId(level);
        ChunkLagSampler.Window window = ChunkLagSampler.isScanning()
                ? ChunkLagSampler.scanWindow() : ChunkLagSampler.residentWindow();
        ChunkLagSampler.ChunkRow row = ChunkLagSampler.row(window, dimension, chunk[0], chunk[1]);
        String where = String.format(Locale.ROOT, "你所在区块: %s 区块(%d,%d) 方块范围 x[%d..%d] z[%d..%d]",
                ChunkLagSampler.dimensionLabel(dimension), chunk[0], chunk[1],
                chunk[0] * 16, chunk[0] * 16 + 15, chunk[1] * 16, chunk[1] * 16 + 15);
        ChatOut.head(source, where);
        if (row == null) {
            ChatOut.line(source, "这块还没有采样数据（可能刚加载，或已超出跟踪上限）。等几秒再看。");
            return;
        }
        ChatOut.line(source, String.format(Locale.ROOT,
                "均值合计 %.2fms / 峰值 %.2fms  [区块 tick %.2f | 实体 %.2f(≈%.0f只) | 方块实体 %.2f]  采样 %d tick",
                row.totalAvgMs, row.totalPeakMs, row.chunkAvgMs, row.entityAvgMs, row.entityCount,
                row.blockEntityAvgMs, row.ticks));
        double threshold = ListManager.getLagFlagThresholdMs();
        if (row.totalAvgMs >= threshold) {
            ChatOut.warn(source, "这块超过判据（≥ " + format(threshold) + "ms），属于异常区块。");
        } else {
            ChatOut.ok(source, "这块目前正常。");
        }
    }

    /**
     * 传送到某个方块坐标。
     *
     * @param commands 服务端 {@code Commands} 实例（用来借用原版 /tp 与 /execute，见下）
     * @param source   命令来源
     * @param blockX   目标方块 X
     * @param blockZ   目标方块 Z
     * @param dimension 目标维度 id（未知传 {@code null} 表示"留在执行者当前维度"）
     * @param target   目标玩家选择器（默认 {@code @s}）
     * @param root     命令根（回显用）
     * @return 是否成功发出传送
     *
     * <p><b>为什么借原版命令而不是直接调 API</b>：原版 {@code /tp} 会自己处理区块加载、
     * 跨维度、乘客/坐骑、位置同步与可见性；直接反射调 {@code ServerPlayer#teleportTo}
     * 要自己把这些都做对，版本差异也大。这里只负责"算出落点"，落地交给原版。
     */
    public static boolean teleport(Object commands, Object source, int blockX, int blockZ, String dimension,
                                   String target, String root) {
        if (commands == null || source == null) {
            return false;
        }
        String selector = target == null || target.isEmpty() ? "@s" : target;

        // 目标维度的 ServerLevel：采样时见过，就直接用它的地表高度图
        Object sourceLevel = VanillaReflect.call(source, "getLevel");
        Object targetLevel = dimension == null ? sourceLevel : ChunkLagSampler.levelForDimension(dimension);
        boolean crossDimension = dimension != null && !dimension.equals(ChunkLagSampler.dimensionId(sourceLevel));

        String prefix = "";
        if (crossDimension) {
            // 原版 /execute in <维度> run 会把执行维度切过去，/tp 的落点就是那个维度
            prefix = "execute in " + dimension + " run ";
        }

        int y = targetLevel == null ? Integer.MIN_VALUE : surfaceY(targetLevel, blockX, blockZ);
        String command;
        if (y != Integer.MIN_VALUE) {
            command = prefix + "tp " + selector + " " + blockX + " " + y + " " + blockZ;
        } else {
            // 拿不到地表高度（版本差异 / 该维度没采过）→ 交给原版 spreadplayers 自己找地表，
            // 这比"硬塞一个 y=100"安全得多（尤其下界，y=100 会卡在基岩层里）
            command = prefix + "spreadplayers " + blockX + " " + blockZ + " 0 1 false " + selector;
            ChatOut.warn(source, "未能解析该坐标的地表高度，改用原版 /spreadplayers 落地到地表（落点会在 ±1 方块内随机）。");
        }
        // ★ 26.3 的这两个方法返回 void：反射调用成功也拿到 null，所以用 callMatchingQuietly 判断
        //   "到底调成了没有"，而不是拿返回值是否为空当成功标志（否则会误报派发失败，甚至重复执行一次）。
        boolean ok = VanillaReflect.callMatchingQuietly(commands, "performPrefixedCommand", source, "/" + command)
                || VanillaReflect.callMatchingQuietly(commands, "performCommand", source, command);
        if (!ok) {
            ChatOut.error(source, "传送命令派发失败（版本差异），请手工执行: " + command);
            return false;
        }
        ChatOut.ok(source, "已传送到 " + ChunkLagSampler.dimensionLabel(dimension) + " x=" + blockX
                + (y == Integer.MIN_VALUE ? " z=" + blockZ : " y=" + y + " z=" + blockZ)
                + "（执行: /" + command + "）");
        return true;
    }

    /** 地表高度（{@code Level#getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)}）；失败返回 {@code Integer.MIN_VALUE}。 */
    private static int surfaceY(Object level, int blockX, int blockZ) {
        Class<?> types = VanillaReflect.findClass("net.minecraft.world.level.levelgen.Heightmap$Types",
                VanillaReflect.loaderFor(level));
        Object motionBlocking = VanillaReflect.enumConstant(types, "MOTION_BLOCKING");
        if (motionBlocking == null) {
            return Integer.MIN_VALUE;
        }
        Object height = VanillaReflect.callMatching(level, "getHeight", motionBlocking,
                Integer.valueOf(blockX), Integer.valueOf(blockZ));
        if (height instanceof Number) {
            return ((Number) height).intValue();
        }
        return Integer.MIN_VALUE;
    }

    /** 小工具：保留两位小数，避免 report 里出现 0.30000000000000004。 */
    public static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
