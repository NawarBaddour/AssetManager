package com.assetmanager.core.mesh;

import com.assetmanager.util.Images;
import com.assetmanager.util.Log;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wavefront OBJ + MTL reader.
 * Handles v/vt/vn, negative (relative) indices, groups, usemtl, mtllib, and line continuations.
 */
public final class ObjLoader {

    /** Faces are split per (object, material) pair, which is how exporters emit them. */
    private static final class Builder {
        final String name;
        Material material;
        final Map<String, Integer> vertexMap = new HashMap<>();
        final List<Float> pos = new ArrayList<>();
        final List<Float> nrm = new ArrayList<>();
        final List<Float> uv  = new ArrayList<>();
        final List<Float> col = new ArrayList<>();
        final List<Integer> idx = new ArrayList<>();
        int groupKey;
        /** True once a face actually supplied a vn index. */
        boolean anyNormals = false;

        Builder(String name, Material material, int groupKey) {
            this.name = name; this.material = material; this.groupKey = groupKey;
        }

        int vertex(int vi, int ti, int ni) {
            String key = vi + "/" + ti + "/" + ni;
            Integer existing = vertexMap.get(key);
            if (existing != null) return existing;
            int id = pos.size() / 3;
            vertexMap.put(key, id);
            // resolve() hands back 1-based OBJ indices; the backing lists are 0-based
            int v = vi - 1, n = ni - 1, t = ti - 1;
            if (v < 0 || v * 3 + 2 >= V.size()) {
                pos.add(0f); pos.add(0f); pos.add(0f);
            } else {
                pos.add(V.get(v * 3)); pos.add(V.get(v * 3 + 1)); pos.add(V.get(v * 3 + 2));
            }
            if (COLS != null && v >= 0 && v * 3 + 2 < COLS.size()) {
                col.add(COLS.get(v*3)); col.add(COLS.get(v*3+1)); col.add(COLS.get(v*3+2));
            } else { col.add(1f); col.add(1f); col.add(1f); }
            if (n >= 0 && N != null && n * 3 + 2 < N.size()) {
                nrm.add(N.get(n*3)); nrm.add(N.get(n*3+1)); nrm.add(N.get(n*3+2));
                anyNormals = true;
            } else { nrm.add(0f); nrm.add(0f); nrm.add(0f); }
            if (t >= 0 && T != null && t * 2 + 1 < T.size()) {
                uv.add(T.get(t*2)); uv.add(1f - T.get(t*2 + 1)); // OBJ V runs bottom-up
            } else { uv.add(0f); uv.add(0f); }
            return id;
        }

        boolean isEmpty() { return idx.isEmpty(); }

        Mesh toMesh() {
            Mesh m = new Mesh();
            m.name = name;
            m.material = material;
            m.positions = toArray(pos);
            // Only trust the normal array if the file really supplied vn entries;
            // a zero-filled one would pass a length check and shade as NaN.
            m.normals = anyNormals ? toArray(nrm) : null;
            m.uvs = toArray(uv);
            m.colors = toArray(col);
            m.indices = new int[idx.size()];
            for (int i = 0; i < idx.size(); i++) m.indices[i] = idx.get(i);
            if (!m.hasNormals()) m.generateNormals();
            return m;
        }
    }

    private static List<Float> V, T, N, COLS;
    private static final Map<String, Material> MATERIALS = new LinkedHashMap<>();
    private static final Map<String, BufferedImage> TEX_CACHE = new HashMap<>();
    private static Path BASE_DIR;
    private static int MAX_TEXTURE = 1024;

    private ObjLoader() {}

