package net.minecraft.network.chat;

/**
 * 替身：点击事件。
 *
 * <p>形状取 <b>1.21.5+</b>：{@code ClickEvent} 是接口，具体动作是 record
 * （{@code ClickEvent.RunCommand(String)}）。勘探报告的可点击传送就靠它，
 * 所以这里必须与新版形状一致，才能真正验证"点一下执行 /hy lag tp N"。
 */
public interface ClickEvent {

    String command();

    /** 1.21.5+ 形状：点一下执行一条命令。 */
    record RunCommand(String command) implements ClickEvent {
    }
}
