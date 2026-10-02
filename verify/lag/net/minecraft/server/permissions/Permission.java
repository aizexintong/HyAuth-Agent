package net.minecraft.server.permissions;

/**
 * 替身：一条权限（26.x）。
 *
 * <p>真实实现里有 {@code Permission.Atom} / {@code Permission.HasCommandLevel} 等多种形态；
 * 替身只需要能区分"命令等级"这一个维度，所以带一个 {@code commandLevel} 字段。
 */
public interface Permission {
}