    public static ModelData load(Path objFile) throws IOException {
        V = new ArrayList<>(); T = new ArrayList<>(); N = new ArrayList<>(); COLS = null;
        MATERIALS.clear(); TEX_CACHE.clear();
        BASE_DIR = objFile.toAbsolutePath().getParent();

        ModelData model = new ModelData();
        model.sourceName = objFile.getFileName().toString();

        Map<Integer, Builder> builders = new LinkedHashMap<>();
        String objectName = objFile.getFileName().toString();
        String groupName = "default";
        Material current = defaultMaterial();
        int groupSeq = 0;

        try (BufferedReader br = Files.newBufferedReader(objFile, StandardCharsets.UTF_8)) {
            String pending = null;
            String line;
            while ((line = br.readLine()) != null) {
                if (pending != null) { line = pending + line; pending = null; }
                if (line.endsWith("\\")) { pending = line.substring(0, line.length() - 1); continue; }

                line = trimComment(line).trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split("\\s+");
                String key = parts[0];

                switch (key) {
                    case "v":  readVertex(parts); break;
                    case "vt": readTexcoord(parts); break;
                    case "vn": readNormal(parts); break;
                    case "f": {
                        final int gk = groupSeq;
                        Builder b = builders.get(gk);
                        if (b == null) {
                            b = new Builder(objectName + "/" + groupName, current, gk);
                            builders.put(gk, b);
                        }
                        b.material = current;
                        readFace(parts, b, model);
                        break;
                    }
                    case "o":
                    case "g":
                        if (parts.length > 1) { groupName = parts[1]; groupSeq++; }
                        break;
                    case "usemtl":
                        if (parts.length > 1) {
                            current = MATERIALS.getOrDefault(parts[1], namedFallback(parts[1]));
                            groupSeq++; // force a new builder for clean material boundaries
                        }
                        break;
                    case "mtllib":
                        for (int i = 1; i < parts.length; i++) loadMtl(parts[i], model);
                        break;
                    // "s" (smoothing), "l"/"p" (line/point), and assorted Maya/3ds extras: ignored
                    default: break;
                }
            }
        }

        for (Builder b : builders.values()) if (!b.isEmpty()) model.meshes.add(b.toMesh());
        model.pruneEmpty();
        model.computeBounds();

        if (model.meshes.isEmpty()) model.warn("No renderable triangles found in OBJ");
        if (T.isEmpty()) model.warn("No texture coordinates (vt) in file");
        if (N.isEmpty()) model.warn("No vertex normals (vn) in file - generated flat normals");
        return model;
    }

    private static void readVertex(String[] p) {
        if (p.length < 4) return;
        V.add(parseF(p[1])); V.add(parseF(p[2])); V.add(parseF(p[3]));
        // optional vertex colours: "v x y z r g b" or "v x y z w"
        if (p.length >= 7) {
            if (COLS == null) COLS = new ArrayList<>();
            while (COLS.size() < (V.size() / 3) * 3) COLS.add(1f);
            COLS.set(COLS.size()-3, parseF(p[4]));
            COLS.set(COLS.size()-2, parseF(p[5]));
            COLS.set(COLS.size()-1, parseF(p[6]));
        }
    }

    private static void readTexcoord(String[] p) {
        if (p.length < 3) return;
        T.add(parseF(p[1]));
        T.add(p.length > 2 ? parseF(p[2]) : 0f);
    }

    private static void readNormal(String[] p) {
        if (p.length < 4) return;
        N.add(parseF(p[1])); N.add(parseF(p[2])); N.add(parseF(p[3]));
    }

    private static void readFace(String[] p, Builder b, ModelData model) {
        int vCount = V.size() / 3;
        int tCount = T.size() / 2;
        int nCount = N.size() / 3;
        if (vCount == 0) return;

        List<Integer> corners = new ArrayList<>(p.length - 1);
        boolean bad = false;
        for (int i = 1; i < p.length; i++) {
            String tok = p[i];
            int a = 0, t = 0, n = 0;
            int first = tok.indexOf('/');
            if (first < 0) {
                a = resolve(tok, vCount, "v");
            } else {
                a = resolve(tok.substring(0, first), vCount, "v");
                int second = tok.indexOf('/', first + 1);
                if (second < 0) {
                    t = resolve(tok.substring(first + 1), tCount, "vt");
                } else {
                    String ts = tok.substring(first + 1, second);
                    if (!ts.isEmpty()) t = resolve(ts, tCount, "vt");
                    n = resolve(tok.substring(second + 1), nCount, "vn");
                }
            }
            if (a <= 0) { bad = true; break; }   // dangling index: drop the whole face
            corners.add(b.vertex(a, t, n));
        }

        if (bad) {
            model.warn("Skipped a face with an out-of-range vertex index");
            return;
        }

        if (corners.size() < 3) {
            model.warn("Skipped degenerate face with " + corners.size() + " corners");
            return;
        }
        for (int i = 1; i + 1 < corners.size(); i++) {   // fan triangulation (correct for convex polys)
            b.idx.add(corners.get(0));
            b.idx.add(corners.get(i));
            b.idx.add(corners.get(i + 1));
        }
    }

