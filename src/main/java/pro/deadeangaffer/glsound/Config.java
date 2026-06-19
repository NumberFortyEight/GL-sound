package pro.deadeangaffer.glsound;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Predicate;
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
        return Optional.ofNullable(System.getenv("APPDATA"))
                .filter(Predicate.not(String::isBlank))
                .map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("user.home"), ".config"))
                .resolve("GL-Sound")
                .resolve("config.properties");
    }

    public Path file() { return file; }

    public void loadOrCreate() throws IOException {
        if (!Files.exists(file)) {
            createDefaults();
            return;
        }
        try (BufferedReader in = Files.newBufferedReader(file)) {
            props.load(in);
        }
        migrateLegacyToken();
    }

    private void createDefaults() throws IOException {
        Files.createDirectories(file.getParent());
        props.setProperty("baseUrl", "");
        props.setProperty("projectPath", "");
        props.setProperty("token", "");
        props.setProperty("refs", "");
        props.setProperty("intervalSeconds", Integer.toString(DEFAULT_INTERVAL_SEC));
        props.setProperty("volumePercent", Integer.toString(DEFAULT_VOLUME_PERCENT));
        try (BufferedWriter out = Files.newBufferedWriter(file)) {
            props.store(out, "GL-Sound configuration.");
        }
    }

    private void migrateLegacyToken() {
        String raw = props.getProperty("token", "").trim();
        if (raw.isEmpty() || raw.startsWith(WindowsDpapi.PREFIX)) return;
        try {
            props.setProperty("token", "%s%s".formatted(WindowsDpapi.PREFIX, WindowsDpapi.protect(raw)));
            save();
            LOG.info("Token migrated from plaintext to DPAPI-encrypted form");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to migrate token to DPAPI; left as plaintext: %s".formatted(e.getMessage()));
        }
    }

    public String baseUrl() { return props.getProperty("baseUrl", "").trim().replaceAll("/+$", ""); }

    public String projectPath() { return props.getProperty("projectPath", "").trim(); }

    public String token() {
        String raw = props.getProperty("token", "").trim();
        if (raw.isEmpty()) return "";
        if (!raw.startsWith(WindowsDpapi.PREFIX)) return raw;
        try {
            return WindowsDpapi.unprotect(raw.substring(WindowsDpapi.PREFIX.length()));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to decrypt token: %s".formatted(e.getMessage()));
            return "";
        }
    }

    public List<String> refs() {
        return Arrays.stream(props.getProperty("refs", "").split(","))
                .map(String::trim)
                .filter(Predicate.not(String::isEmpty))
                .toList();
    }

    public int intervalSeconds() {
        return parseIntOr("intervalSeconds", DEFAULT_INTERVAL_SEC, v -> Math.max(2, v));
    }

    public int volumePercent() {
        return parseIntOr("volumePercent", DEFAULT_VOLUME_PERCENT, v -> Math.max(0, Math.min(100, v)));
    }

    private int parseIntOr(String key, int fallback, java.util.function.IntUnaryOperator normalize) {
        try {
            return normalize.applyAsInt(Integer.parseInt(props.getProperty(key, Integer.toString(fallback)).trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void update(String baseUrl, String projectPath, String token,
                       String refs, int intervalSeconds, int volumePercent) {
        props.setProperty("baseUrl", baseUrl);
        props.setProperty("projectPath", projectPath);
        props.setProperty("token", encryptForStorage(token));
        props.setProperty("refs", refs);
        props.setProperty("intervalSeconds", Integer.toString(Math.max(2, intervalSeconds)));
        props.setProperty("volumePercent", Integer.toString(Math.max(0, Math.min(100, volumePercent))));
    }

    private static String encryptForStorage(String token) {
        if (token == null || token.isBlank()) return "";
        try {
            return "%s%s".formatted(WindowsDpapi.PREFIX, WindowsDpapi.protect(token));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "DPAPI encryption failed, storing token as plaintext: %s".formatted(e.getMessage()));
            return token;
        }
    }

    public void save() throws IOException {
        Files.createDirectories(file.getParent());
        try (BufferedWriter out = Files.newBufferedWriter(file)) {
            props.store(out, "GL-Sound configuration (managed via Settings dialog).");
        }
    }
}
