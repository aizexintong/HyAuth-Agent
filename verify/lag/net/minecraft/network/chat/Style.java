package net.minecraft.network.chat;

import net.minecraft.ChatFormatting;

/** 替身：样式（承载颜色 / 点击事件 / 悬停事件）。 */
public class Style {

    public static final Style EMPTY = new Style();

    private String colorName;
    private String clickCommand;
    private String hoverText;

    public String colorName() {
        return colorName;
    }

    public String clickCommand() {
        return clickCommand;
    }

    public String hoverText() {
        return hoverText;
    }

    public Style withColor(ChatFormatting color) {
        Style copy = copy();
        copy.colorName = String.valueOf(color);
        return copy;
    }

    public Style withClickEvent(ClickEvent event) {
        Style copy = copy();
        copy.clickCommand = event.command();
        return copy;
    }

    public Style withHoverEvent(HoverEvent event) {
        Style copy = copy();
        copy.hoverText = event.text() == null ? null : event.text().toString();
        return copy;
    }

    private Style copy() {
        Style copy = new Style();
        copy.colorName = colorName;
        copy.clickCommand = clickCommand;
        copy.hoverText = hoverText;
        return copy;
    }
}
