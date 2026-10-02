package net.minecraft.server.players;

/** 替身：玩家列表（op 名单从这里取；真实类里还有 isOp(NameAndId) 等方法）。 */
public class PlayerList {
    private final ServerOpList ops = new ServerOpList();

    public ServerOpList getOps() {
        return ops;
    }

    public boolean isOp(Object key) {
        return ops.get(key) != null;
    }
}
