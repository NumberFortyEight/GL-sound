package pro.deadeangaffer.glsound;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public final class Config {

    private static final int DEFAULT_INTERVAL_SEC = 6;

    private final Path file;
    private final Properties props = new Properties();

    public Config(Path file) {
        this.file = file;
    }

    public static Path defaultPath() {
        var appData = System.getenv("APPDATA");
        var base = (appData != null && !appData.isBlank())
                ? Path.of(appData)
                : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("GL-Sound").resolve("config.properties");
    }

    public Path file() { return file; }

    public void loadOrCreate() throws IOException {
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            props.setProperty("baseUrl", "");
            props.setProperty("projectPath", "");
            props.setProperty("token", "");
            props.setProperty("refs", "");
            props.setProperty("intervalSeconds", Integer.toString(DEFAULT_INTERVAL_SEC));
            try (var out = Files.newBufferedWriter(file)) {
                props.store(out, "GL-Sound configuration.");
            }
            return;
        }
        try (var in = Files.newBufferedReader(file)) {
            props.load(in);
        }
    }

    public String baseUrl()      { return props.getProperty("baseUrl", "").trim().replaceAll("/+$", ""); }
    public String projectPath()  { return props.getProperty("projectPath", "").trim(); }
    public String token()        { return props.getProperty("token", "").trim(); }

    public List<String> refs() {
        var raw = props.getProperty("refs", "");
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public int intervalSeconds() {
        try {
            var v = Integer.parseInt(props.getProperty("intervalSeconds",
                    Integer.toString(DEFAULT_INTERVAL_SEC)).trim());
            return Math.max(2, v);
        } catch (NumberFormatException e) {
            return DEFAULT_INTERVAL_SEC;
        }
    }

    public void update(String baseUrl, String projectPath, String token,
                       String refs, int intervalSeconds) {
        props.setProperty("baseUrl", baseUrl);
        props.setProperty("projectPath", projectPath);
        props.setProperty("token", token);
        props.setProperty("refs", refs);
        props.setProperty("intervalSeconds", Integer.toString(Math.max(2, intervalSeconds)));
    }

    public void save() throws IOException {
        Files.createDirectories(file.getParent());
        try (var out = Files.newBufferedWriter(file)) {
            props.store(out, "GL-Sound configuration (managed via Settings dialog).");
        }
    }
}
