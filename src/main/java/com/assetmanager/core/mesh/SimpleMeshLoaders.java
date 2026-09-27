package com.assetmanager.core.mesh;

import com.assetmanager.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** STL (ascii + binary) and PLY (ascii + binary LE) readers, for when geometry has no textures. */
public final class SimpleMeshLoaders {

    private SimpleMeshLoaders() {}

    public static ModelData loadStl(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        boolean ascii = looksLikeAscii(raw);
        ModelData m = ascii ? parseStlAscii(new String(raw, StandardCharsets.ISO_8859_1))
                            : parseStlBinary(raw);
        m.sourceName = file.getFileName().toString();
        m.pruneEmpty();
        m.computeBounds();
        return m;
    }

    private static boolean looksLikeAscii(byte[] raw) {
        int n = Math.min(raw.length, 512);
        for (int i = 0; i < n; i++) {
            byte b = raw[i];
            if (b == 0) return false; // binary STL starts with a float count that is usually non-zero early
        }
        String head = new String(raw, 0, n, StandardCharsets.ISO_8859_1).toLowerCase(java.util.Locale.ROOT);
        return head.contains("solid") && head.contains("facet");
    }

    private static ModelData parseStlAscii(String text) {
        ModelData model = new ModelData();
        Mesh mesh = new Mesh();
        mesh.name = "stl";
        List<Float> pos = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        List<Float> nrm = new ArrayList<>();
        int cursor = 0;

        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash).trim();
            if (line.isEmpty()) continue;
            String[] p = line.split("\\s+");
            try {
                if (p[0].equalsIgnoreCase("vertex")) {
                    pos.add(Float.parseFloat(p[1]));
                    pos.add(Float.parseFloat(p[2]));
                    pos.add(Float.parseFloat(p[3]));
                    cursor++;
                } else if (p[0].equalsIgnoreCase("facet")) {
                    if (p.length >= 5 && p[1].equalsIgnoreCase("normal")) {
                        nrm.add(Float.parseFloat(p[2]));
                        nrm.add(Float.parseFloat(p[3]));
                        nrm.add(Float.parseFloat(p[4]));
                    } else if (p.length >= 4 && p[1].equalsIgnoreCase("outer")) {
                        nrm.add(0f); nrm.add(1f); nrm.add(0f);
                    }
                } else if (p[0].equalsIgnoreCase("endfacet")) {
                    for (int k = 2; k >= 0; k--) idx.add(cursor - 1 - k);
                }
            } catch (RuntimeException e) {
                model.warn("Malformed STL line: " + line);
            }
        }

        mesh.positions = toArray(pos);
        mesh.indices = new int[idx.size()];
        for (int i = 0; i < idx.size(); i++) mesh.indices[i] = Math.min(idx.get(i), mesh.vertexCount() - 1);
        if (nrm.size() >= pos.size()) mesh.normals = toArray(nrm);
        if (!mesh.hasNormals()) mesh.generateNormals();
        Material mat = new Material();
        mat.name = "stl";
        mat.diffuse = new float[] { 0.72f, 0.76f, 0.82f };
        mat.specular = new float[] { 0.4f, 0.4f, 0.4f };
        mat.shininess = 48;
        mesh.material = mat;
        model.meshes.add(mesh);
        return model;
    }

    private static ModelData parseStlBinary(byte[] raw) throws IOException {
        if (raw.length < 84) throw new IOException("STL too short to be binary");
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        bb.position(80);
        long declared = bb.getInt() & 0xFFFFFFFFL;

        ModelData model = new ModelData();
        // Each facet is exactly 50 bytes, so the file length is authoritative and
        // the header count is only a hint. A corrupt count can be negative or
        // absurdly large, and allocating straight from it throws
        // NegativeArraySizeException before the loop can recover.
        long affordable = (raw.length - 84L) / 50L;
        int count = (int) Math.min(declared, affordable);
        if (declared != count) {
            model.warn("STL header claims " + declared + " facets; the file holds " + affordable);
        }

        Mesh mesh = new Mesh();
        mesh.name = "stl";
        float[] pos = new float[count * 9];
        float[] nrm = new float[count * 9];
        int[] idx = new int[count * 3];

        int v = 0;
        for (int t = 0; t < count; t++) {
            if (bb.position() + 50 > raw.length) break;
            float nx = bb.getFloat(), ny = bb.getFloat(), nz = bb.getFloat();
            for (int k = 0; k < 3; k++) {
                float x = bb.getFloat(), y = bb.getFloat(), z = bb.getFloat();
                pos[v*3] = x; pos[v*3+1] = y; pos[v*3+2] = z;
                nrm[v*3] = nx; nrm[v*3+1] = ny; nrm[v*3+2] = nz;
                idx[t*3 + k] = v;
                v++;
            }
            bb.position(bb.position() + 2); // attribute byte count
        }
        mesh.positions = pos;
        mesh.normals = nrm;
        mesh.indices = idx;
        Material mat = new Material();
        mat.name = "stl";
        mat.diffuse = new float[] { 0.72f, 0.76f, 0.82f };
        mat.shininess = 48;
        mesh.material = mat;
        model.meshes.add(mesh);
        return model;
    }

    // --------------------------------------------------------------------- ply

    public static ModelData loadPly(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        ModelData model = new ModelData();
        model.sourceName = file.getFileName().toString();

        int off = 0;
        String line = readLine(raw, off);
        off += line.length() + 1;
        if (!line.startsWith("ply")) throw new IOException("Not a PLY file");

        int vertexCount = 0, faceCount = 0;
        String vertexLayout = null, faceLayout = null;
        boolean binary = false, bigEndian = false;
        List<String> elements = new ArrayList<>();

        while (off < raw.length) {
            line = readLine(raw, off);
            off += line.length() + 1;
            String t = line.trim();
            if (t.equals("end_header")) break;
            if (t.startsWith("format")) {
                binary = t.contains("binary");
                bigEndian = t.contains("big_endian");
            } else if (t.startsWith("element")) {
                String[] p = t.split("\\s+");
                if (p.length >= 3) {
                    elements.add(p[1]);
                    if (p[1].equals("vertex")) vertexCount = Integer.parseInt(p[2]);
                    if (p[1].equals("face")) faceCount = Integer.parseInt(p[2]);
                }
            } else if (t.startsWith("property") && !elements.isEmpty()) {
                String cur = elements.get(elements.size() - 1);
                String prop = t.substring("property".length()).trim();
                if (prop.startsWith("list")) {
                    if (cur.equals("face")) faceLayout = (faceLayout == null ? "" : faceLayout + ",") + "LIST";
                } else {
                    // "property float x" -> type is the first word, name the second
                    String[] p = prop.split("\\s+");
                    String type = p.length >= 1 ? p[0] : "";
                    if (cur.equals("vertex")) vertexLayout = (vertexLayout == null ? "" : vertexLayout + ",") + type;
                }
            }
        }

        boolean hasNormals = vertexLayout != null && vertexLayout.contains("float") && vertexLayout.contains("double");

        Mesh mesh = new Mesh();
        mesh.name = "ply";
        float[] pos = new float[Math.max(1, vertexCount) * 3];
        float[] nrm = hasNormals ? new float[Math.max(1, vertexCount) * 3] : null;
        int[] colors = null;

        String[] vprops = vertexLayout == null ? new String[0] : vertexLayout.split(",");
        int[] idx = new int[Math.max(1, faceCount) * 3];
        int w = 0;

        if (!binary) {
            w = readAsciiData(raw, off, vprops, vertexCount, faceCount, pos, nrm, idx);
            colors = null;   // ASCII colour properties are not interpreted
        } else {
            ByteBuffer bb = ByteBuffer.wrap(raw, off, raw.length - off)
                    .order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);

            int stride = 0;
            for (String s : vprops) stride += typeSize(s);

            for (int i = 0; i < vertexCount; i++) {
                for (int p = 0; p < vprops.length; p++) {
                    float v = readScalar(bb, vprops[p]);
                    String t = vprops[p];
                    if (p == 0) pos[i*3] = v;
                    else if (p == 1) pos[i*3+1] = v;
                    else if (p == 2) pos[i*3+2] = v;
                    else if (t.contains("char") || t.contains("uchar") || t.contains("8")) {
                        if (colors == null) colors = new int[vertexCount];
                        int q = (int) (v * 255) & 0xFF;
                        colors[i] = (q << 16) | (q << 8) | q;
                    } else if (p >= 3 && nrm != null && (p == 3 || p == 4 || p == 5)) {
                        nrm[i*3 + (p - 3)] = v;
                    }
                }
            }

            for (int i = 0; i < faceCount; i++) {
                int n = bb.hasRemaining() ? (int) readScalar(bb, "uchar") : 0;
                int[] tri = new int[n];
                for (int k = 0; k < n; k++) tri[k] = (int) readScalar(bb, "int");
                for (int k = 1; k + 1 < n; k++) {           // fan triangulate
                    if (w < idx.length) { idx[w++] = tri[0]; idx[w++] = tri[k]; idx[w++] = tri[k+1]; }
                }
            }
        }

        mesh.positions = pos;
        if (nrm != null) mesh.normals = nrm;
        mesh.indices = java.util.Arrays.copyOf(idx, w);
        for (int i = 0; i < mesh.indices.length; i++)
            if (mesh.indices[i] < 0 || mesh.indices[i] >= vertexCount) mesh.indices[i] = 0;

        Material mat = new Material();
        mat.name = "ply";
        mat.diffuse = new float[] { 0.78f, 0.80f, 0.85f };
        mat.shininess = 32;
        if (colors != null) {
            mesh.colors = new float[vertexCount * 4];
            for (int i = 0; i < vertexCount; i++) {
                int c = colors[i];
                mesh.colors[i*4]   = ((c >> 16) & 0xFF) / 255f;
                mesh.colors[i*4+1] = ((c >> 8) & 0xFF) / 255f;
                mesh.colors[i*4+2] = (c & 0xFF) / 255f;
                mesh.colors[i*4+3] = 1f;
            }
        }
        if (!mesh.hasNormals()) mesh.generateNormals();
        mesh.material = mat;
        model.meshes.add(mesh);
        model.pruneEmpty();
        model.computeBounds();
        if (vertexCount == 0) model.warn("PLY declared 0 vertices");
        return model;
    }

    /** ASCII PLY body: whitespace-separated numbers. Returns the number of indices written. */
    private static int readAsciiData(byte[] raw, int off, String[] vprops, int vertexCount,
                                     int faceCount, float[] pos, float[] nrm, int[] idx) {
        int[] cursor = { off };
        int w = 0;
        for (int i = 0; i < vertexCount; i++) {
            for (int k = 0; k < vprops.length; k++) {
                float v = readAsciiFloat(raw, cursor);
                if (k < 3 && pos.length > i * 3 + 2) pos[i*3 + k] = v;
                else if (nrm != null && k >= 3 && k <= 5 && nrm.length > i*3 + 2) nrm[i*3 + (k-3)] = v;
            }
        }
        for (int i = 0; i < faceCount; i++) {
            int n = (int) readAsciiFloat(raw, cursor);
            if (n <= 0 || n > 64) continue;
            int[] tri = new int[n];
            for (int k = 0; k < n; k++) tri[k] = (int) readAsciiFloat(raw, cursor);
            for (int k = 1; k + 1 < n; k++) {
                if (w < idx.length) { idx[w++] = tri[0]; idx[w++] = tri[k]; idx[w++] = tri[k+1]; }
            }
        }
        return w;
    }

    /** Reads one whitespace-delimited number and advances the shared cursor past it. */
    private static float readAsciiFloat(byte[] b, int[] cursor) {
        int p = cursor[0];
        while (p < b.length && isSpace(b[p])) p++;
        int start = p;
        while (p < b.length && !isSpace(b[p])) p++;
        cursor[0] = p;
        if (p == start) return 0f;
        try {
            return Float.parseFloat(new String(b, start, p - start,
                    java.nio.charset.StandardCharsets.ISO_8859_1));
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private static boolean isSpace(byte b) {
        return b == ' ' || b == '\n' || b == '\r' || b == '\t';
    }

    private static String readLine(byte[] b, int off) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < b.length; i++) {
            if (b[i] == '\n') break;
            if (b[i] != '\r') sb.append((char) (b[i] & 0xFF));
        }
        return sb.toString();
    }

    private static int typeSize(String t) {
        switch (t) {
            case "char": case "uchar": case "int8": case "uint8":   return 1;
            case "short": case "ushort": case "int16": case "uint16": return 2;
            default: return 4;
        }
    }

    private static float readScalar(ByteBuffer bb, String type) {
        int size = typeSize(type);
        if (bb.remaining() < size) return 0f;
        switch (size) {
            case 1:  return (bb.get() & 0xFF) / 255f;
            case 2:  return (bb.getShort() & 0xFFFF) / 65535f;
            default: return bb.getFloat();
        }
    }

    private static float[] toArray(List<Float> l) {
        float[] a = new float[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }
}
