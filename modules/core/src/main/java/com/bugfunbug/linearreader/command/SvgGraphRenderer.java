package com.bugfunbug.linearreader.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Produces SVG line-graph files from a completed {@link GraphSampler} recording.
 *
 * <p><b>No external dependencies</b> — SVG is built by string concatenation only.
 *
 * <h3>Output modes</h3>
 * <ul>
 *   <li><b>Per-stat</b> (default, {@code singleGraph=false}): one {@code .svg}
 *       file per recorded stat, named {@code graph-<key>-<yyyyMMdd-HHmmss>.svg}.</li>
 *   <li><b>Single graph</b> ({@code singleGraph=true}): all stats on one SVG,
 *       named {@code graph-multi-<yyyyMMdd-HHmmss>.svg}. Callers are responsible
 *       for choosing stats with compatible Y-axis units.</li>
 * </ul>
 *
 * <h3>Output location</h3>
 * {@code <worldRoot>/data/linearreader/graphs/}
 */
public final class SvgGraphRenderer {

    // ── SVG dimensions ────────────────────────────────────────────────────────

    private static final int SVG_W  = 900;
    private static final int SVG_H  = 400;

    /** Left margin — room for Y-axis tick labels. */
    private static final int M_LEFT   = 80;
    /** Right margin. */
    private static final int M_RIGHT  = 20;
    /** Top margin — room for title. */
    private static final int M_TOP    = 45;
    /** Bottom margin — room for X-axis labels and title. */
    private static final int M_BOTTOM = 55;

    private static final int PLOT_X1 = M_LEFT;
    private static final int PLOT_Y1 = M_TOP;
    private static final int PLOT_X2 = SVG_W - M_RIGHT;
    private static final int PLOT_Y2 = SVG_H - M_BOTTOM;
    private static final int PLOT_W  = PLOT_X2 - PLOT_X1;  // 800
    private static final int PLOT_H  = PLOT_Y2 - PLOT_Y1;  // 300

    /** Number of horizontal gridlines (not counting plot boundary). */
    private static final int Y_TICKS = 5;
    /** Number of vertical gridlines (not counting plot boundary). */
    private static final int X_TICKS = 5;

    // ── Color palette (8 distinct colors) ────────────────────────────────────

    private static final String[] COLORS = {
            "#4285f4", // blue
            "#ea4335", // red
            "#34a853", // green
            "#fbbc04", // yellow
            "#ff6d00", // orange
            "#46bdc6", // teal
            "#9c27b0", // purple
            "#795548", // brown
    };

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private SvgGraphRenderer() {}

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Renders SVG graphs from a completed recording.
     *
     * @param worldRoot   used to locate {@code data/linearreader/graphs/}.
     * @param stats       stats that were recorded (in order).
     * @param samples     list of {@code double[]} where index 0 is elapsed
     *                    seconds and indices 1..N are per-stat values.
     * @param singleGraph if {@code true}, all stats are placed on one SVG.
     * @return paths of the SVG files that were written.
     */
    public static List<Path> render(
            Path worldRoot,
            GraphStat[] stats,
            List<double[]> samples,
            boolean singleGraph) throws IOException {

        Path outDir = worldRoot.resolve("data/linearreader/graphs");
        Files.createDirectories(outDir);

        String ts = LocalDateTime.now().format(TIMESTAMP_FMT);
        List<Path> outputPaths = new ArrayList<>();

        if (singleGraph) {
            // All stats on one SVG.
            Path file = outDir.resolve("graph-multi-" + ts + ".svg");
            Files.writeString(file, buildMultiSvg(stats, samples));
            outputPaths.add(file);
        } else {
            // One SVG per stat.
            for (int i = 0; i < stats.length; i++) {
                Path file = outDir.resolve("graph-" + stats[i].getKey() + "-" + ts + ".svg");
                Files.writeString(file, buildSingleSvg(stats[i], i + 1, samples));
                outputPaths.add(file);
            }
        }
        return outputPaths;
    }

    // ── Per-stat SVG ──────────────────────────────────────────────────────────

