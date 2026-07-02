package com.bugfunbug.linearreader.command;

import com.bugfunbug.linearreader.LinearRuntime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Records a time-series of {@link GraphStat} values on a background daemon
 * thread and renders SVG output files on completion.
 *
 * <h3>Lifecycle per run</h3>
 * <ol>
 *   <li><b>Warmup</b> ({@value #WARMUP_SECONDS}s) — LinearStats accumulates
 *       real data; {@link GraphStat#resetDeltaState()} is called at the very
 *       start so that per-interval stats capture a clean baseline over the
 *       warmup window rather than leftover state from previous sessions.
 *       The first {@code sample()} call at the end of warmup captures the
 *       baseline snapshot and returns {@link Double#NaN} for every per-interval
 *       stat, which the SVG renderer skips.</li>
 *   <li><b>Recording</b> — one sample every {@code intervalSeconds}.  Duration
 *       counts from the end of warmup, so a 200-second recording produces
 *       ~200 seconds of data regardless of warmup length.</li>
 *   <li><b>Render</b> — {@link SvgGraphRenderer} produces SVG files and the
 *       completion callback is fired.</li>
 * </ol>
 *
 * <p>Modelled structurally after {@code IdleRecompressor}: daemon thread,
 * {@link AtomicBoolean} RUNNING guard, static start/stop lifecycle.
 */
public final class GraphSampler {

    private GraphSampler() {}

    /** Seconds to wait before the first sample is taken. */
    public static final int WARMUP_SECONDS = 5;

    // ── State ─────────────────────────────────────────────────────────────────

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile Thread worker = null;
    private static volatile String lastError = "";

    // Parameters for the current (or last completed) run.
    private static volatile Set<GraphStat> activeStats = Collections.emptySet();
    private static volatile int activeIntervalSeconds = 10;
    private static volatile int activeDurationSeconds = 300;
    private static volatile boolean activeSingleGraph = false;

    /**
     * Wall-clock time the recording phase started (i.e. after warmup).
     * 0 while warming up or not running.
     */
    private static volatile long recordingStartMs = 0L;

    /** Wall-clock time {@link #start} was called (warmup begins here). */
    private static volatile long runStartMs = 0L;

    /** True while the warmup phase is in progress. */
    private static volatile boolean warmingUp = false;

    private static final AtomicInteger SAMPLES_COLLECTED = new AtomicInteger(0);

    /** Samples written by the sampler thread only. */
    private static volatile List<double[]> collectedSamples = new ArrayList<>();

    /** Paths produced by the most recently completed run. */
    private static volatile List<Path> lastOutputPaths = List.of();

    // ── Completion callback ───────────────────────────────────────────────────

    @FunctionalInterface
    public interface CompletionCallback {
        void onComplete(List<Path> outputPaths, String error);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public static boolean isRunning()             { return RUNNING.get(); }
    public static boolean isWarmingUp()           { return warmingUp; }
    public static int samplesCollected()          { return SAMPLES_COLLECTED.get(); }
    public static String lastError()              { return lastError; }
    public static Set<GraphStat> getActiveStats() { return activeStats; }
    public static int getIntervalSeconds()        { return activeIntervalSeconds; }
    public static int getDurationSeconds()        { return activeDurationSeconds; }
    public static boolean isSingleGraph()         { return activeSingleGraph; }
    public static List<Path> getLastOutputPaths() { return lastOutputPaths; }

    /**
     * Elapsed recording seconds (warmup time is not counted).
     * Returns 0 while warming up.
     */
    public static double elapsedSeconds() {
        long start = recordingStartMs;
        return start == 0L ? 0.0 : (System.currentTimeMillis() - start) / 1000.0;
    }

    /**
     * Seconds remaining in the warmup phase, or 0 if recording has started.
     */
    public static double warmupRemainingSeconds() {
        if (!warmingUp) return 0.0;
        long started = runStartMs;
        if (started == 0L) return 0.0;
        double elapsed = (System.currentTimeMillis() - started) / 1000.0;
        return Math.max(0.0, WARMUP_SECONDS - elapsed);
    }

    /**
     * Starts a new recording. Returns {@code false} if already running.
     *
     * @param stats           stats to record; must be non-empty.
     * @param intervalSeconds time between samples (clamped to ≥ 1 s).
     * @param durationSeconds total recording length after warmup, or -1
     *                        for until-stopped mode.
     * @param singleGraph     if {@code true}, all stats are rendered on one SVG.
     * @param worldRoot       world root used to resolve the output directory.
     * @param callback        invoked on the sampler thread when the run ends.
     */
    public static boolean start(
            Set<GraphStat> stats,
            int intervalSeconds,
            int durationSeconds,
            boolean singleGraph,
            Path worldRoot,
            CompletionCallback callback) {

        if (stats == null || stats.isEmpty()) return false;
        if (!RUNNING.compareAndSet(false, true)) return false;

        // Reset per-run state.
        SAMPLES_COLLECTED.set(0);
        lastError = "";
        lastOutputPaths = List.of();
        runStartMs = System.currentTimeMillis();
        recordingStartMs = 0L;
        warmingUp = true;
        collectedSamples = new ArrayList<>();
        activeStats = Collections.unmodifiableSet(EnumSet.copyOf(stats));
        activeIntervalSeconds = Math.max(1, intervalSeconds);
        activeDurationSeconds = durationSeconds;
        activeSingleGraph = singleGraph;

        // Reset delta baselines before warmup begins so per-interval stats
        // measure deltas from a clean slate.
        GraphStat.resetDeltaState();

        final GraphStat[] statsArray = stats.toArray(new GraphStat[0]);
        final int interval = activeIntervalSeconds;
        final int duration = durationSeconds;

        Thread t = new Thread(() -> {
            List<Path> outputPaths = List.of();
            String error = null;
            try {
                doSampling(statsArray, interval, duration, worldRoot);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // stop() was called — render whatever we have.
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                LinearRuntime.LOGGER.warn("[LinearReader] GraphSampler error: {}", error, e);
            }

            // Render whatever was collected.
            List<double[]> captured = collectedSamples;
            if (!captured.isEmpty() && worldRoot != null) {
                try {
                    outputPaths = SvgGraphRenderer.render(
                            worldRoot, statsArray, captured, singleGraph);
                    lastOutputPaths = outputPaths;
                } catch (IOException renderEx) {
                    String renderErr = "Render failed: " + renderEx.getMessage();
                    LinearRuntime.LOGGER.warn("[LinearReader] GraphSampler render error: {}",
                            renderEx.getMessage(), renderEx);
                    if (error == null) error = renderErr;
                }
            }

            warmingUp = false;
            recordingStartMs = 0L;
            if (error != null) lastError = error;
            RUNNING.set(false);
            worker = null;

            // Fire callback after RUNNING is cleared.
            if (callback != null) {
                try {
                    final List<Path> finalPaths = outputPaths;
                    final String finalError = error;
                    callback.onComplete(finalPaths, finalError);
                } catch (Exception e) {
                    LinearRuntime.LOGGER.warn("[LinearReader] GraphSampler callback error: {}",
                            e.getMessage(), e);
                }
            }
        }, "lr-graph-sampler");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        worker = t;
        t.start();
        return true;
    }

    /**
     * Stops an in-progress recording. The sampler renders collected samples
     * before exiting. No-op if not running.
     */
    public static void stop() {
        Thread w = worker;
        if (w != null) w.interrupt();
    }

    // ── Core sampling loop ────────────────────────────────────────────────────

    private static void doSampling(
            GraphStat[] stats,
            int intervalSeconds,
            int durationSeconds,
            Path worldRoot) throws InterruptedException {

        // ── Warmup phase ─────────────────────────────────────────────────────
        // Sleep for WARMUP_SECONDS so LinearStats can accumulate real data.
        // If we're interrupted during warmup, we simply have no samples to render.
        Thread.sleep(WARMUP_SECONDS * 1000L);

        // Take the baseline sample — this call sets the delta-state snapshot
        // for all per-interval stats and returns NaN, which we intentionally
        // discard (don't add to collectedSamples).
        for (GraphStat stat : stats) {
            stat.sample(); // baseline capture; return value is always NaN for delta stats
        }

        // Recording phase starts now.
        long recordingStart = System.currentTimeMillis();
        recordingStartMs = recordingStart;
        warmingUp = false;

        long intervalMs = intervalSeconds * 1000L;
        long endMs = durationSeconds < 0
                ? Long.MAX_VALUE
                : recordingStart + (long) durationSeconds * 1000L;

        while (!Thread.currentThread().isInterrupted()) {
            long nowMs = System.currentTimeMillis();
            if (nowMs >= endMs) break;

            // Sleep until the next sample window opens.
            long sleepMs = intervalMs - (nowMs - recordingStart) % intervalMs;
            if (sleepMs > 0) {
                Thread.sleep(sleepMs);
            }

            nowMs = System.currentTimeMillis();
            if (nowMs >= endMs) break;

            // Collect one sample.
            double elapsed = (nowMs - recordingStart) / 1000.0;
            double[] sample = new double[stats.length + 1];
            sample[0] = elapsed;
            for (int i = 0; i < stats.length; i++) {
                sample[i + 1] = stats[i].sample();
            }
            collectedSamples.add(sample);
            SAMPLES_COLLECTED.incrementAndGet();
        }
    }

    /**
     * Returns an unmodifiable snapshot of the samples collected so far.
     * Each {@code double[]} has the format:
     * {@code [elapsedSeconds, stat0, stat1, ...]}.
     */
    public static List<double[]> getSamples() {
        return Collections.unmodifiableList(new ArrayList<>(collectedSamples));
    }
}