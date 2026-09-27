package loaderiso;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试替身：模拟 {@code ServerGamePacketListenerImpl} 中与本功能相关的形状。
 *
 * <ul>
 *     <li>{@code chatSession} 字段：null 表示玩家没有聊天公钥；</li>
 *     <li>{@code player} 字段 → {@code getGameProfile().name()}：玩家名；</li>
 *     <li>{@code collectSignedArguments(...)}：原版在没有公钥时抛 DecodeException；</li>
 *     <li>{@code collectUnsignedArguments(List)}：原版自带的「未签名」分支（private）。</li>
 * </ul>
 */
public class IsoChatListener {

    private final Object chatSession;
    private final IsoPlayer player;

    public IsoChatListener(String playerName) {
        this(playerName, false);
    }

    public IsoChatListener(String playerName, boolean hasChatSession) {
        this.player = new IsoPlayer(playerName);
        this.chatSession = hasChatSession ? new Object() : null;
    }

    /** 原版行为：没有聊天公钥 → 解码签名链失败。 */
    public Object collectSignedArguments(Object packet, Object signableCommand, Object lastSeen) throws Exception {
        throw new Exception("SignedMessageChain$DecodeException: chat.disabled.missingProfileKey");
    }

    /** 原版自带的未签名分支。 */
    private Map<String, Object> collectUnsignedArguments(List<?> arguments) {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("UNSIGNED", Boolean.TRUE);
        result.put("argCount", arguments == null ? 0 : arguments.size());
        return result;
    }
}
