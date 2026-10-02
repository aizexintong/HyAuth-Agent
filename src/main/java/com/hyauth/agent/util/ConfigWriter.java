package com.hyauth.agent.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hyauth.agent.config.ListManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 通过命令改配置：{@code /hy add|off|del} 直接改 {@code littleskin_config.json}，
 * 改完立刻触发一次热重载，<b>不用重启服务端</b>。
 *
 * <p>设计要点：
 * <ul>
 *     <li><b>文件才是唯一真相</b>：命令不维护第二份内存名单，而是"读文件 → 改字段 → 原子替换 → reload"，
 *         这样手工编辑与命令改名单不会互相覆盖；</li>
 *     <li><b>保留管理员写的其它字段</b>：用 gson 解析成对象后只动目标数组，
 *         注释以外的所有配置项（含我们没定义过的）原样写回；</li>
 *     <li><b>原子替换</b>：先写 {@code .tmp} 再 {@code ATOMIC_MOVE}，
 *         避免"服务端被 kill 时配置文件只剩半截"；</li>
 *     <li><b>一个名字只属于一个名单</b>：加入外置名单时会检查离线名单，反之亦然，
 *         两个名单里的同名条目会导致登录分流结果取决于判定顺序，属于配置事故。</li>
 * </ul>
 */
