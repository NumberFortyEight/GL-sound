package pro.deadeangaffer.glsound;

import java.awt.TrayIcon;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PipelineWatcher implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(PipelineWatcher.class.getName());

    private record BranchState(long lastId, String lastStatus, String etag) {}

    private final GitLabClient client;
    private final List<String> refs;
    private final int intervalSec;
    private final SoundPlayer sound;
    private final TrayUi ui;
    private final ScheduledExecutorService exec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "pipeline-watcher");
                t.setDaemon(true);
                return t;
            });
    private final Map<String, BranchState> states = new HashMap<>();
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private int consecutiveErrors = 0;

    public PipelineWatcher(GitLabClient client, List<String> refs, int intervalSec,
                           SoundPlayer sound, TrayUi ui) {
        this.client = client;
        this.refs = refs;
        this.intervalSec = intervalSec;
        this.sound = sound;
        this.ui = ui;
    }

    public void setPaused(boolean p) { paused.set(p); }

    public void start() {
        exec.scheduleWithFixedDelay(this::tick, 1, intervalSec, TimeUnit.SECONDS);
    }

    private void tick() {
        if (paused.get()) return;
        boolean anyError = false;
        TrayUi.State worst = TrayUi.State.OK;
        String tooltip = "ok";

        for (var ref : refs) {
            try {
                var prev = states.get(ref);
                var maybe = client.latestPipeline(ref, prev == null ? null : prev.etag());
                if (maybe.isEmpty()) {
                    if (prev != null) {
                        worst = worse(worst, mapState(prev.lastStatus()));
                        tooltip = ref + ": " + prev.lastStatus();
                    }
                    continue;
                }
                var p = maybe.get();
                handleChange(ref, prev, p);
                worst = worse(worst, mapState(p.status()));
                tooltip = ref + ": " + p.status();
            } catch (Exception e) {
                anyError = true;
                LOG.log(Level.WARNING, "Ошибка опроса " + ref + ": " + e.getMessage());
            }
        }

        if (anyError) {
            consecutiveErrors++;
            ui.setState(TrayUi.State.ERROR, "ошибка опроса (×" + consecutiveErrors + ")");
        } else {
            if (consecutiveErrors > 0) {
                ui.notify("GL-Sound", "Связь с GitLab восстановлена", TrayIcon.MessageType.INFO);
            }
            consecutiveErrors = 0;
            ui.setState(worst, tooltip);
        }
    }

    private void handleChange(String ref, BranchState prev, PipelineInfo p) {
        boolean firstObservation = prev == null;
        boolean idChanged = !firstObservation && prev.lastId() != p.id();
        boolean statusChanged = !firstObservation && !prev.lastStatus().equals(p.status());

        states.put(ref, new BranchState(p.id(), p.status(), p.etag()));
        ui.setLastPipeline(p.webUrl());

        if (firstObservation) {
            return;
        }
        if (!idChanged && !statusChanged) return;

        if (p.isSuccess()) {
            sound.playSuccess();
            ui.notify("Pipeline ✓",
                    "[" + ref + "] успешно (#" + p.id() + ")",
                    TrayIcon.MessageType.INFO);
        } else if (p.isFailure()) {
            sound.playFailure();
            ui.notify("Pipeline ✗",
                    "[" + ref + "] упал (#" + p.id() + ")",
                    TrayIcon.MessageType.ERROR);
        } else if ("running".equals(p.status()) && idChanged) {
            sound.playStart();
        }
    }

    private static TrayUi.State mapState(String s) {
        return switch (s) {
            case "success"            -> TrayUi.State.OK;
            case "failed"             -> TrayUi.State.FAIL;
            case "running", "pending",
                 "preparing", "waiting_for_resource",
                 "scheduled", "created"-> TrayUi.State.RUNNING;
            default                   -> TrayUi.State.IDLE;
        };
    }

    private static TrayUi.State worse(TrayUi.State a, TrayUi.State b) {
        return rank(b) > rank(a) ? b : a;
    }

    private static int rank(TrayUi.State s) {
        return switch (s) {
            case OK      -> 0;
            case IDLE    -> 1;
            case RUNNING -> 2;
            case PAUSED  -> 3;
            case ERROR   -> 4;
            case FAIL    -> 5;
        };
    }

    @Override
    public void close() {
        exec.shutdownNow();
    }
}
