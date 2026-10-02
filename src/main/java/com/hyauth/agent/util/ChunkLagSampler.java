package com.hyauth.agent.util;

import com.hyauth.agent.config.ListManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 区块卡顿采样器：把「谁在吃 tick」按<b>区块</b>记下来（MsptMap 那套分层归因思想的服务端版）。
 *
 * <p><b>采样点</b>（切面挂载见 {@code AgentMain}，每个类别都可独立降级）：
 * <table border="1">
 *     <tr><th>类别</th><th>切点</th><th>归属</th></tr>
 *     <tr><td>区块 tick</td><td>{@code ServerLevel#tickChunk(LevelChunk,int)}</td><td>该区块（随机刻、冰雪、闪电…）</td></tr>
 *     <tr><td>实体</td><td>{@code ServerLevel#tickNonPassenger/tickPassenger}</td><td>实体所在区块（怪、矿车、掉落物…）</td></tr>
 *     <tr><td>方块实体</td><td>{@code LevelChunk#tickBlockEntities()}</td><td>该区块（熔炉、漏斗、刷怪笼…）</td></tr>
 *     <tr><td>整服 MSPT</td><td>{@code MinecraftServer#tickServer(BooleanSupplier)}</td><td>全局（做对比基线）</td></tr>
 * </table>
 *
 * <p><b>关于"合计"会不会重复计</b>：不同版本里方块实体 tick 可能被包在区块 tick 内部
 * （那样就是重复计）。这里不靠版本假设，而是<b>运行期探测</b>：如果 {@code tickBlockEntities}
 * 出现在 {@code tickChunk} 的调用栈里面，就把它标记为"已包含"，合计里不再加一遍，
 * 并且报告里会写清用的是哪种口径。
 *
 * <p><b>开销</b>：热路径只有两次 {@code System.nanoTime()} + 一次无装箱哈希表查找 + 几次浮点运算，
 * 全部跑在服务端主线程上（不跨线程、不加锁）。
 */
public final class ChunkLagSampler {

    /** 坐标读取失败的哨兵。 */
    private static final int NO_COORD = Integer.MIN_VALUE;

    /** 区块键读取失败的哨兵。 */
    private static final long NO_KEY = Long.MIN_VALUE;

    /** 常驻窗口（默认开启，滚动累计，随时可看）。 */
    private static final Window RESIDENT = new Window("常驻窗口");

    /** 按需扫描窗口（{@code /hy lag scan} 期间存在，扫描结束自动出报告）。 */
    private static volatile Window scan;

    /** 常驻采样是否开启（配置项 chunk_lag.resident）。 */
    private static volatile boolean residentEnabled = true;

    private static volatile boolean initialized;

    /** 最近一次生成的报告（供 /hy lag tp <序号> 使用）。 */
    private static volatile Analysis lastAnalysis;

    /**
     * 当前是否处于 {@code tickChunk} 调用栈内部（只在服务端主线程使用）。
     *
     * <p>用途：探测"方块实体 tick 是否被包在区块 tick 里"。每个服务端 tick 结束会清零，
     * 因此即便某个切面漏配也不会让探测结果永久跑偏。
     */
    private static int insideChunkTick;

    /** 维度名字缓存（ServerLevel → 维度 id）。 */
    private static final Map<Object, String> DIMENSION_NAMES =
            Collections.synchronizedMap(new WeakHashMap<Object, String>());

    /**
     * 维度 → 该维度的 {@code ServerLevel} 对象（采样时顺手记下）。
     * <p>用途：{@code /hy lag tp} 要跨维度传送时，得在<b>目标维度</b>的地表高度图上算落点。
     */
    private static final Map<String, Object> DIMENSION_LEVELS =
            Collections.synchronizedMap(new HashMap<String, Object>());

    private ChunkLagSampler() {
    }

    // ==================================================================
    // 一、热路径：由切面调用
    // ==================================================================

    /** 是否有人在采样（常驻开着，或按需扫描进行中）。 */
    public static boolean sampling() {
        return scan != null || residentEnabled;
    }

    /** 通用起点：返回 {@code nanoTime}。 */
    public static long begin() {
        return System.nanoTime();
    }

    /** 进入 {@code ServerLevel#tickChunk}。 */
    public static long beginChunkTick() {
        insideChunkTick++;
        return System.nanoTime();
    }

    /** 退出 {@code ServerLevel#tickChunk}。 */
    public static void endChunkTick(long startNanos, Object level, Object chunk) {
        if (insideChunkTick > 0) {
            insideChunkTick--;
        }
        if (startNanos == 0L || !sampling()) {
            return;
        }
        long key = chunkKeyOfChunk(chunk);
        if (key == NO_KEY) {
            return;
        }
        String dimension = dimensionId(level);
        double ms = elapsedMs(startNanos);
        Window resident = RESIDENT;
        if (residentEnabled) {
            resident.recordChunkTick(dimension, key, ms);
        }
        Window current = scan;
        if (current != null) {
            current.recordChunkTick(dimension, key, ms);
        }
    }

    /** 进入 {@code LevelChunk#tickBlockEntities}：顺便探测它是否被包在区块 tick 里。 */
    public static long beginBlockEntityTick() {
        if (insideChunkTick > 0) {
            RESIDENT.markBlockEntitiesNested();
            Window current = scan;
            if (current != null) {
                current.markBlockEntitiesNested();
            }
        }
        return System.nanoTime();
    }

    /** 退出 {@code LevelChunk#tickBlockEntities}（维度从区块自身解析，切面不用带 level 参数）。 */
    public static void endBlockEntityTick(long startNanos, Object chunk) {
        if (startNanos == 0L || !sampling()) {
            return;
        }
        long key = chunkKeyOfChunk(chunk);
        if (key == NO_KEY) {
            return;
        }
        String dimension = dimensionId(levelOfChunk(chunk));
        double ms = elapsedMs(startNanos);
        Window resident = RESIDENT;
        if (residentEnabled) {
            resident.recordBlockEntityTick(dimension, key, ms);
        }
        Window current = scan;
        if (current != null) {
            current.recordBlockEntityTick(dimension, key, ms);
        }
    }

    /** 从区块对象取它所属的 Level（{@code LevelChunk#getLevel()} → {@code level} 字段）。 */
    private static Object levelOfChunk(Object chunk) {
        Object level = VanillaReflect.call(chunk, "getLevel");
        return level != null ? level : VanillaReflect.fieldValue(chunk, "level");
    }

    /**
     * 退出一个 <b>方块实体 ticker</b>（26.x 的真实形状）：
     * 方块实体是逐个 {@code net.minecraft.world.level.block.entity.TickingBlockEntity#tick()} 跑的，
     * 26.3 里 {@code LevelChunk#tickBlockEntities()} 已经不存在（改成 {@code Level#tickBlockEntities()} 维度级遍历），
     * 所以这里改挂 ticker 本身的 {@code tick()}，再用 {@code getPos()} 反推区块 —— 这样归因粒度仍然是"按区块"。
     */
    public static void endBlockEntityTickerTick(long startNanos, Object ticker) {
        if (startNanos == 0L || !sampling()) {
            return;
        }
        int[] chunkXZ = chunkXZOfTicker(ticker);
        if (chunkXZ == null) {
            return;
        }
        long key = key(chunkXZ[0], chunkXZ[1]);
        Object level = levelOfEntity(ticker);
        String dimension = dimensionId(level);
        double ms = elapsedMs(startNanos);
        Window resident = RESIDENT;
        if (residentEnabled) {
            resident.recordBlockEntityTick(dimension, key, ms);
        }
        Window current = scan;
        if (current != null) {
            current.recordBlockEntityTick(dimension, key, ms);
        }
    }

    /** 方块实体 ticker 的区块坐标：{@code getPos()} 拿 BlockPos，再读它的 x/z 字段/取值方法。 */
    private static int[] chunkXZOfTicker(Object ticker) {
        Object pos = VanillaReflect.call(ticker, "getPos");
        if (pos == null) {
            // 有些实现只暴露字段，退化处理
            pos = VanillaReflect.fieldValue(ticker, "pos");
        }
        if (pos == null) {
            return null;
        }
        Integer x = blockPosComponent(pos, "getX", "x");
        Integer z = blockPosComponent(pos, "getZ", "z");
        if (x == null || z == null) {
            return null;
        }
        return new int[] { x.intValue() >> 4, z.intValue() >> 4 };
    }

    private static Integer blockPosComponent(Object pos, String getter, String field) {
        Object value = VanillaReflect.call(pos, getter);
        if (value == null) {
            value = VanillaReflect.fieldValue(pos, field);
        }
        return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null;
    }

    /**
     * ticker 所属的 Level：{@code TickingBlockEntity} 本身没有 level 引用，
     * 但它的实现（如 {@code LevelChunk$RebindableTickingBlockEntityWrapper}）持有内层 ticker，
     * 内层 ticker 通常是 {@code LevelChunk$BoundTickingBlockEntity} 之类，也没 level。
     * 拿不到就返回 null（维度会显示为 unknown，不影响区块归因）。
     */
    private static Object levelOfEntity(Object ticker) {
        Object inner = VanillaReflect.fieldValue(ticker, "ticker");
        Object level = VanillaReflect.call(ticker, "getLevel");
        if (level != null) {
            return level;
        }
        if (inner != null && inner != ticker) {
            level = VanillaReflect.call(inner, "getLevel");
            if (level != null) {
                return level;
            }
        }
        return null;
    }

    /** 单个实体 tick 结束（{@code tickNonPassenger} / {@code tickPassenger}）。 */
    public static void entityTick(long startNanos, Object level, Object entity) {
        if (startNanos == 0L || !sampling()) {
            return;
        }
        long key = chunkKeyOfEntity(entity);
        if (key == NO_KEY) {
            return;
        }
        String dimension = dimensionId(level);
        double ms = elapsedMs(startNanos);
        Window resident = RESIDENT;
        if (residentEnabled) {
            resident.recordEntityTick(dimension, key, ms);
        }
        Window current = scan;
        if (current != null) {
            current.recordEntityTick(dimension, key, ms);
        }
    }

    /** 服务端一个完整 tick 结束（{@code MinecraftServer#tickServer}）。 */
    public static void serverTick(long startNanos) {
        // 每个 tick 结束清零：切面万一漏配，也不会让"嵌套探测"永久误判
        insideChunkTick = 0;
        if (startNanos == 0L || !sampling()) {
            return;
        }
        double ms = elapsedMs(startNanos);
        Window resident = RESIDENT;
        if (residentEnabled) {
            resident.recordServerTick(ms);
        }
        Window current = scan;
        if (current != null) {
            current.recordServerTick(ms);
            if (System.currentTimeMillis() >= current.getDeadlineMillis()) {
                scan = null;
                LagReport.autoReport(current);
            }
        }
    }

    // ==================================================================
    // 二、窗口生命周期（命令调用）
    // ==================================================================

    /** 首次使用时按配置初始化（AgentMain 会调一次；命令里也会兜底调用）。 */
    public static synchronized void initFromConfig() {
        if (initialized) {
            return;
        }
        initialized = true;
        residentEnabled = ListManager.isLagResident();
        System.out.println("[HyAuth] 区块卡顿采样: 常驻窗口 " + (residentEnabled ? "已开启" : "已关闭")
                + "（/hy lag on|off 可随时切换），按需扫描用 /hy lag scan <秒>");
    }

    public static boolean isResidentEnabled() {
        return residentEnabled;
    }

    public static void setResidentEnabled(boolean enabled) {
        residentEnabled = enabled;
    }

    public static boolean isScanning() {
        return scan != null;
    }

    /** 开一次按需扫描：{@code seconds} 秒后自动出报告；{@code root} 是管理员实际输入的命令根（用于回显）。 */
    public static boolean startScan(Object source, int seconds, String root) {
        if (scan != null) {
            return false;
        }
        Window window = new Window("按需扫描");
        window.arm(source, seconds, root);
        scan = window;
        return true;
    }

    /** 停止按需扫描并返回已采到的窗口（没在扫返回 {@code null}）。 */
    public static Window stopScan() {
        Window current = scan;
        scan = null;
        return current;
    }

    public static Window residentWindow() {
        return RESIDENT;
    }

    public static Window scanWindow() {
        return scan;
    }

    /** 清空两个窗口的累计数据。 */
    public static void clearAll() {
        RESIDENT.reset();
        Window current = scan;
        if (current != null) {
            current.reset();
        }
        lastAnalysis = null;
    }

    public static Analysis getLastAnalysis() {
        return lastAnalysis;
    }

    public static void setLastAnalysis(Analysis analysis) {
        lastAnalysis = analysis;
    }

    // ==================================================================
    // 三、分析
    // ==================================================================

    /** 按窗口内容生成一份分析结果（阈值判定 + 邻接合并 + Top-N）。 */
    public static Analysis analyze(String title, Window window, int topN, int maxClusters) {
        List<ChunkRow> rows = snapshot(window);
        int chunkCount = rows.size();
        double[] totals = new double[chunkCount];
        double maxTotal = 0D;
        for (int i = 0; i < chunkCount; i++) {
            totals[i] = rows.get(i).totalAvgMs;
            if (rows.get(i).totalPeakMs > maxTotal) {
                maxTotal = rows.get(i).totalPeakMs;
            }
        }
        double median = percentile(totals, 0.5D);
        double p95 = percentile(totals, 0.95D);

        double threshold = ListManager.getLagFlagThresholdMs();
        double factor = ListManager.getLagRelativeFactor();
        List<ChunkRow> flagged = new ArrayList<ChunkRow>();
        for (ChunkRow row : rows) {
            double total = row.totalAvgMs;
            if (total >= threshold || (median > 0D && total >= 0.15D && total >= median * factor)) {
                flagged.add(row);
            }
        }

        List<Cluster> clusters = cluster(flagged, maxClusters);

        List<ChunkRow> top = new ArrayList<ChunkRow>(rows);
        Collections.sort(top, new Comparator<ChunkRow>() {
            @Override
            public int compare(ChunkRow left, ChunkRow right) {
                return Double.compare(right.totalPeakMs, left.totalPeakMs);
            }
        });
        if (top.size() > topN) {
            top = new ArrayList<ChunkRow>(top.subList(0, topN));
        }

        return new Analysis(title, window, rows.size(), chunkCount, flagged.size(), clusters, top,
                median, p95, maxTotal, threshold, factor);
    }

    /** 快照：把窗口内容变成不可变行列表（之后窗口继续累计也不影响这份报告）。 */
    public static List<ChunkRow> snapshot(Window window) {
        final List<ChunkRow> rows = new ArrayList<ChunkRow>();
        if (window == null) {
            return rows;
        }
        final boolean nested = window.blockEntitiesNested();
        final long windowTicks = window.getServerTicks();
        for (Map.Entry<String, LongKeyMap<Stats>> entry : window.dimensionMaps().entrySet()) {
            final String dimension = entry.getKey();
            entry.getValue().forEach(new LongKeyMap.Visitor<Stats>() {
                @Override
                public void visit(long key, Stats stats) {
                    rows.add(new ChunkRow(dimension, (int) (key >> 32), (int) key, stats, nested, windowTicks));
                }
            });
        }
        return rows;
    }

    private static double percentile(double[] values, double fraction) {
        if (values.length == 0) {
            return 0D;
        }
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        int index = (int) Math.round(fraction * (sorted.length - 1));
        if (index < 0) {
            index = 0;
        }
        if (index >= sorted.length) {
            index = sorted.length - 1;
        }
        return sorted[index];
    }

    /** 把异常区块按 8 邻接合并成"坐标范围"（矩形包围盒）。 */
    private static List<Cluster> cluster(List<ChunkRow> flagged, int maxClusters) {
        Map<String, Map<Long, ChunkRow>> index = new HashMap<String, Map<Long, ChunkRow>>();
        for (ChunkRow row : flagged) {
            Map<Long, ChunkRow> dimension = index.get(row.dimension);
            if (dimension == null) {
                dimension = new HashMap<Long, ChunkRow>();
                index.put(row.dimension, dimension);
            }
            dimension.put(key(row.chunkX, row.chunkZ), row);
        }

        List<Cluster> clusters = new ArrayList<Cluster>();
        for (Map.Entry<String, Map<Long, ChunkRow>> entry : index.entrySet()) {
            Map<Long, ChunkRow> dimension = entry.getValue();
            List<ChunkRow> seeds = new ArrayList<ChunkRow>(dimension.values());
            Collections.sort(seeds, new Comparator<ChunkRow>() {
                @Override
                public int compare(ChunkRow left, ChunkRow right) {
                    return Double.compare(right.totalPeakMs, left.totalPeakMs);
                }
            });
            Set<Long> visited = new HashSet<Long>();
            for (ChunkRow seed : seeds) {
                long seedKey = key(seed.chunkX, seed.chunkZ);
                if (visited.contains(seedKey)) {
                    continue;
                }
                Cluster cluster = new Cluster(seed.dimension);
                Deque<ChunkRow> queue = new ArrayDeque<ChunkRow>();
                queue.add(seed);
                visited.add(seedKey);
                while (!queue.isEmpty()) {
                    ChunkRow current = queue.poll();
                    cluster.add(current);
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dz == 0) {
                                continue;
                            }
                            long neighborKey = key(current.chunkX + dx, current.chunkZ + dz);
                            if (visited.contains(neighborKey)) {
                                continue;
                            }
                            ChunkRow neighbor = dimension.get(neighborKey);
                            if (neighbor != null) {
                                visited.add(neighborKey);
                                queue.add(neighbor);
                            }
                        }
                    }
                }
                clusters.add(cluster);
            }
        }

        Collections.sort(clusters, new Comparator<Cluster>() {
            @Override
            public int compare(Cluster left, Cluster right) {
                int byPeak = Double.compare(right.peakMs, left.peakMs);
                return byPeak != 0 ? byPeak : Integer.compare(right.chunks, left.chunks);
            }
        });
        if (clusters.size() > maxClusters) {
            return new ArrayList<Cluster>(clusters.subList(0, maxClusters));
        }
        return clusters;
    }

    // ==================================================================
    // 四、坐标 / 维度解析（纯反射，失败即放弃该样本）
    // ==================================================================

    /** 区块键：高 32 位 chunkX、低 32 位 chunkZ。 */
    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static long chunkKeyOfChunk(Object chunk) {
        Object pos = VanillaReflect.call(chunk, "getPos");
        int[] coords = coordsOf(pos);
        if (coords == null) {
            return NO_KEY;
        }
        return key(coords[0], coords[1]);
    }

    private static long chunkKeyOfEntity(Object entity) {
        Object blockPos = VanillaReflect.call(entity, "chunkPosition");
        if (blockPos == null) {
            blockPos = VanillaReflect.call(entity, "blockPosition");
        }
        int[] coords = coordsOf(blockPos);
        if (coords != null) {
            return key(coords[0] >> 4, coords[1] >> 4);
        }
        int blockX = VanillaReflect.callInt(entity, "getBlockX", NO_COORD);
        int blockZ = VanillaReflect.callInt(entity, "getBlockZ", NO_COORD);
        if (blockX != NO_COORD && blockZ != NO_COORD) {
            return key(blockX >> 4, blockZ >> 4);
        }
        Object position = VanillaReflect.call(entity, "position");
        if (position != null) {
            double x = VanillaReflect.callDouble(position, "x", Double.NaN);
            double z = VanillaReflect.callDouble(position, "z", Double.NaN);
            if (!Double.isNaN(x) && !Double.isNaN(z)) {
                return key((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
            }
        }
        return NO_KEY;
    }

    /** 读 BlockPos / ChunkPos / Vec3 的 x、z（字段 → {@code x()} → {@code getX()} 三条路都试）。 */
    private static int[] coordsOf(Object holder) {
        if (holder == null) {
            return null;
        }
        int x = readCoord(holder, "x");
        int z = readCoord(holder, "z");
        if (x == NO_COORD || z == NO_COORD) {
            return null;
        }
        return new int[]{x, z};
    }

    /** 实体所在区块（{@code [chunkX, chunkZ]}）；解析失败返回 {@code null}。 */
    public static int[] entityChunk(Object entity) {
        long key = chunkKeyOfEntity(entity);
        return key == NO_KEY ? null : new int[]{(int) (key >> 32), (int) key};
    }

    /** 实体所在的方块坐标（{@code [blockX, blockZ]}）；解析失败返回 {@code null}（选角点用）。 */
    public static int[] entityBlock(Object entity) {
        if (entity == null) {
            return null;
        }
        Object blockPos = VanillaReflect.call(entity, "chunkPosition");
        if (blockPos == null) {
            blockPos = VanillaReflect.call(entity, "blockPosition");
        }
        int[] coords = coordsOf(blockPos);
        if (coords != null) {
            return coords;
        }
        int blockX = VanillaReflect.callInt(entity, "getBlockX", NO_COORD);
        int blockZ = VanillaReflect.callInt(entity, "getBlockZ", NO_COORD);
        if (blockX != NO_COORD && blockZ != NO_COORD) {
            return new int[]{blockX, blockZ};
        }
        Object position = VanillaReflect.call(entity, "position");
        if (position != null) {
            double x = VanillaReflect.callDouble(position, "x", Double.NaN);
            double z = VanillaReflect.callDouble(position, "z", Double.NaN);
            if (!Double.isNaN(x) && !Double.isNaN(z)) {
                return new int[]{(int) Math.floor(x), (int) Math.floor(z)};
            }
        }
        return null;
    }

    /** 从窗口里查某个区块的统计行；没有返回 {@code null}。 */
    public static ChunkRow row(Window window, String dimension, int chunkX, int chunkZ) {
        if (window == null || dimension == null) {
            return null;
        }
        LongKeyMap<Stats> map = window.dimensionMaps().get(dimension);
        if (map == null) {
            return null;
        }
        Stats stats = map.get(key(chunkX, chunkZ));
        return stats == null ? null
                : new ChunkRow(dimension, chunkX, chunkZ, stats, window.blockEntitiesNested(),
                        window.getServerTicks());
    }

    /** 某个维度对应的 {@code ServerLevel}（采样时见过才有）；没有返回 {@code null}。 */
    public static Object levelForDimension(String dimension) {
        return dimension == null ? null : DIMENSION_LEVELS.get(dimension);
    }

    /** 目前已经登记过的维度 id 列表（按 id 排序，用于诊断/校验）。 */
    public static String knownDimensions() {
        java.util.List<String> ids = new ArrayList<String>(DIMENSION_LEVELS.keySet());
        Collections.sort(ids);
        return ids.isEmpty() ? "（暂时一个都没登记，等世界加载后自动出现）" : ids.toString();
    }

    private static int readCoord(Object holder, String name) {
        Object field = VanillaReflect.fieldValue(holder, name);
        if (field instanceof Number) {
            return ((Number) field).intValue();
        }
        int value = VanillaReflect.callInt(holder, name, NO_COORD);
        if (value != NO_COORD) {
            return value;
        }
        String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        return VanillaReflect.callInt(holder, getter, NO_COORD);
    }

    /** ServerLevel → 维度 id（如 {@code minecraft:the_nether}），结果按 level 对象缓存。 */
    public static String dimensionId(Object level) {
        if (level == null) {
            return "unknown";
        }
        String cached = DIMENSION_NAMES.get(level);
        if (cached != null) {
            return cached;
        }
        String id = null;
        Object dimensionKey = VanillaReflect.call(level, "dimension");
        if (dimensionKey != null) {
            Object location = VanillaReflect.call(dimensionKey, "location");
            if (location != null) {
                id = String.valueOf(location);
            }
            if (isBlank(id)) {
                id = String.valueOf(dimensionKey);
            }
        }
        if (isBlank(id)) {
            Object description = VanillaReflect.call(level, "getDescription");
            if (description != null) {
                id = String.valueOf(description);
            }
        }
        if (isBlank(id)) {
            id = "unknown";
        }
        DIMENSION_NAMES.put(level, id);
        DIMENSION_LEVELS.put(id, level);
        return id;
    }

    /** 维度中文标签（报告里更好读）。 */
    public static String dimensionLabel(String id) {
        if (id == null) {
            return "未知维度";
        }
        if (id.endsWith("overworld")) {
            return "主世界";
        }
        if (id.endsWith("the_nether") || id.endsWith("nether")) {
            return "下界";
        }
        if (id.endsWith("the_end") || id.endsWith("end")) {
            return "末地";
        }
        return id;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty() || "null".equals(value);
    }

    private static double elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0D;
    }

    // ==================================================================
    // 五、数据结构
    // ==================================================================

    /** 单个区块的累计统计。 */
    public static final class Stats {

        private long ticks;
        private double chunkSum;
        private double chunkPeak;
        private double chunkEwma;
        private long entityTicks;
        private double entitySum;
        private double entityPeak;
        private double entityEwma;
        private long blockEntityTicks;
        private double blockEntitySum;
        private double blockEntityPeak;
        private double blockEntityEwma;

        void recordChunk(double ms, double alpha) {
            ticks++;
            chunkSum += ms;
            if (ms > chunkPeak) {
                chunkPeak = ms;
            }
            chunkEwma = chunkEwma <= 0D ? ms : chunkEwma + (ms - chunkEwma) * alpha;
        }

        void recordEntity(double ms, double alpha) {
            entityTicks++;
            entitySum += ms;
            if (ms > entityPeak) {
                entityPeak = ms;
            }
            entityEwma = entityEwma <= 0D ? ms : entityEwma + (ms - entityEwma) * alpha;
        }

        void recordBlockEntity(double ms, double alpha) {
            blockEntityTicks++;
            blockEntitySum += ms;
            if (ms > blockEntityPeak) {
                blockEntityPeak = ms;
            }
            blockEntityEwma = blockEntityEwma <= 0D ? ms : blockEntityEwma + (ms - blockEntityEwma) * alpha;
        }

        double chunkAvgMs() {
            return ticks == 0 ? 0D : chunkSum / ticks;
        }

        /**
         * 实体每 tick 摊到该区块的耗时。
         *
         * <p>分母优先用"该区块被区块 tick 的次数"；若这个区块<b>只 tick 了实体</b>
         * 而从没被区块 tick 过（理论上少见，但现实里存在），就回退到"窗口内服务端 tick 数"，
         * 否则分母为 0 会把真实存在的实体开销报成 0，看起来像"这里没问题"。
         */
        double entityAvgMs(long fallbackTicks) {
            long denominator = ticks > 0 ? ticks : fallbackTicks;
            return denominator <= 0 ? 0D : entitySum / denominator;
        }

        double blockEntityAvgMs(long fallbackTicks) {
            long denominator = ticks > 0 ? ticks : fallbackTicks;
            return denominator <= 0 ? 0D : blockEntitySum / denominator;
        }

        /** 平均实体数估算：实体 tick 次数 ÷ 该区块被 tick 的次数。 */
        double entityCount() {
            return ticks == 0 ? 0D : (double) entityTicks / ticks;
        }
    }

    /** 一个采样窗口（常驻一个；按需扫描期间再开一个）。 */
    public static final class Window {

        private final String label;
        private final Map<String, LongKeyMap<Stats>> dimensions = new LinkedHashMap<String, LongKeyMap<Stats>>();
        private int trackedChunks;
        private boolean capped;
        private Object source;
        private String commandRoot;
        private long startedMillis;
        private int plannedSeconds;
        private long deadlineMillis;
        private long serverTicks;
        private double serverMsSum;
        private double serverMsPeak;
        private double serverMsEwma;
        private boolean blockEntitiesNested;

        Window(String label) {
            this.label = label;
            this.startedMillis = System.currentTimeMillis();
        }

        void arm(Object source, int seconds, String root) {
            this.source = source;
            this.commandRoot = root;
            this.plannedSeconds = seconds;
            this.startedMillis = System.currentTimeMillis();
            this.deadlineMillis = this.startedMillis + seconds * 1000L;
        }

        public Object getSource() {
            return source;
        }

        /** 管理员实际输入的命令根（回显里生成"可点击命令"用）。 */
        public String getCommandRoot() {
            return commandRoot;
        }

        public String getLabel() {
            return label;
        }

        public int getPlannedSeconds() {
            return plannedSeconds;
        }

        public long getDeadlineMillis() {
            return deadlineMillis;
        }

        public double getElapsedSeconds() {
            long end = System.currentTimeMillis();
            return Math.max(0D, (end - startedMillis) / 1000.0D);
        }

        public long getServerTicks() {
            return serverTicks;
        }

        public double getServerAvgMs() {
            return serverTicks == 0 ? 0D : serverMsSum / serverTicks;
        }

        public double getServerEwmaMs() {
            return serverMsEwma;
        }

        public double getServerPeakMs() {
            return serverMsPeak;
        }

        public int getTrackedChunks() {
            return trackedChunks;
        }

        public int getDimensionCount() {
            return dimensions.size();
        }

        public boolean isCapped() {
            return capped;
        }

        public boolean blockEntitiesNested() {
            return blockEntitiesNested;
        }

        Map<String, LongKeyMap<Stats>> dimensionMaps() {
            return dimensions;
        }

        void markBlockEntitiesNested() {
            blockEntitiesNested = true;
        }

        void reset() {
            dimensions.clear();
            trackedChunks = 0;
            capped = false;
            serverTicks = 0;
            serverMsSum = 0D;
            serverMsPeak = 0D;
            serverMsEwma = 0D;
            startedMillis = System.currentTimeMillis();
        }

        void recordChunkTick(String dimension, long key, double ms) {
            Stats stats = statsFor(dimension, key);
            if (stats != null) {
                stats.recordChunk(ms, ListManager.getLagEwmaAlpha());
            }
        }

        void recordEntityTick(String dimension, long key, double ms) {
            Stats stats = statsFor(dimension, key);
            if (stats != null) {
                stats.recordEntity(ms, ListManager.getLagEwmaAlpha());
            }
        }

        void recordBlockEntityTick(String dimension, long key, double ms) {
            Stats stats = statsFor(dimension, key);
            if (stats != null) {
                stats.recordBlockEntity(ms, ListManager.getLagEwmaAlpha());
            }
        }

        void recordServerTick(double ms) {
            serverTicks++;
            serverMsSum += ms;
            if (ms > serverMsPeak) {
                serverMsPeak = ms;
            }
            double alpha = ListManager.getLagEwmaAlpha();
            serverMsEwma = serverMsEwma <= 0D ? ms : serverMsEwma + (ms - serverMsEwma) * alpha;
        }

        private Stats statsFor(String dimension, long key) {
            LongKeyMap<Stats> map = dimensions.get(dimension);
            if (map == null) {
                map = new LongKeyMap<Stats>(2048);
                dimensions.put(dimension, map);
            }
            Stats stats = map.get(key);
            if (stats != null) {
                return stats;
            }
            int max = ListManager.getLagMaxChunks();
            if (trackedChunks >= max) {
                if (!capped) {
                    capped = true;
                    System.out.println("[HyAuth] 区块卡顿采样（" + label + "）跟踪的区块已达上限 " + max
                            + "，后续新区块不再纳入统计（调大 chunk_lag.max_tracked_chunks 或 /hy lag clear 后重来）。");
                }
                return null;
            }
            stats = new Stats();
            map.put(key, stats);
            trackedChunks++;
            return stats;
        }
    }

    /** 一份不可变的区块统计行。 */
    public static final class ChunkRow {

        public final String dimension;
        public final int chunkX;
        public final int chunkZ;
        public final long ticks;
        public final double chunkAvgMs;
        public final double chunkPeakMs;
        public final double entityAvgMs;
        public final double blockEntityAvgMs;
        public final double entityCount;
        public final double totalAvgMs;
        public final double totalPeakMs;

        ChunkRow(String dimension, int chunkX, int chunkZ, Stats stats, boolean blockEntitiesNested,
                 long windowTicks) {
            this.dimension = dimension;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.ticks = stats.ticks;
            this.chunkAvgMs = stats.chunkAvgMs();
            this.chunkPeakMs = stats.chunkPeak;
            this.entityAvgMs = stats.entityAvgMs(windowTicks);
            this.blockEntityAvgMs = stats.blockEntityAvgMs(windowTicks);
            this.entityCount = stats.entityCount();
            double blockEntityPart = blockEntitiesNested ? 0D : this.blockEntityAvgMs;
            this.totalAvgMs = this.chunkAvgMs + this.entityAvgMs + blockEntityPart;
            this.totalPeakMs = this.chunkPeakMs + stats.entityPeak
                    + (blockEntitiesNested ? 0D : stats.blockEntityPeak);
        }

        public int centerBlockX() {
            return chunkX * 16 + 8;
        }

        public int centerBlockZ() {
            return chunkZ * 16 + 8;
        }
    }

    /** 一组相邻异常区块的坐标范围（矩形包围盒）。 */
    public static final class Cluster {

        public final String dimension;
        public int minX = Integer.MAX_VALUE;
        public int maxX = Integer.MIN_VALUE;
        public int minZ = Integer.MAX_VALUE;
        public int maxZ = Integer.MIN_VALUE;
        public int chunks;
        public double avgMs;
        public double peakMs;
        public double chunkAvgMs;
        public double entityAvgMs;
        public double blockEntityAvgMs;
        public double entityCount;

        Cluster(String dimension) {
            this.dimension = dimension;
        }

        void add(ChunkRow row) {
            if (row.chunkX < minX) {
                minX = row.chunkX;
            }
            if (row.chunkX > maxX) {
                maxX = row.chunkX;
            }
            if (row.chunkZ < minZ) {
                minZ = row.chunkZ;
            }
            if (row.chunkZ > maxZ) {
                maxZ = row.chunkZ;
            }
            chunks++;
            avgMs += row.totalAvgMs;
            chunkAvgMs += row.chunkAvgMs;
            entityAvgMs += row.entityAvgMs;
            blockEntityAvgMs += row.blockEntityAvgMs;
            entityCount += row.entityCount;
            if (row.totalPeakMs > peakMs) {
                peakMs = row.totalPeakMs;
            }
        }

        public int minBlockX() {
            return minX * 16;
        }

        public int maxBlockX() {
            return maxX * 16 + 15;
        }

        public int minBlockZ() {
            return minZ * 16;
        }

        public int maxBlockZ() {
            return maxZ * 16 + 15;
        }

        public int centerBlockX() {
            return (minBlockX() + maxBlockX()) / 2;
        }

        public int centerBlockZ() {
            return (minBlockZ() + maxBlockZ()) / 2;
        }
    }

    /** 一次分析的结果（报告渲染由 {@link LagReport} 负责）。 */
    public static final class Analysis {

        public final String title;
        public final Window window;
        public final int sampledChunks;
        public final int flaggedChunks;
        public final List<Cluster> clusters;
        public final List<ChunkRow> top;
        public final double medianMs;
        public final double p95Ms;
        public final double maxPeakMs;
        public final double thresholdMs;
        public final double relativeFactor;

        Analysis(String title, Window window, int trackedChunks, int sampledChunks, int flaggedChunks,
                 List<Cluster> clusters, List<ChunkRow> top, double medianMs, double p95Ms, double maxPeakMs,
                 double thresholdMs, double relativeFactor) {
            this.title = title;
            this.window = window;
            this.sampledChunks = sampledChunks;
            this.flaggedChunks = flaggedChunks;
            this.clusters = clusters;
            this.top = top;
            this.medianMs = medianMs;
            this.p95Ms = p95Ms;
            this.maxPeakMs = maxPeakMs;
            this.thresholdMs = thresholdMs;
            this.relativeFactor = relativeFactor;
        }
    }
}
