package harness;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 本地 LittleSkin 模拟服务：提供 API 元数据（公钥）与 hasJoined 接口。 */
public final class MockLittleSkin {

    public static final String LITTLESKIN_ID = "11112222333344445555666677778888";
    public static final String LITTLESKIN_ID_DASHED = "11112222-3333-4444-5555-666677778888";
    public static final String API_ROOT_PATH = "/api/yggdrasil";
    public static final String HAS_JOINED_PATH = "/api/yggdrasil/sessionserver/session/minecraft/hasJoined";

    private final HttpServer server;
    private final KeyPair keyPair;
    private final Map<String, AtomicInteger> hasJoinedCalls = new ConcurrentHashMap<String, AtomicInteger>();
    private final Map<String, String> lastQuery = new ConcurrentHashMap<String, String>();

    public MockLittleSkin(int port) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        this.keyPair = generator.generateKeyPair();
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.createContext(API_ROOT_PATH, this::handleApiRoot);
        this.server.createContext(HAS_JOINED_PATH, this::handleHasJoined);
        this.server.setExecutor(null);
    }

    public void start() {
        server.start();
        System.out.println("[MOCK] LittleSkin 模拟服务已启动: http://127.0.0.1:" + server.getAddress().getPort());
    }

    public void stop() {
        server.stop(0);
    }

    public int requestCount(String username) {
        AtomicInteger counter = hasJoinedCalls.get(username.toLowerCase());
        return counter == null ? 0 : counter.get();
    }

    public String lastQuery(String username) {
        return lastQuery.get(username.toLowerCase());
    }

    // ------------------------------------------------------------------

    private void handleApiRoot(HttpExchange exchange) throws java.io.IOException {
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        String body = "{\"meta\":{\"serverName\":\"HarnessLittleSkin\"},\"skinDomains\":[\"littleskin.cn\"],"
                + "\"signaturePublickey\":\"" + pem.replace("\n", "\\n") + "\"}";
        respond(exchange, 200, body);
    }

    private void handleHasJoined(HttpExchange exchange) throws java.io.IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String username = query.get("username");
        String key = username == null ? "" : username.toLowerCase();
        hasJoinedCalls.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        lastQuery.put(key, exchange.getRequestURI().getRawQuery());
        System.out.println("[MOCK] hasJoined 收到请求: " + exchange.getRequestURI().getRawQuery());

        if ("nosessionplayer".equals(key)) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        String property;
        if ("tamperedplayer".equals(key)) {
            // 签名与内容不匹配：Agent 必须丢弃该属性
            property = propertyJson("textures", texturesValue(username), sign("TAMPERED-CONTENT"));
        } else if ("unsignedplayer".equals(key)) {
            property = "{\"name\":\"textures\",\"value\":\"" + texturesValue(username) + "\"}";
        } else {
            String value = texturesValue(username);
            property = propertyJson("textures", value, sign(value));
        }

        String body = "{\"id\":\"" + LITTLESKIN_ID + "\",\"name\":\"" + username + "\",\"properties\":[" + property + "]}";
        respond(exchange, 200, body);
    }

    private String propertyJson(String name, String value, String signature) {
        return "{\"name\":\"" + name + "\",\"value\":\"" + value + "\",\"signature\":\"" + signature + "\"}";
    }

    private String texturesValue(String username) {
        String payload = "{\"timestamp\":" + System.currentTimeMillis()
                + ",\"profileId\":\"" + LITTLESKIN_ID + "\",\"profileName\":\"" + username + "\","
                + "\"textures\":{\"SKIN\":{\"url\":\"https://littleskin.cn/textures/harness-hash\"}}}";
        return Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String value) {
        try {
            Signature signature = Signature.getInstance("SHA1withRSA");
            signature.initSign(keyPair.getPrivate());
            signature.update(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new HashMap<String, String>();
        if (rawQuery == null) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            try {
                result.put(java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8"),
                        java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
            } catch (Exception ignored) {
                // 忽略非法参数
            }
        }
        return result;
    }

    private static void respond(HttpExchange exchange, int code, String body) throws java.io.IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    /** 供测试断言使用：暴露私钥以便校验签名本身是否正确。 */
    public PrivateKey privateKey() {
        return keyPair.getPrivate();
    }
}
