package net.minecraft.network.chat;

/**
 * 替身：文本组件。
 *
 * <p>真实 1.21.5+ 里 {@code literal} 返回 {@code MutableComponent}、{@code withStyle(Style)} 返回自身；
 * 这里用一个类同时扮演两者，并把 {@code Style} 上的点击/悬停信息<b>抄到自己身上</b>，
 * 让测试能直接断言"这一行点了会执行什么命令"。
 */
public class Component {

    private final String text;

    /** 抄自 Style：点击执行的命令。 */
    public String clickCommand;

    /** 抄自 Style：悬停提示文本。 */
    public String hoverText;

    /** 抄自 Style：颜色名。 */
    public String color;

    private Component(String text) {
        this.text = text;
    }

    public static Component literal(String text) {
        return new Component(text);
    }

    public Component withStyle(Style style) {
        this.clickCommand = style.clickCommand();
        this.hoverText = style.hoverText();
        this.color = style.colorName();
        return this;
    }

    @Override
    public String toString() {
        return text;
    }
}
