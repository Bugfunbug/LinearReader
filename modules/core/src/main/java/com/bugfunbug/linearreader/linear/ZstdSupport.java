package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearRuntime;
import com.bugfunbug.linearreader.LinearStats;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public final class ZstdSupport {

    private static final String EMBEDDED_JAR_RESOURCE = "/META-INF/linearreader-libs/zstd-jni.jar";
    private static volatile Bridge bridge;
    private static volatile ZstdUnavailableException unavailableException;
    private static volatile Throwable testFailure;

    private ZstdSupport() {
    }

    public static void ensureAvailable() {
        bridge();
    }

    static long compressBound(long srcSize) {
        return bridge().compressBound(srcSize);
    }

    static long compress(byte[] dst, byte[] src, int level) {
        return bridge().compress(dst, src, level);
    }

    static long compress(byte[] dst, int dstOff, int dstLen, byte[] src, int srcOff, int srcLen, int level) {
        return bridge().compress(dst, dstOff, dstLen, src, srcOff, srcLen, level);
    }

    static long compressStream(byte[] dst, int dstOff, int dstLen, byte[] src, int srcOff, int srcLen, int level) {
        return bridge().compressStream(dst, dstOff, dstLen, src, srcOff, srcLen, level, Bridge.STREAM_SLICE_BYTES);
    }

    static long compressStream(byte[] dst, int dstOff, int dstLen, byte[] src, int srcOff, int srcLen,
                               int level, int sliceBytes) {
        return bridge().compressStream(dst, dstOff, dstLen, src, srcOff, srcLen, level, sliceBytes);
    }

    static long decompressedSize(byte[] src) {
        return bridge().decompressedSize(src);
    }

    static long decompressedSize(byte[] src, int srcOff, int srcLen) {
        return bridge().decompressedSize(src, srcOff, srcLen);
    }

    static long decompress(byte[] dst, byte[] src) {
        return bridge().decompress(dst, src);
    }

    static long decompress(byte[] dst, int dstOff, int dstLen, byte[] src, int srcOff, int srcLen) {
        return bridge().decompress(dst, dstOff, dstLen, src, srcOff, srcLen);
    }

    static boolean isError(long code) {
        return bridge().isError(code);
    }

    static String getErrorName(long code) {
        return bridge().getErrorName(code);
    }

    private static Bridge bridge() {
        Throwable injectedFailure = testFailure;
        if (injectedFailure != null) {
            throw unavailable(injectedFailure);
        }

        Bridge current = bridge;
        if (current != null) {
            return current;
        }

        ZstdUnavailableException cachedFailure = unavailableException;
        if (cachedFailure != null) {
            throw cachedFailure;
        }

        synchronized (ZstdSupport.class) {
            injectedFailure = testFailure;
            if (injectedFailure != null) {
                throw unavailable(injectedFailure);
            }

            current = bridge;
            if (current == null) {
                ZstdUnavailableException priorFailure = unavailableException;
                if (priorFailure != null) {
                    throw priorFailure;
                }
                try {
                    current = loadBridge();
                    bridge = current;
                } catch (Throwable t) {
                    throw unavailable(t);
                }
            }
            return current;
        }
    }

    static void setTestFailure(Throwable failure) {
        testFailure = failure;
    }

    static void clearTestFailure() {
        testFailure = null;
        unavailableException = null;
    }

    private static ZstdUnavailableException unavailable(Throwable cause) {
        if (cause instanceof ZstdUnavailableException unavailable) {
            unavailableException = unavailable;
            return unavailable;
        }

        ZstdUnavailableException cached = unavailableException;
        if (cached != null && sameFailure(cached.getCause(), cause)) {
            return cached;
        }

        StringBuilder message = new StringBuilder(
                "[LinearReader] Fatal startup incompatibility: zstd-jni could not be initialized. "
                        + "LinearReader cannot safely load worlds because .linear region data would not be writable."
        );
        if (looksLikeAndroidFcl(cause)) {
            message.append(" Android/FCL runtime detected; the embedded zstd-jni library is not supported there.");
        }

        ZstdUnavailableException failure = new ZstdUnavailableException(message.toString(), cause);
        unavailableException = failure;
        return failure;
    }

    private static boolean sameFailure(Throwable left, Throwable right) {
        return left == right || (left != null && right != null
                && left.getClass() == right.getClass()
                && String.valueOf(left.getMessage()).equals(String.valueOf(right.getMessage())));
    }

    private static boolean looksLikeAndroidFcl(Throwable cause) {
        StringBuilder haystack = new StringBuilder();
        appendEnvHint(haystack, System.getProperty("java.vendor"));
        appendEnvHint(haystack, System.getProperty("java.vm.vendor"));
        appendEnvHint(haystack, System.getProperty("java.runtime.name"));
        appendEnvHint(haystack, System.getProperty("java.library.path"));
        appendEnvHint(haystack, System.getProperty("java.class.path"));

        Throwable current = cause;
        while (current != null) {
            appendEnvHint(haystack, current.getClass().getName());
            appendEnvHint(haystack, current.getMessage());
            current = current.getCause();
        }

        String normalized = haystack.toString().toLowerCase(Locale.ROOT);
        return normalized.contains("android")
                || normalized.contains("com.tungsten.fcl")
                || normalized.contains("fclauncher")
                || normalized.contains("pojav");
    }

    private static void appendEnvHint(StringBuilder haystack, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!haystack.isEmpty()) {
            haystack.append(' ');
        }
        haystack.append(value);
    }

    private static Bridge loadBridge() {
        try {
            Path extractedJar = extractEmbeddedJar();
            URLClassLoader loader = new URLClassLoader(
                    new URL[]{extractedJar.toUri().toURL()},
                    ZstdSupport.class.getClassLoader()
            );
            Class<?> zstdClass = Class.forName("com.github.luben.zstd.Zstd", true, loader);
            Class<?> compressCtxClass = Class.forName("com.github.luben.zstd.ZstdCompressCtx", true, loader);
            Class<?> decompressCtxClass = Class.forName("com.github.luben.zstd.ZstdDecompressCtx", true, loader);

            MethodHandles.Lookup lookup = MethodHandles.publicLookup();

            MethodHandle compressBoundHandle = lookup.unreflect(
                            zstdClass.getMethod("compressBound", long.class))
                    .asType(MethodType.methodType(long.class, long.class));
            MethodHandle decompressedSizeHandle = lookup.unreflect(
                            zstdClass.getMethod("decompressedSize", byte[].class))
                    .asType(MethodType.methodType(long.class, byte[].class));
            MethodHandle decompressedSizeSliceHandle = lookup.unreflect(
                            zstdClass.getMethod("decompressedSize", byte[].class, int.class, int.class))
                    .asType(MethodType.methodType(long.class, byte[].class, int.class, int.class));
            MethodHandle isErrorHandle = lookup.unreflect(
                            zstdClass.getMethod("isError", long.class))
                    .asType(MethodType.methodType(boolean.class, long.class));
            MethodHandle getErrorNameHandle = lookup.unreflect(
                            zstdClass.getMethod("getErrorName", long.class))
                    .asType(MethodType.methodType(String.class, long.class));
            MethodHandle compressCtxCtorHandle = lookup.unreflectConstructor(
                            compressCtxClass.getConstructor())
                    .asType(MethodType.methodType(Object.class));
            MethodHandle compressCtxSetLevelHandle = lookup.unreflect(
                            compressCtxClass.getMethod("setLevel", int.class))
                    .asType(MethodType.methodType(void.class, Object.class, int.class));
            MethodHandle compressCtxCompressByteArrayHandle = lookup.unreflect(
                            compressCtxClass.getMethod("compressByteArray",
                                    byte[].class, int.class, int.class,
                                    byte[].class, int.class, int.class))
                    .asType(MethodType.methodType(long.class, Object.class,
                            byte[].class, int.class, int.class,
                            byte[].class, int.class, int.class));
            MethodHandle decompressCtxCtorHandle = lookup.unreflectConstructor(
                            decompressCtxClass.getConstructor())
                    .asType(MethodType.methodType(Object.class));
            MethodHandle decompressCtxDecompressByteArrayHandle = lookup.unreflect(
                            decompressCtxClass.getMethod("decompressByteArray",
                                    byte[].class, int.class, int.class,
                                    byte[].class, int.class, int.class))
                    .asType(MethodType.methodType(long.class, Object.class,
                            byte[].class, int.class, int.class,
                            byte[].class, int.class, int.class));

            // Streaming compression - lets a single region's compress() call be broken into
            // several smaller native calls that together produce ONE continuous zstd frame
            // (identical output/ratio to one-shot compress()). This bounds how long any single
            // JNI call can hold a critical section on a large array, instead of one call
            // holding it for the ENTIRE region body (up to ~45MB+) at once.
            Class<?> endDirectiveClass = Class.forName("com.github.luben.zstd.EndDirective", true, loader);
            MethodHandle compressCtxStreamHandle = lookup.unreflect(
                            compressCtxClass.getMethod("compressByteBufferStream",
                                    java.nio.ByteBuffer.class, java.nio.ByteBuffer.class, endDirectiveClass))
                    .asType(MethodType.methodType(boolean.class, Object.class,
                            java.nio.ByteBuffer.class, java.nio.ByteBuffer.class, Object.class));
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object endDirectiveContinue = Enum.valueOf((Class<Enum>) endDirectiveClass.asSubclass(Enum.class), "CONTINUE");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object endDirectiveEnd = Enum.valueOf((Class<Enum>) endDirectiveClass.asSubclass(Enum.class), "END");

            MethodHandle compressCtxSetPledgedSrcSizeHandle = lookup.unreflect(
                            compressCtxClass.getMethod("setPledgedSrcSize", long.class))
                    .asType(MethodType.methodType(void.class, Object.class, long.class));

            LinearRuntime.LOGGER.debug("[LinearReader] Loaded embedded zstd-jni from {}.", extractedJar);
            return new Bridge(
                    loader,
                    compressBoundHandle,
                    decompressedSizeHandle,
                    decompressedSizeSliceHandle,
                    isErrorHandle,
                    getErrorNameHandle,
                    compressCtxCtorHandle,
                    compressCtxSetLevelHandle,
                    compressCtxCompressByteArrayHandle,
                    compressCtxStreamHandle,
                    endDirectiveContinue,
                    endDirectiveEnd,
                    compressCtxSetPledgedSrcSizeHandle,
                    decompressCtxCtorHandle,
                    decompressCtxDecompressByteArrayHandle
            );
        } catch (ReflectiveOperationException | IOException e) {
            throw new IllegalStateException("[LinearReader] Failed to initialize embedded zstd-jni runtime.", e);
        }
    }

    private static Path extractEmbeddedJar() throws IOException {
        try (InputStream in = ZstdSupport.class.getResourceAsStream(EMBEDDED_JAR_RESOURCE)) {
            if (in == null) {
                throw new IOException("[LinearReader] Missing embedded zstd-jni resource: " + EMBEDDED_JAR_RESOURCE);
            }
            Path tempDir = Files.createTempDirectory("linearreader-zstd");
            Path jarPath = tempDir.resolve("zstd-jni.jar");
            Files.copy(in, jarPath, StandardCopyOption.REPLACE_EXISTING);
            tempDir.toFile().deleteOnExit();
            jarPath.toFile().deleteOnExit();
            return jarPath;
        }
    }

    public static final class ZstdUnavailableException extends IllegalStateException {

        private ZstdUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class Bridge {
        /** Per-call cap on how much source data one native compress call is fed at once. */
        private static final int STREAM_SLICE_BYTES = 4 * 1024 * 1024; // 4MB

        @SuppressWarnings("unused")
        private final URLClassLoader loader;
        private final MethodHandle compressBoundHandle;
        private final MethodHandle decompressedSizeHandle;
        private final MethodHandle decompressedSizeSliceHandle;
        private final MethodHandle isErrorHandle;
        private final MethodHandle getErrorNameHandle;
        private final MethodHandle compressCtxCtorHandle;
        private final MethodHandle compressCtxSetLevelHandle;
        private final MethodHandle compressCtxCompressByteArrayHandle;
        private final MethodHandle compressCtxStreamHandle;
        private final Object endDirectiveContinue;
        private final Object endDirectiveEnd;
        private final MethodHandle compressCtxSetPledgedSrcSizeHandle;
        private final MethodHandle decompressCtxCtorHandle;
        private final MethodHandle decompressCtxDecompressByteArrayHandle;
        private final ThreadLocal<CompressContextState> compressContexts;
        private final ThreadLocal<Object> decompressContexts;

        private Bridge(URLClassLoader loader,
                       MethodHandle compressBoundHandle,
                       MethodHandle decompressedSizeHandle,
                       MethodHandle decompressedSizeSliceHandle,
                       MethodHandle isErrorHandle,
                       MethodHandle getErrorNameHandle,
                       MethodHandle compressCtxCtorHandle,
                       MethodHandle compressCtxSetLevelHandle,
                       MethodHandle compressCtxCompressByteArrayHandle,
                       MethodHandle compressCtxStreamHandle,
                       Object endDirectiveContinue,
                       Object endDirectiveEnd,
                       MethodHandle compressCtxSetPledgedSrcSizeHandle,
                       MethodHandle decompressCtxCtorHandle,
                       MethodHandle decompressCtxDecompressByteArrayHandle) {
            this.loader = loader;
            this.compressBoundHandle = compressBoundHandle;
            this.decompressedSizeHandle = decompressedSizeHandle;
            this.decompressedSizeSliceHandle = decompressedSizeSliceHandle;
            this.isErrorHandle = isErrorHandle;
            this.getErrorNameHandle = getErrorNameHandle;
            this.compressCtxCtorHandle = compressCtxCtorHandle;
            this.compressCtxSetLevelHandle = compressCtxSetLevelHandle;
            this.compressCtxCompressByteArrayHandle = compressCtxCompressByteArrayHandle;
            this.compressCtxStreamHandle = compressCtxStreamHandle;
            this.endDirectiveContinue = endDirectiveContinue;
            this.endDirectiveEnd = endDirectiveEnd;
            this.compressCtxSetPledgedSrcSizeHandle = compressCtxSetPledgedSrcSizeHandle;
            this.decompressCtxCtorHandle = decompressCtxCtorHandle;
            this.decompressCtxDecompressByteArrayHandle = decompressCtxDecompressByteArrayHandle;
            this.compressContexts = ThreadLocal.withInitial(this::newCompressContextState);
            this.decompressContexts = ThreadLocal.withInitial(this::newDecompressContext);
        }

        private long compressBound(long srcSize) {
            try {
                return (long) compressBoundHandle.invokeExact(srcSize);
            } catch (Throwable t) {
                throw wrap("compressBound", t);
            }
        }

        private long compress(byte[] dst, byte[] src, int level) {
            return compress(dst, 0, dst.length, src, 0, src.length, level);
        }

        private long compress(byte[] dst, int dstOff, int dstLen, byte[] src, int srcOff, int srcLen, int level) {
            CompressContextState state = compressContexts.get();
            state.applyLevel(level);
            try {
                return (long) compressCtxCompressByteArrayHandle.invokeExact(
                        state.context, dst, dstOff, dstLen, src, srcOff, srcLen);
            } catch (Throwable t) {
                throw wrap("compressByteArray", t);
            }
        }


        /**
         * Same output as {@link #compress}, but fed to the native encoder in bounded-size
         * slices instead of one call covering the whole source. Produces one continuous zstd
         * frame - identical ratio/output to one-shot compress() - just spread across several
         * smaller native calls, so no single call can hold a JNI critical section open for
         * the full duration a very large region body would otherwise require.
         */
        private long compressStream(byte[] dst, int dstOff, int dstLen, byte[] src, int srcOff, int srcLen, int level, int sliceBytes) {
            CompressContextState state = compressContexts.get();
            state.applyLevel(level);
            try {
                java.nio.ByteBuffer dstBuf = java.nio.ByteBuffer.wrap(dst, dstOff, dstLen);
                java.nio.ByteBuffer srcBuf = java.nio.ByteBuffer.wrap(src, srcOff, srcLen);
                int fullLimit = srcOff + srcLen;

                // Tell zstd the total size up front so the frame header records it, exactly like
                // one-shot compress() does. Without this, decompressedSize() can't size the
                // buffer on the read path. Must equal the total bytes fed below.
                compressCtxSetPledgedSrcSizeHandle.invokeExact(state.context, (long) srcLen);

                long[] sliceTimingsNs = (LinearStats.isEnabled() && sliceBytes >= STREAM_SLICE_BYTES) ? new long[64] : null;
                int sliceIndex = 0;
                while (srcBuf.position() < fullLimit) {
                    int sliceLimit = Math.min(srcBuf.position() + Math.max(4096, sliceBytes), fullLimit);
                    srcBuf.limit(sliceLimit);
                    long sliceStartNs = sliceTimingsNs != null ? System.nanoTime() : 0L;
                    boolean ignoredContinueFlush = (boolean) compressCtxStreamHandle.invokeExact(
                            state.context, dstBuf, srcBuf, endDirectiveContinue);
                    if (sliceTimingsNs != null && sliceIndex < sliceTimingsNs.length) {
                        sliceTimingsNs[sliceIndex++] = System.nanoTime() - sliceStartNs;
                    }
                    srcBuf.limit(fullLimit);
                }
                if (sliceTimingsNs != null && sliceIndex > 0) {
                    logSliceTimingsIfSlow(sliceTimingsNs, sliceIndex);
                }

                srcBuf.limit(srcBuf.position());
                boolean flushed = (boolean) compressCtxStreamHandle.invokeExact(
                        state.context, dstBuf, srcBuf, endDirectiveEnd);
                while (!flushed) {
                    flushed = (boolean) compressCtxStreamHandle.invokeExact(
                            state.context, dstBuf, srcBuf, endDirectiveEnd);
                }

                return dstBuf.position() - dstOff;
            } catch (Throwable t) {
                throw wrap("compressByteBufferStream", t);
            }
        }


        /**
         * Logs a per-slice timing breakdown ONLY when at least one slice took noticeably
         * longer than the rest - this tells us whether a slow compressStream() call is caused
         * by ONE outlier native call (pointing at a discrete external event at that moment)
         * or by every slice being uniformly slow (pointing at a sustained condition across
         * the whole operation). Deliberately separate from the existing slow-save warning,
         * which only reports the aggregate total.
         */
        private void logSliceTimingsIfSlow(long[] timingsNs, int count) {
            long maxNs = 0L;
            long sumNs = 0L;
            int maxIndex = -1;
            for (int i = 0; i < count; i++) {
                sumNs += timingsNs[i];
                if (timingsNs[i] > maxNs) {
                    maxNs = timingsNs[i];
                    maxIndex = i;
                }
            }
            if (sumNs < 1_000_000_000L) return; // only log if the whole call was >= 1s total

            double maxShareOfTotal = (double) maxNs / sumNs;
            StringBuilder sb = new StringBuilder();
            sb.append("[LinearReader] compressStream slice timing - ")
                    .append(count).append(" slices, total=")
                    .append(sumNs / 1_000_000L).append("ms, slowest=slice#")
                    .append(maxIndex).append(" (").append(maxNs / 1_000_000L)
                    .append("ms, ").append(String.format(java.util.Locale.ROOT, "%.0f", maxShareOfTotal * 100))
                    .append("% of total), all slices(ms)=[");
            for (int i = 0; i < count; i++) {
                if (i > 0) sb.append(',');
                sb.append(timingsNs[i] / 1_000_000L);
            }
            sb.append(']');
            LinearRuntime.LOGGER.warn(sb.toString());
        }

        private long decompressedSize(byte[] src) {
            try {
                return (long) decompressedSizeHandle.invokeExact(src);
            } catch (Throwable t) {
                throw wrap("decompressedSize", t);
            }
        }

        private long decompressedSize(byte[] src, int srcOff, int srcLen) {
            try {
                return (long) decompressedSizeSliceHandle.invokeExact(src, srcOff, srcLen);
            } catch (Throwable t) {
                throw wrap("decompressedSize", t);
            }
        }

        private long decompress(byte[] dst, byte[] src) {
            return decompress(dst, 0, dst.length, src, 0, src.length);
        }

        private long decompress(byte[] dst, int dstOff, int dstLen, byte[] src, int srcOff, int srcLen) {
            Object context = decompressContexts.get();
            try {
                return (long) decompressCtxDecompressByteArrayHandle.invokeExact(
                        context, dst, dstOff, dstLen, src, srcOff, srcLen);
            } catch (Throwable t) {
                throw wrap("decompressByteArray", t);
            }
        }

        private boolean isError(long code) {
            try {
                return (boolean) isErrorHandle.invokeExact(code);
            } catch (Throwable t) {
                throw wrap("isError", t);
            }
        }

        private String getErrorName(long code) {
            try {
                return (String) getErrorNameHandle.invokeExact(code);
            } catch (Throwable t) {
                throw wrap("getErrorName", t);
            }
        }

        private CompressContextState newCompressContextState() {
            try {
                return new CompressContextState(compressCtxCtorHandle.invokeExact());
            } catch (Throwable t) {
                throw wrap("ZstdCompressCtx constructor", t);
            }
        }

        private Object newDecompressContext() {
            try {
                return decompressCtxCtorHandle.invokeExact();
            } catch (Throwable t) {
                throw wrap("ZstdDecompressCtx constructor", t);
            }
        }

        private static IllegalStateException wrap(String what, Throwable cause) {
            return new IllegalStateException("[LinearReader] Embedded zstd-jni call failed: " + what + ".", cause);
        }

        private final class CompressContextState {
            private final Object context;
            private int level = Integer.MIN_VALUE;

            private CompressContextState(Object context) {
                this.context = context;
            }

            private void applyLevel(int nextLevel) {
                if (level == nextLevel) return;
                try {
                    compressCtxSetLevelHandle.invokeExact(context, nextLevel);
                } catch (Throwable t) {
                    throw wrap("setLevel", t);
                }
                level = nextLevel;
            }
        }
    }
}