    private static String buildSingleSvg(GraphStat stat, int sampleIdx, List<double[]> samples) {
        double maxT = computeMaxT(samples);
        double[] yRange = computeYRange(samples, new int[]{sampleIdx});

        StringBuilder sb = new StringBuilder();
        appendHeader(sb, stat.getLabel());
        appendGrid(sb, yRange[0], yRange[1], maxT);
        appendPolyline(sb, samples, sampleIdx, yRange[0], yRange[1], maxT, COLORS[0]);
        appendSingleLegend(sb, stat, samples, sampleIdx, COLORS[0]);
        appendXAxisLabel(sb);
        sb.append("</svg>\n");
        return sb.toString();
    }

    // ── Multi-stat SVG ────────────────────────────────────────────────────────

    private static String buildMultiSvg(GraphStat[] stats, List<double[]> samples) {
        double maxT = computeMaxT(samples);
        int[] indices = new int[stats.length];
        for (int i = 0; i < stats.length; i++) indices[i] = i + 1;
        double[] yRange = computeYRange(samples, indices);

        String title = stats.length <= 3
                ? joinLabels(stats)
                : "Multiple Stats (" + stats.length + ")";

        StringBuilder sb = new StringBuilder();
        appendHeader(sb, title);
        appendGrid(sb, yRange[0], yRange[1], maxT);

        for (int i = 0; i < stats.length; i++) {
            String color = COLORS[i % COLORS.length];
            appendPolyline(sb, samples, i + 1, yRange[0], yRange[1], maxT, color);
        }

        appendMultiLegend(sb, stats, samples, indices);
        appendXAxisLabel(sb);
        sb.append("</svg>\n");
        return sb.toString();
    }

    // ── SVG building blocks ───────────────────────────────────────────────────

