package com.bugfunbug.linearreader.linear;

import com.bugfunbug.linearreader.LinearRuntime;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Reflective bridge to Brotli4j, mirroring {@link ZstdSupport}'s structure and
 * failure-handling philosophy exactly - lazy singleton init, cached failure so a
 * broken native load doesn't retry (and re-fail slowly) on every call, graceful
 * unavailability that callers can catch and fall back from.
 *
 * <h3>Why this differs from ZstdSupport</h3>
 * zstd-jni ships as ONE self-contained jar with every platform's native code
 * bundled inside it, so ZstdSupport only ever extracts and loads one file.
 * Brotli4j instead ships the Java API, a small service-loader shim, and each
 * platform's native code as SEPARATE jars (confirmed directly from Brotli4j's
 * own source: {@code Brotli4jLoader}'s static initializer uses
 * {@code ServiceLoader.load(BrotliNativeProvider.class, Brotli4jLoader.class.getClassLoader())}
 * to find the native matching the current OS/arch - which means every one of
 * those jars needs to be on the SAME classloader as Brotli4jLoader itself, not
 * extracted/loaded independently). This class therefore extracts every
 * embedded Brotli jar into one temp directory and puts all of them on one
 * URLClassLoader, then lets Brotli4jLoader do its own platform detection from
 * there - LinearReader does not need to (and does not) duplicate that
 * OS/arch-picking logic itself.
 */
public final class BrotliSupport {

    // Must match the exact filenames these jars are copied into the mod jar
    // under - i.e. whatever the `jar { from(configurations.brotliRuntime) { ... } }`
    // block in each target's build.gradle actually produces. These currently
    // keep their original Maven artifact filenames (no `rename`, unlike the
    // single zstd-jni.jar), so the version number is baked into every name
    // here - bumping the Brotli4j version means updating this list too.
    private static final String[] EMBEDDED_JAR_RESOURCES = {
            "/META-INF/linearreader-libs/brotli4j-1.23.0.jar",
            "/META-INF/linearreader-libs/service-1.23.0.jar",
            "/META-INF/linearreader-libs/native-linux-x86_64-1.23.0.jar",
            "/META-INF/linearreader-libs/native-linux-aarch64-1.23.0.jar",
            "/META-INF/linearreader-libs/native-osx-x86_64-1.23.0.jar",
            "/META-INF/linearreader-libs/native-osx-aarch64-1.23.0.jar",
            "/META-INF/linearreader-libs/native-windows-x86_64-1.23.0.jar",
    };

    private static volatile Bridge bridge;
    private static volatile BrotliUnavailableException unavailableException;

    private BrotliSupport() {
    }

    /** Throws {@link BrotliUnavailableException} if Brotli cannot be loaded on this platform/JVM. */
    public static void ensureAvailable() {
        bridge();
    }

    static byte[] compress(byte[] data, int quality, int lgwin) {
        return bridge().compress(data, quality, lgwin);
    }

    static byte[] decompress(byte[] data, int offset, int length, int maxOutputSize) {
        return bridge().decompress(data, offset, length, maxOutputSize);
    }

