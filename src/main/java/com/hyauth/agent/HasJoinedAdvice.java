package com.hyauth.agent;

import com.hyauth.agent.config.ListManager;
import com.hyauth.agent.config.OfflinePlayer;
import com.hyauth.agent.util.AuthRejection;
import com.hyauth.agent.util.AuthlibProfileFactory;
import com.hyauth.agent.util.VerifiedProfile;
import com.hyauth.agent.util.YggdrasilAuthUtil;
import net.bytebuddy.asm.Advice;

import java.net.InetAddress;

/**
 * {@code YggdrasilMinecraftSessionService#hasJoinedServer} 方法切面。
 *
 * <p>分流规则（优先级从高到低）：
 * <ol>
 *     <li><b>离线名单（offline_players）</b>：管理员手动指定了 UUID 的玩家，
 *         完全跳过外部校验，直接以指定 UUID / 名字进入（配合 {@link HelloPacketAdvice}
 *         让客户端跳过会话上报，纯离线客户端也能进）；</li>
 *     <li><b>LittleSkin 名单</b>：向 LittleSkin 发起 hasJoined 校验并验签，
 *         成功后用外置 Profile 替换原版返回值；</li>
 *     <li><b>其它玩家</b>：进入切面后立即返回 {@code null}（不跳过原方法），
 *         原版 Mojang 校验逻辑照常执行，正版玩家零额外开销。</li>
 * </ol>
 *
 * <p>名单内玩家一旦校验失败，一律以 {@code AuthenticationUnavailableException} 中断登录，
 * <b>绝不降级回退到 Mojang 官方校验</b>，避免同名正版账号被冒用。
 *
 * <p>兼容性说明：Authlib 6.x 起 {@code hasJoinedServer} 的第一个参数由 {@code GameProfile}
 * 变为 {@code String username}，返回值由 {@code GameProfile} 变为
 * {@code com.mojang.authlib.yggdrasil.ProfileResult}。因此切面统一用 {@code Object}
 * 接收参数与返回值，并在 {@link AuthlibProfileFactory} 中按运行期实际形态构造结果。
 *
 * <p><b>⚠️ 改动本类的硬性约束：本类的字节码里不允许出现任何 authlib 类型</b>
 * （包括签名、{@code new}、{@code instanceof}、{@code X.class}）。
 * 原因见 {@link AuthRejection} 的类注释：切面类由 system 加载器加载，
 * Bundler 环境下它看不见 authlib，字节码一旦直接引用就会在挂载阶段
 * {@code NoClassDefFoundError}，导致 Agent 静默失效。需要 authlib 类型时请走
 * {@link AuthRejection} 这类「纯 JDK 反射中转」，或只<b>调用</b>别的类（调用是安全的）。
 */
public class HasJoinedAdvice {

