package com.bugfunbug.linearreader.linear;

/**
 * Encodes/decodes which compression algorithm (and level/quality) a
 * .linear file's single header "compressionLevel" byte (offset 17)
 * represents, WITHOUT changing that byte's size or the outer header
 * layout at all - so files written before Brotli support existed keep
 * decoding correctly with zero special-casing.
 *
 * <h3>Encoding</h3>
 * <ul>
 *   <li>1-22   -&gt; Zstd, level = the value itself (exactly what every
 *       .linear file written before this existed already contains -
 *       unambiguously Zstd, no migration needed).</li>
 *   <li>101-111 -&gt; Brotli, quality = value - 100 (Brotli quality is
 *       0-11; 0 is deliberately never written by LinearReader itself
 *       since it is a poor quality/ratio tradeoff for a cold-storage
 *       tier, but is still decodable for completeness).</li>
 * </ul>
 *
 * Every other byte value is invalid and treated as corruption by
 * {@link #decode}, the same way an out-of-range value already would be
 * today.
 *
 * <h3>Compression hierarchy</h3>
 * LinearReader's cold-storage compression hierarchy, worst to best, is:
 * vanilla zlib (not represented by this class at all - only ever seen
 * transiently during .mca-&gt;.linear conversion) &lt; low Zstd (today's
 * live-write default, e.g. level 4) &lt; high Zstd (level 22, the idle
 * recompressor's Zstd target) &lt; Brotli (quality 11 - the "king" tier;
 * always slower than Zstd, but always the smallest output LinearReader can
 * produce). Brotli ALWAYS outranks every Zstd level regardless of quality,
 * and this must never be silently violated: a Brotli-compressed region must
 * never be downgraded back to Zstd by the idle recompressor or the backup
 * path just because a config value points at Zstd - see
 * {@link #isAlreadyAsGoodAs}. The only thing that may ever turn a
 * Brotli-compressed region back into Zstd is a live write
 * (LinearRegionFile.writeToDisk always uses fast Zstd unconditionally,
 * regardless of what algorithm was on disk before - that is a deliberate,
 * different path, not a "recompression" decision at all).
 */
public final class CompressionAlgorithm {

    private static final int BROTLI_OFFSET = 100;

    /** Highest Brotli quality LinearReader will ever write for cold storage. */
    public static final int BROTLI_QUALITY = 11;

    /** Highest Zstd level LinearReader will ever write for cold storage. */
    public static final int ZSTD_LEVEL = 22;

    /** Added to a Brotli quality to get its rank, guaranteeing it always exceeds any Zstd rank (max 22). */
    private static final int BROTLI_RANK_OFFSET = 1000;

    public enum Algorithm {
        ZSTD,
        BROTLI
    }

    /** (algorithm, level/quality) pair decoded from a header byte. */
    public record Encoded(Algorithm algorithm, int levelOrQuality) {

        /** True if this represents "already at the target cold-storage setting" for its OWN algorithm. */
        boolean isAtColdStorageTarget() {
            return switch (algorithm) {
                case ZSTD -> levelOrQuality >= ZSTD_LEVEL;
                case BROTLI -> levelOrQuality >= BROTLI_QUALITY;
            };
        }

        /**
         * A single comparable "how good is this compression" number. Brotli
         * ALWAYS ranks above every Zstd level, regardless of Brotli quality -
         * see this class's compression-hierarchy javadoc for why that must
         * hold. Within one algorithm, a higher level/quality is simply better,
         * same as comparing plain integers.
         */
        int rank() {
            return algorithm == Algorithm.BROTLI ? BROTLI_RANK_OFFSET + levelOrQuality : levelOrQuality;
        }
    }

    private CompressionAlgorithm() {
    }

    /** Encodes a Zstd level (1-22) into the single header byte value. */
    public static byte encodeZstd(int level) {
        if (level < 1 || level > ZSTD_LEVEL) {
            throw new IllegalArgumentException("Zstd level out of range 1-" + ZSTD_LEVEL + ": " + level);
        }
        return (byte) level;
    }

    /** Encodes a Brotli quality (0-11) into the single header byte value. */
    public static byte encodeBrotli(int quality) {
        if (quality < 0 || quality > BROTLI_QUALITY) {
            throw new IllegalArgumentException("Brotli quality out of range 0-" + BROTLI_QUALITY + ": " + quality);
        }
        return (byte) (BROTLI_OFFSET + quality);
    }

    /**
     * Decodes a raw header byte (already unsigned-masked with {@code & 0xFF}
     * by the caller, matching every existing read site's convention) into
     * an algorithm + level/quality pair.
     *
     * @throws IllegalArgumentException if the byte is not a value LinearReader
     *                                  ever writes (i.e. the file is corrupt,
     *                                  or from a future version using a range
     *                                  this version doesn't know about).
     */
    public static Encoded decode(int unsignedHeaderByte) {
        if (unsignedHeaderByte >= 1 && unsignedHeaderByte <= ZSTD_LEVEL) {
            return new Encoded(Algorithm.ZSTD, unsignedHeaderByte);
        }
        int brotliQuality = unsignedHeaderByte - BROTLI_OFFSET;
        if (brotliQuality >= 0 && brotliQuality <= BROTLI_QUALITY) {
            return new Encoded(Algorithm.BROTLI, brotliQuality);
        }
        throw new IllegalArgumentException(
                "[LinearReader] Unrecognized compression algorithm/level byte: " + unsignedHeaderByte);
    }

    /**
     * True if {@code current} is already at least as good as recompressing
     * to (targetAlgorithm, targetLevelOrQuality) would achieve, per the fixed
     * Zstd-&lt;-Brotli hierarchy - i.e. recompressing would NOT be an upgrade
     * and must be skipped. This is the ONLY correct way to decide whether to
     * recompress; comparing algorithms for exact equality first (as an
     * earlier draft of this class's caller did) is wrong, because it lets a
     * Brotli-compressed file get "recompressed" down to Zstd the moment a
     * config value points at Zstd instead of correctly recognizing Brotli is
     * already better than any Zstd target.
     */
    public static boolean isAlreadyAsGoodAs(Encoded current, Algorithm targetAlgorithm, int targetLevelOrQuality) {
        int targetRank = targetAlgorithm == Algorithm.BROTLI
                ? BROTLI_RANK_OFFSET + targetLevelOrQuality
                : targetLevelOrQuality;
        return current.rank() >= targetRank;
    }
}