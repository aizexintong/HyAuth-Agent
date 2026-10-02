package com.hyauth.agent.util;

/**
 * 以 {@code long} 为键的极简开放寻址哈希表（只为采样热路径服务）。
 *
 * <p><b>为什么不用 {@code HashMap<Long, Stats>}</b>：区块 tick / 实体 tick 是每秒上万次的
 * 热路径，{@code HashMap} 每次 {@code get/put} 都要把 {@code long} 装箱成 {@code Long}
 * （年轻代小对象洪流），而勘探功能本身要求"几乎零开销"。
 * 本表用两个平行数组 + 线性探测，全程无装箱、无迭代器。
 *
 * <p><b>线程模型</b>：只在服务端主线程上写（区块/实体 tick 与命令执行都在主线程），
 * 因此刻意不加锁；跨线程只读的用法请先 copy 快照（见 {@code ChunkLagSampler.snapshot}）。
 */
final class LongKeyMap<V> {

    /** 遍历回调。 */
    interface Visitor<V> {
        void visit(long key, V value);
    }

    private long[] keys;
    private Object[] values;
    private int size;
    private int mask;
    private int resizeAt;

    LongKeyMap(int initialCapacity) {
        int capacity = 16;
        while (capacity < initialCapacity) {
            capacity <<= 1;
        }
        keys = new long[capacity];
        values = new Object[capacity];
        mask = capacity - 1;
        resizeAt = (int) (capacity * 0.6f);
    }

    @SuppressWarnings("unchecked")
    V get(long key) {
        int slot = slot(key);
        return (V) values[slot];
    }

    void put(long key, V value) {
        int slot = slot(key);
        if (values[slot] == null) {
            keys[slot] = key;
            size++;
            if (size > resizeAt) {
                grow();
            }
        }
        values[slot] = value;
    }

    int size() {
        return size;
    }

    void forEach(Visitor<V> visitor) {
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            if (value != null) {
                @SuppressWarnings("unchecked")
                V typed = (V) value;
                visitor.visit(keys[i], typed);
            }
        }
    }

    private int slot(long key) {
        // 混合高低位，避免相邻区块（低位连续）扎堆
        long mixed = key * 0x9E3779B97F4A7C15L;
        int index = (int) (mixed ^ (mixed >>> 32)) & mask;
        while (true) {
            Object value = values[index];
            if (value == null || keys[index] == key) {
                return index;
            }
            index = (index + 1) & mask;
        }
    }

    private void grow() {
        long[] oldKeys = keys;
        Object[] oldValues = values;
        int capacity = oldValues.length << 1;
        if (capacity <= 0) {
            return; // 理论不可达：达到 int 上限就放弃扩容
        }
        keys = new long[capacity];
        values = new Object[capacity];
        mask = capacity - 1;
        resizeAt = (int) (capacity * 0.6f);
        size = 0;
        for (int i = 0; i < oldValues.length; i++) {
            Object value = oldValues[i];
            if (value != null) {
                put(oldKeys[i], cast(value));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private V cast(Object value) {
        return (V) value;
    }
}
