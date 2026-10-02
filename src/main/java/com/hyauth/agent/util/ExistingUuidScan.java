package com.hyauth.agent.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hyauth.agent.config.ListManager;
import com.hyauth.agent.config.OfflinePlayer;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 「发身份证之前先查户口」：扫描服务端上<b>已经存在</b>的 UUID 与这个名字的历史身份。
 *
 * <p>命令 {@code /hy off <名字>} 会给玩家自动发一个新 UUID（UUIDv7）。
 * 但一个名字在服务端上可能<b>早就有了身份</b>：
 * <ul>
 *     <li>配置文件里已有的 {@code offline_players} 条目；</li>
 *     <li>根目录 {@code usercache.json}（原版记录"名字 → UUID"的地方）；</li>
 *     <li>存档 {@code <level-name>/playerdata|stats|advancements/<uuid>.…}（真玩过的痕迹）；</li>
 *     <li>原版离线模式算法 {@code UUID.nameUUIDFromBytes("OfflinePlayer:"+名字)} —— 服务端以前
 *         用离线模式开过服的话，这个玩家的背包/成就全挂在这个 UUID 下。</li>
 * </ul>
 *
 * <p>所以发新 UUID 前必须先扫一遍：<b>名字已经有 UUID 时要拦下来</b>
 * （换 UUID = 换人，老存档数据会变成"另一个人的"），让管理员显式决定是沿用旧 UUID 还是强制新建。
 * 新生成的 UUIDv7 也要与扫描到的全部已知 UUID 比对，确认不重复。
 */
public final class ExistingUuidScan {

    private ExistingUuidScan() {
    }

    /** 扫描结果。 */
    public static final class Report {

        private UUID existingUuid;
        private String existingSource;
        private UUID vanillaOfflineUuid;
        private boolean vanillaOfflineDataExists;
        private final Set<UUID> known = new LinkedHashSet<UUID>();
        private final List<String> notes = new ArrayList<String>();

        /** 该名字<b>已经存在</b>的 UUID；没有则返回 {@code null}。 */
        public UUID getExistingUuid() {
            return existingUuid;
        }

        /** 已有 UUID 的来源描述（usercache.json / 配置文件 …）。 */
        public String getExistingSource() {
            return existingSource;
        }

        /** 原版离线模式会算出的 UUID。 */
        public UUID getVanillaOfflineUuid() {
            return vanillaOfflineUuid;
        }

        /** 原版离线 UUID 名下是否已经有玩家数据（背包等）。 */
        public boolean hasVanillaOfflineData() {
            return vanillaOfflineDataExists;
        }

        /** 已知 UUID 是否已存在（用于新 UUID 去重校验）。 */
        public boolean isKnown(UUID uuid) {
            return uuid != null && known.contains(uuid);
        }

        public int knownCount() {
            return known.size();
        }

        public List<String> getNotes() {
            return Collections.unmodifiableList(notes);
        }

        private void observe(UUID uuid, String source) {
            if (uuid == null) {
                return;
            }
            known.add(uuid);
            if (existingUuid == null) {
                existingUuid = uuid;
                existingSource = source;
            }
        }
    }

    /** 扫描某个玩家名在服务端上的历史身份与全部已知 UUID。 */
    public static Report scan(String playerName) {
        Report report = new Report();
        String name = playerName == null ? "" : playerName.trim();
        if (name.isEmpty()) {
            return report;
        }

        // ① 配置文件里已有的离线名单条目（这是"我们上一版发过的身份证"）
        OfflinePlayer configured = ListManager.getOfflinePlayer(name);
        if (configured != null) {
            report.observe(configured.getUuid(), "配置文件 offline_players（本 Agent 之前发过的）");
        }

        // ② usercache.json：原版的"名字 → UUID"映射
        File usercache = new File("usercache.json");
        if (usercache.isFile()) {
            try (Reader reader = new InputStreamReader(new FileInputStream(usercache), StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (root != null && root.isJsonArray()) {
                    for (JsonElement element : root.getAsJsonArray()) {
                        if (element == null || !element.isJsonObject()) {
                            continue;
                        }
                        JsonObject entry = element.getAsJsonObject();
                        String entryName = string(entry, "name");
                        UUID uuid = UuidV7.parse(string(entry, "uuid"));
                        if (uuid == null) {
                            uuid = UuidV7.parse(string(entry, "id")); // 个别版本字段名叫 id
                        }
                        if (uuid != null) {
                            report.known.add(uuid);
                        }
                        if (uuid != null && entryName != null && entryName.equalsIgnoreCase(name)) {
                            report.observe(uuid, "usercache.json（原版记录过这个名字）");
                        }
                    }
                }
            } catch (Throwable t) {
                report.notes.add("usercache.json 解析失败（已跳过）: " + t);
            }
        } else {
            report.notes.add("没有找到 usercache.json（服务端还没记录过任何玩家名）");
        }

        // ③ 存档里的玩家痕迹：playerdata / stats / advancements 都是按 UUID 命名的
        String levelName = levelName();
        File playerData = new File(levelName, "playerdata");
        collectUuidFiles(playerData, ".dat", report);
        collectUuidFiles(new File(levelName, "stats"), ".json", report);
        collectUuidFiles(new File(levelName, "advancements"), ".json", report);

        // ④ 原版离线模式算法：这个最容易"撞上老存档"
        UUID offline = UuidV7.vanillaOfflineUuid(name);
        report.vanillaOfflineUuid = offline;
        report.known.add(offline);
        report.vanillaOfflineDataExists = new File(playerData, offline + ".dat").isFile();
        if (report.vanillaOfflineDataExists) {
            report.notes.add("存档里已存在原版离线 UUID 的玩家数据: " + offline
                    + "（说明这个名字以前在离线模式下玩过）；沿用它能保住背包/成就，换新 UUID 等于换人。");
        }
        return report;
    }

    private static void collectUuidFiles(File directory, String suffix, Report report) {
        if (directory == null || !directory.isDirectory()) {
            return;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String fileName = file.getName();
            if (!fileName.endsWith(suffix)) {
                continue;
            }
            UUID uuid = UuidV7.parse(fileName.substring(0, fileName.length() - suffix.length()));
            if (uuid != null) {
                report.known.add(uuid);
            }
        }
    }

    /** 读 server.properties 的 level-name（默认 world）。 */
    public static String levelName() {
        File properties = new File("server.properties");
        if (!properties.isFile()) {
            return "world";
        }
        try (Reader reader = new InputStreamReader(new FileInputStream(properties), StandardCharsets.UTF_8)) {
            java.io.BufferedReader buffered = new java.io.BufferedReader(reader);
            String line;
            while ((line = buffered.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                if ("level-name".equals(trimmed.substring(0, eq).trim())) {
                    String value = trimmed.substring(eq + 1).trim();
                    return value.isEmpty() ? "world" : value;
                }
            }
        } catch (Throwable ignored) {
            // 读不动就用默认值
        }
        return "world";
    }

    private static String string(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        try {
            return json.get(key).getAsString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 玩家名校验：与原版一致的 3~16 位 {@code [A-Za-z0-9_]}（超界会被客户端/服务端拒）。 */
    public static String validateName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "玩家名不能为空";
        }
        String value = name.trim();
        if (value.length() < 3 || value.length() > 16) {
            return "玩家名长度必须是 3~16 个字符（原版限制）";
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
            if (!allowed) {
                return "玩家名只能包含字母、数字和下划线（原版限制），非法字符: '" + c + "'";
            }
        }
        return null;
    }

    /** 名字的小写形式（配置里统一按小写比对）。 */
    public static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
