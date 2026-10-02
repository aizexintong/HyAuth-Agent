package net.minecraft.network.chat;

/** 替身：悬停事件（1.21.5+ 的 {@code HoverEvent.ShowText(Component)} 形状）。 */
public interface HoverEvent {

    Component text();

    record ShowText(Component text) implements HoverEvent {
    }
}
