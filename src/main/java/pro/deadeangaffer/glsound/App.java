package pro.deadeangaffer.glsound;

import java.awt.TrayIcon;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public final class App {

    private static final Logger LOG = Logger.getLogger(App.class.getName());
    private static final SoundPlayer SOUND = new SoundPlayer();

    private static HttpClient http;
    private static Config cfg;
    private static TrayUi ui;
    private static volatile PipelineWatcher watcher;

    public static void main(String[] args) throws Exception {
        configureLogging();
        http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        cfg = new Config(Config.defaultPath());
        cfg.loadOrCreate();
        SOUND.setVolumePercent(cfg.volumePercent());
        ui = new TrayUi(cfg.file(), App::pipelinesUrl);
        ui.onOpenSettings(() -> SettingsDialog.open(cfg, http, App::onConfigApplied));
        SplashOverlay.show("GL-Sound запущен — иконка в трее");
        Thread.startVirtualThread(SOUND::playStart);
        ui.notify("GL-Sound запущен",
                "Иконка добавлена в системный трей. Правый клик — меню.",
                TrayIcon.MessageType.INFO);
        startWatcherFromConfig();
        Runtime.getRuntime().addShutdownHook(new Thread(App::onShutdown, "shutdown"));
        Thread.currentThread().join();
    }

    private static void onShutdown() {
        Optional.ofNullable(watcher).ifPresent(PipelineWatcher::close);
        Optional.ofNullable(http).ifPresent(HttpClient::close);
    }

    private static void onConfigApplied() {
        startWatcherFromConfig();
    }

    private static Optional<String> pipelinesUrl() {
        String base = cfg.baseUrl();
        String path = cfg.projectPath();
        if (base.isBlank() || path.isBlank()) return Optional.empty();
        return Optional.of("%s/%s/-/pipelines".formatted(base, path));
    }

    private static synchronized void startWatcherFromConfig() {
        SOUND.setVolumePercent(cfg.volumePercent());
        Optional.ofNullable(watcher).ifPresent(PipelineWatcher::close);
        watcher = null;
        if (cfg.token().isBlank()) {
            ui.setState(TrayUi.State.ERROR, "нет токена в конфиге");
            ui.notify("GL-Sound: настройка",
                    "Открой \"Настройки...\" в трее и впиши Personal Access Token.",
                    TrayIcon.MessageType.WARNING);
            return;
        }
        GitLabClient client = new GitLabClient(http, cfg.baseUrl(), cfg.projectPath(), cfg.token());
        PipelineWatcher fresh = new PipelineWatcher(client, cfg.refs(), cfg.intervalSeconds(), SOUND, ui);
        ui.onPauseToggle(fresh::setPaused);
        ui.setState(TrayUi.State.IDLE, "опрос %dс".formatted(cfg.intervalSeconds()));
        ui.notify("Мониторинг настроен",
                "%s — каждые %dс, ветки: %s".formatted(cfg.projectPath(), cfg.intervalSeconds(), String.join(", ", cfg.refs())),
                TrayIcon.MessageType.INFO);
        fresh.start();
        watcher = fresh;
    }

    private static void configureLogging() {
        try {
            Path dir = Config.defaultPath().getParent();
            Files.createDirectories(dir);
            FileHandler fh = new FileHandler(dir.resolve("gl-sound.log").toString(), 1_000_000, 3, true);
            fh.setFormatter(new SimpleFormatter());
            Logger root = Logger.getLogger("");
            root.addHandler(fh);
            root.setLevel(Level.INFO);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Не удалось настроить файловый лог: %s".formatted(e.getMessage()));
        }
    }
}
