package pro.deadeangaffer.glsound;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Config {

    private static final Logger LOG = Logger.getLogger(Config.class.getName());
    private static final int DEFAULT_INTERVAL_SEC = 6;
    private static final int DEFAULT_VOLUME_PERCENT = 70;

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
            props.setProperty("volumePercent", Integer.toString(DEFAULT_VOLUME_PERCENT));
            try (var out = Files.newBufferedWriter(file)) {
                props.store(out, "GL-Sound configuration.");
            }
            return;
        }
        try (var in = Files.newBufferedReader(file)) {
            props.load(in);
        }

        var raw = props.getProperty("token", "").trim();
        if (!raw.isEmpty() && !raw.startsWith(WindowsDpapi.PREFIX)) {
            try {
                var encrypted = WindowsDpapi.PREFIX + WindowsDpapi.protect(raw);
                props.setProperty("token", encrypted);
                save();
                LOG.info("Token migrated from plaintext to DPAPI-encrypted form");
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to migrate token to DPAPI; left as plaintext: " + e.getMessage());
            }
        }
    }

    public String baseUrl()      { return props.getProperty("baseUrl", "").trim().replaceAll("/+$", ""); }
    public String projectPath()  { return props.getProperty("projectPath", "").trim(); }

    public String token() {
        var raw = props.getProperty("token", "").trim();
        if (raw.isEmpty()) return "";
        if (raw.startsWith(WindowsDpapi.PREFIX)) {
            try {
                return WindowsDpapi.unprotect(raw.substring(WindowsDpapi.PREFIX.length()));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to decrypt token: " + e.getMessage());
                return "";
            }
        }
        return raw;
    }

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

    public int volumePercent() {
        try {
            var v = Integer.parseInt(props.getProperty("volumePercent",
                    Integer.toString(DEFAULT_VOLUME_PERCENT)).trim());
            return Math.max(0, Math.min(100, v));
        } catch (NumberFormatException e) {
            return DEFAULT_VOLUME_PERCENT;
        }
    }

    public void update(String baseUrl, String projectPath, String token,
                       String refs, int intervalSeconds, int volumePercent) {
        props.setProperty("baseUrl", baseUrl);
        props.setProperty("projectPath", projectPath);
        if (token == null || token.isBlank()) {
            props.setProperty("token", "");
        } else {
            try {
                props.setProperty("token", WindowsDpapi.PREFIX + WindowsDpapi.protect(token));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "DPAPI encryption failed, storing token as plaintext: " + e.getMessage());
                props.setProperty("token", token);
            }
        }
        props.setProperty("refs", refs);
        props.setProperty("intervalSeconds", Integer.toString(Math.max(2, intervalSeconds)));
        props.setProperty("volumePercent", Integer.toString(Math.max(0, Math.min(100, volumePercent))));
    }

    public void save() throws IOException {
        Files.createDirectories(file.getParent());
        try (var out = Files.newBufferedWriter(file)) {
            props.store(out, "GL-Sound configuration (managed via Settings dialog).");
        }
    }
}
