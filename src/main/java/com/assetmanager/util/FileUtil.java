package com.assetmanager.util;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class FileUtil {

    /** Stream writer that may throw, used by {@link #writeAtomic}. */
    @FunctionalInterface
    public interface OutputWriter {
        void write(java.io.OutputStream os) throws IOException;
    }

    private FileUtil() {}

    /** Recursively collects regular files under {@code root}, skipping hidden dirs and our own cache. */
    public static List<Path> walkFiles(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (n.startsWith(".") || n.equals("__MACOSX")) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                if (a.isRegularFile()) out.add(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    /** "a.png" -> "a_1.png", "a_1.png" -> "a_2.png" ... */
    public static Path uniquePath(Path target) {
        if (!Files.exists(target)) return target;
        String name = target.getFileName().toString();
        String base = Formats.baseName(name);
        String ext = Formats.extOf(name);
        String suffix = ext.isEmpty() ? "" : "." + ext;
        Path dir = target.getParent();
        for (int i = 1; i < 10000; i++) {
            Path cand = dir.resolve(base + "_" + i + suffix);
            if (!Files.exists(cand)) return cand;
        }
        return dir.resolve(base + "_" + System.nanoTime() + suffix);
    }

    public static void copy(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Writes to a sibling temp file then atomically moves it into place. */
    public static void writeAtomic(Path target, OutputWriter writer) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (java.io.OutputStream os = Files.newOutputStream(tmp)) {
            writer.write(os);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static boolean deleteQuietly(Path p) {
        try { return Files.deleteIfExists(p); }
        catch (IOException e) { Log.warn("delete failed " + p + ": " + e.getMessage()); return false; }
    }

    /** Recursively collects directories, skipping hidden ones. */
    public static List<Path> walkDirs(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (n.startsWith(".") || n.equals("__MACOSX")) return FileVisitResult.SKIP_SUBTREE;
                out.add(dir);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path f, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    public static Comparator<Path> byName() {
        return Comparator.comparing(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT));
    }

    /** Last-resort unique short label for assets sharing a display name. */
    public static String disambiguate(java.util.Collection<Path> sameName) {
        return sameName.stream()
                .map(Path::getParent)
                .map(Path::toString)
                .sorted()
                .findFirst()
                .map(s -> shortenHome(s))
                .orElse("");
    }

    public static String shortenHome(String path) {
        String home = System.getProperty("user.home");
        if (home != null && path.startsWith(home)) return "~" + path.substring(home.length());
        return path;
    }

    /** Path relative to {@code base} when possible, else absolute. */
    public static String relativeTo(Path base, Path child) {
        try { return base.toAbsolutePath().relativize(child.toAbsolutePath()).toString(); }
        catch (IllegalArgumentException e) { return child.toString(); }
    }
}
