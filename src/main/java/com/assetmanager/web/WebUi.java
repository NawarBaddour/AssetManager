package com.assetmanager.web;

import com.assetmanager.core.Asset;
import com.assetmanager.core.AssetScanner;
import com.assetmanager.core.AssetStore;
import com.assetmanager.core.AssetWatcher;
import com.assetmanager.core.ThumbnailService;
import com.assetmanager.core.Waveform;
import com.assetmanager.core.mesh.MeshIo;
import com.assetmanager.core.mesh.ModelData;
import com.assetmanager.preview.SoftRenderer;
import com.assetmanager.tools.AtlasPacker;
import com.assetmanager.tools.ImageOps;
import com.assetmanager.util.AudioPlugins;
import com.assetmanager.util.FileUtil;
import com.assetmanager.util.Formats;
import com.assetmanager.util.Images;
import com.assetmanager.util.Log;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The HTTP server behind the AssetManager UI.
 *
 * It binds to loopback only and serves the interface as a handful of static
 * files; the browser draws the UI. All the real work -- scanning, thumbnailing,
 * waveform extraction, 3D rendering, image operations -- happens in this
 * process, so nothing is uploaded anywhere.
 */
public final class WebUi {

    private final AssetStore store;
    private final ThumbnailService thumbs;
    private final AssetScanner scanner;
    private final HttpServer server;
    private final AssetWatcher watcher;