public final class ConfigWriter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String KEY_LITTLESKIN = "littleskin_players";
    private static final String KEY_OFFLINE = "offline_players";

    private ConfigWriter() {
    }

    /** 命令执行结果（成功/失败 + 回显文本）。 */
    public static final class Result {
        private final boolean ok;
        private final String message;

        private Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }

        public static Result ok(String message) {
            return new Result(true, message);
        }

        public static Result fail(String message) {
            return new Result(false, message);
        }

        public boolean isOk() {
            return ok;
        }

        public String getMessage() {
            return message;
        }
    }

    // ------------------------------------------------------------------
    // 名单操作
    // ------------------------------------------------------------------

    /** 加入 LittleSkin 外置名单。 */
    public static synchronized Result addLittleSkin(String name) {
        try {
            JsonObject json = load();
            JsonArray offline = array(json, KEY_OFFLINE);
            if (indexOfName(offline, name) >= 0) {
                return Result.fail("该名字已在离线名单里（一个名字只能属于一个名单）：先 /hy del " + name);
            }
            JsonArray whitelist = array(json, KEY_LITTLESKIN);
            if (indexOfName(whitelist, name) >= 0) {
                return Result.fail("该名字已经在 LittleSkin 外置名单里了（无需重复添加）");
            }
            whitelist.add(name);
            return finish(json, "已加入 LittleSkin 外置名单: " + name
                    + "（该玩家将走皮肤站外置验证，需自行完成外置登录）");
        } catch (Exception e) {
            return Result.fail("写配置失败: " + e);
        }
    }

    /**
     * 加入（或更新）离线名单条目。
     *
     * @param name 玩家名（保留管理员写法）
     * @param uuid 管理员指定或自动生成的 UUID
     */
    public static synchronized Result addOffline(String name, UUID uuid) {
        try {
            JsonObject json = load();
            JsonArray offline = array(json, KEY_OFFLINE);
            int index = indexOfName(offline, name);
            boolean updated = index >= 0;
            String previous = null;
            if (updated) {
                JsonElement element = offline.get(index);
                if (element != null && element.isJsonObject()) {
                    JsonObject entry = element.getAsJsonObject();
                    previous = string(entry, "uuid");
                    entry.addProperty("name", name);
                    entry.addProperty("uuid", uuid.toString());
                } else {
                    // 形状不对（例如被手工改成字符串）→ 直接替换成规范对象
                    JsonObject entry = new JsonObject();
                    entry.addProperty("name", name);
                    entry.addProperty("uuid", uuid.toString());
                    offline.set(index, entry);
                }
            } else {
                JsonObject entry = new JsonObject();
                entry.addProperty("name", name);
                entry.addProperty("uuid", uuid.toString());
                offline.add(entry);
            }

            // 同名若同时存在于外置名单，会与离线名单冲突，这里顺手摘掉
            JsonArray whitelist = array(json, KEY_LITTLESKIN);
            int whitelistIndex = indexOfName(whitelist, name);
            boolean removedFromWhitelist = false;
            if (whitelistIndex >= 0) {
                whitelist.remove(whitelistIndex);
                removedFromWhitelist = true;
            }

            String action = updated ? "已更新离线名单条目: " : "已加入离线名单: ";
            String suffix = removedFromWhitelist ? "（同一名字原先在 LittleSkin 外置名单里，已摘除）" : "";
            if (updated && previous != null && !previous.equalsIgnoreCase(uuid.toString())) {
                suffix += "（旧 UUID " + previous + " → 新 UUID）";
            }
            return finish(json, action + name + " -> " + uuid + suffix);
        } catch (Exception e) {
            return Result.fail("写配置失败: " + e);
        }
    }

    /** 从两个名单里都移除该名字。 */
    public static synchronized Result remove(String name) {
        try {
            JsonObject json = load();
            boolean removedLittleSkin = removeName(array(json, KEY_LITTLESKIN), name);
            boolean removedOffline = removeName(array(json, KEY_OFFLINE), name);
            if (!removedLittleSkin && !removedOffline) {
                return Result.fail("两个名单里都没有这个名字: " + name);
            }
            StringBuilder message = new StringBuilder("已从");
            if (removedLittleSkin) {
                message.append(" LittleSkin 外置名单");
            }
            if (removedOffline) {
                message.append(removedLittleSkin ? " 与" : "").append(" 离线名单");
            }
            message.append(" 移除: ").append(name);
            message.append("（玩家存档数据仍挂在原 UUID 名下，只是以后不再按名单放行）");
            return finish(json, message.toString());
        } catch (Exception e) {
            return Result.fail("写配置失败: " + e);
        }
    }

    // ------------------------------------------------------------------
    // 文件读写
    // ------------------------------------------------------------------

    private static JsonObject load() throws Exception {
        File file = new File(ListManager.FILE_NAME);
        if (!file.isFile()) {
            return new JsonObject();
        }
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || root.isJsonNull()) {
                return new JsonObject();
            }
            if (!root.isJsonObject()) {
                throw new IllegalStateException("配置根节点必须是 JSON 对象（当前不是，已放弃写入以免破坏文件）");
            }
            return root.getAsJsonObject();
        }
    }

    private static Result finish(JsonObject json, String message) throws Exception {
        save(json);
        // 立刻让内存名单与文件一致；文件监听线程稍后也会再重载一次（幂等）
        ListManager.reload();
        return Result.ok(message);
    }

    private static void save(JsonObject json) throws Exception {
        File target = new File(ListManager.FILE_NAME);
        File temp = new File(ListManager.FILE_NAME + ".tmp");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) {
            GSON.toJson(json, writer);
        }
        try {
            Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicFailed) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static JsonArray array(JsonObject json, String key) {
        if (json.has(key) && json.get(key) != null && json.get(key).isJsonArray()) {
            return json.getAsJsonArray(key);
        }
        JsonArray created = new JsonArray();
        json.add(key, created);
        return created;
    }

    /** 找名字（忽略大小写）；离线条目是对象（name 字段），外置名单是字符串。 */
    private static int indexOfName(JsonArray array, String name) {
        if (array == null || name == null) {
            return -1;
        }
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (element == null || element.isJsonNull()) {
                continue;
            }
            String entryName = null;
            if (element.isJsonObject()) {
                entryName = string(element.getAsJsonObject(), "name");
            } else {
                try {
                    entryName = element.getAsString();
                } catch (Throwable ignored) {
                    entryName = null;
                }
            }
            if (entryName != null && entryName.trim().equalsIgnoreCase(name.trim())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean removeName(JsonArray array, String name) {
        int index = indexOfName(array, name);
        if (index < 0) {
            return false;
        }
        array.remove(index);
        return true;
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
}