    /** OBJ indices are 1-based; negative values count back from the end. */
    private static int resolve(String token, int count, String kind) {
        if (token.isEmpty()) return 0;
        int raw;
        try { raw = Integer.parseInt(token); } catch (NumberFormatException e) { return 0; }
        int idx = raw > 0 ? raw : count + raw + 1;
        if (idx < 1 || idx > count) {
            Log.debug("OBJ " + kind + " index " + token + " out of range (have " + count + ")");
            return 0;
        }
        return idx;
    }

    // ------------------------------------------------------------------- mtl

    private static void loadMtl(String fileName, ModelData model) {
        Path mtl = resolveRelative(fileName);
        if (mtl == null || !Files.isReadable(mtl)) {
            model.warn("Missing material library: " + fileName);
            return;
        }
        Material cur = null;
        try (BufferedReader br = Files.newBufferedReader(mtl, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = trimComment(line).trim();
                if (line.isEmpty()) continue;
                String[] p = line.split("\\s+");
                switch (p[0]) {
                    case "newmtl":
                        if (p.length >= 2) {
                            cur = new Material();
                            cur.name = p[1];
                            MATERIALS.put(p[1], cur);
                        }
                        break;
                    case "Kd": if (cur != null && p.length >= 4) cur.diffuse = rgb(p);  break;
                    case "Ka": if (cur != null && p.length >= 4) cur.ambient = rgb(p);  break;
                    case "Ks": if (cur != null && p.length >= 4) cur.specular = rgb(p); break;
                    case "Ke": if (cur != null && p.length >= 4) cur.emissive = rgb(p); break;
                    case "d":  if (cur != null && p.length >= 2) cur.alpha = clamp01(parseF(p[1])); break;
                    case "Tr": if (cur != null && p.length >= 2) cur.alpha = clamp01(1f - parseF(p[1])); break;
                    case "Ns": if (cur != null && p.length >= 2) cur.shininess = (int) parseF(p[1]); break;
                    case "Ni": if (cur != null && p.length >= 2) cur.metallic = clamp01(parseF(p[1])); break;
                    case "Pr": if (cur != null && p.length >= 2) cur.roughness = clamp01(parseF(p[1])); break;
                    case "illum":
                        if (cur != null && p.length >= 2) {
                            int mode = (int) parseF(p[1]);
                            cur.doubleSided = mode != 2;
                            cur.unlit = mode == 0 || mode == 1;
                        }
                        break;
                    case "map_Kd":
                        if (cur != null && p.length >= 2) loadTextureRef(cur, rest(p), model);
                        break;
                    case "map_Ka":
                        if (cur != null && p.length >= 2 && cur.texturePath == null)
                            loadTextureRef(cur, rest(p), model);
                        break;
                    case "map_Ke":
                        if (cur != null && p.length >= 2) cur.emissiveMapPath = resolveTexture(rest(p));
                        break;
                    case "map_Bump":
                    case "bump":
                    case "norm":
                    case "map_Kn":
                        if (cur != null && p.length >= 2) cur.normalMapPath = resolveTexture(rest(p));
                        break;
                    default: break;
                }
            }
        } catch (IOException e) {
            model.warn("Failed reading " + mtl.getFileName() + ": " + e.getMessage());
        }
    }

