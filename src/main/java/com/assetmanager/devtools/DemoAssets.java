package com.assetmanager.devtools;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

/**
 * Builds a synthetic asset tree, so the app can be tried out and exercised
 * without a real game to point it at.
 *
 * The set is chosen to cover what the app actually does rather than to look
 * pretty: every kind the previews handle, sizes that are deliberately awkward
 * for the texture workbench (non-power-of-two, non-square, tiny, large), maps
 * that split and pack into each other cleanly, a duplicate pair for the
 * duplicate report, and a couple of deliberately broken files so the
 * "could not be read" state is reachable.
 *
 * Deterministic: a fixed seed, so two runs produce identical bytes and the
 * duplicate pair really is a duplicate.
 */
public final class DemoAssets {

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "demo-assets");
        build(root);
        int n = countFiles(root);
        System.out.println("Demo assets written to " + root.toAbsolutePath());
        System.out.println(n + " files. Point AssetManager at it with:");
        System.out.println("  ./run.sh " + root.toAbsolutePath());
    }

    public static void build(Path root) throws Exception {
        Path tex = root.resolve("textures/characters");
        Path props = root.resolve("textures/props");
        Path ui = root.resolve("textures/ui");
        Path env = root.resolve("textures/environment");
        Path sfx = root.resolve("audio/sfx");
        Path amb = root.resolve("audio/ambience");
        Path music = root.resolve("audio/music");
        Path meshes = root.resolve("meshes/props");
        Path broken = root.resolve("broken");

        // Includes the glyph sub-folder: every directory has to exist before the
        // first write into it, or ImageIO fails on a missing parent.
        for (Path p : new Path[] { tex, props, ui, env, sfx, amb, music, meshes, broken,
                ui.resolve("tiles") }) {
            Files.createDirectories(p);
        }

        Random rnd = new Random(7);

        // ---- a character texture set: albedo, normal, ORM.
        // Deliberately built as three separate greyscale-ish maps so channel
        // split and channel pack have something meaningful to work on.
        noise(tex.resolve("knight_albedo.png"), 256, 256, true, rnd);
        normalMap(tex.resolve("knight_normal.png"), 256, 256, rnd);
        ormMap(tex.resolve("knight_orm.png"), 128, 128, rnd);

        // ---- sizes the workbench has to cope with.
        // Non-power-of-two and non-square, which is what real art actually is.
        gradient(props.resolve("crate_diffuse.png"), 200, 150, 0xB08050, 0x8A6038, false);
        gradient(props.resolve("crate_diffuse_4k.png"), 1024, 1024, 0xC89A64, 0x6E4A28, false);
        noise(props.resolve("barrel_roughness.png"), 150, 150, false, rnd);
        radial(ui.resolve("icon_star.png"), 64, 64);
        radial(ui.resolve("icon_heart.png"), 64, 64);
        // A 3-pixel-tall strip: a degenerate case for crop and resize.
        gradient(ui.resolve("health_bar.png"), 256, 3, 0xE06C75, 0x98C379, false);
        // A heightmap with real curvature, so Height -> Normal is visible.
        bumpMap(env.resolve("terrain_height.png"), 192, 192);
        checker(env.resolve("stone_tiles.png"), 128, 128, 16, 0x8A939F, 0x6B7480);
        // Transparency, for the alpha checkerboard and the opacity control.
        ring(props.resolve("shield_rune.png"), 128, 128);

        // ---- an atlas-sized set of identical small tiles, for packing.
        for (int i = 0; i < 8; i++) {
            numbered(ui.resolve("tiles/glyph_" + (char) ('a' + i) + ".png"), 48, 48, i);
        }

        // ---- vector: indexed, and reported as having no decoder here.
        writeSvg(ui.resolve("icon_star.svg"));

        // ---- audio: short effects, a stereo loop, a longer music bed.
        tone(sfx.resolve("jump.wav"), 0.35, 520, 44100, 1, false);
        tone(sfx.resolve("hit.wav"), 0.18, 180, 44100, 1, false);
        tone(sfx.resolve("coin.wav"), 0.5, 990, 44100, 1, true);
        tone(sfx.resolve("footstep_dirt.wav"), 0.22, 320, 22050, 1, false);
        tone(amb.resolve("ambient_loop.wav"), 4.0, 220, 22050, 2, true);
        tone(music.resolve("title_theme.wav"), 6.0, 330, 44100, 2, true);

        // ---- models, one per loadable format.
        writeIcosphere(meshes.resolve("rock_small.obj"), 0.6f, 3, 8);
        writeIcosphere(meshes.resolve("barrel.stl"), 0.5f, 4, 10);
        writeIcosphere(meshes.resolve("crate_low.ply"), 0.7f, 2, 6);
        writeQuad(meshes.resolve("banner.gltf"), 1.6f, 0.9f);
        // Binary STL, which takes a different parse path from the ascii one.
        writeIcosphereBinaryStl(meshes.resolve("gem.stl"), 0.4f, 3, 9);

        // ---- a duplicate pair, so the duplicate report has something to find.
        // Copied rather than re-encoded, so the SHA-256 is guaranteed to match.
        Files.copy(tex.resolve("knight_orm.png"), env.resolve("knight_orm_copy.png"));
        Files.copy(ui.resolve("health_bar.png"), ui.resolve("health_bar_copy.png"));

        // ---- deliberately broken, so the "problem" state is reachable.
        // A PNG header with no image data behind it, and an empty file.
        Files.write(broken.resolve("truncated.png"),
                new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13 });
        Files.write(broken.resolve("empty.png"), new byte[0]);
        // An OBJ that ends mid-face.
        Files.writeString(broken.resolve("truncated.obj"), "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2\n");

        // ---- non-assets that must be ignored by the scanner.
        Files.writeString(root.resolve("README.md"), "Synthetic demo tree for AssetManager.\n");
        Files.writeString(root.resolve("notes.txt"), "not an asset\n");
        Files.writeString(root.resolve("credits.json"), "{ \"generator\": \"DemoAssets\" }\n");
    }

    private static int countFiles(Path root) throws Exception {
        try (java.util.stream.Stream<Path> s = Files.walk(root)) {
            return (int) s.filter(Files::isRegularFile).count();
        }
    }

    // ------------------------------------------------------------------ images

    private static void noise(Path p, int w, int h, boolean alpha, Random rnd) throws Exception {
        BufferedImage img = new BufferedImage(w, h, alpha
                ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int base = rnd.nextInt(60) + 90;
                int r = clamp(base + (x * 60) / w);
                int g = clamp(base + (y * 60) / h);
                int b = clamp(base + rnd.nextInt(40));
                int a = alpha && (x + y) % 37 == 0 ? 140 : 255;
                img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        ImageIO.write(img, "png", p.toFile());
    }

    /** A tangent-space normal map: mostly (0.5, 0.5, 1.0) with noise on the slope. */
    private static void normalMap(Path p, int w, int h, Random rnd) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = 128 + rnd.nextInt(24) - 12;
                int g = 128 + rnd.nextInt(24) - 12;
                img.setRGB(x, y, (r << 16) | (g << 8) | 255);
            }
        }
        ImageIO.write(img, "png", p.toFile());
    }

    /** Occlusion in red, roughness in green, metallic in blue: an ORM map. */
    private static void ormMap(Path p, int w, int h, Random rnd) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int occ = clamp(140 + (x * 100) / w);
                int rough = clamp(120 + rnd.nextInt(110));
                int metal = clamp((x + y) % 60);
                img.setRGB(x, y, (occ << 16) | (rough << 8) | metal);
            }
        }
        ImageIO.write(img, "png", p.toFile());
    }

    /** A smooth dome, so height-to-normal has genuine curvature to follow. */
    private static void bumpMap(Path p, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        double cx = (w - 1) / 2.0, cy = (h - 1) / 2.0;
        double r = Math.min(cx, cy);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double d = Math.hypot(x - cx, y - cy) / r;
                int v = (int) Math.round(255 * Math.max(0, 1 - d * d));
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        ImageIO.write(img, "png", p.toFile());
    }

    /** An n-pointed star, so the shape is obvious after a resize or a rotate. */
    private static void radial(Path p, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int points = p.getFileName().toString().contains("star") ? 5 : 3;
        java.awt.geom.Path2D star = new java.awt.geom.Path2D.Float();
        double cx = w / 2.0, cy = h / 2.0, outer = Math.min(cx, cy) - 2, inner = outer * 0.42;
        for (int i = 0; i < points * 2; i++) {
            double a = -Math.PI / 2 + i * Math.PI / points;
            double rad = (i % 2 == 0) ? outer : inner;
            double px = cx + Math.cos(a) * rad, py = cy + Math.sin(a) * rad;
            if (i == 0) star.moveTo(px, py); else star.lineTo(px, py);
        }
        star.closePath();
        g.setColor(new Color(0xE5C07B));
        g.fill(star);
        g.setColor(new Color(0x1B1F24));
        g.setStroke(new java.awt.BasicStroke(2f));
        g.draw(star);
        g.dispose();
        ImageIO.write(img, "png", p.toFile());
    }

    /** A transparent ring, so alpha handling has something real to act on. */
    private static void ring(Path p, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x3F, 0x8C, 0xFF, 90));
        g.fillOval(6, 6, w - 12, h - 12);
        g.setColor(new Color(0x8B, 0x5C, 0xF6, 210));
        g.setStroke(new java.awt.BasicStroke(8f));
        g.drawOval(12, 12, w - 24, h - 24);
        g.dispose();
        ImageIO.write(img, "png", p.toFile());
    }

    /** A distinct glyph per tile, so an atlas is verifiable by eye. */
    private static void numbered(Path p, int w, int h, int n) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x232830));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(0x3F8CFF));
        g.fillRect(3, 3, w - 6, 4);
        g.setColor(new Color(0xD7DCE3));
        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, h / 2));
        String label = Character.toString((char) ('A' + n));
        java.awt.FontMetrics fm = g.getFontMetrics();
        g.drawString(label, (w - fm.stringWidth(label)) / 2, (h + fm.getAscent()) / 2 - 2);
        g.dispose();
        ImageIO.write(img, "png", p.toFile());
    }

    private static void gradient(Path p, int w, int h, int c1, int c2, boolean alpha) throws Exception {
        BufferedImage img = new BufferedImage(w, h, alpha
                ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(c1), w, h, new Color(c2)));
        g.fillRect(0, 0, w, h);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.65f));
        g.setColor(new Color(255, 255, 255));
        g.fillRect(0, h / 3, w, Math.max(1, h / 12));
        g.dispose();
        ImageIO.write(img, "png", p.toFile());
    }

    private static void checker(Path p, int w, int h, int cell, int c1, int c2) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                boolean even = (((x / cell) + (y / cell)) & 1) == 0;
                img.setRGB(x, y, even ? c1 : c2);
            }
        ImageIO.write(img, "png", p.toFile());
    }

    private static void writeSvg(Path p) throws Exception {
        String svg = "<svg xmlns='http://www.w3.org/2000/svg' width='64' height='64'>"
                + "<rect width='64' height='64' fill='#E5C07B'/>"
                + "<polygon points='32,8 40,30 62,32 42,46 48,62 32,54 16,62 22,46 2,32 24,30' fill='#1B1F24'/>"
                + "</svg>";
        Files.writeString(p, svg);
    }

    // ------------------------------------------------------------------- audio

    /**
     * @param decay true for a percussive hit that fades, false for a steady tone
     *              that runs to the end, so a seek lands somewhere distinguishable
     */
    private static void tone(Path p, double seconds, double freq, int rate, int ch, boolean decay)
            throws Exception {
        int frames = (int) (seconds * rate);
        byte[] pcm = new byte[frames * ch * 2];
        ByteBuffer bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            double t = i / (double) rate;
            double env = decay ? Math.exp(-2.2 * (t % 0.5)) : 1.0;
            double v = Math.sin(2 * Math.PI * freq * t) * 0.5
                     + Math.sin(2 * Math.PI * freq * 2 * t) * 0.2;
            short s = (short) (v * env * 20000);
            for (int c = 0; c < ch; c++) {
                bb.putShort(i * ch * 2 + c * 2, (short) (c == 0 ? s : s * 0.8));
            }
        }
        AudioFormat f = new AudioFormat(rate, 16, ch, true, false);
        AudioSystem.write(new AudioInputStream(new java.io.ByteArrayInputStream(pcm), f, frames),
                AudioFileFormat.Type.WAVE, p.toFile());
    }

    // ------------------------------------------------------------------ models

    /** Shared sphere tessellation, so every writer describes the same shape. */
    private static List<float[]> sphereVerts(float radius, int rings, int segs) {
        List<float[]> v = new java.util.ArrayList<>();
        for (int i = 0; i <= rings; i++) {
            double phi = Math.PI * i / rings;
            for (int j = 0; j < segs; j++) {
                double th = 2 * Math.PI * j / segs;
                v.add(new float[] { (float) (Math.sin(phi) * Math.cos(th)) * radius,
                                    (float) Math.cos(phi) * radius,
                                    (float) (Math.sin(phi) * Math.sin(th)) * radius });
            }
        }
        return v;
    }

    private static List<int[]> sphereTris(int rings, int segs) {
        List<int[]> tris = new java.util.ArrayList<>();
        for (int i = 0; i < rings; i++)
            for (int j = 0; j < segs; j++) {
                int j2 = (j + 1) % segs;
                tris.add(new int[] { i * segs + j, i * segs + j2, (i + 1) * segs + j2 });
                tris.add(new int[] { i * segs + j, (i + 1) * segs + j2, (i + 1) * segs + j });
            }
        return tris;
    }

    private static void writeIcosphere(Path f, float radius, int rings, int segs) throws Exception {
        String ext = ext(f);
        List<float[]> v = sphereVerts(radius, rings, segs);
        List<int[]> tris = sphereTris(rings, segs);

        switch (ext) {
            case "obj": {
                StringBuilder sb = new StringBuilder("mtllib " + stripExt(f.getFileName().toString()) + ".mtl\nusemtl prop\n");
                for (float[] p : v) sb.append("v ").append(p[0]).append(' ').append(p[1]).append(' ').append(p[2]).append('\n');
                for (int[] t : tris) {
                    sb.append("f");
                    for (int idx : t) sb.append(' ').append(idx + 1);
                    sb.append('\n');
                }
                Files.writeString(f, sb.toString());
                Files.writeString(f.resolveSibling(stripExt(f.getFileName().toString()) + ".mtl"),
                        "newmtl prop\nKd 0.78 0.72 0.62\nKa 0.15 0.15 0.15\nNs 28\n");
                break;
            }
            case "stl": {
                StringBuilder sb = new StringBuilder("solid mesh\n");
                for (int[] t : tris) {
                    float[] a = v.get(t[0]), b = v.get(t[1]), c = v.get(t[2]);
                    float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
                    float wx = c[0] - a[0], wy = c[1] - a[1], wz = c[2] - a[2];
                    float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
                    float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (l > 1e-9f) { nx /= l; ny /= l; nz /= l; }
                    sb.append("facet normal ").append(nx).append(' ').append(ny).append(' ').append(nz).append("\n outer loop\n");
                    for (int idx : t) sb.append("  vertex ").append(v.get(idx)[0]).append(' ')
                            .append(v.get(idx)[1]).append(' ').append(v.get(idx)[2]).append('\n');
                    sb.append(" endloop\nendfacet\n");
                }
                sb.append("endsolid mesh\n");
                Files.writeString(f, sb.toString());
                break;
            }
            case "ply": {
                StringBuilder sb = new StringBuilder();
                sb.append("ply\nformat ascii 1.0\n");
                sb.append("element vertex ").append(v.size()).append('\n');
                sb.append("property float x\nproperty float y\nproperty float z\n");
                sb.append("element face ").append(tris.size()).append('\n');
                sb.append("property list uchar int vertex_index\nend_header\n");
                for (float[] p : v) sb.append(p[0]).append(' ').append(p[1]).append(' ').append(p[2]).append('\n');
                for (int[] t : tris) sb.append("3 ").append(t[0]).append(' ').append(t[1]).append(' ').append(t[2]).append('\n');
                Files.writeString(f, sb.toString());
                break;
            }
            default: throw new IllegalArgumentException("unsupported " + ext);
        }
    }

    /**
     * The binary STL encoding: an 80-byte header, the facet count, then 50 bytes
     * per facet.
     *
     * Every scalar is little-endian, which is the opposite of what
     * {@link java.io.DataOutputStream} writes. Using one anyway produces a
     * big-endian count that a reader decodes as a huge number, so this goes
     * through a little-endian buffer instead.
     */
    private static void writeIcosphereBinaryStl(Path f, float radius, int rings, int segs)
            throws Exception {
        List<float[]> v = sphereVerts(radius, rings, segs);
        List<int[]> tris = sphereTris(rings, segs);
        int n = tris.size();

        java.nio.ByteBuffer bb = java.nio.ByteBuffer
                .allocate(84 + n * 50)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.position(80);                          // 80-byte header, left blank
        bb.putInt(n);
        for (int[] t : tris) {
            float[] a = v.get(t[0]), b = v.get(t[1]), c = v.get(t[2]);
            float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
            float wx = c[0] - a[0], wy = c[1] - a[1], wz = c[2] - a[2];
            float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
            float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (l > 1e-9f) { nx /= l; ny /= l; nz /= l; }
            bb.putFloat(nx).putFloat(ny).putFloat(nz);
            for (int idx : t) bb.putFloat(v.get(idx)[0]).putFloat(v.get(idx)[1]).putFloat(v.get(idx)[2]);
            bb.putShort((short) 0);              // attribute byte count
        }
        Files.write(f, bb.array());
    }

    /** A minimal glTF 2.0 file with an embedded buffer and a plain material. */
    private static void writeQuad(Path f, float w, float h) throws Exception {
        float[] pos = {
            -w, -h, 0,   w, -h, 0,   w,  h, 0,  -w,  h, 0
        };
        float[] uv = { 0, 0,  1, 0,  1, 1,  0, 1 };
        float[] nrm = { 0, 0, 1,  0, 0, 1,  0, 0, 1,  0, 0, 1 };
        int[] idx = { 0, 1, 2, 0, 2, 3 };

        int posBytes = pos.length * 4, uvBytes = uv.length * 4;
        int nrmBytes = nrm.length * 4, idxBytes = idx.length * 4;
        int posOff = 0, nrmOff = posOff + posBytes, uvOff = nrmOff + nrmBytes, idxOff = uvOff + uvBytes;
        int total = idxOff + idxBytes;

        java.nio.ByteBuffer bin = java.nio.ByteBuffer.allocate(total).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float v : pos) bin.putFloat(v);
        for (float v : nrm) bin.putFloat(v);
        for (float v : uv)  bin.putFloat(v);
        for (int v : idx)   bin.putInt(v);

        String b64 = java.util.Base64.getEncoder().encodeToString(bin.array());
        String json = "{"
                + "\"asset\":{\"version\":\"2.0\",\"generator\":\"AssetManager demo\"},"
                + "\"scene\":0,"
                + "\"scenes\":[{\"nodes\":[0]}],"
                + "\"nodes\":[{\"mesh\":0,\"name\":\"Banner\"}],"
                + "\"meshes\":[{\"name\":\"BannerMesh\",\"primitives\":[{"
                + "\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2},"
                + "\"indices\":3,\"material\":0}]}],"
                + "\"materials\":[{\"name\":\"Cloth\",\"pbrMetallicRoughness\":{"
                + "\"baseColorFactor\":[0.85,0.35,0.4,1.0],"
                + "\"roughness\":0.6},"
                + "\"doubleSided\":true}],"
                + "\"buffers\":[{\"byteLength\":" + total + ",\"uri\":\"data:application/octet-stream;base64," + b64 + "\"}],"
                + "\"bufferViews\":["
                + "{\"buffer\":0,\"byteOffset\":" + posOff + ",\"byteLength\":" + posBytes + "},"
                + "{\"buffer\":0,\"byteOffset\":" + nrmOff + ",\"byteLength\":" + nrmBytes + "},"
                + "{\"buffer\":0,\"byteOffset\":" + uvOff + ",\"byteLength\":" + uvBytes + "},"
                + "{\"buffer\":0,\"byteOffset\":" + idxOff + ",\"byteLength\":" + idxBytes + "}],"
                + "\"accessors\":["
                + "{\"bufferView\":0,\"componentType\":5126,\"count\":4,\"type\":\"VEC3\","
                + "\"min\":[" + (-w) + "," + (-h) + ",0],\"max\":[" + w + "," + h + ",0]},"
                + "{\"bufferView\":1,\"componentType\":5126,\"count\":4,\"type\":\"VEC3\"},"
                + "{\"bufferView\":2,\"componentType\":5126,\"count\":4,\"type\":\"VEC2\"},"
                + "{\"bufferView\":3,\"componentType\":5125,\"count\":6,\"type\":\"SCALAR\"}]"
                + "}";
        Files.writeString(f, json);
    }

    // ----------------------------------------------------------------- helpers

    private static String ext(Path p) {
        String n = p.getFileName().toString();
        return n.substring(n.lastIndexOf('.') + 1).toLowerCase();
    }

    private static String stripExt(String n) {
        return n.substring(0, n.lastIndexOf('.'));
    }

    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
}
