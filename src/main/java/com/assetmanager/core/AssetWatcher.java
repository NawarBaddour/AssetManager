package com.assetmanager.core;

import com.assetmanager.util.FileUtil;
import com.assetmanager.util.Log;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watches indexed roots for new/changed/removed files and batches them so a
 * "save" in an external tool triggers one re-index rather than fifty.
 */
public final class AssetWatcher implements AutoCloseable {

    /** Fires with the set of affected directories after a quiet period. */
    public interface Listener { void onChanges(Set<Path> directories); }

    private static final long QUIET_MS = 1200;

    private final AssetStore store;
    private final Listener listener;
    private final WatchService service;
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Map<WatchKey, Path> registered = new HashMap<>();

    private long lastEventAt = 0;
    private final Set<Path> pending = new HashSet<>();

    public AssetWatcher(AssetStore store, Listener listener) throws IOException {
        this.store = store;
        this.listener = listener;
        this.service = FileSystems.getDefault().newWatchService();
        for (AssetStore.RootRow r : store.roots()) {
            if (r.watch) registerTree(Path.of(r.path));
        }
        this.thread = new Thread(this::loop, "asset-watcher");
        this.thread.setDaemon(true);
        this.thread.start();
        Log.info("Watcher started, " + registered.size() + " directories under watch");
    }

    private void registerTree(Path root) {
        if (!Files.isDirectory(root)) return;
        try {
            for (Path dir : FileUtil.walkDirs(root)) register(dir);
        } catch (IOException e) {
            Log.warn("watch register failed for " + root + ": " + e.getMessage());
        }
    }

    private void register(Path dir) {
        try {
            WatchKey k = dir.register(service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            registered.put(k, dir);
        } catch (IOException e) {
            Log.debug("cannot watch " + dir + ": " + e.getMessage());
        }
    }

    private void loop() {
        while (running.get()) {
            WatchKey key;
            try {
                key = service.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                return;
            }

            if (key != null) {
                Path dir = registered.get(key);
                for (WatchEvent<?> ev : key.pollEvents()) {
                    if (ev.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                    Object ctx = ev.context();
                    if (!(ctx instanceof Path)) continue;
                    Path rel = (Path) ctx;
                    Path full = dir == null ? rel : dir.resolve(rel);

                    if (ev.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(full)) {
                        registerTree(full);          // new folder: start watching it
                    }
                    pending.add(dir == null ? Path.of(".") : dir);
                }
                lastEventAt = System.currentTimeMillis();
                if (!key.reset()) registered.remove(key);
                continue;
            }

            // quiet period elapsed: flush the batch
            if (!pending.isEmpty() && System.currentTimeMillis() - lastEventAt > QUIET_MS) {
                Set<Path> batch = new HashSet<>(pending);
                pending.clear();
                try { listener.onChanges(batch); }
                catch (Exception e) { Log.error("watch listener failed", e); }
            }
        }
    }

    /** Adds a new root to the watch set at runtime. */
    public void addRoot(Path root) { registerTree(root); }

    public int watchedDirectoryCount() { return registered.size(); }

    @Override public void close() {
        running.set(false);
        try { service.close(); } catch (IOException ignored) { }
        thread.interrupt();
    }
}
