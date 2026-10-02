package com.hyauth.agent.util;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * UUIDv7 生成器（RFC 9562 §5.7）。
 *
 * <p><b>为什么要 v7</b>：给离线名单玩家"发身份证"时，v4 纯随机、数据库/日志里毫无顺序；
 * v7 把 48 位毫秒时间戳放在最高位，因此<b>按 UUID 排序 ≈ 按创建时间排序</b>，
 * 排查"谁是什么时候被加进来的"一目了然，同时仍然保留 74 位随机性，不会撞车。
 *
 * <p><b>布局</b>：
 * <pre>
 *  0                   48        52        64
 *  +-------------------+---------+---------+
 *  | unix_ts_ms (48)   | ver=7(4)| rand_a  |          高 64 位
 *  +-------------------+---------+---------+
 *  | var=10(2) | rand_b (62)                   |     低 64 位
 *  +-------------------------------------------+
 * </pre>
 *
 * <p>{@code rand_a} 用作<b>同毫秒内的单调计数器</b>：同一毫秒里连续生成也严格递增
 * （计数器溢出则把时间戳进位一毫秒），因此本生成器产出的 UUID 全局单调递增，
 * 非常适合"快速连续加好几个玩家"的命令场景。
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** rand_a 的位宽（12 位）。 */
    private static final int RAND_A_BITS = 12;
    private static final int RAND_A_MASK = (1 << RAND_A_BITS) - 1;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private static long lastMillis = -1L;
    private static int counter = 0;

    private UuidV7() {
    }

    /** 生成一个单调递增的 UUIDv7。 */
    public static synchronized UUID generate() {
        long millis = System.currentTimeMillis();
        if (millis > lastMillis) {
            lastMillis = millis;
            counter = RANDOM.nextInt(RAND_A_MASK + 1);
        } else {
            millis = lastMillis;
            counter++;
            if (counter > RAND_A_MASK) {
                // 同一毫秒内超过 4096 个：时间戳进位，计数器重置（仍保持单调）
                counter = 0;
                lastMillis++;
                millis = lastMillis;
            }
        }
        long most = ((millis & 0xFFFFFFFFFFFFL) << 16) | (0x7L << RAND_A_BITS) | (counter & RAND_A_MASK);
        long least = RANDOM.nextLong();
        least = (least & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(most, least);
    }

    /** 版本号（v7 应为 7）。 */
    public static int version(UUID uuid) {
        return (int) ((uuid.getMostSignificantBits() >>> 12) & 0xFL);
    }

    /** 变体位（RFC 变体应为 2，即二进制 10）。 */
    public static int variant(UUID uuid) {
        return (int) ((uuid.getLeastSignificantBits() >>> 62) & 0x3L);
    }

    /** 从 UUIDv7 取回毫秒时间戳。 */
    public static long timestamp(UUID uuid) {
        return (uuid.getMostSignificantBits() >>> 16) & 0xFFFFFFFFFFFFL;
    }

    /** 人类可读的生成时间。 */
    public static String describeTime(UUID uuid) {
        if (version(uuid) != 7) {
            return "非 UUIDv7（无法从 UUID 读出时间）";
        }
        return TIME_FORMAT.format(Instant.ofEpochMilli(timestamp(uuid)).atZone(ZoneId.systemDefault()));
    }

    /** 一行式校验描述，用于命令回显。 */
    public static String describe(UUID uuid) {
        return uuid + "（UUIDv" + version(uuid) + "，生成时间 " + describeTime(uuid) + "）";
    }

    /** 兼容 32 位无连字符与标准带连字符两种写法；非法返回 {@code null}。 */
    public static UUID parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            if (value.length() == 32 && value.indexOf('-') < 0) {
                value = value.substring(0, 8) + "-" + value.substring(8, 12) + "-" + value.substring(12, 16)
                        + "-" + value.substring(16, 20) + "-" + value.substring(20);
            }
            return UUID.fromString(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** 原版离线模式使用的 UUID 算法（{@code OfflinePlayer:<名字>} 的 MD5 版 UUID）。 */
    public static UUID vanillaOfflineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
