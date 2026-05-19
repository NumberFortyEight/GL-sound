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
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class GitLabClient {

    private final HttpClient http;
    private final String baseUrl;
    private final String encodedProject;
    private final String token;

    public GitLabClient(String baseUrl, String projectPath, String token) {
        this.baseUrl = baseUrl;
        this.encodedProject = URLEncoder.encode(projectPath, StandardCharsets.UTF_8);
        this.token = token;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Optional<PipelineInfo> latestPipeline(String ref, String previousEtag) throws IOException, InterruptedException {
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
        if (code >= 400) {
            throw new IOException("GitLab HTTP " + code + ": " + truncate(resp.body()));
        }

        var etag = resp.headers().firstValue("etag").orElse(null);
        List<Object> arr = JSON.std.listFrom(resp.body());
        if (arr.isEmpty()) return Optional.empty();

        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) arr.getFirst();
        long id = ((Number) p.get("id")).longValue();
        var status = String.valueOf(p.getOrDefault("status", "unknown"));
        var resolvedRef = String.valueOf(p.getOrDefault("ref", ref));
        var webUrl = String.valueOf(p.getOrDefault("web_url", ""));
        return Optional.of(new PipelineInfo(id, status, resolvedRef, webUrl, etag));
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
