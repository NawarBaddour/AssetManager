package com.assetmanager.core;

import com.assetmanager.core.mesh.MeshIo;
import com.assetmanager.core.mesh.ModelData;
import com.assetmanager.preview.SoftRenderer;
import com.assetmanager.util.Images;
import com.assetmanager.util.Log;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Generates and caches grid thumbnails. Images become scaled bitmaps, audio becomes a
 * waveform strip, 3D models get a shaded turntable still -- so the grid is useful for
 * every asset type without opening anything.
 */
public final class ThumbnailService implements AutoCloseable {

    public static final int SIZE = 192;

    private final AssetStore store;
    private final Path thumbDir;
    private final Path peakDir;
    private final ExecutorService pool;

    /** path -> thumbnail, bounded so a huge library cannot exhaust the heap. */
    private final Map<String, BufferedImage> mem = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                    return size() > 3000;
                }
            });

    private final Map<String, Boolean> inFlight = new ConcurrentHashMap<>();

    public ThumbnailService(AssetStore store, Path cacheDir) throws IOException {
        this.store = store;
        this.thumbDir = cacheDir.resolve("thumbs");
        this.peakDir = cacheDir.resolve("peaks");
        Files.createDirectories(thumbDir);
        Files.createDirectories(peakDir);

        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "thumb-gen");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        };
        this.pool = Executors.newFixedThreadPool(
                Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)), tf);
    }

    /** Cached thumbnail, or null. Safe to call from the EDT (disk read on miss only). */
    public BufferedImage cached(Asset a) {
        BufferedImage hit = mem.get(a.path);
        if (hit != null) return hit;
        AssetStore.ThumbRow row = store.thumbFor(a.path, a.mtime, a.size);
        if (row == null || row.thumb == null) return null;
        Path f = Path.of(row.thumb);
        // The database remembers the file to serve, so a renderer change has to
        // invalidate what it remembers -- not merely what gets written next.
        // Checking the version on write alone leaves every existing library
        // serving its old tiles until the cache is cleared by hand.
        if (!f.getFileName().toString().startsWith(RENDER + "-")) {
            stale(f);
            return null;
        }
        if (!Files.isReadable(f)) return null;
        try {
            BufferedImage img = javax.imageio.ImageIO.read(f.toFile());
            if (img != null) { mem.put(a.path, img); return img; }
        } catch (IOException e) {
            Log.debug("thumb read failed " + f + ": " + e.getMessage());
        }
        return null;
    }

    /** Drops a tile left behind by an earlier render version. Best effort. */
    private void stale(Path f) {
        try {
            if (Files.deleteIfExists(f)) Log.debug("dropped superseded thumb " + f.getFileName());
        } catch (IOException e) {
            // Not worth failing a read over; Clear cache sweeps the directory.
        }
    }

    /** Queues generation if needed. {@code onReady} runs on a pool thread. */
    public void request(Asset a, Consumer<Asset> onReady) {
        if (cached(a) != null) return;
        if (inFlight.putIfAbsent(a.path, Boolean.TRUE) != null) return;
        pool.execute(() -> {
            try {
                BufferedImage img = build(a);
                if (img != null) {
                    mem.put(a.path, img);
                    writeCache(a, img);
                    onReady.accept(a);
                }
            } catch (Throwable t) {
                // Thumbnail generation is best-effort; never let it kill a pool thread.
                Log.debug("thumb failed for " + a.name + ": " + t);
            } finally {
                inFlight.remove(a.path);
            }
        });
    }

    private void writeCache(Asset a, BufferedImage img) {
        try {
            Path f = thumbDir.resolve(key(a) + ".png");
            javax.imageio.ImageIO.write(img, "png", f.toFile());
            store.putThumb(a.path, a.mtime, a.size, f.toString(), peaksFile(a) == null ? null : peaksFile(a).toString());
        } catch (IOException e) {
            Log.debug("thumb write failed: " + e.getMessage());
        }
    }

    /**
     * Bump this whenever the appearance of a generated tile changes.
     *
     * <p>It goes into the on-disk name, so a stale tile cannot be found and
     * served after a renderer change — see {@link #cached}, which has to check it
     * because the database, not this class, decides which file to read. It also
     * goes into the thumbnail URL the browser requests, so a cached copy in the
     * page's HTTP cache cannot outlive the change either.
     *
     * <p>Waveform peaks deliberately carry no version: they measure the audio
     * rather than draw it, so they stay valid and are not recomputed.
     */
    private static final String RENDER = "r4";

    /**
     * The current render version, for callers that need to put it in a URL.
     * Generated images are served cacheable, so a renderer change has to
     * change their address too: otherwise the browser keeps handing back
     * the bytes it fetched under the old version, and the change stays
     * invisible until that cache happens to expire.
     */
    public String renderVersion() { return RENDER; }

    private String key(Asset a) {
        return RENDER + "-" + Integer.toHexString(a.path.hashCode()) + "-" + Long.toHexString(a.mtime)
                + "-" + Integer.toHexString((int) a.size);
    }

    /** Cache name for waveform peaks: no render version, see {@link #RENDER}. */
    private String peakKey(Asset a) {
        return Integer.toHexString(a.path.hashCode()) + "-" + Long.toHexString(a.mtime)
                + "-" + Integer.toHexString((int) a.size);
    }

    private BufferedImage build(Asset a) {
        switch (a.category) {
            case IMAGE: return buildImage(a);
            case AUDIO: return buildAudio(a);
            case MODEL: return buildModel(a);
            default:    return placeholder(a);
        }
    }

    private BufferedImage buildImage(Asset a) {
        BufferedImage src;
        try { src = Images.read(a.file()); }
        catch (Throwable t) {
            Log.debug("thumb image read failed " + a.name + ": " + t);
            return errorTile(a, "no decoder");
        }
        if (src == null) return errorTile(a, "no decoder");
        try {
            return compose(Images.scaleToFit(src, SIZE), src.getWidth(), src.getHeight());
        } catch (Throwable t) {
            return errorTile(a, "too large");
        }
    }

    private BufferedImage buildAudio(Asset a) {
        int w = SIZE, h = SIZE / 2;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Background stays --panel, as it always was. The wave is --wave: the
        // accent's lighter step, because the accent fill itself is only 3.2:1
        // against this background and reads as brown at thumbnail size.
        g.setColor(new Color(0x1B1D24));
        g.fillRect(0, 0, w, h);

        Waveform wf = peaks(a);
        if (wf != null && wf.bucketCount() > 0) {
            g.setColor(new Color(0xDE675E));
            int n = wf.bucketCount();
            int mid = h / 2;
            for (int x = 0; x < w; x++) {
                int b0 = (int) ((long) x * n / w);
                int b1 = Math.max(b0 + 1, (int) ((long) (x + 1) * n / w));
                float lo = 0, hi = 0;
                for (int b = b0; b < b1 && b < n; b++) {
                    lo = Math.min(lo, wf.minAt(b));
                    hi = Math.max(hi, wf.maxAt(b));
                }
                int y0 = mid - (int) (hi * mid * 0.92f);
                int y1 = mid - (int) (lo * mid * 0.92f);
                g.drawLine(x, y0, x, Math.max(y0 + 1, y1));
            }
            // The zero-crossing guide, back to the quiet grey it was before.
            g.setColor(new Color(0x44485A));
            g.drawLine(0, mid, w, mid);
        }
        g.dispose();
        return compose(out, w, h);
    }

    private BufferedImage buildModel(Asset a) {
        if (!MeshIo.supports(a.file())) return errorTile(a, "no preview");
        try {
            ModelData m = MeshIo.load(a.file());
            if (m.meshes.isEmpty()) return errorTile(a, "empty");
            SoftRenderer.Camera cam = new SoftRenderer.Camera();
            cam.azimuth = 0.7f; cam.elevation = 0.35f; cam.distance = 2.9f;
            cam.target = m.center();
            cam.fov = (float) Math.toRadians(42);
            SoftRenderer.Options o = new SoftRenderer.Options();
            o.grid = false;
            BufferedImage img = SoftRenderer.render(m, cam, o, SIZE, SIZE);
            if (img == null) return errorTile(a, "render failed");
            int v = m.vertexCount(), t = m.triangleCount();
            return compose(img, v, t);
        } catch (Exception e) {
            Log.debug("thumb model failed " + a.name + ": " + e.getMessage());
            return errorTile(a, "load failed");
        }
    }

    /** Draws the thumbnail centred on a card-sized canvas. */
    private BufferedImage compose(BufferedImage content, int w, int h) {
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        int x = (SIZE - content.getWidth()) / 2;
        int y = (SIZE - content.getHeight()) / 2;
        g.drawImage(content, x, y, null);
        g.dispose();
        return out;
    }

    private BufferedImage placeholder(Asset a) {
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(new Color(0x252630));
        g.fillRect(0, 0, SIZE, SIZE);
        g.setColor(new Color(0x8F93A3));
        g.setFont(g.getFont().deriveFont(11f));
        String label = a.ext.toUpperCase(java.util.Locale.ROOT);
        int tw = g.getFontMetrics().stringWidth(label);
        g.drawString(label, (SIZE - tw) / 2, SIZE / 2);
        g.dispose();
        return out;
    }

    private BufferedImage errorTile(Asset a, String why) {
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        // Low chroma on purpose: this fills a whole 140px tile, so at full
        // saturation it out-shouted the rest of the grid. The amber mark below
        // is what actually says "failed".
        g.setColor(new Color(0x2E281F));
        g.fillRect(0, 0, SIZE, SIZE);
        g.setColor(new Color(0xF3AB3F));
        g.setFont(g.getFont().deriveFont(11f));
        g.drawString("!", SIZE / 2 - 3, SIZE / 2);
        g.setFont(g.getFont().deriveFont(9f));
        g.drawString(why, 6, SIZE - 8);
        g.dispose();
        return out;
    }

    // ------------------------------------------------------------------ peaks

    private Path peaksFile(Asset a) {
        String row = store.thumbFor(a.path, a.mtime, a.size) == null
                ? null : store.thumbFor(a.path, a.mtime, a.size).peaks;
        return row == null ? peakDir.resolve(peakKey(a) + ".pk") : Path.of(row);
    }

    /** Waveform peaks, decoding and caching on first use. */
    public Waveform peaks(Asset a) {
        Path f = peakDir.resolve(peakKey(a) + ".pk");
        Waveform cachedWave = Waveform.load(f);
        if (cachedWave != null) return cachedWave;

        Waveform wf = Waveform.analyse(a.file(), Waveform.PEAKS);
        try { wf.save(f); } catch (IOException e) { Log.debug("peak write failed: " + e.getMessage()); }
        return wf;
    }

    // ------------------------------------------------------------------ cache

    public void invalidate(Asset a) {
        mem.remove(a.path);
        store.thumbFor(a.path, a.mtime, a.size);
    }

    public void forget(Asset a) {
        mem.remove(a.path);
        try {
            Path f = thumbDir.resolve(key(a) + ".png");
            Files.deleteIfExists(f);
            Files.deleteIfExists(peakDir.resolve(peakKey(a) + ".pk"));
        } catch (IOException ignored) { }
    }

    public void clearAll() {
        mem.clear();
        store.clearThumbs();
        deleteRecursively(thumbDir);
        deleteRecursively(peakDir);
        try { Files.createDirectories(thumbDir); Files.createDirectories(peakDir); }
        catch (IOException e) { Log.warn("thumb cache reset: " + e.getMessage()); }
    }

    private void deleteRecursively(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    public int queueDepth() {
        if (pool instanceof java.util.concurrent.ThreadPoolExecutor) {
            java.util.concurrent.ThreadPoolExecutor t = (java.util.concurrent.ThreadPoolExecutor) pool;
            return t.getQueue().size() + t.getActiveCount();
        }
        return 0;
    }

    /** Blocks until queued thumbnails finish; used before export/tests. */
    public boolean awaitIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (queueDepth() > 0 && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(40); } catch (InterruptedException e) { return false; }
        }
        return queueDepth() == 0;
    }

    public void shutdownNow() { pool.shutdownNow(); }

    @Override public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(2, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
