package net.minecraft.world.level.levelgen;

/**
 * 替身：高度图类型。
 *
 * <p>勘探功能用 {@code Level#getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)} 算传送落点，
 * 所以替身里必须有同名枚举常量。
 */
public class Heightmap {

    public enum Types {
        WORLD_SURFACE,
        MOTION_BLOCKING
    }
}
