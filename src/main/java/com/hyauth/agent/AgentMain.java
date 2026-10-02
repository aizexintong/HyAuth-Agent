package com.hyauth.agent;

import com.hyauth.agent.config.ListManager;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.implementation.bytecode.assign.TypeCasting;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;

/**
 * HyAuth-Agent 启动入口。
 *
 * <p>通过 JVM Instrumentation + Byte Buddy 在 {@code com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService}
 * 的 {@code hasJoinedServer} 方法上挂载鉴权分流切面：
 * <ul>
 *     <li>白名单内的玩家 → 走 LittleSkin（外置 Yggdrasil）校验；</li>
 *     <li>不在白名单的玩家 → 直接放行原版逻辑（Mojang 官方校验，零额外开销）。</li>
 * </ul>
 *
 * <p>注意：不同版本的 Authlib 中 {@code hasJoinedServer} 的签名并不相同，切面同时兼容：
 * <ul>
 *     <li>Authlib ≤ 5.x（Minecraft ≤ 1.20.4）：{@code GameProfile hasJoinedServer(GameProfile, String, InetAddress)}</li>
 *     <li>Authlib ≥ 6.x（Minecraft ≥ 1.20.5）：{@code ProfileResult hasJoinedServer(String, String, InetAddress)}</li>
 * </ul>
 */
public class AgentMain {

    /**
     * 目标类所在的包前缀。已知的会话服务实现类：
     * <ul>
     *     <li>Authlib ≤ 9.x（MC ≤ 1.21.x）：{@code com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService}</li>
     *     <li>Authlib ≥ 10.x（MC ≥ 26.x）：{@code com.mojang.authlib.services.MinecraftServicesSessionService}</li>
     * </ul>
     * 为兼容后续可能的重命名，这里不写死类名，而是用
     * “包前缀 + 声明了 hasJoinedServer(3 参数) + 非接口”来匹配。
     */
    private static final String AUTH_LIB_PACKAGE_PREFIX = "com.mojang.authlib.";

    /** 目标方法：服务端向验证服务器确认“客户端确实已登录”。 */
    private static final String HAS_JOINED_METHOD = "hasJoinedServer";

    /** 登录处理器（26.x 服务端类未混淆）：用于取出 LoginStart 中的玩家名。 */
    private static final String LOGIN_HANDLER_CLASS = "net.minecraft.server.network.ServerLoginPacketListenerImpl";

    /** 登录处理器中处理 LoginStart 的方法。 */
    private static final String HANDLE_HELLO_METHOD = "handleHello";

    /** 服务端下发的握手包：其中的 shouldAuthenticate 决定客户端是否上报会话。 */
    private static final String HELLO_PACKET_CLASS = "net.minecraft.network.protocol.login.ClientboundHelloPacket";

    /** 聊天签名密钥校验器的来源：原版用 Mojang 服务密钥，外置账号必被拒。 */
    private static final String SERVICES_CLASS = "net.minecraft.server.Services";

    /** {@code Services} 中返回聊天密钥校验器的方法。 */
    private static final String PROFILE_KEY_VALIDATOR_METHOD = "profileKeySignatureValidator";

    /** 处理玩家指令的监听器：其中的签名实参解码会用掉「聊天公钥」。 */
    private static final String GAME_LISTENER_CLASS = "net.minecraft.server.network.ServerGamePacketListenerImpl";

    /**
     * 上面那个监听器的父类。
     *
     * <p>聊天发送方法（{@code sendPlayerChatMessage} / {@code sendDisguisedChatMessage}）究竟声明在
     * 子类还是这个父类里，各版本不一定；两个类都挂一遍最省心：<b>没声明该方法的那个类是空操作</b>
     * （Byte Buddy 的 {@code visit} 只作用于该类自己声明的方法）。
     */
    private static final String COMMON_LISTENER_CLASS = "net.minecraft.server.network.ServerCommonPacketListenerImpl";

