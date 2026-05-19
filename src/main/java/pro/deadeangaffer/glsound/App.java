package pro.deadeangaffer.glsound;

import java.awt.TrayIcon;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public final class App {

    private static final Logger LOG = Logger.getLogger("gl-sound");
    private static final SoundPlayer SOUND = new SoundPlayer();

    private static Config cfg;
    private static TrayUi ui;
    private static volatile PipelineWatcher watcher;

    public static void main(String[] args) throws Exception {
        configureLogging();

        cfg = new Config(Config.defaultPath());
        cfg.loadOrCreate();

        ui = new TrayUi(cfg.file(), App::pipelinesUrl);
        ui.onOpenSettings(() -> SettingsDialog.open(cfg, App::onConfigApplied));

        startWatcherFromConfig();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            var w = watcher;
            if (w != null) w.close();
        }, "shutdown"));

        Thread.currentThread().join();
    }

    private static synchronized void onConfigApplied(Config newCfg) {
        startWatcherFromConfig();
    }

    private static String pipelinesUrl() {
        var base = cfg.baseUrl();
        var path = cfg.projectPath();
        if (base.isBlank() || path.isBlank()) return null;
        return base + "/" + path + "/-/pipelines";
    }

    private static synchronized void startWatcherFromConfig() {
        var old = watcher;
        if (old != null) {
            old.close();
            watcher = null;
        }

        if (cfg.token().isBlank()) {
            ui.setState(TrayUi.State.ERROR, "нет токена в конфиге");
            ui.notify("GL-Sound: настройка",
                    "Открой \"Настройки...\" в трее и впиши Personal Access Token.",
                    TrayIcon.MessageType.WARNING);
            return;
        }

        var client  = new GitLabClient(cfg.baseUrl(), cfg.projectPath(), cfg.token());
        var fresh   = new PipelineWatcher(client, cfg.refs(), cfg.intervalSeconds(), SOUND, ui);

        ui.onPauseToggle(fresh::setPaused);
        ui.setState(TrayUi.State.IDLE, "опрос " + cfg.intervalSeconds() + "с");
        ui.notify("GL-Sound запущен",
                "%s — каждые %dс, ветки: %s".formatted(
                        cfg.projectPath(),
                        cfg.intervalSeconds(),
                        String.join(", ", cfg.refs())),
                TrayIcon.MessageType.INFO);

        fresh.start();
        watcher = fresh;
    }

    private static void configureLogging() {
        try {
            var dir = Config.defaultPath().getParent();
            Files.createDirectories(dir);
            var fh = new FileHandler(dir.resolve("gl-sound.log").toString(), 1_000_000, 3, true);
            fh.setFormatter(new SimpleFormatter());
            Logger root = Logger.getLogger("");
            root.addHandler(fh);
            root.setLevel(Level.INFO);
        } catch (IOException ignored) {
        }
        try {
            System.setErr(new java.io.PrintStream(OutputStream.nullOutputStream()));
        } catch (Exception ignored) {
        }
    }
}
