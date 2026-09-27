package loaderiso;

/** 测试替身：模拟 {@code ServerPlayer#getGameProfile().name()} 这一条取名链路。 */
public class IsoPlayer {

    private final String name;

    public IsoPlayer(String name) {
        this.name = name;
    }

    public IsoGameProfile getGameProfile() {
        return new IsoGameProfile(name);
    }

    /** 模拟 authlib 10.x 的 record 访问器。 */
    public static class IsoGameProfile {
        private final String name;

        IsoGameProfile(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }
    }
}