    /** 解码「签名版指令包」实参签名的方法（无公钥时会抛 DecodeException）。 */
    private static final String COLLECT_SIGNED_ARGUMENTS = "collectSignedArguments";

    /** 为「没有聊天公钥的玩家」构造聊天解码器的接口（按玩家决定要不要强制安全档案）。 */
    private static final String CHAT_DECODER_CLASS = "net.minecraft.network.chat.SignedMessageChain$Decoder";

    // ------------------------------------------------------------------
    // 区块卡顿勘探 + 管理员命令（v1.0.3，见 README 第十五/十六章）
    //
    // 这一组切点是"按名字尽可能挂、挂不上就降级"的：原版类名/方法名随版本变动，
    // 每个类别独立命中、独立播报，缺一个只影响那一类的数字，绝不连累鉴权主链路。
    // ------------------------------------------------------------------

    /** 区块 tick（随机刻、冰雪、闪电…）的宿主类。 */
    private static final String SERVER_LEVEL_CLASS = "net.minecraft.server.level.ServerLevel";

    /** 方块实体 tick 的宿主类（原版按区块遍历方块实体）。 */
    private static final String LEVEL_CHUNK_CLASS = "net.minecraft.world.level.chunk.LevelChunk";

    /** 服务端主类：整 tick 计时的宿主。 */
    private static final String MINECRAFT_SERVER_CLASS = "net.minecraft.server.MinecraftServer";

    /** 命令系统：管理员命令的接管点。 */
    private static final String COMMANDS_CLASS = "net.minecraft.commands.Commands";

    /** 该接口里构造「未签名解码器」的静态工厂：unsigned(UUID, BooleanSupplier)。 */
    private static final String CHAT_DECODER_FACTORY = "unsigned";

    /**
     * 每个接收者各调用一次的聊天发送方法。
     *
     * <p>原版调用链：{@code PlayerList#broadcastChatMessage} → {@code ServerPlayer#sendChatMessage}
     * → {@code OutgoingChatMessage.Player#sendToPlayer} → <b>本方法</b>。
     * 切在这里才知道"这条消息发给谁"，从而能把外置账号的消息按接收者分流
     * （作者本人保留原版签名消息，其他人改用伪装聊天），
     * 见 {@code com.hyauth.agent.util.ExternalChatBroadcast}。
     */
    private static final String SEND_PLAYER_CHAT_MESSAGE = "sendPlayerChatMessage";

    /**
     * 宽松赋值器。
     *
     * <p>切面统一用 {@code Object} 承载参数与返回值，以同时适配新旧 Authlib；
     * 而 Byte Buddy 默认赋值器只接受“静态可赋值”的类型，遇到
     * {@code Object → ProfileResult / GameProfile} 会直接抛
     * {@code IllegalStateException: Cannot assign class java.lang.Object to class ...}，
     * 导致目标类完全不被转换。这里显式允许对引用类型插入 CHECKCAST。
     */
    private static final Assigner LENIENT_ASSIGNER = new Assigner() {
        @Override
        public StackManipulation assign(TypeDescription.Generic source,
                                        TypeDescription.Generic target,
                                        Typing typing) {
            TypeDescription sourceType = source.asErasure();
            TypeDescription targetType = target.asErasure();
            if (sourceType.isPrimitive() || targetType.isPrimitive()) {
                return Assigner.DEFAULT.assign(source, target, typing);
            }
            if (sourceType.equals(targetType)) {
                return StackManipulation.Trivial.INSTANCE;
            }
            if (sourceType.isAssignableTo(targetType)) {
                return Assigner.DEFAULT.assign(source, target, typing);
            }
            // Object → 具体类型：插入 CHECKCAST，运行期类型由切面保证
            return TypeCasting.to(targetType);
        }
    };

