package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearReader;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class ZstdSupport {

    private static final String EMBEDDED_JAR_RESOURCE = "/META-INF/linearreader-libs/zstd-jni.jar";
    private static volatile Bridge bridge;

    private ZstdSupport() {
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
        Bridge current = bridge;
        if (current != null) {
            return current;
        }
        synchronized (ZstdSupport.class) {
            current = bridge;
            if (current == null) {
                current = loadBridge();
                bridge = current;
            }
            return current;
        }
    }

    private static Bridge loadBridge() {
        try {
            Path extractedJar = extractEmbeddedJar();
            URLClassLoader loader = new URLClassLoader(
                    new URL[]{extractedJar.toUri().toURL()},
                    ZstdSupport.class.getClassLoader()
            );
            Class<?> zstdClass = Class.forName("com.github.luben.zstd.Zstd", true, loader);
            LinearReader.LOGGER.debug("[LinearReader] Loaded embedded zstd-jni from {}.", extractedJar);
            return new Bridge(
                    loader,
                    zstdClass.getMethod("compressBound", long.class),
                    zstdClass.getMethod("compress", byte[].class, byte[].class, int.class),
                    zstdClass.getMethod("compressByteArray",
                            byte[].class, int.class, int.class,
                            byte[].class, int.class, int.class, int.class),
                    zstdClass.getMethod("decompressedSize", byte[].class),
                    zstdClass.getMethod("decompressedSize", byte[].class, int.class, int.class),
                    zstdClass.getMethod("decompress", byte[].class, byte[].class),
                    zstdClass.getMethod("decompressByteArray",
                            byte[].class, int.class, int.class,
                            byte[].class, int.class, int.class),
                    zstdClass.getMethod("isError", long.class),
                    zstdClass.getMethod("getErrorName", long.class)
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

    private static final class Bridge {
        @SuppressWarnings("unused")
        private final URLClassLoader loader;
        private final Method compressBound;
        private final Method compress;
        private final Method compressByteArray;
        private final Method decompressedSize;
        private final Method decompressedSizeSlice;
        private final Method decompress;
        private final Method decompressByteArray;
        private final Method isError;
        private final Method getErrorName;

        private Bridge(URLClassLoader loader,
                       Method compressBound,
                       Method compress,
                       Method compressByteArray,
                       Method decompressedSize,
                       Method decompressedSizeSlice,
                       Method decompress,
                       Method decompressByteArray,
                       Method isError,
                       Method getErrorName) {
            this.loader = loader;
            this.compressBound = compressBound;
            this.compress = compress;
            this.compressByteArray = compressByteArray;
            this.decompressedSize = decompressedSize;
            this.decompressedSizeSlice = decompressedSizeSlice;
            this.decompress = decompress;
            this.decompressByteArray = decompressByteArray;
            this.isError = isError;
            this.getErrorName = getErrorName;
        }

        private long compressBound(long srcSize) {
            return invokeLong(compressBound, srcSize);
        }

        private long compress(byte[] dst, byte[] src, int level) {
            return invokeLong(compress, dst, src, level);
        }

        private long compress(byte[] dst, int dstOff, int dstLen, byte[] src, int srcOff, int srcLen, int level) {
            return invokeLong(compressByteArray, dst, dstOff, dstLen, src, srcOff, srcLen, level);
        }

        private long decompressedSize(byte[] src) {
            return invokeLong(decompressedSize, src);
        }

        private long decompressedSize(byte[] src, int srcOff, int srcLen) {
            return invokeLong(decompressedSizeSlice, src, srcOff, srcLen);
        }

        private long decompress(byte[] dst, byte[] src) {
            return invokeLong(decompress, dst, src);
        }

        private long decompress(byte[] dst, int dstOff, int dstLen, byte[] src, int srcOff, int srcLen) {
            return invokeLong(decompressByteArray, dst, dstOff, dstLen, src, srcOff, srcLen);
        }

        private boolean isError(long code) {
            return invokeBoolean(isError, code);
        }

        private String getErrorName(long code) {
            return invokeString(getErrorName, code);
        }

        private static long invokeLong(Method method, Object... args) {
            return ((Number) invoke(method, args)).longValue();
        }

        private static boolean invokeBoolean(Method method, Object... args) {
            return (Boolean) invoke(method, args);
        }

        private static String invokeString(Method method, Object... args) {
            return (String) invoke(method, args);
        }

        private static Object invoke(Method method, Object... args) {
            try {
                return method.invoke(null, args);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("[LinearReader] Could not access embedded zstd-jni method " + method.getName() + ".", e);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new IllegalStateException("[LinearReader] Embedded zstd-jni call failed: " + method.getName() + ".", cause);
            }
        }
    }
}
