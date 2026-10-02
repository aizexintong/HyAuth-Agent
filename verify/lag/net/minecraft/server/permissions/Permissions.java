package net.minecraft.server.permissions;

/**
 * 替身：命令等级权限常量（26.x）。
 *
 * <p>真实类里还有聊天类权限等；替身只保留命令等级四个，对应原版等级
 * 1 moderator / 2 gamemaster / 3 admin / 4 owner —— 与 {@code AdminCommands} 的反推一致。
 */
public final class Permissions {

    public static final Permission COMMANDS_MODERATOR = new Level(1);
    public static final Permission COMMANDS_GAMEMASTER = new Level(2);
    public static final Permission COMMANDS_ADMIN = new Level(3);
    public static final Permission COMMANDS_OWNER = new Level(4);

    private Permissions() {
    }

    /** 带等级的实现，供替身里的权限集合比较。 */
    public static final class Level implements Permission {
        public final int commandLevel;

        Level(int commandLevel) {
            this.commandLevel = commandLevel;
        }
    }
}