    /**
     * 方法进入切面。
     *
     * @param profileOrName 第一个参数：Authlib ≤ 5.x 为 GameProfile，≥ 6.x 为 String 玩家名
     * @param serverId      服务端下发的 serverId
     * @param address       客户端 IP（仅当服务端开启 prevent-proxy-connections 时非空）
     * @return {@code null} 表示不跳过原方法（继续原版 Mojang 校验）；
     *         非 null 表示跳过原方法，并将该对象作为方法返回值
     */
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static Object onEnter(
            @Advice.Argument(0) Object profileOrName,
            @Advice.Argument(1) String serverId,
            @Advice.Argument(2) InetAddress address) {

        final String username = resolveUsername(profileOrName);

        // 参数形态无法识别时不做任何干预，交由原版逻辑处理
        if (username == null || username.isEmpty()) {
            return null;
        }

        // 步骤 1：离线名单（优先级最高）—— 管理员手动指定 UUID，完全跳过外部校验
        OfflinePlayer offline = ListManager.getOfflinePlayer(username);
        if (offline != null) {
            System.out.println("[HyAuth] 匹配到离线名单玩家: " + username
                    + "（管理员指定 UUID: " + offline.getUuid() + "），跳过全部外部鉴权。");
            return allowOfflinePlayer(profileOrName, username, offline);
        }

        // 步骤 2：非 LittleSkin 名单玩家直接放行，不产生任何网络请求
        if (!ListManager.isLittleSkinPlayer(username)) {
            return null;
        }

        // 步骤 3：名单内玩家 → 走 LittleSkin 外置鉴权通道
        System.out.println("[HyAuth] 匹配到 LittleSkin 白名单玩家: " + username + "，发起外置鉴权...");

        VerifiedProfile profile = null;
        try {
            profile = YggdrasilAuthUtil.verifyLittleSkin(username, serverId, address);
        } catch (Throwable t) {
            System.err.println("[HyAuth] LittleSkin 鉴权请求异常: " + t);
        }

        if (profile != null) {
            Object result = null;
            try {
                result = AuthlibProfileFactory.createResult(profileOrName, profile);
            } catch (Throwable t) {
                System.err.println("[HyAuth] 构造外置 Profile 返回值失败: " + t);
            }
            if (result != null) {
                System.out.println("[HyAuth] 玩家 " + username + " 通过 LittleSkin 鉴权成功！资料属性: "
                        + YggdrasilAuthUtil.describeTextures(profile));
                return result;
            }
            System.err.println("[HyAuth] 当前服务端 Authlib 版本无法构造鉴权结果，按失败处理。");
        } else {
            System.err.println("[HyAuth] 玩家 " + username + " 在名单中，但 LittleSkin 鉴权失败/未登录！");
        }

        // 步骤 3：失败关闭（fail-closed）——中断登录，杜绝降级回退 Mojang
        // 注意：不能写成 throw new AuthenticationUnavailableException(...)，
        // 本类字节码里不允许出现 authlib 类型（见类注释与 AuthRejection）。
        AuthRejection.reject(
                "HyAuth: 玩家 " + username + " 未通过 LittleSkin 外置鉴权，已拒绝其进入服务器。");
        return null; // 不可达，仅为通过编译
    }

    /**
     * 方法退出切面：把外置鉴权结果回写到原方法返回值上。
     *
     * <p>{@code enterResult} 为 {@code null} 时说明未拦截，原版返回值保持不变。
     */
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.Enter Object enterResult,
            @Advice.Return(readOnly = false) Object result) {

        if (enterResult != null) {
            result = enterResult;
        }
    }

    /**
     * 离线名单放行：以管理员指定的 UUID / 名字构造返回值。
     * <p>失败关闭：无法构造时以 {@code AuthenticationUnavailableException} 中断登录，绝不放行。
     * <p>注意：该方法会被 Byte Buddy 内联进目标类，因此必须是 public static；
     * 且字节码里不能出现 authlib 类型（见类注释）。
     */
    public static Object allowOfflinePlayer(Object profileOrName, String username, OfflinePlayer offline) {
        try {
            VerifiedProfile pinned = new VerifiedProfile(offline.getUuid(), offline.getName(), null);
            Object result = AuthlibProfileFactory.createResult(profileOrName, pinned);
            if (result != null) {
                System.out.println("[HyAuth] 玩家 " + offline.getName() + " 已按离线名单放行（UUID: "
                        + offline.getUuid() + "）。");
                return result;
            }
        } catch (Throwable t) {
            System.err.println("[HyAuth] 构造离线名单返回值失败: " + t);
        }
        AuthRejection.reject(
                "HyAuth: 无法为离线名单玩家 " + username + " 构造鉴权结果，已拒绝其进入服务器。");
        return null; // 不可达，仅为通过编译
    }

    /**
     * 从第一个参数中解析玩家名，兼容 Authlib 的两种方法签名。
     *
     * <p>该方法会被 Byte Buddy 内联进目标类，因此必须是 public static。
     * 全程鸭子类型（反射），不做任何 authlib 类型判断，原因见类注释：
     * {@code instanceof GameProfile} 这样的字节码引用在 Bundler 环境下会让切面挂载直接失败。
     */
    public static String resolveUsername(Object profileOrName) {
        if (profileOrName == null) {
            return null;
        }
        if (profileOrName instanceof String) {
            return (String) profileOrName;
        }
        // Authlib ≤ 9.x：GameProfile#getName()；Authlib 10.x：GameProfile 变成 record，访问器为 name()
        String name = invokeString(profileOrName, "getName");
        if (name == null) {
            name = invokeString(profileOrName, "name");
        }
        return name;
    }

    private static String invokeString(Object target, String method) {
        try {
            return (String) target.getClass().getMethod(method).invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
