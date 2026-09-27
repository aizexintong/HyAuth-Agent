package com.hyauth.agent.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hyauth.agent.config.ListManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * LittleSkin（外置 Yggdrasil 验证服务器）HTTP 请求与 RSA 验签工具。
 *
 * <p>流程严格遵循 Yggdrasil 服务端技术规范：
 * <ol>
 *     <li>请求 {@code GET {apiRoot}/sessionserver/session/minecraft/hasJoined?username=&serverId=&ip=}；</li>
 *     <li>HTTP 200 → 解析角色 UUID / 名称 / 属性；204 → 该玩家没有有效会话；</li>
 *     <li>对每个属性用验证服务器公钥做 {@code SHA1withRSA} 验签，<b>只保留验签通过的属性</b>，
 *         防止中间人伪造皮肤/披风数据；</li>
 *     <li>公钥优先取配置文件 {@code public_key}，否则自动从 {@code {apiRoot}} 的
 *         {@code signaturePublickey} 字段获取并缓存（失败时按 30 秒退避重试）。</li>
 * </ol>
 */
public final class YggdrasilAuthUtil {

    private static final String HAS_JOINED_PATH = "sessionserver/session/minecraft/hasJoined";
    private static final String SIGNATURE_ALGORITHM = "SHA1withRSA";
    private static final String USER_AGENT = "HyAuth-Agent/1.0.0";

    /** 公钥获取失败后的退避时间，避免验证服务器故障时每个登录请求都打一次公钥接口。 */
    private static final long PUBLIC_KEY_RETRY_BACKOFF_MS = 30000L;

    private static final Object KEY_LOCK = new Object();

    private static volatile PublicKey cachedPublicKey;
    private static volatile String cachedPublicKeyApiRoot = "";
    private static volatile String cachedPublicKeyOverride = "";
    private static volatile long nextPublicKeyAttemptAt = 0L;
    private static volatile boolean missingKeyWarned = false;

    private YggdrasilAuthUtil() {
    }

    /**
     * 向 LittleSkin 校验玩家会话，并完成属性验签。
     *
     * @param username 玩家名（外置登录角色名）
     * @param serverId 服务端下发的 serverId
     * @param address  客户端 IP，可为 null（仅当服务端开启 prevent-proxy-connections 时传值）
     * @return 校验通过的角色信息；未登录、会话无效或校验失败时返回 null
     */
    public static VerifiedProfile verifyLittleSkin(String username, String serverId, InetAddress address) {
        final String apiRoot = ListManager.getApiRoot();
        HttpURLConnection connection = null;
        try {
            String url = buildHasJoinedUrl(apiRoot, username, serverId, address);
            connection = open(url);

            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NO_CONTENT) {
                debug("LittleSkin 返回 204：玩家 " + username + " 当前没有有效会话（未登录或 serverId 不匹配）");
                return null;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                System.err.println("[HyAuth] LittleSkin hasJoined 返回异常状态码 " + code + "，玩家: " + username);
                return null;
            }

            String body = readAll(connection.getInputStream());
            return parseProfile(body, username, resolvePublicKey(apiRoot));
        } catch (Exception e) {
            System.err.println("[HyAuth] LittleSkin 验证请求失败: " + e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------
    // 响应解析与验签
    // ------------------------------------------------------------------

    static VerifiedProfile parseProfile(String body, String expectedUsername, PublicKey publicKey) {
        JsonElement root;
        try {
            root = JsonParser.parseString(body);
        } catch (Exception e) {
            System.err.println("[HyAuth] LittleSkin 响应不是合法 JSON: " + e.getMessage());
            return null;
        }
        if (root == null || !root.isJsonObject()) {
            System.err.println("[HyAuth] LittleSkin 响应结构异常，期望 JSON 对象。");
            return null;
        }

        JsonObject json = root.getAsJsonObject();
        JsonElement idElement = json.get("id");
        JsonElement nameElement = json.get("name");
        if (idElement == null || idElement.isJsonNull() || nameElement == null || nameElement.isJsonNull()) {
            System.err.println("[HyAuth] LittleSkin 响应缺少 id/name 字段。");
            return null;
        }

        UUID id = parseUuid(idElement.getAsString());
        String name = nameElement.getAsString();
        if (id == null || name == null || name.isEmpty()) {
            System.err.println("[HyAuth] LittleSkin 响应中的 id/name 非法。");
            return null;
        }

        // 防冒用：验证服务器返回的角色名必须与请求的玩家名一致（忽略大小写）
        if (!name.equalsIgnoreCase(expectedUsername)) {
            System.err.println("[HyAuth] 安全拦截：LittleSkin 返回的角色名 " + name
                    + " 与请求玩家名 " + expectedUsername + " 不一致，已拒绝本次鉴权。");
            return null;
        }

        List<VerifiedProperty> properties = new ArrayList<VerifiedProperty>();
        if (json.has("properties") && json.get("properties").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("properties")) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject property = element.getAsJsonObject();
                String propertyName = getString(property, "name");
                String propertyValue = getString(property, "value");
                String signature = getString(property, "signature");
                if (propertyName == null || propertyValue == null) {
                    continue;
                }

                if (signature == null || signature.isEmpty()) {
                    System.err.println("[HyAuth] 属性 " + propertyName + " 未携带签名，已丢弃。");
                    continue;
                }
                if (publicKey == null) {
                    warnMissingKeyOnce();
                    continue;
                }
                if (!verifySignature(propertyValue, signature, publicKey)) {
                    System.err.println("[HyAuth] 安全拦截：属性 " + propertyName + " 签名校验未通过，已丢弃。");
                    continue;
                }
                properties.add(new VerifiedProperty(propertyName, propertyValue, signature));
            }
        }

        return new VerifiedProfile(id, name, properties);
    }

