package lag;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 区块卡顿勘探 + 管理员命令的离线回归测试。
 *
 * <p>复现 26.x 的真实形状：{@code ServerLevel#tickChunk} / {@code tickNonPassenger} / {@code tickPassenger} /
 * {@code LevelChunk#tickBlockEntities} / {@code MinecraftServer#tickServer} /
 * {@code Commands#performPrefixedCommand}，把 <b>Agent 真正的切面类</b>用 Byte Buddy 内联进替身，
 * 然后跑真正的辅助类（{@code ChunkLagSampler} / {@code LagReport} / {@code AdminCommands} /
 * {@code ConfigWriter} / {@code UuidV7} / {@code ExistingUuidScan}），断言：
 *
 * <ol>
 *   <li>切面类字节码里<b>没有任何 net/minecraft</b>（README §8.4 的硬约束，用字节码扫描回归）；</li>
 *   <li>区块 tick 耗时被按区块记账，相邻异常区块被合并成"坐标范围"；</li>
 *   <li>报告行带可点击传送（{@code ClickEvent.RunCommand})，且 tp 落点交给原版 {@code /tp}；</li>
 *   <li>实体耗时按"实体所在区块"归因（含乘客实体那条路）；</li>
 *   <li>方块实体耗时是否被包在区块 tick 里能被<b>运行期探测</b>出来（口径自动切换）；</li>
 *   <li>命令接管的边界：非本命令根不拦、权限不够拒绝、控制台/OP 放行；</li>
 *   <li>名单管理：{@code /hy off} 自动生成 UUIDv7、先扫已有 UUID 查重、
 *       名字已有身份时默认拦下、{@code force} 才换新、指定 UUID 则沿用。</li>
 * </ol>
 */
public class LagAdviceMain {

    private static final String OVERWORLD = "minecraft:overworld";

    private static int failures = 0;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        String stubDir = args[0];
        String agentJar = args.length > 1 ? args[1] : null;
        ClassLoader app = LagAdviceMain.class.getClassLoader();

        System.out.println("==========================================================");
        System.out.println("=== 区块卡顿勘探 + 管理员命令 回归测试");
        System.out.println("==========================================================");

        // ---------- 0) 切面字节码硬约束（§8.4） ----------
        if (agentJar != null) {
            checkAdviceBytecode(new File(agentJar));
        }

        // ---------- 配置（含一份"已有身份"的 usercache.json） ----------
        writeConfig(true);
        writeUserCache();
        Class<?> listManager = Class.forName("com.hyauth.agent.config.ListManager", true, app);
        listManager.getMethod("reload").invoke(null);
        Class<?> sampler = Class.forName("com.hyauth.agent.util.ChunkLagSampler", true, app);
        sampler.getMethod("initFromConfig").invoke(null);
        check(Boolean.TRUE.equals(sampler.getMethod("sampling").invoke(null)),
                "常驻采样默认开启（sampling() == true）");

        // ---------- 把真实切面内联进替身 ----------
        URLClassLoader child = new URLClassLoader(new URL[]{new File(stubDir).toURI().toURL()}, app);
        ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(child);

        redefineServerLevel(child, locator, app);
        redefineOne(child, locator, app, "com.hyauth.agent.BlockEntityTickAdvice",
                "net.minecraft.world.level.chunk.LevelChunk", "tickBlockEntities", 0);
        redefineOne(child, locator, app, "com.hyauth.agent.ServerTickAdvice",
                "net.minecraft.server.MinecraftServer", "tickServer", 1);

        // Commands：两个方法一起挂（performPrefixedCommand + performCommand）
        Class<?> commandAdvice = Class.forName("com.hyauth.agent.CommandAdvice", false, app);
        TypeDescription commandsDesc = describe(locator, "net.minecraft.commands.Commands");
        Class<?> commandsType = new ByteBuddy()
                .redefine(commandsDesc, locator)
                .visit(Advice.to(commandAdvice)
                        .on(ElementMatchers.named("performPrefixedCommand")
                                .or(ElementMatchers.named("performCommand"))
                                .and(ElementMatchers.takesArguments(2))
                                .and(ElementMatchers.returns(int.class))))
                .make()
                .load(child, ClassLoadingStrategy.Default.INJECTION)
                .getLoaded();

        Class<?> levelType = child.loadClass("net.minecraft.server.level.ServerLevel");
        Class<?> chunkType = child.loadClass("net.minecraft.world.level.chunk.LevelChunk");
        Class<?> entityType = child.loadClass("net.minecraft.world.entity.Entity");
        Class<?> serverType = child.loadClass("net.minecraft.server.MinecraftServer");
        Class<?> sourceType = child.loadClass("net.minecraft.commands.CommandSourceStack");

        // ---------- 造世界：区块(0,0) 与 (1,0) 各 8ms（相邻，应合并成一组），(10,10) 不卡 ----------
        Object level = levelType.getConstructor(String.class).newInstance(OVERWORLD);
        @SuppressWarnings("unchecked")
        java.util.Map<Long, Long> sleeps = (java.util.Map<Long, Long>)
                levelType.getField("chunkSleepMs").get(null);
        sleeps.put(key(0, 0), 8L);
        sleeps.put(key(1, 0), 8L);
        sleeps.put(key(10, 10), 0L);

        Object hot0 = newChunk(chunkType, levelType, level, 0, 0);
        Object hot1 = newChunk(chunkType, levelType, level, 1, 0);
        Object cold = newChunk(chunkType, levelType, level, 10, 10);
        chunkType.getField("blockEntitySleepMs").set(hot1, 1L);

        Object entityHot = entityType.getConstructor(int.class, int.class, long.class)
                .newInstance(8, 8, 4L);      // 在区块 (0,0) 里，每次 tick 4ms
        Object entityCold = entityType.getConstructor(int.class, int.class, long.class)
                .newInstance(168, 168, 0L);  // 在区块 (10,10) 里

        Object server = serverType.getConstructor().newInstance();
        serverType.getField("tickSleepMs").set(server, 15L); // 整服 MSPT 基线：15ms
        serverType.getField("consoleLevel").set(server, level);   // 控制台命令源用的世界
        sourceType.getField("server").set(null, server);          // CommandSourceStack#getServer()
        Object commands = serverType.getMethod("getCommands").invoke(server);
        Object console = sourceType.getConstructor(int.class, Object.class, levelType)
                .newInstance(4, null, level);
        Object noPerm = sourceType.getConstructor(int.class, Object.class, levelType)
                .newInstance(0, null, level);

        // ---------- 采样：30 个 tick ----------
        Method tickServer = serverType.getMethod("tickServer", java.util.function.BooleanSupplier.class);
        Method tickChunk = levelType.getMethod("tickChunk", chunkType, int.class);
        Method tickNonPassenger = levelType.getMethod("tickNonPassenger", entityType);
        for (int tick = 0; tick < 30; tick++) {
            tickServer.invoke(server, (java.util.function.BooleanSupplier) () -> true);
            tickChunk.invoke(level, hot0, 3);
            tickChunk.invoke(level, hot1, 3);
            tickChunk.invoke(level, cold, 3);
            tickNonPassenger.invoke(level, entityHot);
            tickNonPassenger.invoke(level, entityCold);
        }

        // ---------- 1) /hy lag list ----------
        sourceType.getMethod("reset").invoke(null);
        commandsType.getMethod("reset").invoke(null);
        int handled = (Integer) commandsType.getMethod("performPrefixedCommand", sourceType, String.class)
                .invoke(commands, console, "/hy lag list");
        check(handled == 1, "命令拦截生效：/hy lag list 由 Agent 接管（返回值 1）");
        check(sourceType.getMethod("said", String.class).invoke(null, "x[0..31]") == Boolean.TRUE
                        && sourceType.getMethod("said", String.class).invoke(null, "z[0..15]") == Boolean.TRUE,
                "相邻的两个高耗时区块被合并成一组坐标范围 x[0..31] z[0..15]（8ms/区块）"
                        + sourceType.getMethod("tail", int.class).invoke(null, 3));
        check(sourceType.getMethod("said", String.class).invoke(null, "2 区块") == Boolean.TRUE,
                "该组范围统计到 2 个区块");
        check(sourceType.getMethod("said", String.class).invoke(null, "合计 = 区块 tick") == Boolean.TRUE,
                "报告写明了耗时口径（合计 = 区块 tick + 实体 + 方块实体）");
        check(sourceType.getMethod("clicked", String.class).invoke(null, "/hy lag tp 1") == Boolean.TRUE,
                "报告行带可点击传送事件（ClickEvent.RunCommand = /hy lag tp 1）");
        check(saidNumberIn(sourceType, "实体 ", 1.5D, 9.0D),
                "实体耗时按区块归因后计入该组均值（≈每 tick 4ms 的实体 ÷ 相邻两块；归因错了会是 0）"
                        + sourceType.getMethod("tail", int.class).invoke(null, 2));
        check(saidNumberIn(sourceType, "全服 MSPT 均值 ", 14.0D, 80.0D),
                "整服 MSPT 基线来自 MinecraftServer#tickServer（替身里每次 tick 睡 15ms）"
                        + sourceType.getMethod("tail", int.class).invoke(null, 3));

        // ---------- 2) /hy lag top 1 ----------
        sourceType.getMethod("reset").invoke(null);
        commandsType.getMethod("performPrefixedCommand", sourceType, String.class)
                .invoke(commands, console, "/hy lag top 1");
        check(Boolean.TRUE.equals(sourceType.getMethod("said", String.class).invoke(null, "区块(0,0)")),
                "单区块榜列出最卡的区块 (0,0)");
        check(saidNumberIn(sourceType, "实体 ", 3.0D, 12.0D),
                "该区块的实体分项 ≈ 单只实体每 tick 的耗时（每 tick 一只约 4ms 的实体）"
                        + sourceType.getMethod("tail", int.class).invoke(null, 2));

        // ---------- 3) /hy lag tp 1 —— 落点交给原版 /tp ----------
        sourceType.getMethod("reset").invoke(null);
        commandsType.getMethod("reset").invoke(null);
        commandsType.getMethod("performPrefixedCommand", sourceType, String.class)
                .invoke(commands, console, "/hy lag tp 1");
        check((Boolean) commandsType.getMethod("dispatchedAny", String.class).invoke(null, "plain:tp @s "),
                "传送被交给原版 /tp（未自行实现落点与区块加载）→ " + commandsType.getMethod("trace").invoke(null));
        check((Boolean) commandsType.getMethod("dispatchedAny", String.class).invoke(null, " 70 "),
                "落点 y 来自地表高度图（替身返回 70）");
        String heightQuery = (String) levelType.getField("lastHeightQuery").get(null);
        check(heightQuery != null && heightQuery.startsWith("MOTION_BLOCKING"),
                "地表高度用的是 WorldSurface/MOTION_BLOCKING 高度图 → " + heightQuery);

        // ---------- 4) 非本命令根不被吞 ----------
        commandsType.getMethod("reset").invoke(null);
        commandsType.getMethod("performPrefixedCommand", sourceType, String.class)
                .invoke(commands, console, "/tp Notch");
        check((Boolean) commandsType.getMethod("dispatched", String.class).invoke(null, "plain:tp Notch"),
                "非本命令根（/tp）原样交给原版，绝不被吞掉");

        // ---------- 5) 权限门槛 ----------
        sourceType.getMethod("reset").invoke(null);
        commandsType.getMethod("performPrefixedCommand", sourceType, String.class)
                .invoke(commands, noPerm, "/hy lag list");
        check(Boolean.TRUE.equals(sourceType.getMethod("said", String.class).invoke(null, "需要权限等级")),
                "权限不足（等级 0）时拒绝执行并说明原因");

        // ---------- 6) 名单管理：UUIDv7 + 查重 ----------
        sourceType.getMethod("reset").invoke(null);
        commandsType.getMethod("performPrefixedCommand", sourceType, String.class)
                .invoke(commands, console, "/hy off TestGuy");
        String config = read("littleskin_config.json");
        check(config.contains("TestGuy"), "「/hy off TestGuy」写进了配置文件（热重载不用重启）");
        String newUuid = firstUuidFor(config, "TestGuy");
        check(newUuid != null && newUuid.charAt(14) == '7',
                "自动生成的 UUID 是 UUIDv7（版本半字节 = 7）：" + newUuid);
        check(Boolean.TRUE.equals(sourceType.getMethod("said", String.class).invoke(null, "version=7")),
                "回显里写明了 UUID 版本与生成时间");

        // 名字已有身份（usercache.json 里的 ConflictGuy）→ 必须拦下
        sourceType.getMethod("reset").invoke(null);
        commandsType.getMethod("performPrefixedCommand", sourceType, String.class)
                .invoke(commands, console, "/hy off ConflictGuy");
        String afterRefuse = read("littleskin_config.json");
        check(!afterRefuse.contains("ConflictGuy"),
                "名字在 usercache.json 里已有身份时：默认不发新 UUID、不写配置（防止换人丢存档）");
        check(Boolean.TRUE.equals(sourceType.getMethod("said", String.class).invoke(null, "已拦下")),
                "并且明确告知被拦下的原因与已有 UUID"
                        + sourceType.getMethod("tail", int.class).invoke(null, 3));

        // whois 查户口（此时这个名字只在 usercache.json 里，还没进过配置）
        sourceType.getMethod("reset").invoke(null);
        commandsType.getMethod("performPrefixedCommand", sourceType, String.class)
                .invoke(commands, console, "/hy whois ConflictGuy");
        check(Boolean.TRUE.equals(sourceType.getMethod("said", String.class).invoke(null, "usercache.json")),
                "「/hy whois」能报出已有身份来自 usercache.json");
        check(Boolean.TRUE.equals(sourceType.getMethod("said", String.class).invoke(null, "原版离线算法")),
                "「/hy whois」同时给出原版离线算法会算出的 UUID（老存档最容易挂在这个 UUID 上）");

        // 显式沿用旧 UUID
        sourceType.getMethod("reset").invoke(null);
        commandsType.getMethod("performPrefixedCommand", sourceType, String.class)
                .invoke(commands, console, "/hy off ConflictGuy " + CONFLICT_UUID);
        String afterAdopt = read("littleskin_config.json");
        check(afterAdopt.contains(CONFLICT_UUID),
                "「/hy off ConflictGuy <旧UUID>」按管理员指定沿用旧身份（保住背包/成就）");

        // 外置名单
        sourceType.getMethod("reset").invoke(null);
        commandsType.getMethod("performPrefixedCommand", sourceType, String.class)
                .invoke(commands, console, "/hy add LittleGuy");
        check(read("littleskin_config.json").contains("LittleGuy"),
                "「/hy add LittleGuy」写进 LittleSkin 外置名单");

        // 6b) 省事写法：批量 + 别名
        sourceType.getMethod("reset").invoke(null);
        run(commandsType, sourceType, commands, console, "/hy add BatchA BatchB");
        String afterBatchAdd = read("littleskin_config.json");
        check(afterBatchAdd.contains("BatchA") && afterBatchAdd.contains("BatchB"),
                "「/hy add A B」一次加多个名字");
        check(said(sourceType, "批量结果"), "批量操作给出汇总（成功/失败/被拦下各几个）");
        run(commandsType, sourceType, commands, console, "/hy + PlusGuy");
        check(read("littleskin_config.json").contains("PlusGuy"), "别名「/hy + 名字」＝ add");
        run(commandsType, sourceType, commands, console, "/hy - PlusGuy");
        check(!read("littleskin_config.json").contains("PlusGuy"), "别名「/hy - 名字」＝ del");
        run(commandsType, sourceType, commands, console, "/hy del BatchA BatchB");
        String afterBatchDel = read("littleskin_config.json");
        check(!afterBatchDel.contains("BatchA") && !afterBatchDel.contains("BatchB"), "「/hy del A B」批量移除");
        sourceType.getMethod("reset").invoke(null);
        run(commandsType, sourceType, commands, console, "/hy off Off1 Off2");
        String afterBatchOff = read("littleskin_config.json");
        check(afterBatchOff.contains("Off1") && afterBatchOff.contains("Off2"),
                "「/hy off A B」批量发离线身份证（每个都独立查重）");
        sourceType.getMethod("reset").invoke(null);
        run(commandsType, sourceType, commands, console, "/hy ls");
        check(said(sourceType, "LittleSkin 外置名单") && said(sourceType, "离线名单"),
                "别名「/hy ls」＝ list，一次看清两个名单");
        sourceType.getMethod("reset").invoke(null);
        run(commandsType, sourceType, commands, console, "/hy id ConflictGuy");
        check(said(sourceType, "名字身份扫描"), "别名「/hy id 名字」＝ whois");

        // ---------- 7) 常驻开关 ----------
        commandsType.getMethod("performPrefixedCommand", sourceType, String.class)
                .invoke(commands, console, "/hy lag off");
        check(Boolean.FALSE.equals(sampler.getMethod("sampling").invoke(null)),
                "「/hy lag off」立刻停止常驻采样（sampling() == false）");
        commandsType.getMethod("performPrefixedCommand", sourceType, String.class)
                .invoke(commands, console, "/hy lag on");
        check(Boolean.TRUE.equals(sampler.getMethod("sampling").invoke(null)),
                "「/hy lag on」重新开启常驻采样");

        // ---------- 8) 按需扫描：到点自动出报告 ----------
        sourceType.getMethod("reset").invoke(null);
        commandsType.getMethod("performPrefixedCommand", sourceType, String.class)
                .invoke(commands, console, "/hy lag scan 1");
        check(Boolean.TRUE.equals(sourceType.getMethod("said", String.class).invoke(null, "开始区块卡顿勘探")),
                "「/hy lag scan 1」开始按需扫描");
        Thread.sleep(1200L);
        tickServer.invoke(server, (java.util.function.BooleanSupplier) () -> true);
        check(Boolean.TRUE.equals(sourceType.getMethod("said", String.class).invoke(null, "扫描完成")),
                "扫描到点后自动把报告发给发起者（不需要再敲命令）"
                        + sourceType.getMethod("tail", int.class).invoke(null, 2));

        // ---------- 9) 方块实体"是否被包在区块 tick 里"的运行期探测 ----------
        chunkType.getField("nestedInChunkTick").setBoolean(null, true);
        for (int tick = 0; tick < 5; tick++) {
            tickChunk.invoke(level, hot1, 3);
        }
        sourceType.getMethod("reset").invoke(null);
        commandsType.getMethod("performPrefixedCommand", sourceType, String.class)
                .invoke(commands, console, "/hy lag list");
        check(Boolean.TRUE.equals(sourceType.getMethod("said", String.class)
                        .invoke(null, "已包含在区块 tick 里")),
                "探测到方块实体 tick 嵌在区块 tick 内部 → 合计不再重复计（口径自动切换）"
                        + sourceType.getMethod("tail", int.class).invoke(null, 3));
        chunkType.getField("nestedInChunkTick").setBoolean(null, false);

        // ---------- 9b) 方块实体独立归因（模拟"方块实体不在区块 tick 里"的版本形态） ----------
        sourceType.getMethod("reset").invoke(null);
        commandsType.getMethod("performPrefixedCommand", sourceType, String.class)
                .invoke(commands, console, "/hy lag clear");
        chunkType.getField("blockEntitySleepMs").set(cold, 2L);
        Method tickBlockEntities = chunkType.getMethod("tickBlockEntities");
        for (int tick = 0; tick < 10; tick++) {
            tickServer.invoke(server, (java.util.function.BooleanSupplier) () -> true);
            tickBlockEntities.invoke(cold);
        }
        Object blockEntityRow = rowOf(sampler, app, 10, 10);
        double blockEntityAvg = blockEntityRow == null ? -1D
                : ((Number) blockEntityRow.getClass().getField("blockEntityAvgMs").get(blockEntityRow)).doubleValue();
        check(blockEntityAvg >= 1.5D && blockEntityAvg <= 8.0D,
                "tickBlockEntities 的耗时被归因到该区块（均值 " + blockEntityAvg + "ms ≈ 2ms）");
        chunkType.getField("blockEntitySleepMs").set(cold, 0L);

        // ---------- 10) 乘客实体归因（tickPassenger 那条路） ----------
        sourceType.getMethod("reset").invoke(null);
        commandsType.getMethod("performPrefixedCommand", sourceType, String.class)
                .invoke(commands, console, "/hy lag clear");
        Object passenger = entityType.getConstructor(int.class, int.class, long.class)
                .newInstance(168, 168, 3L);
        Method tickPassenger = levelType.getMethod("tickPassenger", entityType, entityType);
        for (int tick = 0; tick < 10; tick++) {
            tickServer.invoke(server, (java.util.function.BooleanSupplier) () -> true);
            tickPassenger.invoke(level, entityCold, passenger);
        }
        Object resident = sampler.getMethod("residentWindow").invoke(null);
        Object row = rowOf(sampler, app, 10, 10);
        check(row != null, "乘客实体所在区块 (10,10) 有采样行");
        double entityAvg = row == null ? -1D
                : ((Number) row.getClass().getField("entityAvgMs").get(row)).doubleValue();
        check(entityAvg >= 2.0D,
                "tickPassenger 的耗时被归因到乘客所在区块（均值 " + entityAvg + "ms ≈ 3ms）");

        // ---------- 11) 状态/能力探测不崩 ----------
        sourceType.getMethod("reset").invoke(null);
        commandsType.getMethod("performPrefixedCommand", sourceType, String.class)
                .invoke(commands, console, "/hy lag status");
        check(Boolean.TRUE.equals(sourceType.getMethod("said", String.class).invoke(null, "可挂载")),
                "「/hy lag status」打印各切点能力探测结果"
                        + sourceType.getMethod("tail", int.class).invoke(null, 2));

        // ==================================================================
        // 12) 空置域挖掘（命令版世吞）
        // ==================================================================
        Class<?> clearJob = Class.forName("com.hyauth.agent.util.ClearJob", true, app);
        Object op4 = sourceType.getConstructor(int.class, Object.class, levelType).newInstance(4, null, level);
        Object op2 = sourceType.getConstructor(int.class, Object.class, levelType).newInstance(2, null, level);
        Method levelTick = levelType.getMethod("tick", java.util.function.BooleanSupplier.class);

        commandsType.getMethod("reset").invoke(null);
        sourceType.getMethod("reset").invoke(null);

        // 12a) 破坏性操作有更高的权限门槛（clear.op_level 默认 3）
        run(commandsType, sourceType, commands, op2, "/hy clear pos1 0 0");
        check(said(sourceType, "需要权限等级"), "空置域挖掘权限门槛更高：等级 2 被拒绝");
        check(!said(sourceType, "已选角点"), "被拒绝时没有记录任何选区");

        // 12b) 最常用形式：四个数字＝对角两点，选完自动预演
        sourceType.getMethod("reset").invoke(null);
        run(commandsType, sourceType, commands, op4, "/hy clear pos1 0 0");
        check(said(sourceType, "已选角点 1"), "逐点选点：pos1 记录方块坐标");
        sourceType.getMethod("reset").invoke(null);
        run(commandsType, sourceType, commands, op4, "/hy clear 0 0 31 31");
        check(said(sourceType, "已选角点 2") && said(sourceType, "内圈 x[0..31] z[0..31]"),
                "「/hy clear <x1> <z1> <x2> <z2>」四个数字直接选定对角两点");
        check(said(sourceType, "预演"), "选完自动给预演，不用再敲 preview"
                + sourceType.getMethod("tail", int.class).invoke(null, 3));
        check(said(sourceType, "点击确认开始挖掘"), "预演里带可点击的「确认开始」");

        // 12b2) 站在一角上，只给对角的两个数字
        Object playerSource = sourceType.getConstructor(int.class, Object.class, levelType)
                .newInstance(4, entityType.getConstructor(int.class, int.class, long.class)
                        .newInstance(0, 0, 0L), level);
        sourceType.getMethod("reset").invoke(null);
        run(commandsType, sourceType, commands, playerSource, "/hy clear 31 31");
        check(said(sourceType, "内圈 x[0..31] z[0..31]"),
                "「/hy clear <x2> <z2>」两个数字＝以你当前站位为角点 1（这里站位是 0,0）");

        // 12b3) 一个数字＝以你为中心的边长
        sourceType.getMethod("reset").invoke(null);
        run(commandsType, sourceType, commands, playerSource, "/hy clear 32");
        check(said(sourceType, "以你为中心，边长 32") && said(sourceType, "内圈 x[-15..16] z[-15..16]"),
                "「/hy clear <边长>」一个数字＝以你为中心的正方形"
                        + sourceType.getMethod("tail", int.class).invoke(null, 2));

        // 12b4) 控制台下两个数字没有站位可依据 → 明确提示改用四个数字；维度指定也有校验
        sourceType.getMethod("reset").invoke(null);
        run(commandsType, sourceType, commands, op4, "/hy clear 31 31");
        check(said(sourceType, "控制台请给四个数字"), "控制台下缺站位时明确提示改用四个数字");
        sourceType.getMethod("reset").invoke(null);
        run(commandsType, sourceType, commands, op4, "/hy clear dim nope");
        check(said(sourceType, "找不到维度 nope"), "「/hy clear dim <维度>」会校验维度是否存在");
        run(commandsType, sourceType, commands, op4, "/hy clear dim overworld");
        check(said(sourceType, "已把目标维度固定为"), "维度 id 可以写简写（overworld → minecraft:overworld）");
        run(commandsType, sourceType, commands, op4, "/hy clear reset");

        // 恢复成待测的选区（后面的节流/三阶段断言都基于它）
        sourceType.getMethod("reset").invoke(null);
        run(commandsType, sourceType, commands, op4, "/hy clear 0 0 31 31");

        // 12c) preview 只预演、不动世界
        commandsType.getMethod("reset").invoke(null);
        sourceType.getMethod("reset").invoke(null);
        run(commandsType, sourceType, commands, op4, "/hy clear preview");
        check(said(sourceType, "预演"), "preview 打印预演报告");
        check(countVanilla(commandsType, "") == 0,
                "preview 阶段一条原版命令都没派发（预演不动世界）→ " + commandsType.getMethod("trace").invoke(null));
        int planSize = ((Number) clearJob.getMethod("planSize").invoke(null)).intValue();
        int planFills = ((Number) clearJob.getMethod("planFills").invoke(null)).intValue();
        long planBlocks = ((Number) clearJob.getMethod("plannedBlocks").invoke(null)).longValue();
        check(planSize > 20 && planFills > 10 && planBlocks > 100_000L,
                "计划规模合理: " + planSize + " 步 / " + planFills + " 条 fill / 约 " + planBlocks + " 方块");
        check(said(sourceType, "阶段 1 外圈上方清空") && said(sourceType, "阶段 3 内圈清空"),
                "预演列出了三阶段与 y 范围");

        // 12d) 不带 confirm 不允许开始
        sourceType.getMethod("reset").invoke(null);
        run(commandsType, sourceType, commands, op4, "/hy clear start");
        check(said(sourceType, "confirm") && !Boolean.TRUE.equals(clearJob.getMethod("isRunning").invoke(null)),
                "start 不带 confirm 被拒绝，任务没有开始");

        // 12e) confirm / go 都会开始，并按配置节流推进
        commandsType.getMethod("reset").invoke(null);
        sourceType.getMethod("reset").invoke(null);
        run(commandsType, sourceType, commands, op4, "/hy clear go");
        check(Boolean.TRUE.equals(clearJob.getMethod("isRunning").invoke(null)),
                "「/hy clear go」等价于 start confirm，预演后一条命令就能开挖");
        for (int i = 0; i < 10; i++) {
            levelTick.invoke(level, (java.util.function.BooleanSupplier) () -> true);
            Thread.sleep(50L);
        }
        int fillsAfter10 = countVanilla(commandsType, "fill ");
        check(fillsAfter10 >= 1 && fillsAfter10 <= 10,
                "节流生效：10 个 tick 内只派发 " + fillsAfter10 + " 条 fill（计划共 " + planFills + " 条，没有一次刷完）");

        // 12f) 跑完整套计划，检查三阶段与实体清理
        for (int i = 0; i < 400 && Boolean.TRUE.equals(clearJob.getMethod("isRunning").invoke(null)); i++) {
            levelTick.invoke(level, (java.util.function.BooleanSupplier) () -> true);
            Thread.sleep(50L);
        }
        check(!Boolean.TRUE.equals(clearJob.getMethod("isRunning").invoke(null)), "任务在有限 tick 内跑完（自检区域很小）");
        check(vanillaHas(commandsType, "forceload add -1 -1 32 32"),
                "阶段1/3 每个批次先 forceload add（保证要挖的区块已加载）");
        long maxFill = maxFillVolume(commandsType);
        check(maxFill > 0L && maxFill <= 32768L,
                "每条 fill 的方块数都不超过原版上限 32768（实际最大 " + maxFill + "）");
        check(maxFill >= 16384L, "分层切分没有过度保守（最大一条 fill " + maxFill + " 方块，接近上限）");
        check(yCoverageProblem(commandsType, -1, -1, 14, 14, 63, 319) == null,
                "阶段1 某一区块列的 y 分层连续、无重叠、无空隙，覆盖 63..319 → "
                        + yCoverageProblem(commandsType, -1, -1, 14, 14, 63, 319));
        check(yCoverageProblem(commandsType, 0, 0, 15, 15, -64, 62) == null,
                "阶段3 某一区块列的 y 分层连续、无重叠、无空隙，覆盖 -64..62 → "
                        + yCoverageProblem(commandsType, 0, 0, 15, 15, -64, 62));
        check(vanillaHas(commandsType, "run fill -1 -63 -1 32 62 -1 sand"),
                "阶段2: 沿外圈四条边填防爆沟（沙子，y -63..62）");
        check(vanillaHas(commandsType, "run fill 0 -64 0 15 62 15 air"), "阶段3: 内圈清空（y -64..62）");
        check(vanillaHas(commandsType, "kill @e[type=item,x="),
                "掉落物清理用体积选择器限定在本区域，不碰区域外的东西");
        check(vanillaHas(commandsType, "forceload remove"), "每批结束都 forceload remove（不长期霸占区块加载）");
        check(said(sourceType, "空置域挖掘完成"), "完成后把报告发回发起者");

        // 12g) y 范围按维度建筑高度夹取（模拟下界/末地的 0..255）
        levelType.getField("minBuildHeight").set(level, 0);
        levelType.getField("maxBuildHeight").set(level, 256);
        sourceType.getMethod("reset").invoke(null);
        run(commandsType, sourceType, commands, op4, "/hy clear preview");
        check(said(sourceType, "已按该维度的建筑高度夹取") && said(sourceType, "min_y=0"),
                "y 范围按建筑高度夹取（min_y=0 / max_y=255）"
                        + sourceType.getMethod("tail", int.class).invoke(null, 2));
        levelType.getField("minBuildHeight").set(level, -64);
        levelType.getField("maxBuildHeight").set(level, 320);

        // 12h) stop 立即中止
        run(commandsType, sourceType, commands, op4, "/hy clear start confirm");
        for (int i = 0; i < 3; i++) {
            levelTick.invoke(level, (java.util.function.BooleanSupplier) () -> true);
            Thread.sleep(50L);
        }
        run(commandsType, sourceType, commands, op4, "/hy clear stop");
        check(!Boolean.TRUE.equals(clearJob.getMethod("isRunning").invoke(null)), "stop 立即中止任务");
        int before = countVanilla(commandsType, "fill ");
        for (int i = 0; i < 6; i++) {
            levelTick.invoke(level, (java.util.function.BooleanSupplier) () -> true);
            Thread.sleep(50L);
        }
        check(countVanilla(commandsType, "fill ") == before, "中止之后不再派发任何 fill（已挖的不回滚）");

        // 12i) 基岩开关：break_bedrock=false ⇒ 保留基岩层，挖掘下界抬到 bedrock_top_y 之上
        writeConfig(false);
        listManager.getMethod("reload").invoke(null);
        sourceType.getMethod("reset").invoke(null);
        run(commandsType, sourceType, commands, op4, "/hy clear 0 0 31 31");
        check(said(sourceType, "基岩层: 保留") && said(sourceType, "阶段 3 内圈清空: y -59 .. 62"),
                "break_bedrock=false：保留基岩层，内圈从 y=-59 开始清（-60 及以下的基岩不动）"
                        + sourceType.getMethod("tail", int.class).invoke(null, 3));
        writeConfig(true);
        listManager.getMethod("reload").invoke(null);
        sourceType.getMethod("reset").invoke(null);
        run(commandsType, sourceType, commands, op4, "/hy clear 0 0 31 31");
        check(said(sourceType, "基岩层: 一起挖掉") && said(sourceType, "阶段 3 内圈清空: y -64 .. 62"),
                "break_bedrock=true（默认）：连基岩一起挖掉，底部是虚空");

        // 12j) 勘探的省事写法：/lag 无参＝直接出报告；/lag 30 ＝采样 30 秒
        sourceType.getMethod("reset").invoke(null);
        run(commandsType, sourceType, commands, console, "/lag");
        check(said(sourceType, "区块卡顿勘探") && said(sourceType, "异常判据"),
                "「/lag」不带子命令＝直接出报告（最常用的事不用记子命令）");
        sourceType.getMethod("reset").invoke(null);
        run(commandsType, sourceType, commands, console, "/lag 1");
        check(said(sourceType, "开始区块卡顿勘探：采样 1 秒"),
                "「/lag 1」带数字＝采样 1 秒（= lag scan 1）");

        System.out.println();
        System.out.println(failures == 0 ? "[lag] 全部通过" : "[lag] 失败项: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    // ==================================================================

    private static final String CONFLICT_UUID = "99998888-7777-6666-5555-444433332222";

    /** 以某个来源执行一条命令。 */
    private static void run(Class<?> commandsType, Class<?> sourceType, Object commands, Object source, String line)
            throws Exception {
        commandsType.getMethod("performPrefixedCommand", sourceType, String.class).invoke(commands, source, line);
    }

    /** 来源是否收到过包含该片段的输出。 */
    private static boolean said(Class<?> sourceType, String needle) throws Exception {
        return Boolean.TRUE.equals(sourceType.getMethod("said", String.class).invoke(null, needle));
    }

    /** 原版命令轨迹里是否有包含该片段的一行。 */
    private static boolean vanillaHas(Class<?> commandsType, String needle) throws Exception {
        return Boolean.TRUE.equals(commandsType.getMethod("dispatchedAny", String.class).invoke(null, needle));
    }

    /** 统计真正派发到原版的命令条数（只数 "plain:" 那一层，避免 prefixed/plain 重复计数）。 */
    private static int countVanilla(Class<?> commandsType, String needle) throws Exception {
        java.util.List<?> vanilla = (java.util.List<?>) commandsType.getField("vanilla").get(null);
        int count = 0;
        for (Object entry : vanilla) {
            String text = String.valueOf(entry);
            if (text.startsWith("plain:") && text.contains(needle)) {
                count++;
            }
        }
        return count;
    }

    private static java.util.List<?> vanillaList(Class<?> commandsType) throws Exception {
        return (java.util.List<?>) commandsType.getField("vanilla").get(null);
    }

    /** 所有 {@code fill} 命令里最大的一条占多少方块（原版上限 32768，超了命令会直接失败）。 */
    private static long maxFillVolume(Class<?> commandsType) throws Exception {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "run fill (-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+) \\w+");
        long max = 0L;
        for (Object entry : vanillaList(commandsType)) {
            java.util.regex.Matcher matcher = pattern.matcher(String.valueOf(entry));
            while (matcher.find()) {
                long dx = Long.parseLong(matcher.group(4)) - Long.parseLong(matcher.group(1)) + 1;
                long dy = Long.parseLong(matcher.group(5)) - Long.parseLong(matcher.group(2)) + 1;
                long dz = Long.parseLong(matcher.group(6)) - Long.parseLong(matcher.group(3)) + 1;
                long volume = dx * dy * dz;
                if (volume > max) {
                    max = volume;
                }
            }
        }
        return max;
    }

    /**
     * 检查某个区块列的 y 分层：必须<b>连续、不重叠、不覆盖缺口</b>，且恰好覆盖期望区间。
     *
     * @return {@code null} 表示没问题，否则返回问题描述
     */
    private static String yCoverageProblem(Class<?> commandsType, int x1, int z1, int x2, int z2,
                                           int expectMinY, int expectMaxY) throws Exception {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "run fill " + x1 + " (-?\\d+) " + z1 + " " + x2 + " (-?\\d+) " + z2 + " \\w+");
        java.util.Set<String> seen = new java.util.HashSet<String>();
        java.util.List<int[]> slices = new java.util.ArrayList<int[]>();
        for (Object entry : vanillaList(commandsType)) {
            java.util.regex.Matcher matcher = pattern.matcher(String.valueOf(entry));
            while (matcher.find()) {
                String key = matcher.group(1) + ".." + matcher.group(2);
                if (seen.add(key)) {
                    slices.add(new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))});
                }
            }
        }
        if (slices.isEmpty()) {
            return "没有匹配到该列的 fill";
        }
        java.util.Collections.sort(slices, new java.util.Comparator<int[]>() {
            @Override
            public int compare(int[] left, int[] right) {
                return Integer.compare(left[0], right[0]);
            }
        });
        int cursor = expectMinY;
        for (int[] slice : slices) {
            if (slice[0] != cursor) {
                return "y 分层不连续/重叠：期望从 " + cursor + " 开始，实际 [" + slice[0] + ".." + slice[1] + "]";
            }
            cursor = slice[1] + 1;
        }
        if (cursor - 1 != expectMaxY) {
            return "y 覆盖不足：只到 " + (cursor - 1) + "，期望 " + expectMaxY;
        }
        return null;
    }

    /** 取常驻窗口里某个区块的统计行（直接调真实的 {@code ChunkLagSampler.row}）。 */
    private static Object rowOf(Class<?> sampler, ClassLoader app, int chunkX, int chunkZ) throws Exception {
        Object resident = sampler.getMethod("residentWindow").invoke(null);
        return sampler.getMethod("row",
                        Class.forName("com.hyauth.agent.util.ChunkLagSampler$Window", true, app),
                        String.class, int.class, int.class)
                .invoke(null, resident, OVERWORLD, chunkX, chunkZ);
    }

    /**
     * 在收到的输出里找「标签 + 数字」，判断是否有落在 {@code [min,max]} 内的。
     *
     * <p>为什么要区间而不是精确值：替身里的耗时是 {@code Thread.sleep}，
     * Windows 的计时器粒度约 1ms（睡 4ms 实测 5.0ms 左右），精确断言必然假失败。
     * 区间断言照样能证明"这笔耗时确实被记到了这个区块/这个分项上"。
     */
    private static boolean saidNumberIn(Class<?> sourceType, String label, double min, double max)
            throws Exception {
        @SuppressWarnings("unchecked")
        java.util.List<String> messages = (java.util.List<String>) sourceType.getField("messages").get(null);
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(label) + "\\s*([0-9]+\\.[0-9]+)");
        for (String message : messages) {
            java.util.regex.Matcher matcher = pattern.matcher(message);
            while (matcher.find()) {
                double value = Double.parseDouble(matcher.group(1));
                if (value >= min && value <= max) {
                    return true;
                }
            }
        }
        return false;
    }

    /** ServerLevel 上要挂四个切面（区块 tick / 非乘客实体 / 乘客实体 / 世界 tick 心跳），必须一次 redefine。 */
    private static void redefineServerLevel(ClassLoader child, ClassFileLocator locator, ClassLoader app)
            throws Exception {
        Class<?> chunkTick = Class.forName("com.hyauth.agent.ChunkTickAdvice", false, app);
        Class<?> entityTick = Class.forName("com.hyauth.agent.EntityTickAdvice", false, app);
        Class<?> passengerTick = Class.forName("com.hyauth.agent.PassengerTickAdvice", false, app);
        Class<?> levelTick = Class.forName("com.hyauth.agent.LevelTickAdvice", false, app);
        TypeDescription target = describe(locator, "net.minecraft.server.level.ServerLevel");
        new ByteBuddy()
                .redefine(target, locator)
                .visit(Advice.to(chunkTick)
                        .on(ElementMatchers.named("tickChunk").and(ElementMatchers.takesArguments(2))))
                .visit(Advice.to(entityTick)
                        .on(ElementMatchers.named("tickNonPassenger").and(ElementMatchers.takesArguments(1))))
                .visit(Advice.to(passengerTick)
                        .on(ElementMatchers.named("tickPassenger").and(ElementMatchers.takesArguments(2))))
                .visit(Advice.to(levelTick)
                        .on(ElementMatchers.named("tick").and(ElementMatchers.takesArguments(1))))
                .make()
                .load(child, ClassLoadingStrategy.Default.INJECTION);
    }

    private static void redefineOne(ClassLoader child, ClassFileLocator locator, ClassLoader app,
                                    String adviceName, String className, String method, int arity)
            throws Exception {
        Class<?> advice = Class.forName(adviceName, false, app);
        TypeDescription target = describe(locator, className);
        new ByteBuddy()
                .redefine(target, locator)
                .visit(Advice.to(advice)
                        .on(ElementMatchers.named(method).and(ElementMatchers.takesArguments(arity))))
                .make()
                .load(child, ClassLoadingStrategy.Default.INJECTION);
    }

    private static TypeDescription describe(ClassFileLocator locator, String className) {
        return TypePool.Default.WithLazyResolution.of(locator).describe(className).resolve();
    }

    private static Object newChunk(Class<?> chunkType, Class<?> levelType, Object level, int x, int z)
            throws Exception {
        return chunkType.getConstructor(levelType, int.class, int.class).newInstance(level, x, z);
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * 扫描 Agent jar 里所有 {@code *Advice.class}，断言常量池里<b>没有</b> {@code net/minecraft}。
     *
     * <p>这是 README §8.4 那条硬约束的机器化回归：切面类由 system 加载器加载，
     * 一旦字节码里出现服务端类型，Bundler 环境下挂载就会失败，
     * 而且失败方式是"服务端照常开服、功能静默失效"——所以必须由测试兜住。
     */
    private static void checkAdviceBytecode(File agentJar) throws Exception {
        boolean clean = true;
        int scanned = 0;
        try (JarFile jar = new JarFile(agentJar)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith("com/hyauth/agent/") || !name.endsWith("Advice.class")) {
                    continue;
                }
                scanned++;
                byte[] bytes = readAll(jar.getInputStream(entry));
                if (new String(bytes, StandardCharsets.ISO_8859_1).contains("net/minecraft")) {
                    System.out.println("[FAIL] 切面类字节码里出现了 net/minecraft: " + name);
                    clean = false;
                }
            }
        }
        check(scanned >= 6 && clean,
                "全部 " + scanned + " 个 *Advice 切面类字节码中都不含 net/minecraft（§8.4 硬约束回归）");
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        in.close();
        return buffer.toByteArray();
    }

    /** 写测试配置：命令根 hy/lag、判据 1ms 或中位数 6 倍、常驻开启、空置域挖掘参数。 */
    private static void writeConfig(boolean breakBedrock) throws Exception {
        String json = "{\n"
                + "  \"description\": \"lag 自检配置\",\n"
                + "  \"littleskin_players\": [],\n"
                + "  \"offline_players\": [],\n"
                + "  \"api_root\": \"http://127.0.0.1:25588/api/yggdrasil\",\n"
                + "  \"debug\": true,\n"
                + "  \"chunk_lag\": {\n"
                + "    \"resident\": true,\n"
                + "    \"flag_threshold_ms\": 1.0,\n"
                + "    \"flag_relative_factor\": 6.0,\n"
                + "    \"scan_default_seconds\": 30,\n"
                + "    \"report_top\": 10,\n"
                + "    \"max_clusters\": 8,\n"
                + "    \"max_tracked_chunks\": 20000\n"
                + "  },\n"
                + "  \"commands\": { \"roots\": [ \"hy\", \"lag\" ], \"op_level\": 2 },\n"
                + "  \"clear\": {\n"
                + "    \"op_level\": 3,\n"
                + "    \"interval_ticks\": 4,\n"
                + "    \"fills_per_step\": 2,\n"
                + "    \"load_wait_ticks\": 1,\n"
                + "    \"kill\": \"items\",\n"
                + "    \"min_y\": -64,\n"
                + "    \"top_y\": 63,\n"
                + "    \"above_height\": 256,\n"
                + "    \"trench\": true,\n"
                + "    \"trench_block\": \"sand\",\n"
                + "    \"batch_chunks\": 4,\n"
                + "    \"max_side\": 512,\n"
                + "    \"max_volume\": 100000000,\n"
                + "    \"announce_percent\": 20,\n"
                + "    \"break_bedrock\": " + breakBedrock + ",\n"
                + "    \"bedrock_top_y\": -60\n"
                + "  }\n"
                + "}\n";
        try (Writer writer = new OutputStreamWriter(new FileOutputStream("littleskin_config.json"),
                StandardCharsets.UTF_8)) {
            writer.write(json);
        }
    }

    /** 写一份 usercache.json：ConflictGuy 已经有身份（用来测"先查重再发证"）。 */
    private static void writeUserCache() throws Exception {
        String json = "[\n"
                + "  { \"name\": \"ConflictGuy\", \"uuid\": \"" + CONFLICT_UUID + "\","
                + " \"expiresOn\": \"2030-01-01 00:00:00 +0000\" },\n"
                + "  { \"name\": \"SomeOneElse\", \"uuid\": \"11112222-3333-4444-5555-666677778888\","
                + " \"expiresOn\": \"2030-01-01 00:00:00 +0000\" }\n"
                + "]\n";
        try (Writer writer = new OutputStreamWriter(new FileOutputStream("usercache.json"),
                StandardCharsets.UTF_8)) {
            writer.write(json);
        }
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    /** 从配置文本里抠出某个名字对应的 UUID（用于断言版本号）。 */
    private static String firstUuidFor(String config, String name) {
        int at = config.indexOf(name);
        while (at >= 0) {
            int uuidAt = config.indexOf("\"uuid\"", at);
            if (uuidAt < 0) {
                return null;
            }
            int firstQuote = config.indexOf('"', uuidAt + 6);
            int secondQuote = firstQuote < 0 ? -1 : config.indexOf('"', firstQuote + 1);
            if (firstQuote > 0 && secondQuote > firstQuote) {
                return config.substring(firstQuote + 1, secondQuote);
            }
            at = config.indexOf(name, at + 1);
        }
        return null;
    }
}
