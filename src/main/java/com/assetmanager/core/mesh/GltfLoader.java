package com.assetmanager.core.mesh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.assetmanager.util.Images;
import com.assetmanager.util.Log;
import com.assetmanager.util.Mat4;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * glTF 2.0 / GLB reader covering the core spec plus embedded and external images.
 * Draco / meshopt compression is detected and reported rather than silently failing.
 */
public final class GltfLoader {

    private static final int MAGIC = 0x46546C67; // "glTF"

    private static final int BYTE = 5120, UBYTE = 5121, SHORT = 5122, USHORT = 5123, UINT = 5125, FLOAT = 5126;

    private JsonObject root;
    private final Map<Integer, byte[]> buffers = new HashMap<>();
    private final List<byte[]> bufferViews = new ArrayList<>();
    private final List<Material> materials = new ArrayList<>();
    private final Map<Integer, BufferedImage> images = new HashMap<>();
    private final Map<Integer, Integer> imageSamplerFor = new HashMap<>();
    private Path baseDir;
    private int maxTexture = 1024;
    private int skippedPrimitives = 0;

    private GltfLoader() {}

    public static ModelData load(Path file) throws IOException {
        GltfLoader g = new GltfLoader();
        return g.read(file);
    }

    private ModelData read(Path file) throws IOException {
        baseDir = file.toAbsolutePath().getParent();
        byte[] raw = Files.readAllBytes(file);

        if (raw.length >= 12 && intLE(raw, 0) == MAGIC) {
            readGlb(raw);
        } else {
            root = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        if (root == null) throw new IOException("Not a glTF file: " + file.getFileName());

        readBufferViews();
        readMaterials();

        ModelData model = new ModelData();
        model.sourceName = file.getFileName().toString();
        buildScene(model);
        model.pruneEmpty();
        model.computeBounds();

        if (skippedPrimitives > 0)
            model.warn(skippedPrimitives + " primitive(s) skipped (needs Draco or meshopt extension)");
        if (model.meshes.isEmpty()) model.warn("No renderable primitives found");
        return model;
    }

    // -------------------------------------------------------------------- glb

    private void readGlb(byte[] raw) throws IOException {
        int version = intLE(raw, 4);
        if (version != 2) {
            Log.warn("GLB container version " + version + " (only v2 supported); trying JSON anyway");
        }
        int off = 12;
        byte[] jsonChunk = null;
        byte[] binChunk = null;
        while (off + 8 <= raw.length) {
            int len = intLE(raw, off);
            int type = intLE(raw, off + 4);
            if (len < 0 || off + 8 + len > raw.length) break;
            byte[] payload = new byte[len];
            System.arraycopy(raw, off + 8, payload, 0, len);
            if (type == 0x4E4F534A) jsonChunk = payload;          // JSON
            else if (type == 0x004E4942) binChunk = payload;      // BIN
            off += 8 + len + ((4 - (len % 4)) % 4);               // chunks are 4-byte aligned
        }
        if (jsonChunk == null) throw new IOException("GLB has no JSON chunk");
        String txt = new String(jsonChunk, StandardCharsets.UTF_8).trim();
        int nul = 0;
        while (nul < txt.length() && txt.charAt(nul) != 0) nul++;
        root = JsonParser.parseString(txt.substring(0, nul)).getAsJsonObject();
        if (binChunk != null) buffers.put(0, binChunk);
    }

    // ----------------------------------------------------------------- buffers

    private void readBufferViews() throws IOException {
        JsonArray bufArr = arr(root, "buffers");
        JsonArray views = arr(root, "bufferViews");
        for (int i = 0; i < views.size(); i++) {
            JsonObject v = views.get(i).getAsJsonObject();
            int bufIdx = v.has("buffer") ? v.get("buffer").getAsInt() : -1;
            if (!buffers.containsKey(bufIdx)) {
                String uri = "";
                if (bufIdx >= 0 && bufIdx < bufArr.size()) {
                    JsonObject bo = bufArr.get(bufIdx).getAsJsonObject();
                    if (bo.has("uri")) uri = bo.get("uri").getAsString();
                    else if (bufIdx == 0 && buffers.containsKey(0)) uri = null; // GLB binary chunk
                }
                buffers.put(bufIdx, uri == null ? buffers.getOrDefault(bufIdx, new byte[0]) : readUri(uri));
            }
            byte[] data = buffers.get(bufIdx);
            int len = v.has("byteLength") ? v.get("byteLength").getAsInt() : 0;
            if (data == null) { bufferViews.add(new byte[0]); continue; }
            int start = v.has("byteOffset") ? v.get("byteOffset").getAsInt() : 0;
            if (start < 0) start = 0;
            if (start > data.length) { bufferViews.add(new byte[0]); continue; }
            bufferViews.add(java.util.Arrays.copyOfRange(data, start, Math.min(data.length, start + len)));
        }
    }

    private byte[] readUri(String uri) throws IOException {
        if (uri == null || uri.isEmpty()) return new byte[0];
        if (uri.startsWith("data:")) {
            int comma = uri.indexOf(',');
            if (comma < 0) return new byte[0];
            String meta = uri.substring(5, comma);
            String payload = uri.substring(comma + 1);
            try {
                if (meta.endsWith(";base64")) return Base64.getMimeDecoder().decode(payload);
                return java.net.URLDecoder.decode(payload, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) { return new byte[0]; }
        }
        Path p = baseDir == null ? Path.of(uri) : baseDir.resolve(uri);
        return Files.isReadable(p) ? Files.readAllBytes(p) : new byte[0];
    }

    // ---------------------------------------------------------------- accessor

    private static int componentSize(int componentType) {
        switch (componentType) {
            case BYTE:
            case UBYTE:  return 1;
            case SHORT:
            case USHORT: return 2;
            case UINT:
            case FLOAT:
            default:     return 4;
        }
    }

    private static int typeComponents(String type) {
        switch (type) {
            case "SCALAR": return 1;
            case "VEC2":   return 2;
            case "VEC3":   return 3;
            case "VEC4":   return 4;
            case "MAT2":   return 4;
            case "MAT3":   return 9;
            case "MAT4":   return 16;
            default:       return 1;
        }
    }

    /** Reads an accessor into a float array (de-normalising integer types). */
    private float[] readFloats(int accessorIndex, int expectComponents) {
        JsonArray accs = arr(root, "accessors");
        if (accessorIndex < 0 || accessorIndex >= accs.size()) return null;
        JsonObject a = accs.get(accessorIndex).getAsJsonObject();
        int count = a.get("count").getAsInt();
        int comps = typeComponents(optString(a, "type", "SCALAR"));
        int ctype = a.has("componentType") ? a.get("componentType").getAsInt() : FLOAT;
        boolean normalized = a.has("normalized") && a.get("normalized").getAsBoolean();
        int elemSize = comps * componentSize(ctype);

        float[] out = new float[count * comps];
        int viewIdx = a.has("bufferView") ? a.get("bufferView").getAsInt() : -1;
        if (viewIdx >= 0 && viewIdx < bufferViews.size()) {
            byte[] view = bufferViews.get(viewIdx);
            int accOff = a.has("byteOffset") ? a.get("byteOffset").getAsInt() : 0;
            int stride = 0;
            if (viewIdx < arr(root, "bufferViews").size()) {
                JsonObject vo = arr(root, "bufferViews").get(viewIdx).getAsJsonObject();
                if (vo.has("byteStride")) stride = vo.get("byteStride").getAsInt();
            }
            if (stride == 0) stride = elemSize;
            ByteBuffer bb = ByteBuffer.wrap(view).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) {
                int off = accOff + i * stride;
                if (off + elemSize > view.length) break;
                for (int k = 0; k < comps; k++) {
                    int p = off + k * componentSize(ctype);
                    out[i * comps + k] = deNormalise(bb, p, ctype, normalized);
                }
            }
        }
        if (comps != expectComponents) {
            float[] conv = new float[count * expectComponents];
            int copy = Math.min(comps, expectComponents);
            for (int i = 0; i < count; i++) System.arraycopy(out, i * comps, conv, i * expectComponents, copy);
            return conv;
        }
        return out;
    }

    private static float deNormalise(ByteBuffer bb, int p, int ctype, boolean normalized) {
        switch (ctype) {
            case BYTE:   return (float) bb.get(p) / (normalized ? 127f : 1f);
            case UBYTE:  return (float) (bb.get(p) & 0xFF) / (normalized ? 255f : 1f);
            case SHORT:  return (float) bb.getShort(p) / (normalized ? 32767f : 1f);
            case USHORT: return (float) (bb.getShort(p) & 0xFFFF) / (normalized ? 65535f : 1f);
            case UINT:   return (float) (bb.getInt(p) & 0xFFFFFFFFL);
            default:     return bb.getFloat(p);
        }
    }

    private int[] readIndices(int accessorIndex, int vertexCount) {
        JsonArray accs = arr(root, "accessors");
        if (accessorIndex < 0 || accessorIndex >= accs.size()) return null;
        JsonObject a = accs.get(accessorIndex).getAsJsonObject();
        int count = a.get("count").getAsInt();
        int ctype = a.has("componentType") ? a.get("componentType").getAsInt() : USHORT;
        int[] out = new int[count];
        int viewIdx = a.has("bufferView") ? a.get("bufferView").getAsInt() : -1;
        if (viewIdx >= 0 && viewIdx < bufferViews.size()) {
            byte[] view = bufferViews.get(viewIdx);
            int accOff = a.has("byteOffset") ? a.get("byteOffset").getAsInt() : 0;
            int csize = componentSize(ctype);
            ByteBuffer bb = ByteBuffer.wrap(view).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) {
                int p = accOff + i * csize;
                if (p + csize > view.length) break;
                out[i] = readIndex(bb, p, ctype);
            }
        }
        // guard against out-of-range indices from broken exports
        for (int i = 0; i < out.length; i++) {
            if (out[i] < 0 || out[i] >= vertexCount) out[i] = 0;
        }
        return out;
    }

