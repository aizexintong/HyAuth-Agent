package com.hyauth.agent.config;

import java.util.UUID;

/**
 * 离线名单条目：管理员手动指定的「玩家名 + UUID」。
 *
 * <p>命中该名单的玩家不经过任何外部验证服务器，也不回退 Mojang 官方校验，
 * 直接以管理员指定的 UUID / 名称进入服务器，从而：
 * <ul>
 *     <li>与正版同名账号的数据彻底隔离（UUID 由管理员固定，而不是由名字推导）；</li>
 *     <li>避免"离线 UUID = 名字哈希"被他人推算后冒用同一份存档数据。</li>
 * </ul>
 */
public final class OfflinePlayer {

    private final String name;
    private final UUID uuid;

    public OfflinePlayer(String name, UUID uuid) {
        this.name = name;
        this.uuid = uuid;
    }

    /** 管理员书写的名字（大小写以此为准，登录后会统一成这个写法）。 */
    public String getName() {
        return name;
    }

    public UUID getUuid() {
        return uuid;
    }

    @Override
    public String toString() {
        return name + " -> " + uuid;
    }
}
