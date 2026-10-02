package net.minecraft.server.permissions;

/**
 * 替身：权限集合（26.x 的新权限模型）。
 *
 * <p>26.3 实测 {@code CommandSourceStack} 已经删掉 {@code hasPermission(int)}，
 * 改为 {@code permissions()} 返回本接口，再用 {@code hasPermission(Permission)} 判权限。
 */
public interface PermissionSet {

    boolean hasPermission(Permission permission);
}
