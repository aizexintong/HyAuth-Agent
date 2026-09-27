import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/** 自检脚本用的小型下载器: java Fetch.java <url> <outFile> */
public class Fetch {
    public static void main(String[] args) throws Exception {
        String url = args[0];
        Path target = Paths.get(args[1]);
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(180))
                .header("User-Agent", "HyAuth-Agent-Verify/1.0")
                .GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("下载失败 HTTP " + response.statusCode() + ": " + url);
        }
        try (OutputStream out = Files.newOutputStream(target)) {
            out.write(response.body());
        }
        System.out.println("已下载 (" + response.body().length + " 字节) -> " + target);
    }
}
