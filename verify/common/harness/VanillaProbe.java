package harness;

/** 记录“原版实现是否真的被执行”，用于验证白名单外的玩家确实被放行。 */
public final class VanillaProbe {
    public static volatile boolean vanillaCalled = false;
    public static volatile String lastUsername = null;

    private VanillaProbe() {
    }
}
