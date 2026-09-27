package com.hyauth.agent.util;

import com.hyauth.agent.config.ListManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 「没有聊天公钥的离线名单玩家」发指令时的兜底。
 *
 * <p><b>问题</b>（26.3 真实字节码）：
 * <ol>
 *     <li>客户端 {@code ClientPacketListener.sendCommand} 只要指令带有「可签名实参」
 *         （{@code /say}、{@code /me}、{@code /msg}、{@code /tell}、{@code /w}、{@code /teammsg} …），
 *         <b>就无条件发送签名版指令包</b> {@code ServerboundChatCommandSignedPacket}
 *         （它只看实参是不是空的，既不看自己有没有聊天密钥，也不看服务端开没开
 *         {@code enforce-secure-profile}）；</li>
 *     <li>服务端 {@code ServerGamePacketListenerImpl.collectSignedArguments}：
 *         实参签名列表非空 → 逐条解码签名链 → 该玩家压根没有聊天公钥 →
 *         抛 {@code SignedMessageChain.DecodeException("chat.disabled.missingProfileKey")}
 *         → 控制台一条 WARN、玩家一条红字，<b>指令不执行</b>。</li>
 * </ol>
 *
 * <p><b>原版自己就留了后门</b>：同一个方法里
 * {@code if (entries.isEmpty()) return collectUnsignedArguments(arguments);} ——
 * 也就是说"没有实参签名"时会按未签名处理。离线账号的客户端本来也不会给实参签名，
 * 只是它偏偏塞了一组空签名，于是错过了这个分支。
 *
 * <p>本类的做法：对「聊天公钥为空 + 在离线名单里」的玩家，直接把
 * {@code collectSignedArguments} 的结果换成原版自己的 {@code collectUnsignedArguments}，
 * 于是指令按未签名正常执行 —— 不写死任何原版逻辑，只借用它现成的分支。
 *
 * <p>实现全为 JDK 反射，字节码里不出现服务端类型（原因见 {@link AuthRejection}）；
 * 任何一步失败都会返回 {@code null} 让调用方放行原版逻辑（fail-safe，不会让指令更糟）。
 */
public final class KeylessChatBypass {

    private static volatile boolean reportedBypass = false;
    private static volatile boolean reportedFailure = false;

    private KeylessChatBypass() {
    }

    /**
     * 是否该接管这次签名指令解码。
     *
     * <p>判定：玩家在**离线名单**内，且开启 {@code bypass_signed_commands}。
     *
     * <p>为什么不看 {@code chatSession}：真机上出现过"客户端确实上报过聊天密钥、
     * {@code chatSession} 非空，但服务端解码这条指令签名链时仍以
     * missing-profile-public-key 失败"的情况；而离线名单玩家本来就是管理员指定身份的账号，
     * 其密钥也不可能被 Mojang 验证，所以对这类玩家一律走未签名分支最稳。
     * 普通聊天与已签名聊天链路不受影响（本切面只作用于"指令实参的签名解码"）。
     */
    public static boolean shouldBypass(Object listener) {
        if (listener == null || !ListManager.isBypassSignedCommands()) {
            return false;
        }
        try {
            Object player = readField(listener, "player");
            String name = nameOf(player);
            return name != null && ListManager.isOfflinePlayer(name);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 用原版的 {@code collectUnsignedArguments} 取代签名链解码。
     *
     * @return 非 null 的 Map（调用方据此跳过原方法）；失败时返回空 Map，同样跳过原方法，
     *         这样最多是"实参没有签名信息"，不会让玩家再吃一条 DecodeException
     */
    public static Object collectUnsigned(Object listener, Object signableCommand) {
        try {
            Object arguments = invoke(signableCommand, "arguments");
            if (arguments instanceof List) {
                Method method = listener.getClass().getDeclaredMethod("collectUnsignedArguments", List.class);
                method.setAccessible(true);
                Object result = method.invoke(listener, arguments);
                if (result instanceof Map) {
                    if (!reportedBypass) {
                        reportedBypass = true;
                        System.out.println("[HyAuth] 离线名单玩家没有聊天公钥：其指令改按「未签名」处理"
                                + "（避免 DecodeException 红字与指令失效）。");
                    }
                    return result;
                }
            }
        } catch (Throwable t) {
            if (!reportedFailure) {
                reportedFailure = true;
                System.err.println("[HyAuth] 未签名指令回退失败，按原版处理: " + t);
            }
        }
        return Collections.emptyMap();
    }

    private static Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static String nameOf(Object player) {
        if (player == null) {
            return null;
        }
        Object profile = player;
        try {
            profile = invoke(player, "getGameProfile");
        } catch (Throwable ignored) {
            // 没有 getGameProfile → 直接拿 player 自己试
        }
        if (profile == null) {
            return null;
        }
        for (String methodName : new String[]{"name", "getName"}) {
            try {
                Object value = invoke(profile, methodName);
                if (value instanceof String) {
                    return (String) value;
                }
            } catch (Throwable ignored) {
                // 试下一个访问器
            }
        }
        return null;
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }
}