    /**
     * 字节码改写结果播报器。
     *
     * <p>为什么要专门做这件事：本 Agent 曾经出现过<b>静默失效</b>——切面因为
     * 类加载器隔离（authlib 在 Bundler 子加载器里、Agent 类在 system 加载器里）挂载失败，
     * 服务端照常开服、玩家照常能进（正版走原版逻辑），只有名单完全不生效，
     * 唯一的线索是一段容易被忽略的 Byte Buddy ERROR。
     *
     * <p>现在：成功会打印 {@code [HyAuth] 已改写目标类字节码}，失败会打印显眼的
     * {@code [HyAuth] 字节码改写失败} 并附上异常摘要（完整堆栈仍由上面的
     * {@code StreamWriting.toSystemError()} 输出）。看到"命中目标类"却看不到"已改写"，
     * 就说明 Agent 没有真正生效。
     */
    private static final AgentBuilder.Listener FAILURE_REPORT = new AgentBuilder.Listener() {
        @Override
        public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
            // 不需要
        }

        @Override
        public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                     JavaModule module, boolean loaded, DynamicType dynamicType) {
            System.out.println("[HyAuth] 已改写目标类字节码: " + typeDescription.getName()
                    + "（加载器: " + describe(classLoader) + "）");
        }

        @Override
        public void onIgnored(TypeDescription typeDescription, ClassLoader classLoader,
                              JavaModule module, boolean loaded) {
            // 不需要
        }

        @Override
        public void onError(String typeName, ClassLoader classLoader, JavaModule module,
                            boolean loaded, Throwable throwable) {
            System.err.println("[HyAuth] 字节码改写失败: " + typeName + "（加载器: " + describe(classLoader) + "）");
            System.err.println("[HyAuth] 原因: " + throwable);
            System.err.println("[HyAuth] 这会让 Agent 静默失效（服务端能开服，但名单不生效），"
                    + "请把完整堆栈发给维护者；完整堆栈见紧随其后的 [Byte Buddy] ERROR 段落。");
            System.err.println("[HyAuth] 常见原因：切面类字节码里引用了 authlib 类型"
                    + "（Bundler 环境下 system 加载器看不到 authlib），解法见 AuthRejection 的注释。");
        }

        @Override
        public void onComplete(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
            // 不需要
        }

        private String describe(ClassLoader classLoader) {
            return classLoader == null ? "bootstrap" : classLoader.getClass().getName() + "@"
                    + Integer.toHexString(System.identityHashCode(classLoader));
        }
    };

    public static void premain(String agentArgs, Instrumentation inst) {
        banner();
        initConfigSafely();
        install(inst);
    }

    /**
     * 支持动态 Attach（{@code VirtualMachine.loadAgent}）热挂载。
     * 该场景下目标类通常已加载，因此依赖 RETRANSFORMATION 重转换。
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        banner();
        System.out.println("[HyAuth] 检测到运行时 Attach，尝试重新挂载鉴权切面...");
        initConfigSafely();
        install(inst);
    }

    /**
     * 初始化配置。
     *
     * <p>原版 {@code server.jar}（Mojang Bundler）把服务端依赖放在子类加载器里，
     * 在 premain 阶段本 JVM 看不到 gson，此时初始化会失败 —— 这属于正常现象，
     * 配置会在 {@link LoaderBridge} 把 Agent 类注入服务端类加载器后再初始化。
     */
    private static void initConfigSafely() {
        try {
            ListManager.init();
        } catch (Throwable t) {
            System.err.println("[HyAuth] 当前阶段无法初始化配置（原版 Bundler 启动的正常现象），"
                    + "将延迟到服务端类加载器中执行: " + t);
        }
    }

    private static void install(final Instrumentation inst) {
        AgentBuilder builder = new AgentBuilder.Default()
                // 关闭 Byte Buddy 默认的忽略规则，确保只按我们自己的 type 规则筛选
                .ignore(ElementMatchers.none())
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
                // 显式打印“改写成功/失败”：静默失效比报错更危险（见 FAILURE_REPORT）
                .with(FAILURE_REPORT)
                .type(ElementMatchers.<TypeDescription>nameStartsWith(AUTH_LIB_PACKAGE_PREFIX)
                        .and(ElementMatchers.not(ElementMatchers.isInterface()))
                        .and(ElementMatchers.declaresMethod(ElementMatchers.named(HAS_JOINED_METHOD)
                                .and(ElementMatchers.takesArguments(3)))))
                .transform((dynamicType, typeDescription, classLoader, module, protectionDomain) -> {
                    System.out.println("[HyAuth] 命中目标类: " + typeDescription.getName()
                            + "，开始挂载 hasJoinedServer 切面。");
                    // 原版 Bundler 下服务端类加载器看不到 Agent 类，这里先注入
                    LoaderBridge.ensureInjected(classLoader);
                    return dynamicType.visit(
                            Advice.to(HasJoinedAdvice.class)
                                    .withAssigner(LENIENT_ASSIGNER)
                                    .on(ElementMatchers.named(HAS_JOINED_METHOD)
                                            .and(ElementMatchers.takesArguments(3)))
                    );
                })
                // 登录握手 1/2：取出 LoginStart 里的玩家名，供下一步判断
                .type(ElementMatchers.named(LOGIN_HANDLER_CLASS))
                .transform((dynamicType, typeDescription, classLoader, module, protectionDomain) -> {
                    LoaderBridge.ensureInjected(classLoader);
                    System.out.println("[HyAuth] 命中登录处理器: " + typeDescription.getName()
                            + "，挂载离线名单登录握手切面。");
                    return dynamicType.visit(
                            Advice.to(LoginNameAdvice.class)
                                    .on(ElementMatchers.named(HANDLE_HELLO_METHOD)
                                            .and(ElementMatchers.takesArguments(1)))
                    );
                })
                // 登录握手 2/2：离线名单玩家让客户端跳过会话上报（shouldAuthenticate=false）
                .type(ElementMatchers.named(HELLO_PACKET_CLASS))
                .transform((dynamicType, typeDescription, classLoader, module, protectionDomain) -> {
                    LoaderBridge.ensureInjected(classLoader);
                    System.out.println("[HyAuth] 命中登录握手包: " + typeDescription.getName()
                            + "，挂载 shouldAuthenticate 改写切面。");
                    return dynamicType.visit(
                            Advice.to(HelloPacketAdvice.class)
                                    .on(ElementMatchers.isConstructor().and(ElementMatchers.takesArguments(4)))
                    );
                })
                // 聊天签名密钥校验：外置账号的密钥不是 Mojang 签的，严格校验会把玩家直接踢下线
                .type(ElementMatchers.named(SERVICES_CLASS))
                .transform((dynamicType, typeDescription, classLoader, module, protectionDomain) -> {
                    LoaderBridge.ensureInjected(classLoader);
                    System.out.println("[HyAuth] 命中聊天密钥校验器来源: " + typeDescription.getName()
                            + "，挂载「外置账号放宽」切面。");
                    return dynamicType.visit(
                            Advice.to(ChatKeyValidatorAdvice.class)
                                    .withAssigner(LENIENT_ASSIGNER)
                                    .on(ElementMatchers.named(PROFILE_KEY_VALIDATOR_METHOD)
                                            .and(ElementMatchers.takesArguments(0)))
                    );
                })
                // 玩家指令/聊天监听器（子类与父类一起匹配，只用一个 transform，避免依赖多切面叠加）
                .type(ElementMatchers.<TypeDescription>named(GAME_LISTENER_CLASS)
                        .or(ElementMatchers.<TypeDescription>named(COMMON_LISTENER_CLASS)))
                .transform((dynamicType, typeDescription, classLoader, module, protectionDomain) -> {
                    LoaderBridge.ensureInjected(classLoader);
                    System.out.println("[HyAuth] 命中玩家指令/聊天监听器: " + typeDescription.getName()
                            + "，挂载「无公钥指令回退」与「外置账号逐接收者未签名广播」切面。");
                    return dynamicType
                            // 离线名单玩家没有聊天公钥：让 /say、/me、/msg 这类指令按「未签名」执行
                            .visit(
                                    Advice.to(ChatCommandAdvice.class)
                                            .withAssigner(LENIENT_ASSIGNER)
                                            .on(ElementMatchers.named(COLLECT_SIGNED_ARGUMENTS)
                                                    .and(ElementMatchers.takesArguments(3)))
                            )
                            // 外置账号的聊天：作者本人保留原版签名消息，其他接收者改用原版「伪装聊天」
                            .visit(
                                    Advice.to(ExternalChatAdvice.class)
                                            .on(ElementMatchers.named(SEND_PLAYER_CHAT_MESSAGE)
                                                    .and(ElementMatchers.takesArguments(2)))
                            );
                })
                // 离线名单玩家按玩家豁免 enforce-secure-profile（不必关掉服务器的全局开关）
                .type(ElementMatchers.named(CHAT_DECODER_CLASS))
                .transform((dynamicType, typeDescription, classLoader, module, protectionDomain) -> {
                    LoaderBridge.ensureInjected(classLoader);
                    System.out.println("[HyAuth] 命中聊天解码器工厂: " + typeDescription.getName()
                            + "，挂载「离线玩家豁免安全档案」切面。");
                    return dynamicType.visit(
                            Advice.to(OfflineDecoderAdvice.class)
                                    .on(ElementMatchers.named(CHAT_DECODER_FACTORY)
                                            .and(ElementMatchers.takesArguments(2)))
                    );
                })
                // ==========================================================
                // 区块卡顿勘探：按类别各挂一处，缺哪个版本项就只少那一类数字
                // ==========================================================
                // ① 区块 tick（随机刻、冰雪、闪电…）：勘探主干
                .type(ElementMatchers.named(SERVER_LEVEL_CLASS))
                .transform((dynamicType, typeDescription, classLoader, module, protectionDomain) -> {
                    LoaderBridge.ensureInjected(classLoader);
                    System.out.println("[HyAuth] 命中区块 tick 宿主: " + typeDescription.getName()
                            + "，挂载「区块 tick 计时 / 实体计时」勘探切面。");
                    return dynamicType
                            .visit(Advice.to(ChunkTickAdvice.class)
                                    .on(ElementMatchers.named("tickChunk").and(ElementMatchers.takesArguments(2))))
                            // 非乘客实体：怪、掉落物、矿车本体…
                            .visit(Advice.to(EntityTickAdvice.class)
                                    .on(ElementMatchers.named("tickNonPassenger")
                                            .and(ElementMatchers.takesArguments(1))))
                            // 乘客实体：船上/矿车上的玩家与生物
                            .visit(Advice.to(PassengerTickAdvice.class)
                                    .on(ElementMatchers.named("tickPassenger")
                                            .and(ElementMatchers.takesArguments(2))))
                            // 世界 tick：给空置域挖掘任务做心跳（与 MinecraftServer#tickServer 互相兜底）
                            .visit(Advice.to(LevelTickAdvice.class)
                                    .on(ElementMatchers.named("tick").and(ElementMatchers.takesArguments(1))));
                })
                // ② 方块实体 tick：熔炉、漏斗、刷怪笼…（按区块归因）
                //
                // 26.3 实测：LevelChunk#tickBlockEntities() 已不存在，方块实体改成
                // Level#tickBlockEntities() 维度级遍历 TickingBlockEntity 实现。
                // 所以这里挂"每个 ticker 的 tick()"，再用 getPos() 反推区块 —— 保住"按区块"的粒度。
                // 老的 LevelChunk 切点保留给 1.18~1.21.x（那些版本的方块实体也走 ticker，
                // 但为免同一份耗时记两次，只在老类存在且没有 ticker 切点时才有意义 —— 见 README 说明）。
                .type(ElementMatchers.hasSuperType(
                        ElementMatchers.named("net.minecraft.world.level.block.entity.TickingBlockEntity")))
                .transform((dynamicType, typeDescription, classLoader, module, protectionDomain) -> {
                    LoaderBridge.ensureInjected(classLoader);
                    return dynamicType.visit(Advice.to(BlockEntityTickerAdvice.class)
                            .on(ElementMatchers.named("tick").and(ElementMatchers.takesArguments(0))));
                })
                // ②b 老版本（≤1.21.x）的按区块 tick 入口：类/方法不存在时匹配为空，无副作用
                .type(ElementMatchers.named(LEVEL_CHUNK_CLASS))
                .transform((dynamicType, typeDescription, classLoader, module, protectionDomain) -> {
                    LoaderBridge.ensureInjected(classLoader);
                    if (typeDescription.getDeclaredMethods().filter(
                            ElementMatchers.named("tickBlockEntities").and(ElementMatchers.takesArguments(0)))
                            .isEmpty()) {
                        return dynamicType;   // 26.3 起没有这个方法，直接跳过（不再打印误导性的"已挂载"）
                    }
                    System.out.println("[HyAuth] 命中区块类: " + typeDescription.getName()
                            + "，挂载「方块实体计时」勘探切面（旧版按区块入口）。");
                    return dynamicType.visit(Advice.to(BlockEntityTickAdvice.class)
                            .on(ElementMatchers.named("tickBlockEntities").and(ElementMatchers.takesArguments(0))));
                })
                // ③ 整服 MSPT 基线
                .type(ElementMatchers.named(MINECRAFT_SERVER_CLASS))
                .transform((dynamicType, typeDescription, classLoader, module, protectionDomain) -> {
                    LoaderBridge.ensureInjected(classLoader);
                    System.out.println("[HyAuth] 命中服务端主类: " + typeDescription.getName()
                            + "，挂载「整服 MSPT 计时」勘探切面。");
                    return dynamicType.visit(Advice.to(ServerTickAdvice.class)
                            .on(ElementMatchers.named("tickServer").and(ElementMatchers.takesArguments(1))));
                })
                // ④ 管理员命令接管：控制台 / 游戏内 / RCON 的汇聚点
                //
                // ⚠️ 必须按返回类型分别挂：26.3 实测这两个方法都是 void（老版本是 int）。
                //    v1.0.6 只挂了 int 版本 ⇒ 真机上一个方法都没匹配上，命令全落到原版。
                //    注意"命中命令系统 / 已改写目标类字节码"在方法匹配为空时也会打印，
                //    所以判断是否真的挂上要看 verify/realjar.ps1（拿真 server.jar 核对签名）。
                .type(ElementMatchers.named(COMMANDS_CLASS))
                .transform((dynamicType, typeDescription, classLoader, module, protectionDomain) -> {
                    LoaderBridge.ensureInjected(classLoader);
                    System.out.println("[HyAuth] 命中命令系统: " + typeDescription.getName()
                            + "，挂载「管理员命令接管」切面（命令根见配置 commands.roots）。");
                    ElementMatcher.Junction<MethodDescription> entry = ElementMatchers
                            .named("performPrefixedCommand")
                            .or(ElementMatchers.named("performCommand"))
                            .and(ElementMatchers.takesArguments(2));
                    return dynamicType
                            .visit(Advice.to(CommandAdviceVoid.class)
                                    .on(entry.and(ElementMatchers.returns(void.class))))
                            .visit(Advice.to(CommandAdvice.class)
                                    .on(entry.and(ElementMatchers.returns(int.class))))
                            .visit(Advice.to(CommandAdviceBoolean.class)
                                    .on(entry.and(ElementMatchers.returns(boolean.class))));
                });

        try {
            builder.installOn(inst);
            System.out.println("[HyAuth] 已成功挂载 Mojang Authlib 验证切面。");
        } catch (Throwable t) {
            System.err.println("[HyAuth] 切面挂载失败，Agent 未能生效: " + t);
            t.printStackTrace();
        }
    }

    private static void banner() {
        System.out.println("==================================================");
        System.out.println("  HyAuth-Agent 内存鉴权分流代理已启动");
        System.out.println("  版本: " + AgentMain.class.getPackage().getImplementationVersion());
        System.out.println("  Java: " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")");
        System.out.println("==================================================");
    }
}
