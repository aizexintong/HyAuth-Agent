package net.minecraft.commands;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 替身：命令来源。
 *
 * <p>只实现勘探/管理层真正用到的那几个方法：
 * {@code hasPermission(int)} 做权限门槛、{@code getEntity()/getLevel()} 供"脚下这块"与传送落点、
 * {@code sendSuccess(Supplier,boolean)} 收集"发给管理员的那一份"（文本 + 点击命令 + 悬停文本）。
 */
public class CommandSourceStack {

    /** 收到过的文本（模拟聊天/控制台输出）。 */
    public static final List<String> messages = new ArrayList<String>();

    /** 收到过的「点击执行的命令」。 */
    public static final List<String> clicks = new ArrayList<String>();

    /** 收到过的悬停文本。 */
    public static final List<String> hovers = new ArrayList<String>();

    private final int permission;
    private final Object entity;
    private final ServerLevel level;

    /** 替身里由测试注入的"服务端"（真实 CommandSourceStack 自己持有它）。 */
    public static net.minecraft.server.MinecraftServer server;

    public CommandSourceStack(int permission, Object entity, ServerLevel level) {
        this.permission = permission;
        this.entity = entity;
        this.level = level;
    }

    public Object getServer() {
        return server;
    }

    /** 命令来源的显示名（审计/回显用）。 */
    public String getTextName() {
        return entity == null ? "Server" : "Player";
    }

    /**
     * 26.3 的真实权限入口：{@code permissions()} 返回 {@code PermissionSet}，
     * 用 {@code Permissions.COMMANDS_*} 判等级。
     *
     * <p>注意替身里<b>故意不提供</b> {@code hasPermission(int)} —— 26.3 已经删掉它了，
     * 留着会让"只走旧路径"的实现照样自检通过（v1.0.6 就是这么在真机上翻车的）。
     */
    public net.minecraft.server.permissions.PermissionSet permissions() {
        final int level = permission;
        return new net.minecraft.server.permissions.PermissionSet() {
            @Override
            public boolean hasPermission(net.minecraft.server.permissions.Permission permission) {
                if (permission instanceof net.minecraft.server.permissions.Permissions.Level) {
                    return level >= ((net.minecraft.server.permissions.Permissions.Level) permission).commandLevel;
                }
                return false;
            }
        };
    }

    public Object getEntity() {
        return entity;
    }

    public ServerLevel getLevel() {
        return level;
    }

    public void sendSuccess(Supplier<Component> messageSupplier, boolean allowLogging) {
        record(messageSupplier == null ? null : messageSupplier.get());
    }

    public void sendSystemMessage(Component message) {
        record(message);
    }

    private static void record(Component component) {
        if (component == null) {
            return;
        }
        messages.add(component.toString());
        if (component.clickCommand != null) {
            clicks.add(component.clickCommand);
        }
        if (component.hoverText != null) {
            hovers.add(component.hoverText);
        }
    }

    public static void reset() {
        messages.clear();
        clicks.clear();
        hovers.clear();
    }

    /** 是否有某条输出<b>包含</b>给定片段。 */
    public static boolean said(String needle) {
        for (String message : messages) {
            if (message.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** 是否有某条输出匹配给定正则。 */
    public static boolean saidMatches(String regex) {
        for (String message : messages) {
            if (message.matches(regex)) {
                return true;
            }
        }
        return false;
    }

    public static boolean clicked(String exact) {
        return clicks.contains(exact);
    }

    /** 调试用：最后几条输出。 */
    public static String tail(int count) {
        StringBuilder builder = new StringBuilder();
        int from = Math.max(0, messages.size() - count);
        for (int i = from; i < messages.size(); i++) {
            builder.append("\n    | ").append(messages.get(i));
        }
        return builder.toString();
    }
}
