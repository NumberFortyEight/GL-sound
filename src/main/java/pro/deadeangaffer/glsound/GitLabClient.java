package pro.deadeangaffer.glsound;

import com.fasterxml.jackson.jr.ob.JSON;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

    public Optional<PipelineInfo> latestPipeline(String ref, String previousEtag)
            throws IOException, InterruptedException {
        var url = "%s/api/v4/projects/%s/pipelines?per_page=1&ref=%s"
                .formatted(baseUrl, encodedProject,
                        URLEncoder.encode(ref, StandardCharsets.UTF_8));

        var b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("PRIVATE-TOKEN", token)
                .header("Accept", "application/json")
                .GET();
        if (previousEtag != null && !previousEtag.isBlank()) {
            b.header("If-None-Match", previousEtag);
        }

        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int code = resp.statusCode();
        if (code == 304) return Optional.empty();
        if (code == 401 || code == 403) {
            throw new IOException("Auth error " + code + ": проверь PAT (scope read_api)");
        }
        if (code == 404) {
            throw new IOException("GitLab 404: проект '" + decodedProjectForMessage() + "' или ветка '" + ref + "' не найдены");
        }
        if (code >= 400) {
            throw new IOException("GitLab HTTP " + code + ": " + truncate(resp.body()));
        }

        var etag = resp.headers().firstValue("etag").orElse(null);
        Object parsed = JSON.std.anyFrom(resp.body());
        if (!(parsed instanceof java.util.List<?> arr) || arr.isEmpty()) {
            return Optional.empty();
        }
        if (!(arr.getFirst() instanceof java.util.Map<?, ?> p)) {
            throw new IOException("GitLab JSON: ожидался объект pipeline, получено " + arr.getFirst());
        }

        long id = readLong(p, "id");
        var status = readString(p, "status", "unknown");
        var resolvedRef = readString(p, "ref", ref);
        var webUrl = readString(p, "web_url", "");
        return Optional.of(new PipelineInfo(id, status, resolvedRef, webUrl, etag));
    }

    private String decodedProjectForMessage() {
        return java.net.URLDecoder.decode(encodedProject, StandardCharsets.UTF_8);
    }

    private static long readLong(java.util.Map<?, ?> p, String key) throws IOException {
        var v = p.get(key);
        if (v instanceof Number n) return n.longValue();
        throw new IOException("GitLab JSON: поле '" + key + "' отсутствует или не число (" + v + ")");
    }

    private static String readString(java.util.Map<?, ?> p, String key, String fallback) {
        var v = p.get(key);
        return v == null ? fallback : v.toString();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
