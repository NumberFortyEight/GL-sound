package pro.deadeangaffer.glsound;

import java.awt.TrayIcon;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.Comparator;
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
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "pipeline-watcher");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService branchPool = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, BranchState> states = new ConcurrentHashMap<>();
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private int consecutiveErrors = 0;

    public PipelineWatcher(GitLabClient client, List<String> refs, int intervalSec, SoundPlayer sound, TrayUi ui) {
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

        List<Callable<BranchTick>> tasks = refs.stream()
                .<Callable<BranchTick>>map(ref -> () -> pollOne(ref))
                .toList();
        List<Future<BranchTick>> futures;
        try {
            futures = branchPool.invokeAll(tasks, Math.max(intervalSec * 2L, 30L), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        List<Optional<BranchTick>> results = futures.stream().map(this::resultOf).toList();
        boolean anyError = results.stream().anyMatch(r -> r.map(BranchTick::errored).orElse(true));
        BranchTick worst = results.stream()
                .flatMap(Optional::stream)
                .max(Comparator.comparingInt(t -> rank(t.state())))
                .orElse(new BranchTick("", TrayUi.State.OK, "ok", false));

        if (anyError) {
            consecutiveErrors++;
            ui.setState(TrayUi.State.ERROR, "ошибка опроса (×%d)".formatted(consecutiveErrors));
            return;
        }
        if (consecutiveErrors > 0) ui.notify("GL-Sound", "Связь с GitLab восстановлена", TrayIcon.MessageType.INFO);
        consecutiveErrors = 0;
        ui.setState(worst.state(), worst.tooltip());
    }

    private Optional<BranchTick> resultOf(Future<BranchTick> f) {
        try {
            return Optional.of(f.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException e) {
            LOG.log(Level.WARNING, "Branch task failed", e.getCause());
            return Optional.empty();
        }
    }

    private BranchTick pollOne(String ref) {
        BranchState prev = states.get(ref);
        try {
            Optional<PipelineInfo> maybe = client.latestPipeline(ref, prev == null ? null : prev.etag());
            if (maybe.isEmpty()) {
                if (prev == null) return new BranchTick(ref, TrayUi.State.IDLE, "%s: нет данных".formatted(ref), false);
                return new BranchTick(ref, mapState(prev.lastStatus()), "%s: %s".formatted(ref, prev.lastStatus()), false);
            }
            PipelineInfo p = maybe.get();
            handleChange(ref, prev, p);
            return new BranchTick(ref, mapState(p.status()), "%s: %s".formatted(ref, p.status()), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new BranchTick(ref, TrayUi.State.ERROR, "%s: прервано".formatted(ref), true);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Ошибка опроса %s: %s".formatted(ref, e.getMessage()));
            return new BranchTick(ref, TrayUi.State.ERROR, "%s: ошибка".formatted(ref), true);
        }
    }

    private void handleChange(String ref, BranchState prev, PipelineInfo p) {
        states.put(ref, new BranchState(p.id(), p.status(), p.etag()));
        ui.setLastPipeline(p.webUrl());
        if (prev == null) return;
        boolean idChanged = prev.lastId() != p.id();
        boolean statusChanged = !prev.lastStatus().equals(p.status());
        if (!idChanged && !statusChanged) return;
        if (p.isSuccess()) {
            sound.playSuccess();
            ui.notify("Pipeline ✓", "[%s] успешно (#%d)".formatted(ref, p.id()), TrayIcon.MessageType.INFO);
            return;
        }
        if (p.isFailure()) {
            sound.playFailure();
            ui.notify("Pipeline ✗", "[%s] упал (#%d)".formatted(ref, p.id()), TrayIcon.MessageType.ERROR);
            return;
        }
        if ("running".equals(p.status()) && idChanged) sound.playStart();
    }

    private static TrayUi.State mapState(String s) {
        return switch (s) {
            case "success" -> TrayUi.State.OK;
            case "failed" -> TrayUi.State.FAIL;
            case "running", "pending", "preparing", "waiting_for_resource", "scheduled", "created" -> TrayUi.State.RUNNING;
            default -> TrayUi.State.IDLE;
        };
    }

    private static int rank(TrayUi.State s) {
        return switch (s) {
            case OK -> 0;
            case IDLE -> 1;
            case RUNNING -> 2;
            case PAUSED -> 3;
            case ERROR -> 4;
            case FAIL -> 5;
        };
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        branchPool.shutdownNow();
    }
}