    private static Bridge bridge() {
        Bridge current = bridge;
        if (current != null) {
            return current;
        }

        BrotliUnavailableException cachedFailure = unavailableException;
        if (cachedFailure != null) {
            throw cachedFailure;
        }

        synchronized (BrotliSupport.class) {
            current = bridge;
            if (current == null) {
                BrotliUnavailableException priorFailure = unavailableException;
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

    private static BrotliUnavailableException unavailable(Throwable cause) {
        if (cause instanceof BrotliUnavailableException already) {
            unavailableException = already;
            return already;
        }
        BrotliUnavailableException failure = new BrotliUnavailableException(
                "[LinearReader] Brotli native library could not be initialized. "
                        + "Brotli-based recompression is unavailable on this platform/JVM; "
                        + "callers should fall back to Zstd instead of failing outright.",
                cause);
        unavailableException = failure;
        return failure;
    }

    private static Bridge loadBridge() throws Exception {
        Path tempDir = Files.createTempDirectory("linearreader-brotli");
        URL[] urls = new URL[EMBEDDED_JAR_RESOURCES.length];
        for (int i = 0; i < EMBEDDED_JAR_RESOURCES.length; i++) {
            urls[i] = extractEmbeddedJar(EMBEDDED_JAR_RESOURCES[i], tempDir, i).toUri().toURL();
        }
        URLClassLoader loader = new URLClassLoader(urls, BrotliSupport.class.getClassLoader());

        // Triggers Brotli4jLoader's static initializer (native detection + load)
        // and throws UnsatisfiedLinkError with the real cause if it fails - same
        // role as ZstdSupport's own ensureAvailable() check during loadBridge().
        Class<?> loaderClass = Class.forName("com.aayushatharva.brotli4j.Brotli4jLoader", true, loader);
        Method ensureAvailability = loaderClass.getMethod("ensureAvailability");
        ensureAvailability.invoke(null);

        Class<?> encoderClass = Class.forName("com.aayushatharva.brotli4j.encoder.Encoder", true, loader);
        Class<?> paramsClass = Class.forName("com.aayushatharva.brotli4j.encoder.Encoder$Parameters", true, loader);
        Class<?> modeClass = Class.forName("com.aayushatharva.brotli4j.encoder.Encoder$Mode", true, loader);
        Class<?> decoderClass = Class.forName("com.aayushatharva.brotli4j.decoder.Decoder", true, loader);

        Method paramsCreate = paramsClass.getMethod("create", int.class, int.class, modeClass);
        Method encoderCompress = encoderClass.getMethod("compress", byte[].class, paramsClass);
        Method decoderDecompress = decoderClass.getMethod(
                "decompress", byte[].class, int.class, int.class, int.class);

        @SuppressWarnings({"unchecked", "rawtypes"})
        Object genericMode = Enum.valueOf((Class<Enum>) modeClass.asSubclass(Enum.class), "GENERIC");

        LinearRuntime.LOGGER.debug("[LinearReader] Loaded embedded Brotli4j from {}.", tempDir);
        return new Bridge(loader, paramsCreate, encoderCompress, decoderDecompress, genericMode);
    }

    private static Path extractEmbeddedJar(String resource, Path tempDir, int index) throws IOException {
        try (InputStream in = BrotliSupport.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("[LinearReader] Missing embedded Brotli resource: " + resource);
            }
            Path jarPath = tempDir.resolve("brotli-" + index + ".jar");
            Files.copy(in, jarPath, StandardCopyOption.REPLACE_EXISTING);
            jarPath.toFile().deleteOnExit();
            return jarPath;
        }
    }

    public static final class BrotliUnavailableException extends IllegalStateException {
        private BrotliUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class Bridge {
        @SuppressWarnings("unused")
        private final URLClassLoader loader; // kept alive for the JVM's lifetime; never explicitly closed
        private final Method paramsCreate;
        private final Method encoderCompress;
        private final Method decoderDecompress;
        private final Object genericMode;

        private Bridge(URLClassLoader loader, Method paramsCreate, Method encoderCompress,
                       Method decoderDecompress, Object genericMode) {
            this.loader = loader;
            this.paramsCreate = paramsCreate;
            this.encoderCompress = encoderCompress;
            this.decoderDecompress = decoderDecompress;
            this.genericMode = genericMode;
        }

        private byte[] compress(byte[] data, int quality, int lgwin) {
            try {
                Object params = paramsCreate.invoke(null, quality, lgwin, genericMode);
                return (byte[]) encoderCompress.invoke(null, data, params);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new IllegalStateException("[LinearReader] Brotli compression failed.", cause);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("[LinearReader] Could not access Brotli compress method.", e);
            }
        }

        private byte[] decompress(byte[] data, int offset, int length, int maxOutputSize) {
            try {
                return (byte[]) decoderDecompress.invoke(null, data, offset, length, maxOutputSize);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new IllegalStateException("[LinearReader] Brotli decompression failed.", cause);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("[LinearReader] Could not access Brotli decompress method.", e);
            }
        }
    }
}