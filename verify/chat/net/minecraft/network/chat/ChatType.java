package net.minecraft.network.chat;

/** 测试替身：{@code ChatType} 与其内部类 {@code Bound}（聊天类型绑定）。 */
public class ChatType {

    public static class Bound {

        private final String name;

        public Bound(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        @Override
        public String toString() {
            return "Bound[" + name + "]";
        }
    }
}