    private static int readIndex(ByteBuffer bb, int p, int ctype) {
        switch (ctype) {
            case UBYTE:
            case BYTE:   return bb.get(p) & 0xFF;
            case USHORT:
            case SHORT:  return bb.getShort(p) & 0xFFFF;
            case UINT:   return bb.getInt(p);
            default:     return Math.round(bb.getFloat(p));
        }
    }

    // --------------------------------------------------------------- materials

    private void readMaterials() throws IOException {
        JsonArray mats = arr(root, "materials");
        for (int i = 0; i < mats.size(); i++) {
            JsonObject m = mats.get(i).getAsJsonObject();
            Material mat = new Material();
            mat.name = optString(m, "name", "material_" + i);
            JsonObject pbr = m.has("pbrMetallicRoughness") ? m.getAsJsonObject("pbrMetallicRoughness") : null;
            if (pbr != null) {
                if (pbr.has("baseColorFactor")) {
                    // baseColorFactor is a bare JSON array [r,g,b,a], not an accessor
                    float[] bc = floats(pbr.get("baseColorFactor"), 4);
                    mat.diffuse = new float[] { bc[0], bc[1], bc[2] };
                    mat.alpha = bc[3];
                }
                mat.metallic = optFloat(pbr, "metallicFactor", 0f);
                mat.roughness = optFloat(pbr, "roughnessFactor", 1f);
                if (pbr.has("baseColorTexture")) {
                    BufferedImage bi = textureFrom(pbr.getAsJsonObject("baseColorTexture"));
                    if (bi != null) { mat.setTexture(bi); mat.texturePath = "(embedded)"; }
                }
            }
            if (m.has("normalTexture")) mat.normalMapPath = "(embedded)";
            if (m.has("emissiveFactor")) {
                float[] ef = floats(m.get("emissiveFactor"), 3);
                if (ef.length >= 3) mat.emissive = ef;
            }
            if (m.has("emissiveTexture")) mat.emissiveMapPath = "(embedded)";
            mat.alphaMode = optString(m, "alphaMode", "OPAQUE");
            mat.alphaCutoff = optDouble(m, "alphaCutoff", 0.5);
            mat.doubleSided = m.has("doubleSided") && m.get("doubleSided").getAsBoolean();
            materials.add(mat);
        }
    }

