package com.assetmanager.core;

import com.assetmanager.util.FileUtil;
import com.assetmanager.util.Formats;
import com.assetmanager.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Walks folders, probes each file, and syncs the index. */
public final class AssetScanner {

    public static final class Report {
        public int added, updated, removed, skipped, failed;
        public long bytes;
        public long elapsedMs;
        public final List<String> problems = new ArrayList<>();
        public boolean cancelled;
        public String summary() {
            return String.format("+%d new, ~%d updated, -%d gone, %d skipped, %d failed in %.1fs",
                    added, updated, removed, skipped, failed, elapsedMs / 1000.0);
        }
    }

    private final AssetStore store;
    private final boolean computeHashes;

    public AssetScanner(AssetStore store, boolean computeHashes) {
        this.store = store;
        this.computeHashes = computeHashes;
    }

    /** Progress callback: (done, total, currentFileName). */
    public interface Progress { void onProgress(int done, int total, String current); }

    public Report scan(List<Path> roots, Progress progress, AtomicBoolean cancel) {
        long t0 = System.currentTimeMillis();
        Report rep = new Report();

        // 1. collect candidate files
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            if (cancel.get()) { rep.cancelled = true; break; }
            if (!Files.isDirectory(root)) {
                if (Files.isRegularFile(root)) files.add(root);
                continue;
            }
            try {
                for (Path f : FileUtil.walkFiles(root)) {
                    if (Formats.isIndexable(f.getFileName().toString())) files.add(f);
                }
            } catch (IOException e) {
                rep.problems.add("Could not read " + root + ": " + e.getMessage());
            }
        }

        int total = files.size();
        int done = 0;
        Set<String> seen = new LinkedHashSet<>();

        for (Path f : files) {
            if (cancel.get()) { rep.cancelled = true; break; }
            done++;
            if (progress != null && (done % 25 == 0 || done == total)) {
                progress.onProgress(done, total, f.getFileName().toString());
            }

            String path = f.toAbsolutePath().toString();
            seen.add(path);

            try {
                long size = Files.size(f);
                long mtime = Files.getLastModifiedTime(f).toMillis();
                Asset existing = store.byPath(path);

                if (existing != null && existing.mtime == mtime && existing.size == size) {
                    rep.skipped++;                 // unchanged since last scan
                    continue;
                }

                Asset a = Asset.of(f);
                a.size = size;
                a.mtime = mtime;
                a.addedAt = existing == null ? System.currentTimeMillis() : existing.addedAt;
                if (existing != null && existing.contentHash != null) a.contentHash = existing.contentHash;
                if (existing != null) a.note = existing.note;

                MetaProbe.probe(a);
                if (computeHashes && a.contentHash == null) a.contentHash = Hashing.trySha256(f);

                store.upsert(a);
                if (existing == null) { rep.added++; rep.bytes += size; }
                else rep.updated++;

            } catch (Throwable e) {
                // Deliberately Throwable: an ImageIO plugin with a missing transitive
                // jar throws NoClassDefFoundError, which must not abort the whole scan.
                rep.failed++;
                if (rep.problems.size() < 40) {
                    rep.problems.add(f.getFileName() + ": " + e);
                }
                Log.debug("scan failed for " + f + ": " + e);
            }
        }

        // 2. drop rows whose files vanished (only under the scanned roots)
        if (!rep.cancelled) {
            Set<String> underRoots = new LinkedHashSet<>();
            for (Path root : roots) underRoots.add(root.toAbsolutePath().toString());
            List<String> gone = new ArrayList<>();
            for (Asset a : store.all()) {
                if (seen.contains(a.path)) continue;
                if (!underAnyRoot(a.path, roots)) continue;
                gone.add(a.path);
            }
            if (!gone.isEmpty()) {
                store.deleteByPaths(gone);
                rep.removed = gone.size();
            }
        }

        rep.elapsedMs = System.currentTimeMillis() - t0;
        return rep;
    }

    private static boolean underAnyRoot(String path, List<Path> roots) {
        Path p = Path.of(path);
        for (Path r : roots) {
            Path ra = r.toAbsolutePath();
            if (java.nio.file.Files.isRegularFile(ra)) {
                if (ra.equals(p)) return true;
                continue;
            }
            if (p.startsWith(ra)) return true;
        }
        return false;
    }

    /** Index a single file (used by drag-and-drop and the CLI). */
    public Asset indexOne(Path file) {
        try {
            Asset a = Asset.of(file);
            a.size = Files.size(file);
            a.mtime = Files.getLastModifiedTime(file).toMillis();
            a.addedAt = System.currentTimeMillis();
            MetaProbe.probe(a);
            if (computeHashes) a.contentHash = Hashing.trySha256(file);
            a.id = store.upsert(a);
            return a;
        } catch (IOException e) {
            Log.warn("index failed " + file + ": " + e.getMessage());
            return null;
        }
    }

    /** Re-hash everything, e.g. after enabling hash-based duplicate finding. */
    public int rehashAll(Consumer<Integer> progress) {
        List<Asset> all = store.all();
        int n = 0;
        for (Asset a : all) {
            Path p = a.file();
            if (!Files.isReadable(p)) continue;
            String h = Hashing.trySha256(p);
            if (h != null && !h.equals(a.contentHash)) {
                a.contentHash = h;
                store.upsert(a);
            }
            n++;
            if (progress != null) progress.accept(n);
        }
        return n;
    }
}