    private static boolean verifySignature(String value, String signatureBase64, PublicKey key) {
        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initVerify(key);
            signature.update(value.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (Exception e) {
            System.err.println("[HyAuth] 属性签名校验异常: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 公钥加载
    // ------------------------------------------------------------------

    /**
     * 获取验签公钥：优先配置文件 {@code public_key}，否则从 {@code {apiRoot}} 的
     * {@code signaturePublickey} 字段拉取并缓存。
     */
    static PublicKey resolvePublicKey(String apiRoot) {
        final String override = ListManager.getPublicKey();

        PublicKey key = cachedPublicKey;
        if (key != null && apiRoot.equals(cachedPublicKeyApiRoot) && override.equals(cachedPublicKeyOverride)) {
            return key;
        }

        synchronized (KEY_LOCK) {
            key = cachedPublicKey;
            if (key != null && apiRoot.equals(cachedPublicKeyApiRoot) && override.equals(cachedPublicKeyOverride)) {
                return key;
            }
            if (System.currentTimeMillis() < nextPublicKeyAttemptAt) {
                return null;
            }

            String source;
            PublicKey parsed;
            if (!override.isEmpty()) {
                source = "配置文件 public_key";
                parsed = parsePublicKey(override);
            } else {
                source = apiRoot + " 的 signaturePublickey";
                parsed = parsePublicKey(fetchSignaturePublicKey(apiRoot));
            }

            cachedPublicKey = parsed;
            cachedPublicKeyApiRoot = apiRoot;
            cachedPublicKeyOverride = override;

            if (parsed != null) {
                System.out.println("[HyAuth] 已加载 LittleSkin 验签公钥，来源: " + source);
                missingKeyWarned = false;
            } else {
                System.err.println("[HyAuth] 未能获取 LittleSkin 验签公钥（来源: " + source
                        + "），本次将丢弃全部角色属性（玩家皮肤不可见）。");
                nextPublicKeyAttemptAt = System.currentTimeMillis() + PUBLIC_KEY_RETRY_BACKOFF_MS;
            }
            return parsed;
        }
    }

    private static String fetchSignaturePublicKey(String apiRoot) {
        HttpURLConnection connection = null;
        try {
            connection = open(apiRoot);
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                System.err.println("[HyAuth] 获取 API 元数据失败，HTTP " + code + ": " + apiRoot);
                return null;
            }
            JsonElement root = JsonParser.parseString(readAll(connection.getInputStream()));
            if (root == null || !root.isJsonObject()) {
                return null;
            }
            return getString(root.getAsJsonObject(), "signaturePublickey");
        } catch (Exception e) {
            System.err.println("[HyAuth] 获取 API 元数据异常: " + e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 解析 PEM 或裸 Base64 的 X.509 RSA 公钥。 */
    static PublicKey parsePublicKey(String pemOrBase64) {
        if (pemOrBase64 == null || pemOrBase64.trim().isEmpty()) {
            return null;
        }
        try {
            String base64 = pemOrBase64
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            System.err.println("[HyAuth] 解析 LittleSkin RSA 公钥失败: " + e);
            return null;
        }
    }

    private static void warnMissingKeyOnce() {
        if (!missingKeyWarned) {
            missingKeyWarned = true;
            System.err.println("[HyAuth] 当前没有可用的验签公钥，已丢弃角色属性。"
                    + "可在 " + ListManager.FILE_NAME + " 中配置 public_key 后热重载。");
        }
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private static String buildHasJoinedUrl(String apiRoot, String username, String serverId, InetAddress address)
            throws UnsupportedEncodingException {
        StringBuilder url = new StringBuilder(apiRoot);
        if (!apiRoot.endsWith("/")) {
            url.append('/');
        }
        url.append(HAS_JOINED_PATH)
                .append("?username=").append(encode(username))
                .append("&serverId=").append(encode(serverId == null ? "" : serverId));
        if (address != null) {
            url.append("&ip=").append(encode(address.getHostAddress()));
        }
        return url.toString();
    }

    private static String encode(String value) throws UnsupportedEncodingException {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(ListManager.getConnectTimeoutMs());
        connection.setReadTimeout(ListManager.getReadTimeoutMs());
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        return connection;
    }

    private static String readAll(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String getString(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        return json.get(key).getAsString();
    }

    /** 兼容带连字符与无符号（32 位）两种 UUID 写法。 */
    static UUID parseUuid(String raw) {
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
            System.err.println("[HyAuth] 非法的角色 UUID: " + raw);
            return null;
        }
    }

    private static void debug(String message) {
        if (ListManager.isDebug()) {
            System.out.println("[HyAuth][DEBUG] " + message);
        }
    }

    /**
     * 给日志用：描述外置资料里的 {@code textures} 属性（皮肤/披风）。
     *
     * <p>客户端显示的皮肤来自服务端下发的 {@code textures} 属性里的链接；它不显示时，
     * 第一件要分清的事是"服务端到底有没有这份数据"：
     * <ul>
     *     <li>有链接 → 问题在客户端/账号侧（皮肤站上没上传皮肤、客户端缓存、CDN 拉不到）；</li>
     *     <li>没有该属性 → 皮肤站没返回皮肤（或该角色就是默认皮肤）。</li>
     * </ul>
     */
    public static String describeTextures(VerifiedProfile profile) {
        try {
            return describeTexturesSafely(profile);
        } catch (Throwable t) {
            return "属性解析失败（不影响登录）";
        }
    }

    private static String describeTexturesSafely(VerifiedProfile profile) {
        if (profile == null) {
            return "资料为空";
        }
        List<VerifiedProperty> properties = profile.getProperties();
        if (properties == null || properties.isEmpty()) {
            return "没有任何属性（含皮肤）";
        }
        for (VerifiedProperty property : properties) {
            if (!"textures".equals(property.getName())) {
                continue;
            }
            String url = extractSkinUrl(property.getValue());
            boolean signed = property.getSignature() != null && !property.getSignature().isEmpty();
            if (url == null) {
                return "textures 属性存在但未解析出皮肤链接（签名" + (signed ? "有" : "无") + "）";
            }
            return "皮肤 " + url + "（签名" + (signed ? "有" : "无") + "）";
        }
        return "没有 textures 属性（客户端会显示默认皮肤）";
    }

    private static String extractSkinUrl(String base64Value) {
        if (base64Value == null) {
            return null;
        }
        try {
            byte[] raw = java.util.Base64.getDecoder().decode(base64Value.trim());
            String json = new String(raw, StandardCharsets.UTF_8);
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            return matcher.find() ? matcher.group(1) : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