    /** "Kd 1 0 0 somefile.png" -> picks the arg after the 3 colour numbers. */
    private static String rest(String[] p) {
        int i = 1;
        int seen = 0;
        while (i < p.length && seen < 3) {
            try { parseF(p[i]); seen++; } catch (NumberFormatException e) { break; }
            i++;
        }
        return (i < p.length) ? p[i] : null;
    }

    private static void loadTextureRef(Material m, String ref, ModelData model) {
        if (ref == null) return;
        m.texturePath = resolveTexture(ref);
        if (m.texturePath == null) model.warn("Texture not found for material " + m.name + ": " + ref);
    }

    private static String resolveTexture(String ref) {
        if (ref == null || ref.isBlank()) return null;
        String cleaned = ref.replace("\\", "/").trim();
        if (cleaned.startsWith("-") || cleaned.contains(" -")) {
            int sp = cleaned.indexOf(' ');
            if (sp > 0) cleaned = cleaned.substring(sp + 1).trim();
        }
        Path p = resolveRelative(cleaned);
        return (p != null && Files.isReadable(p)) ? p.toString() : null;
    }

    private static Path resolveRelative(String name) {
        if (name == null || name.isBlank() || BASE_DIR == null) return null;
        String n = name.trim().replace("\\", "/");
        if (n.contains("://")) return null; // remote reference: skip
        Path direct = BASE_DIR.resolve(n).normalize();
        if (Files.isReadable(direct)) return direct;
        // case-insensitive retry: exports are often written for Windows
        Path dir = BASE_DIR;
        String[] want = n.split("/");
        Path cur = dir;
        for (String seg : want) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            Path next = cur.resolve(seg);
            if (Files.exists(next)) { cur = next; continue; }
            try (java.util.stream.Stream<Path> s = Files.list(cur)) {
                Path hit = s.filter(p -> p.getFileName().toString().equalsIgnoreCase(seg)).findFirst().orElse(null);
                if (hit == null) return null;
                cur = hit;
            } catch (IOException e) { return null; }
        }
        return Files.isReadable(cur) ? cur : null;
    }

    // ----------------------------------------------------------------- shared

    static BufferedImage loadTexture(String absPath) {
        if (absPath == null) return null;
        BufferedImage hit = TEX_CACHE.get(absPath);
        if (hit != null) return hit;
        try {
            BufferedImage bi = Images.read(Path.of(absPath));
            if (bi.getWidth() > MAX_TEXTURE || bi.getHeight() > MAX_TEXTURE) {
                int s = (int) Math.ceil(Math.max(bi.getWidth(), bi.getHeight()) / (double) MAX_TEXTURE);
                bi = Images.scale(bi, bi.getWidth() / s, bi.getHeight() / s);
            }
            TEX_CACHE.put(absPath, bi);
            return bi;
        } catch (Exception e) {
            Log.debug("texture load failed " + absPath + ": " + e.getMessage());
            return null;
        }
    }

    private static Material defaultMaterial() {
        Material m = new Material();
        m.name = "default";
        return m;
    }

    private static Material namedFallback(String name) {
        Material m = new Material();
        m.name = name;
        // deterministic colour so unknown materials are still distinguishable
        int h = Math.abs(name.hashCode());
        m.diffuse = new float[] { 0.4f + ((h >> 16) & 0xFF) / 510f,
                                  0.4f + ((h >> 8) & 0xFF) / 510f,
                                  0.4f + (h & 0xFF) / 510f };
        return m;
    }

    private static float[] rgb(String[] p) {
        return new float[] { clamp01(parseF(p[1])), clamp01(parseF(p[2])), clamp01(parseF(p[3])) };
    }

    static float parseF(String s) {
        if (s.endsWith("f") || s.endsWith("F")) return Float.parseFloat(s.substring(0, s.length() - 1));
        return Float.parseFloat(s);
    }

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    private static String trimComment(String line) {
        int h = line.indexOf('#');
        return h < 0 ? line : line.substring(0, h);
    }

    private static float[] toArray(List<Float> l) {
        float[] a = new float[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }
}
