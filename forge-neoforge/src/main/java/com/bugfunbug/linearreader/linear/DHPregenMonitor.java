package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearReader;
import com.bugfunbug.linearreader.config.LinearConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Watches server logs for Distant Horizons pregen start/stop messages and
 * applies in-memory config overrides that are never written to disk.
 *
 * <h3>Why in-memory overrides, not ForgeConfigSpec.set()</h3>
 * {@code ConfigValue.set()} persists the value to the TOML file immediately.
 * If the server crashes mid-pregen the file would be left with pregen values,
 * and on the next boot {@code savedXxx} would hold uninitialized defaults -
 * the user's real settings would be silently lost.  We avoid that entirely by
 * keeping overrides purely in memory via the {@code effective*()} accessors
 * below. All config consumers call those instead of {@code LinearConfig.*.get()}.
 *
 * <h3>Thread safety</h3>
 * {@link #PREGEN_ACTIVE} is {@code volatile} (via {@code AtomicBoolean}), so
 * the effective-value reads are always fresh without locking.  The saved fields
 * are written exactly once (in {@link #onPregenStart}) before {@code PREGEN_ACTIVE}
 * is set, guaranteeing visibility.
 *
 * <h3>Matched log lines (from MinecraftServer, not DH's own logger)</h3>
 * Start : "Starting pregen. Progress will be in the server console."
 * Stop  : "Pregen is cancelled"
 *
 * <h3>Why we log from a daemon thread, not directly from append()</h3>
 * Log4j 2 has a per-thread recursion detector in {@code Logger.log()} that
 * fires before it even invokes {@code append()} a second time, producing a
 * "Recursive call to appender" warning in the log regardless of any guard we
 * add inside {@code append()}.  Spawning a daemon thread to do the actual
 * {@code LOGGER.info()} call means that call happens on a different thread -
 * Log4j never sees recursion and no warning is emitted.  {@code append()}
 * itself returns in microseconds.
 */
public final class DHPregenMonitor {

    private DHPregenMonitor() {}

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private static final AtomicBoolean PREGEN_ACTIVE = new AtomicBoolean(false);
    /** Number of currently active Chunky tasks (one per dimension). */
    private static final AtomicInteger CHUNKY_ACTIVE_TASKS = new AtomicInteger(0);

    // Written once before PREGEN_ACTIVE flips to true; volatile for cross-thread visibility.
    private static volatile int savedCacheSize;
    private static volatile int savedRegionsPerSaveTick;

    // -------------------------------------------------------------------------
    // Pregen-mode target values
    // -------------------------------------------------------------------------

    /**
     * Region cache size during pregen.
     *
     * With 8 open regions at most, each region is evicted and flushed quickly,
     * keeping peak in-memory NBT payload to ~8 x (up to ~8 MB) instead of the
     * default 256 x that.  This is the single biggest RAM win during pregen.
     *
     * Must stay >= 1 - {@code linearGetOrCreate} calls {@code removeLast()}
     * without an isEmpty() guard.
     */
    private static final int PREGEN_CACHE_SIZE = 8;

    /**
     * Floor for {@code REGIONS_PER_SAVE_TICK} during pregen.
     * Drains the flush queue faster than DH adds to it.
     * We never lower the user's configured value, only raise it.
     */
    private static final int PREGEN_MIN_REGIONS_PER_TICK = 16;

    // -------------------------------------------------------------------------
    // Effective-value accessors
    // Call these everywhere instead of LinearConfig.*.get() directly.
    // -------------------------------------------------------------------------

    /**
     * Effective region cache size - capped at {@link #PREGEN_CACHE_SIZE} during pregen.
     */
    public static int effectiveCacheSize() {
        if (!isPregenActive()) return LinearConfig.getRegionCacheSize();
        return Math.min(savedCacheSize, PREGEN_CACHE_SIZE);
    }

    /**
     * Whether backups are currently enabled.
     * Always {@code false} during pregen - backup copies of every region file
     * would double disk writes for zero benefit while DH rewrites everything anyway.
     */
    public static boolean isBackupEnabled() {
        return !isPregenActive() && LinearConfig.isBackupEnabled();
    }

    /**
     * Effective regions-per-save-tick - floored at {@link #PREGEN_MIN_REGIONS_PER_TICK}
     * during pregen so the flush queue cannot grow unboundedly.
     */
    public static int effectiveRegionsPerSaveTick() {
        int configured = LinearConfig.getRegionsPerSaveTick();
        if (!isPregenActive()) return configured;
        return Math.max(configured, PREGEN_MIN_REGIONS_PER_TICK);
    }

    /** {@code true} while DH or Chunky pregen is active. */
    public static boolean isPregenActive() {
        return PREGEN_ACTIVE.get() || CHUNKY_ACTIVE_TASKS.get() > 0;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Registers the log-watching appender on the root logger.
     * Must be called once during mod construction so the appender is in place
     * before DH can ever log its pregen messages.
     */
    public static void install() {
        org.apache.logging.log4j.core.Logger root =
                (org.apache.logging.log4j.core.Logger) LogManager.getRootLogger();
        DHLogAppender appender = new DHLogAppender();
        appender.start();
        root.addAppender(appender);
        LinearReader.LOGGER.debug("DHPregenMonitor installed.");
    }

    /**
     * Call from {@code ServerStoppingEvent}.
     * Ensures pregen-mode is deactivated even if the server was killed before
     * DH could log a cancellation (e.g. watchdog timeout).
     *
     * Not called from inside append(), so logging here is safe.
     */
    public static void notifyServerStopping() {
        if (PREGEN_ACTIVE.compareAndSet(true, false)) {
            LinearReader.LOGGER.info(
                    "Server stopping during DH pregen — pregen-mode overrides deactivated.");
        }
        int chunkyWas = CHUNKY_ACTIVE_TASKS.getAndSet(0);
        if (chunkyWas > 0) {
            LinearReader.LOGGER.info(
                    "Server stopping with {} Chunky task(s) active — "
                            + "Chunky pregen-mode deactivated.", chunkyWas);
        }
    }

    // -------------------------------------------------------------------------
    // Internal transition helpers
    // -------------------------------------------------------------------------

    private static void onPregenStart() {
        if (!PREGEN_ACTIVE.compareAndSet(false, true)) return; // already active

        // Snapshot user values BEFORE publishing PREGEN_ACTIVE = true.
        // Any thread that sees PREGEN_ACTIVE==true is guaranteed to also see
        // these volatile writes (happens-before via the CAS + volatile chain).
        savedCacheSize          = LinearConfig.getRegionCacheSize();
        savedRegionsPerSaveTick = LinearConfig.getRegionsPerSaveTick();

        // Capture locals for the lambda — the volatile fields are safe to read
        // from the new thread too, but locals avoid any ambiguity.
        final int cacheWas = savedCacheSize;
        final int cacheNow = Math.min(savedCacheSize, PREGEN_CACHE_SIZE);
        final int rptWas   = savedRegionsPerSaveTick;
        final int rptNow   = Math.max(savedRegionsPerSaveTick, PREGEN_MIN_REGIONS_PER_TICK);

        logAsync("DH pregen started - pregen-mode active. " +
                "cacheSize " + cacheWas + " -> " + cacheNow +
                ", regionsPerSaveTick " + rptWas + " -> " + rptNow +
                ", backups suppressed.");
    }

    private static void onPregenEnd(String triggerMessage) {
        if (!PREGEN_ACTIVE.compareAndSet(true, false)) return; // wasn't active

        final String trimmed = triggerMessage.trim();
        logAsync("DH pregen ended (\"" + trimmed + "\") - " +
                "pregen-mode deactivated, original settings restored.");
    }

    private static void onChunkyTaskStart(String msg) {
        // Snapshot user values only on the first activation of anything
        boolean wasInactive = !isPregenActive();
        if (wasInactive) {
            savedCacheSize          = LinearConfig.getRegionCacheSize();
            savedRegionsPerSaveTick = LinearConfig.getRegionsPerSaveTick();
        }
        int current = CHUNKY_ACTIVE_TASKS.incrementAndGet();

        final int cacheWas = savedCacheSize;
        final int cacheNow = Math.min(savedCacheSize, PREGEN_CACHE_SIZE);
        final int rptWas   = savedRegionsPerSaveTick;
        final int rptNow   = Math.max(savedRegionsPerSaveTick, PREGEN_MIN_REGIONS_PER_TICK);
        final String trimmed = msg.trim();

        if (current == 1 && wasInactive) {
            // First activation overall — log full mode change
            logAsync("Chunky pregen started (\"" + trimmed + "\") — pregen-mode active. "
                    + "cacheSize " + cacheWas + " -> " + cacheNow
                    + ", regionsPerSaveTick " + rptWas + " -> " + rptNow
                    + ", backups suppressed.");
        } else {
            logAsync("Chunky pregen task started (" + current + " active): \"" + trimmed + "\".");
        }
    }

    private static void onChunkyTaskEnd(String msg) {
        int remaining = CHUNKY_ACTIVE_TASKS.decrementAndGet();
        if (remaining < 0) {
            CHUNKY_ACTIVE_TASKS.set(0);
            return;
        }
        final String trimmed = msg.trim();
        if (remaining == 0 && !PREGEN_ACTIVE.get()) {
            logAsync("Chunky pregen ended (\"" + trimmed + "\") — "
                    + "pregen-mode deactivated, original settings restored.");
        } else {
            logAsync("Chunky pregen task ended (" + remaining + " still active): \"" + trimmed + "\".");
        }
    }

    /**
     * Logs {@code msg} at INFO level from a short-lived daemon thread.
     *
     * This is called from inside {@link DHLogAppender#append}, and calling
     * any Log4j logger synchronously from within append() causes Log4j's own
     * per-thread recursion guard to emit a "Recursive call to appender" warning
     * regardless of any guard we add.  Handing the work to a new thread means
     * the LOGGER.info() call happens entirely outside append(), so Log4j never
     * sees recursion.
     *
     * The new thread's log message does contain the word "pregen", but it does
     * NOT contain the exact trigger strings ("Starting pregen." / "Pregen is
     * cancelled"), so the appender's filter passes it through harmlessly.
     */
    private static void logAsync(String msg) {
        Thread t = new Thread(() -> LinearReader.LOGGER.info(msg), "lr-dh-log");
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------------
    // Log4j appender
    // -------------------------------------------------------------------------

    /**
     * Appender that watches every log event for DH pregen messages.
     *
     * <p><b>Why no logger-name filter:</b> DH's "/dh pregen start" and
     * "/dh pregen stop" commands are handled by DH code, but the resulting
     * messages ("Starting pregen." / "Pregen is cancelled") are logged by
     * {@code net.minecraft.server.MinecraftServer} - not by any DH logger.
     * Filtering on logger name therefore silently missed both events.
     *
     * <p><b>Performance:</b> the fast-path bail-out on messages that don't
     * contain the ASCII substring "regen" means the vast majority of log
     * events cost only one {@code String.contains} call.  The actual
     * {@code onPregenStart} / {@code onPregenEnd} paths fire at most twice
     * per pregen session.
     *
     * <p><b>No IN_APPEND guard needed:</b> we never call any logger from
     * inside append() - see {@link #logAsync}.
     */
    private static final class DHLogAppender extends AbstractAppender {

        DHLogAppender() {
            super("LinearReader-DHPregenWatcher", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            String msg = event.getMessage().getFormattedMessage();
            if (msg == null) return;

            // DH pregen
            if (msg.contains("regen") || msg.contains("Regen")) {
                if (msg.contains("Starting pregen.")) {
                    onPregenStart();
                } else if (msg.contains("Pregen is cancelled")) {
                    onPregenEnd(msg);
                }
            }

            // Chunky pregen
            if (msg.contains("[Chunky]")) {
                if (msg.contains("Task started")) {
                    onChunkyTaskStart(msg);
                } else if (msg.contains("Task finished") || msg.contains("Task stopped")) {
                    onChunkyTaskEnd(msg);
                }
            }
        }
    }
}