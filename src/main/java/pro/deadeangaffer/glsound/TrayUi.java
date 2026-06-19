package pro.deadeangaffer.glsound;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class TrayUi {

    public enum State { IDLE, OK, FAIL, RUNNING, ERROR, PAUSED }

    private TrayIcon trayIcon;
    private final Map<State, BufferedImage> icons = new EnumMap<>(State.class);
    private final MenuItem pauseItem;
    private final MenuItem lastPipelineItem;
    private volatile String lastPipelineUrl;
    private volatile boolean paused;
    private Consumer<Boolean> pauseListener = p -> {};
    private Runnable settingsListener = () -> {};

    public TrayUi(Path configFile, Supplier<String> pipelinesUrl) throws AWTException {
        if (!SystemTray.isSupported()) {
            throw new AWTException("SystemTray не поддерживается в этой системе");
        }
        for (var s : State.values()) icons.put(s, drawIcon(colorFor(s)));

        var menu = new PopupMenu();

        lastPipelineItem = new MenuItem("Открыть последний pipeline");
        lastPipelineItem.setEnabled(false);
        lastPipelineItem.addActionListener(e -> openInBrowser(lastPipelineUrl));
        menu.add(lastPipelineItem);

        var openProject = new MenuItem("Открыть проект (pipelines)");
        openProject.addActionListener(e -> openInBrowser(pipelinesUrl.get()));
        menu.add(openProject);

        menu.addSeparator();

        pauseItem = new MenuItem("Пауза");
        pauseItem.addActionListener(e -> togglePause());
        menu.add(pauseItem);

        var settingsItem = new MenuItem("Настройки...");
        settingsItem.addActionListener(e -> settingsListener.run());
        menu.add(settingsItem);

        var openConfig = new MenuItem("Открыть файл конфига");
        openConfig.addActionListener(e -> openInBrowser(configFile.toUri().toString()));
        menu.add(openConfig);

        menu.addSeparator();

        var exitItem = new MenuItem("Выход");
        exitItem.addActionListener(e -> {
            SystemTray.getSystemTray().remove(trayIcon);
            System.exit(0);
        });
        menu.add(exitItem);

        trayIcon = new TrayIcon(icons.get(State.IDLE), "GL-Sound: ожидание", menu);
        trayIcon.setImageAutoSize(true);
        SystemTray.getSystemTray().add(trayIcon);
    }

    public void onPauseToggle(Consumer<Boolean> listener) { this.pauseListener = listener; }
    public void onOpenSettings(Runnable listener) { this.settingsListener = listener; }

    public void setState(State s, String tooltip) {
        runOnEdt(() -> {
            trayIcon.setImage(icons.get(s));
            trayIcon.setToolTip("GL-Sound: " + tooltip);
        });
    }

    public void setLastPipeline(String url) {
        this.lastPipelineUrl = url;
        runOnEdt(() -> lastPipelineItem.setEnabled(url != null && !url.isBlank()));
    }

    public void notify(String title, String message, TrayIcon.MessageType type) {
        runOnEdt(() -> trayIcon.displayMessage(title, message, type));
    }

    public boolean isPaused() { return paused; }

    private void togglePause() {
        paused = !paused;
        pauseItem.setLabel(paused ? "Продолжить" : "Пауза");
        if (paused) setState(State.PAUSED, "пауза");
        pauseListener.accept(paused);
    }

    private static void runOnEdt(Runnable r) {
        if (EventQueue.isDispatchThread()) {
            r.run();
        } else {
            EventQueue.invokeLater(r);
        }
    }

    private static Color colorFor(State s) {
        return switch (s) {
            case IDLE    -> new Color(0x9aa0a6);
            case OK      -> new Color(0x2e7d32);
            case FAIL    -> new Color(0xc62828);
            case RUNNING -> new Color(0x1565c0);
            case ERROR   -> new Color(0xef6c00);
            case PAUSED  -> new Color(0x616161);
        };
    }

    private static BufferedImage drawIcon(Color color) {
        int size = 32;
        var img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(color);
        g.fillOval(2, 2, size - 4, size - 4);
        g.setColor(new Color(0, 0, 0, 90));
        g.drawOval(2, 2, size - 4, size - 4);
        g.dispose();
        return img;
    }

    private static void openInBrowser(String url) {
        if (url == null || url.isBlank()) return;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ignored) {
        }
    }
}
