package com.hyauth.agent;

import com.hyauth.agent.util.ExternalChatBroadcast;
import net.bytebuddy.asm.Advice;

/**
 * {@code ServerGamePacketListenerImpl#sendPlayerChatMessage(PlayerChatMessage, ChatType.Bound)} 切面。
 *
 * <p>这是原版<b>每个接收者各调用一次</b>的聊天发送方法（调用链：
 * {@code PlayerList#broadcastChatMessage} → {@code ServerPlayer#sendChatMessage}
 * → {@code OutgoingChatMessage.Player#sendToPlayer} → 本方法），所以能精确知道"这条消息发给谁"，
 * 从而做到：
 * <ul>
 *     <li><b>发给作者本人</b>：原样放行 —— 保持原版「带签名」消息，作者自己就能看到自己发的话
 *         （上一版把消息统一改成未签名，导致作者自己的客户端用链式校验器验不过，
 *         红字「聊天验证错误」且消息不显示）；</li>
 *     <li><b>发给其他人</b>：改用原版自带的「伪装聊天」{@code sendDisguisedChatMessage}
 *         直接送达（不参与签名账本）—— 客户端不再走签名校验，谁都不会弹「聊天验证错误」，
 *         也不会出现"服务端算了签名、客户端没算"导致的 {@code Checksum mismatch} 踢人。</li>
 * </ul>
 *
 * <p>完整机制与真实字节码依据见 {@link ExternalChatBroadcast}。只影响外置名单玩家
 * （配置 {@code unsigned_external_chat}，默认开启）；正版玩家的签名聊天完全不动。
 *
 * <p>注意：切面参数用 {@code Object} 承载（切面类字节码里不能出现服务端类型，见
 * {@code AuthRejection} 的类注释），返回值 {@code true} = 已经自己发过了，
 * 由 {@code skipOn = OnNonDefaultValue} 让 Byte Buddy 跳过原方法。
 */
public class ExternalChatAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
            @Advice.This Object connection,
            @Advice.Argument(0) Object message,
            @Advice.Argument(1) Object chatType) {

        return ExternalChatBroadcast.sendAsDisguised(connection, message, chatType);
    }
}
