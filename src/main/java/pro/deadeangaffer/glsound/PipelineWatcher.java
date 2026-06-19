package pro.deadeangaffer.glsound;

import java.awt.TrayIcon;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PipelineWatcher implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(PipelineWatcher.class.getName());

    private record BranchState(long lastId, String lastStatus, String etag) {}
    private record BranchTick(String ref, TrayUi.State state, String tooltip, boolean errored) {}

    private final GitLabClient client;
    private final List<String> refs;
    private final int intervalSec;
    private final SoundPlayer sound;
    private final TrayUi ui;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "pipeline-watcher");
                t.setDaemon(true);
                return t;
            });
    private final ExecutorService branchPool = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, BranchState> states = new ConcurrentHashMap<>();
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
        scheduler.scheduleWithFixedDelay(this::tick, 1, intervalSec, TimeUnit.SECONDS);
    }

    private void tick() {
        if (paused.get()) return;

        List<Callable<BranchTick>> tasks = new ArrayList<>(refs.size());
        for (var ref : refs) tasks.add(() -> pollOne(ref));

        List<Future<BranchTick>> futures;
        try {
            futures = branchPool.invokeAll(tasks, Math.max(intervalSec * 2L, 30L), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        boolean anyError = false;
        TrayUi.State worst = TrayUi.State.OK;
        String tooltip = "ok";
        for (var f : futures) {
            try {
                var r = f.get();
                if (r.errored()) anyError = true;
                if (rank(r.state()) > rank(worst)) {
                    worst = r.state();
                    tooltip = r.tooltip();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                anyError = true;
                LOG.log(Level.WARNING, "Branch task failed", e);
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

    private BranchTick pollOne(String ref) {
        var prev = states.get(ref);
        try {
            var maybe = client.latestPipeline(ref, prev == null ? null : prev.etag());
            if (maybe.isEmpty()) {
                if (prev != null) return new BranchTick(ref, mapState(prev.lastStatus()), ref + ": " + prev.lastStatus(), false);
                return new BranchTick(ref, TrayUi.State.IDLE, ref + ": нет данных", false);
            }
            var p = maybe.get();
            handleChange(ref, prev, p);
            return new BranchTick(ref, mapState(p.status()), ref + ": " + p.status(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new BranchTick(ref, TrayUi.State.ERROR, ref + ": прервано", true);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Ошибка опроса " + ref + ": " + e.getMessage());
            return new BranchTick(ref, TrayUi.State.ERROR, ref + ": ошибка", true);
        }
    }

    private void handleChange(String ref, BranchState prev, PipelineInfo p) {
        boolean firstObservation = prev == null;
        boolean idChanged = !firstObservation && prev.lastId() != p.id();
        boolean statusChanged = !firstObservation && !prev.lastStatus().equals(p.status());

        states.put(ref, new BranchState(p.id(), p.status(), p.etag()));
        ui.setLastPipeline(p.webUrl());

        if (firstObservation) return;
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
        scheduler.shutdownNow();
        branchPool.shutdownNow();
    }
}