    private BufferedImage textureFrom(JsonObject textureRef) throws IOException {
        int texIdx = textureRef.has("index") ? textureRef.get("index").getAsInt() : -1;
        JsonArray textures = arr(root, "textures");
        if (texIdx < 0 || texIdx >= textures.size()) return null;
        JsonObject tex = textures.get(texIdx).getAsJsonObject();
        if (!tex.has("source")) return null;
        return image(tex.get("source").getAsInt());
    }

    private BufferedImage image(int imageIdx) throws IOException {
        BufferedImage hit = images.get(imageIdx);
        if (hit != null) return hit;
        JsonArray imgs = arr(root, "images");
        if (imageIdx < 0 || imageIdx >= imgs.size()) return null;
        JsonObject img = imgs.get(imageIdx).getAsJsonObject();
        BufferedImage bi = null;
        try {
            if (img.has("uri")) {
                bi = decodeImage(readUri(img.get("uri").getAsString()), optString(img, "mimeType", ""));
            } else if (img.has("bufferView")) {
                int bv = img.get("bufferView").getAsInt();
                if (bv >= 0 && bv < bufferViews.size()) {
                    bi = decodeImage(bufferViews.get(bv), optString(img, "mimeType", ""));
                }
            }
        } catch (Exception e) {
            Log.warn("gltf image decode failed: " + e.getMessage());
        }
        if (bi != null && (bi.getWidth() > maxTexture || bi.getHeight() > maxTexture)) {
            double s = Math.max(bi.getWidth(), bi.getHeight()) / (double) maxTexture;
            bi = Images.scale(bi, (int) (bi.getWidth() / s), (int) (bi.getHeight() / s));
        }
        if (bi != null) images.put(imageIdx, bi);
        return bi;
    }

