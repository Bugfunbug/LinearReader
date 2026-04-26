package com.bugfunbug.linearreader;

import com.bugfunbug.linearreader.config.LinearConfig;
import com.bugfunbug.linearreader.linear.LinearRegionFile;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

public final class LinearTestSupport {

    private LinearTestSupport() {}

    public static void resetState() {
        LinearConfig.update(
                4,
                256,
                true,
                32,
                2048,
                30,
                60,
                4,
                60,
                4,
                16,
                500,
                1,
                true,
                20,
                15
        );

        for (LinearRegionFile region : List.copyOf(LinearRegionFile.ALL_OPEN)) {
            LinearRegionFile.ALL_OPEN.remove(region);
            region.releaseChunkData();
        }
        LinearRegionFile.shutdownBackupExecutor();
    }

    public static Path copyCorpusTree(String relativePath, Path targetRoot) throws IOException {
        Path sourceRoot = resourcePath("corpus/" + relativePath);
        copyTree(sourceRoot, targetRoot);
        return targetRoot;
    }

    public static Path copyCorpusFile(String relativePath, Path targetFile) throws IOException {
        Path sourceFile = resourcePath("corpus/" + relativePath);
        Path parent = targetFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        return targetFile;
    }

    public static Path resourcePath(String resourcePath) {
        URL url = LinearTestSupport.class.getClassLoader().getResource(resourcePath);
        if (url == null) {
            throw new IllegalArgumentException("Missing test resource: " + resourcePath);
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Could not resolve resource URI: " + resourcePath, e);
        }
    }

    public static void copyTree(Path sourceRoot, Path targetRoot) throws IOException {
        deleteTree(targetRoot);
        Files.createDirectories(targetRoot);
        try (var stream = Files.walk(sourceRoot)) {
            for (Path source : (Iterable<Path>) stream::iterator) {
                Path relative = sourceRoot.relativize(source);
                Path target = targetRoot.resolve(relative.toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    public static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path path : (Iterable<Path>) stream.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }

    public static Path onlyFileMatching(Path dir, String prefix, String suffix) throws IOException {
        try (var stream = Files.list(Objects.requireNonNull(dir, "dir"))) {
            List<Path> matches = stream
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(prefix) && name.endsWith(suffix);
                    })
                    .toList();
            if (matches.size() != 1) {
                throw new AssertionError("Expected exactly one match in " + dir + " but found " + matches.size());
            }
            return matches.get(0);
        }
    }
}
