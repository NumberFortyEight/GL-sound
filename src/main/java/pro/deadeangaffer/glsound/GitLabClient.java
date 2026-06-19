package pro.deadeangaffer.glsound;

import com.fasterxml.jackson.jr.ob.JSON;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class GitLabClient {

    private final HttpClient http;
    private final String baseUrl;
    private final String encodedProject;
    private final String token;

    public GitLabClient(HttpClient http, String baseUrl, String projectPath, String token) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.encodedProject = URLEncoder.encode(projectPath, StandardCharsets.UTF_8);
        this.token = token;
    }

    public Optional<PipelineInfo> latestPipeline(String ref, String previousEtag) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("%s/api/v4/projects/%s/pipelines?per_page=1&ref=%s"
                        .formatted(baseUrl, encodedProject, URLEncoder.encode(ref, StandardCharsets.UTF_8))))
                .timeout(Duration.ofSeconds(15))
                .header("PRIVATE-TOKEN", token)
                .header("Accept", "application/json")
                .GET();
        Optional.ofNullable(previousEtag)
                .filter(s -> !s.isBlank())
                .ifPresent(tag -> builder.header("If-None-Match", tag));

        HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        int code = resp.statusCode();
        if (code == 304) return Optional.empty();
        if (code == 401 || code == 403) throw new IOException("Auth error %d: проверь PAT (scope read_api)".formatted(code));
        if (code == 404) throw new IOException("GitLab 404: проект '%s' или ветка '%s' не найдены"
                .formatted(URLDecoder.decode(encodedProject, StandardCharsets.UTF_8), ref));
        if (code >= 400) throw new IOException("GitLab HTTP %d: %s".formatted(code, truncate(resp.body())));

        Object parsed = JSON.std.anyFrom(resp.body());
        if (!(parsed instanceof List<?> arr) || arr.isEmpty()) return Optional.empty();
        if (!(arr.getFirst() instanceof Map<?, ?> p)) throw new IOException("GitLab JSON: ожидался объект pipeline, получено %s".formatted(arr.getFirst()));

        return Optional.of(new PipelineInfo(
                readLong(p, "id"),
                readString(p, "status", "unknown"),
                readString(p, "ref", ref),
                readString(p, "web_url", ""),
                resp.headers().firstValue("etag").orElse(null)));
    }

    private static long readLong(Map<?, ?> p, String key) throws IOException {
        Object v = p.get(key);
        if (v instanceof Number n) return n.longValue();
        throw new IOException("GitLab JSON: поле '%s' отсутствует или не число (%s)".formatted(key, v));
    }

    private static String readString(Map<?, ?> p, String key, String fallback) {
        return Optional.ofNullable(p.get(key))
                .map(Object::toString)
                .orElse(fallback);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        if (s.length() <= 200) return s;
        return "%s…".formatted(s.substring(0, 200));
    }
}