    private BufferedImage decodeImage(byte[] data, String mime) throws IOException {
        if (data == null || data.length == 0) return null;
        java.awt.image.BufferedImage direct = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data));
        if (direct != null) return Images.normalise(direct);
        Path tmp = Files.createTempFile("assetmanager-img", guessExt(mime));
        try {
            Files.write(tmp, data);
            return Images.read(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String guessExt(String mime) {
        if (mime == null) return ".png";
        switch (mime) {
            case "image/jpeg": return ".jpg";
            case "image/webp": return ".webp";
            case "image/ktx2": return ".ktx2";
            default: return ".png";
        }
    }

    // ------------------------------------------------------------------ scene

    private void buildScene(ModelData model) {
        JsonArray nodes = arr(root, "nodes");
        if (nodes.size() == 0) return;

        int sceneIdx = root.has("scene") ? root.get("scene").getAsInt() : 0;
        List<Integer> roots;
        JsonObject scenes = root.has("scenes") ? root.getAsJsonArray("scenes").get(sceneIdx).getAsJsonObject() : null;
        if (scenes != null && scenes.has("nodes")) {
            roots = new ArrayList<>();
            for (JsonElement e : scenes.getAsJsonArray("nodes")) roots.add(e.getAsInt());
        } else {
            roots = new ArrayList<>();
            for (int i = 0; i < nodes.size(); i++) {
                JsonObject n = nodes.get(i).getAsJsonObject();
                boolean referenced = false;
                for (JsonElement e : arr(root, "nodes")) {
                    if (e.getAsJsonObject().has("children"))
                        for (JsonElement c : e.getAsJsonObject().getAsJsonArray("children"))
                            if (c.getAsInt() == i) referenced = true;
                }
                if (!referenced) roots.add(i);
            }
        }
        for (int idx : roots) walkNode(model, nodes, idx, Mat4.identity(), new boolean[nodes.size()]);
    }

    private void walkNode(ModelData model, JsonArray nodes, int idx, Mat4 parent, boolean[] seen) {
        if (idx < 0 || idx >= nodes.size() || seen[idx]) return;   // guards cyclic/duplicate refs
        seen[idx] = true;
        JsonObject n = nodes.get(idx).getAsJsonObject();

        Mat4 local = Mat4.identity();
        if (n.has("matrix")) {
            local = Mat4.of(floats(n.get("matrix"), 16));
        } else {
            if (n.has("translation")) local = Mat4.multiply(local, Mat4.translation(floats(n.get("translation"), 3)[0],
                    floats(n.get("translation"), 3)[1], floats(n.get("translation"), 3)[2]));
            if (n.has("rotation")) {
                float[] q = floats(n.get("rotation"), 4);
                local = Mat4.multiply(local, quatToMat(q[0], q[1], q[2], q[3]));
            }
            if (n.has("scale")) {
                float[] s = floats(n.get("scale"), 3);
                local = Mat4.multiply(local, Mat4.scaling(s[0], s[1], s[2]));
            }
        }
        Mat4 world = Mat4.multiply(parent, local);

        if (n.has("mesh")) {
            try {
                emitMesh(model, n.get("mesh").getAsInt(), world, optString(n, "name", ""));
            } catch (Exception e) {
                model.warn("Mesh node failed: " + e.getMessage());
                skippedPrimitives++;
            }
        }
        if (n.has("children"))
            for (JsonElement c : n.getAsJsonArray("children")) walkNode(model, nodes, c.getAsInt(), world, seen);
        seen[idx] = false;
    }

    private void emitMesh(ModelData model, int meshIdx, Mat4 world, String nodeName) throws IOException {
        JsonArray meshes = arr(root, "meshes");
        if (meshIdx < 0 || meshIdx >= meshes.size()) return;
        JsonObject mesh = meshes.get(meshIdx).getAsJsonObject();
        String meshName = optString(mesh, "name", nodeName.isEmpty() ? "mesh_" + meshIdx : nodeName);

        JsonArray extUsed = root.has("extensionsUsed") ? root.getAsJsonArray("extensionsUsed") : new JsonArray();
        boolean draco = hasExtension(extUsed, "KHR_draco_mesh_compression");
        boolean meshopt = hasExtension(extUsed, "EXT_meshopt_compression");

        for (JsonElement pe : mesh.getAsJsonArray("primitives")) {
            JsonObject prim = pe.getAsJsonObject();
            if (!prim.has("attributes")) continue;
            JsonObject attrs = prim.getAsJsonObject("attributes");
            if (!attrs.has("POSITION")) continue;

            boolean compressed = false;
            if (attrs.has("KHR_draco_mesh_compression") || attrs.has("EXT_meshopt_compression")) compressed = true;
            if ((draco || meshopt) && compressed) { skippedPrimitives++; continue; }

            int posAcc = attrs.get("POSITION").getAsInt();
            float[] pos = readFloats(posAcc, 3);
            if (pos == null) continue;
            int vertexCount = pos.length / 3;

            Mesh m = new Mesh();
            m.name = meshName;
            m.positions = pos;
            m.normals = attrs.has("NORMAL") ? readFloats(attrs.get("NORMAL").getAsInt(), 3) : null;
            m.uvs = attrs.has("TEXCOORD_0") ? readFloats(attrs.get("TEXCOORD_0").getAsInt(), 2) : null;
            m.colors = attrs.has("COLOR_0") ? readFloats(attrs.get("COLOR_0").getAsInt(), 4) : null;

            if (prim.has("indices")) {
                m.indices = readIndices(prim.get("indices").getAsInt(), vertexCount);
            } else {
                m.indices = new int[vertexCount];
                for (int i = 0; i < vertexCount; i++) m.indices[i] = i;
            }

            int mode = prim.has("mode") ? prim.get("mode").getAsInt() : 4;
            if (mode == 4) {
                // already triangles
            } else if (mode == 5 || mode == 6) {
                m.indices = expandStripFan(m.indices, mode == 5, vertexCount);
            } else if (mode == 0 || mode == 1) {
                model.warn("Primitive mode " + mode + " (points/lines) rendered as a point cloud");
                m.indices = new int[vertexCount];
                for (int i = 0; i < vertexCount; i++) m.indices[i] = i;
            } else {
                skippedPrimitives++;
                continue;
            }

            int matIdx = prim.has("material") ? prim.get("material").getAsInt() : -1;
            m.material = (matIdx >= 0 && matIdx < materials.size())
                    ? materials.get(matIdx) : new Material();

            if (!m.hasNormals()) m.generateNormals();
            model.meshes.add(m);
        }
    }

    private static boolean hasExtension(JsonArray used, String name) {
        for (JsonElement e : used) if (e.getAsString().equals(name)) return true;
        return false;
    }

    private static int[] expandStripFan(int[] idx, boolean strip, int vertexCount) {
        if (idx == null) return new int[0];
        List<Integer> out = new ArrayList<>();
        if (strip) {
            for (int i = 0; i + 2 < idx.length; i++) {
                if ((i & 1) == 0) { out.add(idx[i]); out.add(idx[i+1]); out.add(idx[i+2]); }
                else { out.add(idx[i+1]); out.add(idx[i]); out.add(idx[i+2]); }
            }
        } else {
            for (int i = 1; i + 1 < idx.length; i++) { out.add(idx[0]); out.add(idx[i]); out.add(idx[i+1]); }
        }
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = Math.min(out.get(i), Math.max(0, vertexCount - 1));
        return r;
    }

    // ------------------------------------------------------------------- util

    private static Mat4 quatToMat(float x, float y, float z, float w) {
        float xx = x*x, yy = y*y, zz = z*z;
        float xy = x*y, xz = x*z, yz = y*z;
        float wx = w*x, wy = w*y, wz = w*z;
        return Mat4.of(
            1-2*(yy+zz), 2*(xy+wz),   2*(xz-wy),   0,
            2*(xy-wz),   1-2*(xx+zz), 2*(yz+wx),   0,
            2*(xz+wy),   2*(yz-wx),   1-2*(xx+yy), 0,
            0,           0,           0,           1);
    }

    private static JsonArray arr(JsonObject o, String key) {
        return (o != null && o.has(key) && o.get(key).isJsonArray()) ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static String optString(JsonObject o, String key, String def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        JsonElement e = o.get(key);
        return e.isJsonPrimitive() ? e.getAsString() : def;
    }

    private static float optFloat(JsonObject o, String key, float def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try { return o.get(key).getAsFloat(); } catch (Exception e) { return def; }
    }

    private static double optDouble(JsonObject o, String key, double def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try { return o.get(key).getAsDouble(); } catch (Exception e) { return def; }
    }

    private static float[] floats(JsonElement e, int expect) {
        if (e == null || !e.isJsonArray()) return new float[expect];
        JsonArray a = e.getAsJsonArray();
        float[] out = new float[Math.max(expect, a.size())];
        for (int i = 0; i < a.size(); i++) {
            JsonElement v = a.get(i);
            if (v.isJsonPrimitive()) out[i] = v.getAsFloat();
        }
        return out;
    }

    private static int intLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off+1] & 0xFF) << 8) | ((b[off+2] & 0xFF) << 16) | ((b[off+3] & 0xFF) << 24);
    }

    static Map<String, Material> emptyMap() { return new LinkedHashMap<>(); }

    static byte[] toBytes(ByteArrayOutputStream bos) { return bos.toByteArray(); }
}
