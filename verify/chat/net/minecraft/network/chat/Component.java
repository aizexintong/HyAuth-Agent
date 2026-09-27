package net.minecraft.network.chat;

/** 测试替身：聊天内容（原版是 Component 接口，这里只要能承载字符串即可）。 */
public class Component {

    private final String text;

    public Component(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }

    public String getString() {
        return text;
    }

    @Override
    public String toString() {
        return text;
    }
}
