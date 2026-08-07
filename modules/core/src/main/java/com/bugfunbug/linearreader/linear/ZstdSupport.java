package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearRuntime;

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