    private final AtomicReference<String> scanStatus = new AtomicReference<>("idle");
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private final Map<Long, Object> modelCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Object> e) {
            return size() > 24;
        }
    };

    private WebUi(AssetStore store, ThumbnailService thumbs) throws IOException {
        this.store = store;
        this.thumbs = thumbs;
        this.scanner = new AssetScanner(store, true);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));

        // Re-index when a watched folder changes, so saving in an external tool
        // shows up without pressing Rescan. Debounced inside the watcher.
        AssetWatcher w = null;
        if (!"0".equals(System.getProperty("assetmanager.watch"))) {
            try {
                w = new AssetWatcher(store, dirs -> {
                    scanStatus.set("change detected, re-indexing...");
                    doScan();
                });
            } catch (Exception e) {
                // Not fatal: the library is still fully usable, just without live
                // re-indexing, and the reason is in the log.
                Log.warn("folder watching unavailable: " + e.getMessage());
            }
        }
        this.watcher = w;
    }

    public static WebUi start(AssetStore store, ThumbnailService thumbs) throws IOException {
        WebUi ui = new WebUi(store, thumbs);
        ui.routes();
        ui.server.start();
        return ui;
    }

    public int port() { return server.getAddress().getPort(); }

    public String url() { return "http://127.0.0.1:" + port() + "/"; }

    public void stop() {
        server.stop(0);
        if (watcher != null) watcher.close();
    }

    /** Indexes folders given on the command line, then kicks off a scan. */
    public void importPaths(List<Path> paths) {
        int files = 0;
        for (Path p : paths) {
            if (Files.isDirectory(p)) {
                store.addRoot(p.toAbsolutePath().toString());
                if (watcher != null) watcher.addRoot(p);
            } else if (Files.isRegularFile(p) && Formats.isIndexable(p.getFileName().toString())) {
                if (scanner.indexOne(p) != null) files++;
            }
        }
        System.out.println("  Indexed " + paths.size() + " startup path(s)" + (files > 0 ? " (" + files + " file(s))" : ""));
        doScan();
    }

    // ------------------------------------------------------------------ routes

    private void routes() {
        // One context, dispatching by hand: relying on longest-prefix matching between
        // "/" and "/api/" is easy to get wrong and silently serves the wrong handler.
        server.createContext("/", (x) -> {
            boolean api = normalise(x.getRequestURI().getPath()).startsWith(API_PREFIX);
            try {
                if (api) handleApi(x); else handleStatic(x);
            } catch (IOException e) {
                // A browser cancels an in-flight request whenever it re-points an
                // <img>, switches asset or navigates away. Writing the response then
                // fails with EPIPE/ECONNRESET, which says nothing about the server --
                // logging it at warn fills the console with noise that looks like a
                // fault every time the user drags the 3D viewer.
                if (isClientGone(e)) {
                    Log.debug("client cancelled the request" + (api ? " (api)" : " (static)")
                            + ": " + e.getMessage());
                } else {
                    Log.warn((api ? "api" : "static") + " io: " + e.getMessage());
                }
            } finally {
                closeQuietly(x);
            }
        });
    }

    /**
     * True when the failure is the peer having gone away rather than a server fault.
     *
     * The message is matched rather than the exception type because the JDK wraps
     * these differently depending on the layer that noticed: the HTTP server
     * surfaces a plain {@link IOException} with the text "Broken pipe", while a
     * socket-level reset can arrive as {@code SocketException} several frames down.
     */
    public static boolean isClientGone(IOException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m == null) continue;
            String lower = m.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("broken pipe")
                    || lower.contains("connection reset")
                    || lower.contains("connection abort")
                    || lower.contains("stream closed")
                    || lower.contains("socket closed")
                    || lower.contains("an existing connection")) {
                return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    /** Closes the exchange, ignoring a second failure from an already-dead socket. */
    private static void closeQuietly(HttpExchange x) {
        try { x.close(); } catch (RuntimeException ignored) { /* already gone */ }
    }

    /**
     * Collapses repeated slashes and drops a trailing one. Hand-typed URLs and
     * naive "base + path" concatenation both produce "//api/x", which would
     * otherwise miss every route and look like a server fault.
     */
    static String normalise(String path) {
        if (path == null || path.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder(path.length());
        char prev = 0;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/' && prev == '/') continue;
            sb.append(c);
            prev = c;
        }
        if (sb.length() > 1 && sb.charAt(sb.length() - 1) == '/') sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    private void handleStatic(HttpExchange x) throws IOException {
        String path = normalise(x.getRequestURI().getPath());
        if (path.equals("/")) path = "/index.html";
        // Refuse anything that could climb out of /web on the classpath.
        if (path.contains("..") || path.indexOf('\\') >= 0 || path.contains("//")) { notFound(x); return; }
        String resource = "/web" + path;
        try (InputStream in = WebUi.class.getResourceAsStream(resource)) {
            if (in == null) { notFound(x); return; }
            byte[] body = in.readAllBytes();
            x.getResponseHeaders().add("Content-Type", contentType(path));
            x.getResponseHeaders().add("Cache-Control", "no-store");
            send(x, 200, body);
        }
    }

    private static final String API_PREFIX = "/api/";

    private void handleApi(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath().substring(API_PREFIX.length());
        if (path.startsWith("/")) path = path.substring(1);
        Map<String, String> q = query(x.getRequestURI().getRawQuery());
        try {
            switch (path) {
                case "assets":
                    sendJson(x, listAssets(q)); break;
                case "asset":
                    sendJson(x, detail(q)); break;
                case "tags":
                    sendJson(x, tagsJson()); break;
                case "stats":
                    sendJson(x, statsJson()); break;
                case "scan":
                    doScan();
                    sendJson(x, "{\"ok\":true}"); break;
                case "addfolder":
                    addFolder(q.get("path"));
                    sendJson(x, "{\"ok\":true}"); break;
                case "roots":
                    sendJson(x, rootsJson()); break;
                case "setnote":
                    setNote(q);
                    sendJson(x, "{\"ok\":true}"); break;
                case "addtag":
                    addTag(q);
                    sendJson(x, "{\"ok\":true}"); break;
                case "removetag":
                    removeTag(q);
                    sendJson(x, "{\"ok\":true}"); break;
                case "batchtag":
                    sendJson(x, batchTag(q)); break;
                case "forget":
                    sendJson(x, forget(q)); break;
                case "copyto":
                    sendJson(x, copyTo(q)); break;
                case "duplicates":
                    sendJson(x, duplicatesJson()); break;
                case "formats":
                    sendJson(x, formatsJson()); break;
                case "imgtool":
                    imageTool(x, q); break;
                case "channelsplit":
                    sendJson(x, channelSplit(q)); break;
                case "atlas":
                    atlas(x, q); break;
                case "clearcache":
                    sendJson(x, clearCache(q)); break;
                case "log":
                    sendJson(x, logJson()); break;
                case "thumb":
                    sendThumb(x, q); break;
                case "image":
                    sendImage(x, q); break;
                case "audio":
                    sendAudio(x, q); break;
                case "peaks":
                    sendJson(x, peaks(q)); break;
                case "model":
                    sendModelRender(x, q); break;
                case "file":
                    sendOriginal(x, q); break;
                case "checkpath":
                    sendJson(x, checkPathJson(q.get("path"))); break;
                default:
                    notFound(x);
            }
        } catch (RuntimeException e) {
            Log.error("web handler " + path + " failed", e);
            send(x, 500, ("{\"error\":" + Json.str(String.valueOf(e.getMessage())) + "}").getBytes(StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------------ assets

    private String listAssets(Map<String, String> q) {
        AssetStore.Query query = buildQuery(q);
        List<Asset> found = store.search(query);

        StringBuilder sb = new StringBuilder("{\"items\":[");
        boolean first = true;
        for (Asset a : found) {
            if (!first) sb.append(',');
            first = false;
            sb.append(assetJson(a));
        }
        long total;
        try {
            // Same predicate, no LIMIT: the true size of the result set, not the page size.
            total = store.count(query);
        } catch (RuntimeException e) { total = found.size(); }

        sb.append("],\"render\":").append(Json.str(thumbs.renderVersion()))
          .append(",\"total\":").append(total)
          .append(",\"shown\":").append(found.size())
          .append(",\"scan\":").append(Json.str(scanStatus.get()))
          .append('}');
        return sb.toString();
    }

    private String assetJson(Asset a) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"id\":").append(a.id)
          .append(",\"name\":").append(Json.str(a.name))
          .append(",\"label\":").append(Json.str(a.displayName()))
          .append(",\"dir\":").append(Json.str(a.parent()))
          .append(",\"ext\":").append(Json.str(a.ext))
          .append(",\"category\":").append(Json.str(a.category.name()))
          .append(",\"size\":").append(a.size)
          .append(",\"mtime\":").append(a.mtime)
          .append(",\"width\":").append(a.width)
          .append(",\"height\":").append(a.height)
          .append(",\"duration\":").append(String.format(java.util.Locale.ROOT, "%.3f", a.duration))
          .append(",\"sampleRate\":").append(a.sampleRate)
          .append(",\"channels\":").append(a.channels)
          .append(",\"bitDepth\":").append(a.bitDepth)
          .append(",\"tris\":").append(a.meshTriangles)
          .append(",\"verts\":").append(a.meshVertices)
          .append(",\"problem\":").append(com.assetmanager.core.MetaProbe.isProblem(a))
          .append(",\"preview\":").append(Json.str(previewKind(a)))
          .append(",\"tags\":").append(Json.strings(a.tags()))
          .append('}');
        return sb.toString();
    }

    private static String previewKind(Asset a) {
        if (a.category == Formats.Category.IMAGE) {
            return Formats.isImageLoadable(a.ext) ? "image"
                 : Formats.IMAGE_NO_DECODER.contains(a.ext) ? "vector" : "none";
        }
        if (a.category == Formats.Category.AUDIO)
            return AudioPlugins.canDecode(a.file()) ? "audio" : "none";
        if (a.category == Formats.Category.MODEL)
            return Formats.isModelLoadable(a.ext) ? "model" : "none";
        return "none";
    }

    private String detail(Map<String, String> q) {
        Asset a = lookup(q);
        if (a == null) return "{\"error\":\"not found\"}";
        StringBuilder sb = new StringBuilder(assetJson(a));
        sb.setLength(sb.length() - 1);
        sb.append(",\"path\":").append(Json.str(a.path));
        sb.append(",\"note\":").append(Json.str(a.note == null ? "" : a.note));
        sb.append(",\"addedAt\":").append(a.addedAt);
        sb.append(",\"hash\":").append(Json.str(a.contentHash == null ? "" : a.contentHash));
        sb.append('}');
        return sb.toString();
    }

    private String tagsJson() {
        StringBuilder sb = new StringBuilder("{\"tags\":[");
        boolean first = true;
        for (AssetStore.TagRow t : store.allTags()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":").append(Json.str(t.name))
              .append(",\"count\":").append(t.count).append('}');
        }
        return sb.append("]}").toString();
    }

    private String rootsJson() {
        StringBuilder sb = new StringBuilder("{\"roots\":[");
        boolean first = true;
        for (AssetStore.RootRow r : store.roots()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"path\":").append(Json.str(r.path))
              .append(",\"watch\":").append(r.watch).append('}');
        }
        return sb.append("]}").toString();
    }

    private String statsJson() {
        Map<String, Object> s = store.stats();
        StringBuilder sb = new StringBuilder("{\"count\":").append(s.get("count"))
                .append(",\"bytes\":").append(s.get("bytes"));
        Object byCat = s.get("byCategory");
        if (byCat instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) byCat;
            sb.append(",\"byCategory\":{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(Json.str(String.valueOf(e.getKey()))).append(':').append(e.getValue());
            }
            sb.append('}');
        }
        return sb.append('}').toString();
    }

    private AssetStore.Query buildQuery(Map<String, String> q) {
        AssetStore.Query query = new AssetStore.Query();
        query.text = orEmpty(q.get("q"));
        String cat = q.get("category");
        if (cat != null && !cat.isEmpty() && !"ALL".equals(cat)) {
            try { query.category = Formats.Category.valueOf(cat); } catch (IllegalArgumentException ignored) { }
        }
        String tags = q.get("tags");
        if (tags != null && !tags.isEmpty()) {
            for (String t : tags.split(",")) if (!t.isBlank()) query.tagNamesAll.add(t.trim());
        }
        String minDim = q.get("mindim");
        if (minDim != null && !minDim.isBlank()) {
            try { query.minDim = Integer.parseInt(minDim); } catch (NumberFormatException ignored) { }
        }
        query.onlyUntagged = "1".equals(q.get("untagged"));
        query.onlyDuplicates = "1".equals(q.get("duplicates"));
        query.onlyProblems = "1".equals(q.get("problems"));
        String sort = q.get("sort");
        if (sort != null) {
            for (AssetStore.Query.Sort s : AssetStore.Query.Sort.values()) {
                if (s.name().equals(sort)) { query.sort = s; break; }
            }
        }
        int limit = 3000;
        try {
            String l = q.get("limit");
            if (l != null) limit = Math.max(1, Math.min(20000, Integer.parseInt(l)));
        } catch (NumberFormatException ignored) { }
        query.limit = limit;
        return query;
    }

    // ----------------------------------------------------------------- binary

    private void sendThumb(HttpExchange x, Map<String, String> q) throws IOException {
        Asset a = lookup(q);
        if (a == null) { notFound(x); return; }
        BufferedImage img = thumbs.cached(a);
        if (img == null) {
            thumbs.request(a, ignored -> { });
            thumbs.awaitIdle(15000);
            img = thumbs.cached(a);
        }
        if (img == null) { placeholder(x); return; }
        sendPng(x, img);
    }

    private void sendImage(HttpExchange x, Map<String, String> q) throws IOException {
        Asset a = lookup(q);
        if (a == null) { notFound(x); return; }
        try {
            BufferedImage img = Images.read(a.file());
            if (img.getWidth() > 4096 || img.getHeight() > 4096) img = Images.scaleToFit(img, 4096);
            sendPng(x, img);
        } catch (Exception e) {
            send(x, 415, ("Cannot decode ." + a.ext).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Streams the original audio so the browser can play and seek it natively. */
    private void sendAudio(HttpExchange x, Map<String, String> q) throws IOException {
        Asset a = lookup(q);
        if (a == null) { notFound(x); return; }
        Path p = a.file();
        if (!Files.isReadable(p)) { notFound(x); return; }
        x.getResponseHeaders().add("Content-Type", mimeFor(p));
        x.getResponseHeaders().add("Accept-Ranges", "none");
        byte[] data = Files.readAllBytes(p);
        send(x, 200, data);
    }

    private void sendOriginal(HttpExchange x, Map<String, String> q) throws IOException {
        Asset a = lookup(q);
        if (a == null) { notFound(x); return; }
        Path p = a.file();
        if (!Files.isReadable(p)) { notFound(x); return; }
        byte[] data = Files.readAllBytes(p);
        String name = a.name.replace("\"", "");
        // dl=1 switches to attachment, which is what makes the browser hand the
        // file to the operating system and offer its own save dialog.
        String disposition = "1".equals(q.get("dl")) ? "attachment" : "inline";
        x.getResponseHeaders().add("Content-Type", mimeFor(p));
        x.getResponseHeaders().add("Content-Disposition", disposition + "; filename=\"" + name + "\"");
        send(x, 200, data);
    }

    /**
     * Reports whether a path is a usable folder, so the path-based dialog can say
     * so before the user commits rather than after.
     */
    private String checkPathJson(String path) {
        if (path == null || path.isBlank()) return pathCheck(false, "enter a path", false);
        final Path p;
        try {
            p = Path.of(path).toAbsolutePath().normalize();
        } catch (Exception e) {
            return pathCheck(false, "not a valid path", false);
        }
        if (!Files.exists(p)) return pathCheck(false, "does not exist", false);
        if (!Files.isDirectory(p)) return pathCheck(false, "not a folder", false);
        if (!Files.isReadable(p)) return pathCheck(false, "not readable", false);
        return pathCheck(true, underRoot(p) ? "already indexed" : "will be added to the library", true);
    }

    private String pathCheck(boolean ok, String note, boolean usable) {
        return "{\"ok\":" + ok + ",\"usable\":" + usable + ",\"note\":" + Json.str(note) + "}";
    }

    private String peaks(Map<String, String> q) {
        Asset a = lookup(q);
        if (a == null) return "{\"error\":\"not found\"}";
        Waveform w = thumbs.peaks(a);
        StringBuilder sb = new StringBuilder("{\"buckets\":[");
        int n = Math.min(w.bucketCount(), 4000);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.ROOT, "%.3f", w.minAt(i)))
              .append(',')
              .append(String.format(java.util.Locale.ROOT, "%.3f", w.maxAt(i)));
        }
        return sb.append("],\"duration\":").append(String.format(java.util.Locale.ROOT, "%.3f", w.duration()))
                 .append(",\"sampleRate\":").append(w.sampleRate())
                 .append(",\"channels\":").append(w.channels()).append('}').toString();
    }

    /** Server-side render of a 3D model; the browser drives the camera. */
    private void sendModelRender(HttpExchange x, Map<String, String> q) throws IOException {
        Asset a = lookup(q);
        if (a == null) { notFound(x); return; }
        if (!MeshIo.supports(a.file())) {
            send(x, 415, ("No renderer for ." + a.ext).getBytes(StandardCharsets.UTF_8));
            return;
        }
        int w = intParam(q, "w", 640, 64, 1600);
        int h = intParam(q, "h", 480, 64, 1200);

        Object cached = modelCache.get(a.id);
        ModelData model;
        if (cached instanceof ModelData) {
            model = (ModelData) cached;
        } else {
            try {
                model = MeshIo.load(a.file());
                modelCache.put(a.id, model);
            } catch (Exception e) {
                send(x, 415, ("Cannot load model: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
                return;
            }
        }

        SoftRenderer.Camera cam = new SoftRenderer.Camera();
        cam.azimuth = floatParam(q, "az", 0.7f);
        cam.elevation = floatParam(q, "el", 0.3f);
        cam.distance = Math.max(0.6f, Math.min(20f, floatParam(q, "d", 2.8f)));
        cam.target = model.center();
        if (model.boundsValid) {
            cam.target[1] = model.center()[1];
        }

        SoftRenderer.Options o = new SoftRenderer.Options();
        o.grid = "1".equals(q.get("grid"));
        o.wireframe = "1".equals(q.get("wire"));
        o.textured = !"0".equals(q.get("tex"));

        BufferedImage img = SoftRenderer.render(model, cam, o, w, h);
        if (img == null) { notFound(x); return; }
        sendPng(x, img);
    }

    // ----------------------------------------------------------------- actions

    private void doScan() {
        if (!scanning.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                List<Path> roots = new ArrayList<>();
                for (AssetStore.RootRow r : store.roots()) roots.add(Path.of(r.path));
                if (roots.isEmpty()) { scanStatus.set("no folders indexed"); return; }
                scanStatus.set("scanning...");
                AssetScanner.Report rep = scanner.scan(roots, (done, total, cur) -> {
                    scanStatus.set("scanning " + done + "/" + total + " " + cur);
                }, new AtomicBoolean());
                scanStatus.set(rep.summary());
            } catch (Exception e) {
                scanStatus.set("scan failed: " + e.getMessage());
                Log.error("web scan failed", e);
            } finally {
                scanning.set(false);
            }
        }, "web-scan");
        t.setDaemon(true);
        t.start();
    }

    private void addFolder(String path) {
        if (path == null || path.isBlank()) return;
        Path p = Path.of(path).toAbsolutePath();
        if (!Files.isDirectory(p)) return;
        store.addRoot(p.toString());
        if (watcher != null) watcher.addRoot(p);
        doScan();
    }

    private void setNote(Map<String, String> q) {
        Asset a = lookup(q);
        if (a == null) return;
        store.setNote(a.id, orEmpty(q.get("note")));
    }

    private void addTag(Map<String, String> q) {
        Asset a = lookup(q);
        if (a == null) return;
        String tag = q.get("tag");
        if (tag == null || tag.isBlank()) return;
        store.addTagToAssets(List.of(a.id), tag.trim());
    }

    private void removeTag(Map<String, String> q) {
        Asset a = lookup(q);
        if (a == null) return;
        String tag = q.get("tag");
        if (tag == null || tag.isBlank()) return;
        store.removeTagFromAssets(List.of(a.id), tag);
    }

    /** Adds or removes a tag on a whole selection in one statement. */
    private String batchTag(Map<String, String> q) {
        String tag = q.get("tag");
        if (tag == null || tag.isBlank()) return err("no tag given");
        List<Long> ids = existing(idList(q));
        if (ids.isEmpty()) return err("nothing selected");
        String t = tag.trim();
        if ("remove".equals(q.get("op"))) store.removeTagFromAssets(ids, t);
        else store.addTagToAssets(ids, t);
        return "{\"ok\":true,\"count\":" + ids.size() + ",\"tag\":" + Json.str(t) + "}";
    }

    /**
     * Drops assets from the index. The files stay on disk: this is "stop tracking
     * this", not "delete this".
     */
    private String forget(Map<String, String> q) {
        List<Long> ids = existing(idList(q));
        if (ids.isEmpty()) return err("nothing selected");
        List<String> paths = new ArrayList<>();
        for (Long id : ids) {
            Asset a = store.byId(id);
            paths.add(a.path);
            thumbs.forget(a);
        }
        store.deleteByPaths(paths);
        return "{\"ok\":true,\"count\":" + paths.size() + "}";
    }

    /** Copies the selection into a folder, for dropping into an engine project. */
    private String copyTo(Map<String, String> q) {
        String dest = q.get("dest");
        if (dest == null || dest.isBlank()) return err("no destination folder");
        List<Long> ids = existing(idList(q));
        if (ids.isEmpty()) return err("nothing selected");
        Path dir = Path.of(dest).toAbsolutePath();
        if (!Files.isDirectory(dir)) return err("destination is not a folder: " + dir);
        int ok = 0;
        List<String> failed = new ArrayList<>();
        for (Long id : ids) {
            Asset a = store.byId(id);
            if (a == null) continue;
            try {
                FileUtil.copy(a.file(), dir.resolve(a.name));
                ok++;
            } catch (Exception e) {
                failed.add(a.name);
                Log.warn("copy failed " + a.name + ": " + e.getMessage());
            }
        }
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"copied\":").append(ok);
        sb.append(",\"failed\":").append(failed.size());
        sb.append(",\"files\":[");
        for (int i = 0; i < failed.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(Json.str(failed.get(i)));
        }
        return sb.append("]}").toString();
    }

    /** Duplicate groups by content hash, plus the space reclaimable by dropping the copies. */
    private String duplicatesJson() {
        Map<String, List<Asset>> groups = store.duplicates();
        long wasted = 0;
        StringBuilder sb = new StringBuilder("{\"groups\":[");
        boolean first = true;
        for (Map.Entry<String, List<Asset>> e : groups.entrySet()) {
            List<Asset> g = e.getValue();
            // Everything past the first copy is redundant by definition.
            for (int i = 1; i < g.size(); i++) wasted += g.get(i).size;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"hash\":").append(Json.str(e.getKey()))
              .append(",\"count\":").append(g.size())
              .append(",\"size\":").append(g.get(0).size)
              .append(",\"reclaimable\":").append(wasted)
              .append(",\"items\":[");
            for (int i = 0; i < g.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(assetJson(g.get(i)));
            }
            sb.append("]}");
        }
        return sb.append("],\"groupCount\":").append(groups.size())
                 .append(",\"reclaimable\":").append(wasted).append('}').toString();
    }

    private String clearCache(Map<String, String> q) {
        thumbs.clearAll();
        return "{\"ok\":true}";
    }

    private String logJson() {
        StringBuilder sb = new StringBuilder("{\"entries\":[");
        boolean first = true;
        for (Log.Entry e : Log.tail()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"level\":").append(Json.str(e.level))
              .append(",\"time\":").append(e.time)
              .append(",\"message\":").append(Json.str(e.msg))
              .append('}');
        }
        return sb.append("]}").toString();
    }

    /** What the texture workbench needs to build its controls: formats and channels. */
    private String formatsJson() {
        StringBuilder sb = new StringBuilder("{\"formats\":[");
        Set<String> have = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        have.addAll(List.of(Images.writableFormats()));
        boolean first = true;
        for (String want : new String[] { "png", "jpg", "tif", "bmp", "gif" }) {
            if (!have.contains(want)) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"ext\":").append(Json.str(want))
              .append(",\"label\":").append(Json.str(Images.formatLabel(want)))
              .append('}');
        }
        if (first) sb.append("{\"ext\":\"png\",\"label\":\"PNG\"}");
        sb.append("],\"channels\":[");
        for (ImageOps.Channel c : ImageOps.Channel.values()) {
            if (c != ImageOps.Channel.R) sb.append(',');
            sb.append(Json.str(c.name()));
        }
        return sb.append("]}").toString();
    }

    // -------------------------------------------------------------- image tools

    /**
     * One image in, one image out. Without {@code save} it returns the result as
     * a PNG for the workbench preview; with it, the result is written to disk and
     * the response is JSON describing what happened.
     */
    private void imageTool(HttpExchange x, Map<String, String> q) throws IOException {
        Asset a = lookup(q);
        if (a == null) { notFound(x); return; }
        if (!"IMAGE".equals(a.category.name()) || !Formats.isImageLoadable(a.ext)) {
            send(x, 415, ("Texture tools need a decodable image, not ." + a.ext).getBytes(StandardCharsets.UTF_8));
            return;
        }
        BufferedImage result;
        try {
            result = applyOp(Images.read(a.file()), q);
        } catch (Exception e) {
            send(x, 400, ("Operation failed: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
            return;
        }
        // inline=1 encodes and returns the bytes without touching the disk, so the
        // browser can write them wherever the user picked in a native save dialog.
        // Checked before the preview branch, which would otherwise swallow it.
        if ("1".equals(q.get("inline"))) {
            try {
                String format = writerName(q.get("format"));
                sendEncoded(x, encodeImage(result, format), format);
            } catch (Exception e) {
                sendJson(x, err("encode failed: " + e.getMessage()));
            }
            return;
        }

        if (!"1".equals(q.get("save"))) { sendPng(x, result); return; }

        try {
            String format = writerName(q.get("format"));
            Path target = "1".equals(q.get("overwrite"))
                    ? a.file()
                    : Path.of(orEmpty(q.get("dest"))).toAbsolutePath();
            if (target == null || target.getNameCount() == 0) {
                sendJson(x, err("no destination file"));
                return;
            }
            writeImage(result, target, format);
            // The file just changed on disk, so its hash, size and thumbnail are stale.
            // Only re-index when it landed inside an indexed folder: saving to
            // /tmp would otherwise add a row the browser has no folder for.
            if (underRoot(target)) scanner.indexOne(target);
            sendJson(x, "{\"ok\":true,\"file\":" + Json.str(target.toString())
                    + ",\"width\":" + result.getWidth()
                    + ",\"height\":" + result.getHeight()
                    + ",\"format\":" + Json.str(format) + "}");
        } catch (Exception e) {
            Log.error("image tool save failed", e);
            sendJson(x, err("save failed: " + e.getMessage()));
        }
    }

    /** Runs one named operation from the query string. Pure; the source is never mutated. */
    private static BufferedImage applyOp(BufferedImage src, Map<String, String> q) {
        String op = orEmpty(q.get("op"));
        switch (op) {
            case "resize":
                return ImageOps.resize(src,
                        intParam(q, "w", src.getWidth(), 1, 16384),
                        intParam(q, "h", src.getHeight(), 1, 16384));
            case "pot":
                return ImageOps.toPowerOfTwo(src, "1".equals(q.get("upscale")));
            case "rot90":  return ImageOps.rotate90(src, true);
            case "rot270": return ImageOps.rotate90(src, false);
            case "fliph":  return ImageOps.flipH(src);
            case "flipv":  return ImageOps.flipV(src);
            case "crop":
                return ImageOps.crop(src,
                        intParam(q, "x", 0, 0, 16384),
                        intParam(q, "y", 0, 0, 16384),
                        intParam(q, "w", src.getWidth(), 1, 16384),
                        intParam(q, "h", src.getHeight(), 1, 16384));
            case "crop50":
                return ImageOps.crop(src,
                        src.getWidth() / 4, src.getHeight() / 4,
                        src.getWidth() / 2, src.getHeight() / 2);
            case "pack":
                return ImageOps.packChannels(src,
                        channel(q, "r", ImageOps.Channel.R),
                        channel(q, "g", ImageOps.Channel.G),
                        channel(q, "b", ImageOps.Channel.B),
                        channel(q, "a", ImageOps.Channel.A));
            case "normal":
                return ImageOps.heightToNormal(src, floatParam(q, "strength", 1.0f));
            case "opacity":
                return ImageOps.setOpacity(src, floatParam(q, "factor", 1.0f));
            default:
                throw new IllegalArgumentException("unknown operation '" + op + "'");
        }
    }

    /** Writes R, G and B as three greyscale PNGs beside the source, as the desktop tool did. */
    private String channelSplit(Map<String, String> q) {
        Asset a = lookup(q);
        if (a == null) return err("no such asset");
        if (!"IMAGE".equals(a.category.name()) || !Formats.isImageLoadable(a.ext)) {
            return err("not a decodable image");
        }
        try {
            BufferedImage src = Images.read(a.file());
            Path dir = a.file().toAbsolutePath().getParent();
            List<String> written = new ArrayList<>();
            for (ImageOps.Channel c : new ImageOps.Channel[] {
                    ImageOps.Channel.R, ImageOps.Channel.G, ImageOps.Channel.B }) {
                Path out = FileUtil.uniquePath(dir.resolve(Formats.baseName(a.name) + "_" + c + ".png"));
                writeImage(ImageOps.extractChannel(src, c), out, "png");
                written.add(out.toString());
            }
            StringBuilder sb = new StringBuilder("{\"ok\":true,\"count\":").append(written.size())
                    .append(",\"files\":[");
            for (int i = 0; i < written.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(Json.str(written.get(i)));
            }
            return sb.append("]}").toString();
        } catch (Exception e) {
            Log.warn("channel split failed", e);
            return err("split failed: " + e.getMessage());
        }
    }

    /** Skyline-packs the selected images into one atlas. */
    private void atlas(HttpExchange x, Map<String, String> q) throws IOException {
        List<AtlasPacker.Entry> entries = new ArrayList<>();
        for (Long id : idList(q)) {
            Asset a = store.byId(id);
            if (a == null || !"IMAGE".equals(a.category.name()) || !Formats.isImageLoadable(a.ext)) continue;
            try {
                entries.add(new AtlasPacker.Entry(a.name, Images.read(a.file())));
            } catch (Exception e) {
                Log.warn("atlas skipped " + a.name + ": " + e.getMessage());
            }
        }
        if (entries.isEmpty()) {
            send(x, 415, "No decodable images in the selection.".getBytes(StandardCharsets.UTF_8));
            return;
        }
        int pad = intParam(q, "pad", 2, 0, 64);
        AtlasPacker.Result r = AtlasPacker.pack(entries, pad);

        // An atlas is only half-useful without knowing where each tile landed:
        // an engine needs the UV rectangle to address them. meta=1 returns the
        // placement map as JSON instead of the image, so the workbench can show
        // it and the user can copy it into their pipeline.
        if ("1".equals(q.get("meta"))) { sendJson(x, atlasJson(r)); return; }

        // inline=1: hand back the encoded bytes so the browser can write them
        // through a handle from the native save dialog. Before the preview
        // branch, which would otherwise always win.
        if ("1".equals(q.get("inline"))) {
            try {
                String format = writerName(q.get("format"));
                sendEncoded(x, encodeImage(r.atlas, format), format);
            } catch (Exception e) {
                sendJson(x, err("encode failed: " + e.getMessage()));
            }
            return;
        }

        if (!"1".equals(q.get("save"))) { sendPng(x, r.atlas); return; }
        try {
            String format = writerName(q.get("format"));
            Path target = Path.of(orEmpty(q.get("dest"))).toAbsolutePath();
            if (target == null || target.getNameCount() == 0) {
                sendJson(x, err("no destination file"));
                return;
            }
            writeImage(r.atlas, target, format);
            sendJson(x, "{\"ok\":true,\"file\":" + Json.str(target.toString())
                    + ",\"width\":" + r.atlas.getWidth()
                    + ",\"height\":" + r.atlas.getHeight()
                    + ",\"padding\":" + r.padding
                    + ",\"count\":" + r.placements.size()
                    + ",\"efficiency\":"
                    + String.format(java.util.Locale.ROOT, "%.4f", r.efficiency())
                    + ",\"tiles\":" + atlasTiles(r)
                    + ",\"format\":" + Json.str(format) + "}");
        } catch (Exception e) {
            Log.error("atlas save failed", e);
            sendJson(x, err("save failed: " + e.getMessage()));
        }
    }

    /**
     * The placement map for a packed atlas: the atlas size, how well it filled,
     * and each tile's pixel rectangle plus the matching UV rectangle.
     *
     * UVs use the usual top-left origin, which is what most exporters and
     * importers use directly. Engines with a bottom-left V origin will want
     * {@code v -> 1 - v}.
     */
    private static String atlasJson(AtlasPacker.Result r) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"width\":").append(r.atlas.getWidth())
          .append(",\"height\":").append(r.atlas.getHeight())
          .append(",\"padding\":").append(r.padding)
          .append(",\"count\":").append(r.placements.size())
          .append(",\"efficiency\":")
          .append(String.format(java.util.Locale.ROOT, "%.4f", r.efficiency()))
          .append(",\"tiles\":").append(atlasTiles(r))
          .append('}');
        return sb.toString();
    }

    /** The {@code tiles} array, shared by the metadata and the save response. */
    private static String atlasTiles(AtlasPacker.Result r) {
        int w = r.atlas.getWidth(), h = r.atlas.getHeight();
        StringBuilder sb = new StringBuilder(64 + r.placements.size() * 96);
        sb.append('[');
        for (int i = 0; i < r.placements.size(); i++) {
            AtlasPacker.Rectangle t = r.placements.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":").append(Json.str(t.name()))
              .append(",\"x\":").append(t.x())
              .append(",\"y\":").append(t.y())
              .append(",\"w\":").append(t.w())
              .append(",\"h\":").append(t.h())
              .append(",\"u0\":").append(uv(t.x(), w))
              .append(",\"v0\":").append(uv(t.y(), h))
              .append(",\"u1\":").append(uv(t.x() + t.w(), w))
              .append(",\"v1\":").append(uv(t.y() + t.h(), h))
              .append('}');
        }
        return sb.append(']').toString();
    }

    private static String uv(int px, int extent) {
        return String.format(java.util.Locale.ROOT, "%.6f", px / (double) extent);
    }
    /**
     * Encodes to a format, flattening alpha when the format cannot carry it.
     *
     * Split out from {@link #writeImage} because the native save dialog cannot
     * tell the page where the user wants the file, so the browser does the
     * writing -- and then it needs the finished bytes, not a path.
     */
    private static byte[] encodeImage(BufferedImage img, String format) throws IOException {
        BufferedImage toWrite = img;
        if (Images.isOpaqueFormat(format) && img.getColorModel().hasAlpha()) {
            toWrite = Images.flatten(img, java.awt.Color.BLACK);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (!ImageIO.write(toWrite, format, bos)) {
            throw new IOException("No writer for format '" + format + "'");
        }
        return bos.toByteArray();
    }

    /**
     * Writes through a temp file so a crash mid-encode cannot truncate the
     * original.
     */
    private static void writeImage(BufferedImage img, Path target, String format) throws IOException {
        final byte[] bytes = encodeImage(img, format);
        FileUtil.writeAtomic(target, os -> os.write(bytes));
    }

    /** Returns encoded image bytes, for the browser to place itself. */
    private static void sendEncoded(HttpExchange x, byte[] bytes, String format) throws IOException {
        x.getResponseHeaders().add("Content-Type", mimeForFormat(format));
        x.getResponseHeaders().add("Cache-Control", "no-store");
        send(x, 200, bytes);
    }

    private static String mimeForFormat(String format) {
        switch (format.toLowerCase(java.util.Locale.ROOT)) {
            case "png":  return "image/png";
            case "jpeg": case "jpg": return "image/jpeg";
            case "gif":  return "image/gif";
            case "bmp":  return "image/bmp";
            case "tiff": case "tif": return "image/tiff";
            default: return "application/octet-stream";
        }
    }

    private static String writerName(String ext) {
        String e = orEmpty(ext).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (e.isEmpty()) return "png";
        String name = Images.writerForExt(e);
        for (String have : Images.writableFormats()) {
            if (have.equalsIgnoreCase(name)) return have;
        }
        throw new IllegalArgumentException("no writer for format '" + ext + "'");
    }

    private static ImageOps.Channel channel(Map<String, String> q, String key, ImageOps.Channel def) {
        String v = q.get(key);
        if (v == null || v.isBlank()) return def;
        try { return ImageOps.Channel.valueOf(v.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { return def; }
    }

    // ------------------------------------------------------------------ helpers

    /** Parses the {@code ids} selection parameter, skipping anything unparseable. */
    private Set<Long> idList(Map<String, String> q) {
        Set<Long> out = new LinkedHashSet<>();
        String raw = q.get("ids");
        if (raw == null || raw.isBlank()) {
            String one = q.get("id");
            if (one != null) raw = one;
        }
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(",")) {
            try { out.add(Long.parseLong(part.trim())); }
            catch (NumberFormatException ignored) { /* a bad id is simply skipped */ }
        }
        return out;
    }

    private boolean underRoot(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        for (AssetStore.RootRow r : store.roots()) {
            try {
                if (abs.startsWith(Path.of(r.path).toAbsolutePath().normalize())) return true;
            } catch (Exception ignored) { /* a malformed root simply does not match */ }
        }
        return false;
    }

    /**
     * Drops ids that no longer resolve to an asset. The browser can hold a stale
     * id -- one forgotten, or re-indexed away -- and the tag tables have foreign
     * keys, so passing one straight through fails the whole batch.
     */
    private List<Long> existing(Set<Long> ids) {
        List<Long> out = new ArrayList<>();
        for (Long id : ids) {
            if (store.byId(id) != null) out.add(id);
        }
        return out;
    }

    private static String err(String message) {
        return "{\"error\":" + Json.str(message) + "}";
    }

    private Asset lookup(Map<String, String> q) {
        String id = q.get("id");
        if (id == null) return null;
        try { return store.byId(Long.parseLong(id)); }
        catch (NumberFormatException e) { return null; }
    }

    private static String orEmpty(String s) { return s == null ? "" : s; }

    private static int intParam(Map<String, String> q, String k, int def, int lo, int hi) {
        try { return Math.max(lo, Math.min(hi, Integer.parseInt(q.get(k)))); }
        catch (Exception e) { return def; }
    }

    private static float floatParam(Map<String, String> q, String k, float def) {
        try { return Float.parseFloat(q.get(k)); }
        catch (Exception e) { return def; }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) continue;
            out.put(decode(pair.substring(0, i)), decode(pair.substring(i + 1)));
        }
        return out;
    }

    private static String decode(String s) {
        try { return URLDecoder.decode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }

    private static String mimeFor(Path p) {
        String e = Formats.extOf(p.getFileName().toString());
        switch (e) {
            case "png":  return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif":  return "image/gif";
            case "webp": return "image/webp";
            case "bmp":  return "image/bmp";
            case "wav": case "wave": return "audio/wav";
            case "mp3":  return "audio/mpeg";
            case "ogg": case "oga": return "audio/ogg";
            case "aif": case "aiff": return "audio/aiff";
            case "svg":  return "image/svg+xml";
            case "json": return "application/json";
            default: return "application/octet-stream";
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css"))  return "text/css; charset=utf-8";
        if (path.endsWith(".js"))   return "application/javascript; charset=utf-8";
        if (path.endsWith(".svg"))  return "image/svg+xml";
        if (path.endsWith(".png"))  return "image/png";
        return "application/octet-stream";
    }

    private void sendPng(HttpExchange x, BufferedImage img) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        x.getResponseHeaders().add("Content-Type", "image/png");
        x.getResponseHeaders().add("Cache-Control", "public, max-age=300");
        send(x, 200, bos.toByteArray());
    }

    private void sendJson(HttpExchange x, String json) throws IOException {
        x.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        x.getResponseHeaders().add("Cache-Control", "no-store");
        send(x, 200, json.getBytes(StandardCharsets.UTF_8));
    }

    private void placeholder(HttpExchange x) throws IOException {
        BufferedImage img = new BufferedImage(160, 160, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(0x252630));
        g.fillRect(0, 0, 160, 160);
        g.setColor(new java.awt.Color(0x8F93A3));
        g.drawString("no preview", 34, 84);
        g.dispose();
        sendPng(x, img);
    }

    private static void notFound(HttpExchange x) throws IOException {
        send(x, 404, "not found".getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange x, int code, byte[] body) throws IOException {
        x.sendResponseHeaders(code, body.length);
        try (OutputStream os = x.getResponseBody()) { os.write(body); }
    }
}