    private static void appendHeader(StringBuilder sb, String title) {
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<svg viewBox=\"0 0 ").append(SVG_W).append(" ").append(SVG_H)
                .append("\" xmlns=\"http://www.w3.org/2000/svg\">\n");
        // Background
        sb.append("<rect width=\"").append(SVG_W).append("\" height=\"").append(SVG_H)
                .append("\" fill=\"white\"/>\n");
        // Title
        String escaped = xmlEscape(title);
        sb.append("<text x=\"").append(SVG_W / 2)
                .append("\" y=\"22\" text-anchor=\"middle\" font-family=\"monospace\" ")
                .append("font-size=\"14\" font-weight=\"bold\" fill=\"#222\">")
                .append(escaped).append("</text>\n");
        // Plot area border
        sb.append("<rect x=\"").append(PLOT_X1).append("\" y=\"").append(PLOT_Y1)
                .append("\" width=\"").append(PLOT_W).append("\" height=\"").append(PLOT_H)
                .append("\" fill=\"none\" stroke=\"#aaa\" stroke-width=\"1\"/>\n");
    }

    private static void appendGrid(StringBuilder sb, double yMin, double yMax, double maxT) {
        // Horizontal gridlines + Y-axis tick labels.
        for (int i = 0; i <= Y_TICKS; i++) {
            double fraction = (double) i / Y_TICKS;
            int y = PLOT_Y2 - (int) Math.round(fraction * PLOT_H);
            double value = yMin + fraction * (yMax - yMin);

            if (i > 0 && i < Y_TICKS) {
                // Inner gridline only (boundary drawn by plot rect).
                sb.append("<line x1=\"").append(PLOT_X1).append("\" y1=\"").append(y)
                        .append("\" x2=\"").append(PLOT_X2).append("\" y2=\"").append(y)
                        .append("\" stroke=\"#e0e0e0\" stroke-width=\"1\"/>\n");
            }
            // Y tick label (right-aligned, vertically centered on the tick).
            sb.append("<text x=\"").append(PLOT_X1 - 6)
                    .append("\" y=\"").append(y + 4)
                    .append("\" text-anchor=\"end\" font-family=\"monospace\" font-size=\"10\" fill=\"#555\">")
                    .append(fmtValue(value)).append("</text>\n");
        }

        // Vertical gridlines + X-axis tick labels.
        for (int i = 0; i <= X_TICKS; i++) {
            double fraction = (double) i / X_TICKS;
            int x = PLOT_X1 + (int) Math.round(fraction * PLOT_W);
            double time = fraction * maxT;

            if (i > 0 && i < X_TICKS) {
                sb.append("<line x1=\"").append(x).append("\" y1=\"").append(PLOT_Y1)
                        .append("\" x2=\"").append(x).append("\" y2=\"").append(PLOT_Y2)
                        .append("\" stroke=\"#e0e0e0\" stroke-width=\"1\"/>\n");
            }
            // X tick label.
            sb.append("<text x=\"").append(x)
                    .append("\" y=\"").append(PLOT_Y2 + 16)
                    .append("\" text-anchor=\"middle\" font-family=\"monospace\" font-size=\"10\" fill=\"#555\">")
                    .append(fmtTime(time)).append("</text>\n");
        }
    }

    private static void appendPolyline(
            StringBuilder sb,
            List<double[]> samples,
            int sampleIdx,
            double yMin, double yMax,
            double maxT,
            String color) {

        if (samples.isEmpty()) return;

        sb.append("<polyline fill=\"none\" stroke=\"").append(color)
                .append("\" stroke-width=\"1.5\" stroke-linejoin=\"round\" points=\"");

        boolean first = true;
        for (double[] sample : samples) {
            double t = sample[0];
            double v = sample[sampleIdx];
            if (Double.isNaN(v)) continue;

            int x = PLOT_X1 + (int) Math.round(maxT > 0 ? (t / maxT) * PLOT_W : 0);
            int y = PLOT_Y2 - (int) Math.round(
                    (yMax - yMin) > 0 ? ((v - yMin) / (yMax - yMin)) * PLOT_H : PLOT_H / 2.0);

            // Clamp to plot area.
            x = clamp(x, PLOT_X1, PLOT_X2);
            y = clamp(y, PLOT_Y1, PLOT_Y2);

            if (!first) sb.append(' ');
            sb.append(x).append(',').append(y);
            first = false;
        }

        sb.append("\"/>\n");
    }

    private static void appendSingleLegend(
            StringBuilder sb,
            GraphStat stat,
            List<double[]> samples,
            int sampleIdx,
            String color) {

        double finalVal = lastNonNaN(samples, sampleIdx);
        int lx = PLOT_X2 - 205;
        int ly = PLOT_Y1 + 10;

        sb.append("<rect x=\"").append(lx - 5).append("\" y=\"").append(ly - 3)
                .append("\" width=\"210\" height=\"20\" rx=\"3\" fill=\"white\" ")
                .append("stroke=\"#ccc\" stroke-width=\"1\"/>\n");

        // Color swatch.
        sb.append("<line x1=\"").append(lx).append("\" y1=\"").append(ly + 7)
                .append("\" x2=\"").append(lx + 20).append("\" y2=\"").append(ly + 7)
                .append("\" stroke=\"").append(color).append("\" stroke-width=\"2\"/>\n");

        // Label + final value.
        String text = xmlEscape(stat.getKey()) + ": " + fmtValue(finalVal);
        sb.append("<text x=\"").append(lx + 24).append("\" y=\"").append(ly + 11)
                .append("\" font-family=\"monospace\" font-size=\"10\" fill=\"#222\">")
                .append(text).append("</text>\n");
    }

    private static void appendMultiLegend(
            StringBuilder sb,
            GraphStat[] stats,
            List<double[]> samples,
            int[] indices) {

        int maxVisible = Math.min(stats.length, 8);
        int boxH = maxVisible * 18 + 8;
        int lx = PLOT_X2 - 205;
        int ly = PLOT_Y1 + 8;

        sb.append("<rect x=\"").append(lx - 5).append("\" y=\"").append(ly - 3)
                .append("\" width=\"210\" height=\"").append(boxH)
                .append("\" rx=\"3\" fill=\"white\" stroke=\"#ccc\" stroke-width=\"1\"/>\n");

        for (int i = 0; i < maxVisible; i++) {
            String color = COLORS[i % COLORS.length];
            double finalVal = lastNonNaN(samples, indices[i]);
            int rowY = ly + 12 + i * 18;

            // Color swatch.
            sb.append("<line x1=\"").append(lx).append("\" y1=\"").append(rowY)
                    .append("\" x2=\"").append(lx + 18).append("\" y2=\"").append(rowY)
                    .append("\" stroke=\"").append(color).append("\" stroke-width=\"2\"/>\n");

            String text = xmlEscape(stats[i].getKey()) + ": " + fmtValue(finalVal);
            sb.append("<text x=\"").append(lx + 22).append("\" y=\"").append(rowY + 4)
                    .append("\" font-family=\"monospace\" font-size=\"10\" fill=\"#222\">")
                    .append(text).append("</text>\n");
        }

        if (stats.length > maxVisible) {
            int rowY = ly + 12 + maxVisible * 18;
            sb.append("<text x=\"").append(lx + 5).append("\" y=\"").append(rowY)
                    .append("\" font-family=\"monospace\" font-size=\"10\" fill=\"#888\">+")
                    .append(stats.length - maxVisible).append(" more</text>\n");
        }
    }

    private static void appendXAxisLabel(StringBuilder sb) {
        sb.append("<text x=\"").append(PLOT_X1 + PLOT_W / 2)
                .append("\" y=\"").append(SVG_H - 6)
                .append("\" text-anchor=\"middle\" font-family=\"monospace\" font-size=\"11\" fill=\"#555\">")
                .append("Time (s)</text>\n");
    }

    // ── Range computation ─────────────────────────────────────────────────────

    private static double computeMaxT(List<double[]> samples) {
        double max = 1.0;
        for (double[] s : samples) {
            if (s[0] > max) max = s[0];
        }
        return max;
    }

    /**
     * Computes padded [yMin, yMax] for the given stat indices across all samples.
     * Handles the flat-line case as specified: ±10% of value, or ±1 if value is 0.
     */
    private static double[] computeYRange(List<double[]> samples, int[] statIndices) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;

        for (double[] sample : samples) {
            for (int idx : statIndices) {
                double v = sample[idx];
                if (!Double.isNaN(v) && !Double.isInfinite(v)) {
                    if (v < min) min = v;
                    if (v > max) max = v;
                }
            }
        }

        // No valid data.
        if (min == Double.MAX_VALUE) {
            return new double[]{-1.0, 1.0};
        }

        double range = max - min;
        if (range < 1e-10) {
            // Flat line: pad ±10% of value, or ±1 if value is 0.
            double pad = Math.abs(max) * 0.10;
            if (pad < 1.0) pad = 1.0;
            double yMin = min - pad;
            double yMax = max + pad;
            // Don't go negative when all data is non-negative.
            if (min >= 0 && yMin < 0) yMin = 0;
            return new double[]{yMin, yMax};
        } else {
            // Normal case: 5% padding each side.
            double pad = range * 0.05;
            double yMin = min - pad;
            double yMax = max + pad;
            // Don't go negative when all data is non-negative.
            if (min >= 0 && yMin < 0) yMin = 0;
            return new double[]{yMin, yMax};
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static double lastNonNaN(List<double[]> samples, int idx) {
        for (int i = samples.size() - 1; i >= 0; i--) {
            double v = samples.get(i)[idx];
            if (!Double.isNaN(v)) return v;
        }
        return Double.NaN;
    }

    private static String fmtValue(double v) {
        if (Double.isNaN(v)) return "N/A";
        if (Double.isInfinite(v)) return v > 0 ? "+∞" : "-∞";
        double abs = Math.abs(v);
        if (abs >= 10_000) return String.format(Locale.ROOT, "%.0f", v);
        if (abs >= 100)    return String.format(Locale.ROOT, "%.1f", v);
        if (abs >= 1)      return String.format(Locale.ROOT, "%.2f", v);
        if (abs >= 0.01)   return String.format(Locale.ROOT, "%.3f", v);
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static String fmtTime(double seconds) {
        if (seconds < 60) return String.format(Locale.ROOT, "%.0fs", seconds);
        long m = (long) (seconds / 60);
        long s = (long) seconds % 60;
        return s == 0 ? m + "m" : m + "m" + s + "s";
    }

    private static String joinLabels(GraphStat[] stats) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stats.length; i++) {
            if (i > 0) sb.append(" / ");
            sb.append(stats[i].getLabel());
        }
        return sb.toString();
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}