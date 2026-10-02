import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 自检脚本用的小型下载器: java Fetch.java &lt;url&gt; &lt;outFile&gt;
 *
 * <p>为什么不是"一次失败就退出"：CI / Release 的 runner 每次都是干净环境，
 * 自检要现场下 6 个 jar；官方仓库偶发 5xx 或超时就会让整套自检白挂一次
 * （Release 里表现为"自检 1/4 失败、只跑了 4 秒"）。所以这里做三件事：
 *
 * <ol>
 *   <li>每个地址重试 3 次，指数退避（1s / 2s）；</li>
 *   <li>官方 Mojang 仓库（libraries.minecraft.net）失败时自动改用 Maven Central 镜像
 *       —— {@code com.mojang:authlib} 在 Central 上也有发布；</li>
 *   <li>下载完校验：文件必须存在且非空，否则按失败处理（避免留下半截文件让下次"以为已下好"）。</li>
 * </ol>
 */
public class Fetch {

    private static final int ATTEMPTS_PER_URL = 3;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: java Fetch.java <url> <outFile>");
            System.exit(2);
        }
        String url = args[0];
        Path target = Paths.get(args[1]);
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        List<String> candidates = new ArrayList<String>();
        candidates.add(url);
        String mirror = mirrorOf(url);
        if (mirror != null) {
            candidates.add(mirror);
            System.out.println("[Fetch] 官方仓库失败时将改用镜像: " + mirror);
        }

        String lastError = "(未尝试)";
        for (String candidate : candidates) {
            for (int attempt = 1; attempt <= ATTEMPTS_PER_URL; attempt++) {
                try {
                    long bytes = download(client, candidate, target);
                    System.out.println("[Fetch] 已下载 (" + bytes + " 字节) -> " + target);
                    return;
                } catch (Exception e) {
                    lastError = candidate + " 第 " + attempt + " 次失败: " + e;
                    System.err.println("[Fetch] " + lastError);
                    if (attempt < ATTEMPTS_PER_URL) {
                        Thread.sleep(1000L * attempt);   // 1s、2s 退避
                    }
                }
            }
        }
        System.err.println("[Fetch] 全部地址与重试都失败，最后一个错误: " + lastError);
        System.exit(1);
    }

    private static long download(HttpClient client, String url, Path target) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(180))
                .header("User-Agent", "HyAuth-Agent-Verify/1.0")
                .GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        byte[] body = response.body();
        if (body == null || body.length == 0) {
            throw new IllegalStateException("响应为空");
        }
        try (OutputStream out = Files.newOutputStream(target)) {
            out.write(body);
        }
        if (!Files.exists(target) || Files.size(target) == 0) {
            throw new IllegalStateException("写盘后文件为空: " + target);
        }
        return body.length;
    }

    /** Mojang 官方仓库 → Maven Central 的等价地址（拿不到就返回 null）。 */
    private static String mirrorOf(String url) {
        String mojang = "https://libraries.minecraft.net/";
        if (url.startsWith(mojang)) {
            return "https://repo1.maven.org/maven2/" + url.substring(mojang.length());
        }
        return null;
    }
}
