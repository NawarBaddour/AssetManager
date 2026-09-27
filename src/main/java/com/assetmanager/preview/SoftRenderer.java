package com.assetmanager.preview;

import com.assetmanager.core.mesh.Material;
import com.assetmanager.core.mesh.Mesh;
import com.assetmanager.core.mesh.ModelData;
import com.assetmanager.util.Log;
import com.assetmanager.util.Mat4;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;

/**
 * Dependency-free z-buffered triangle rasteriser with Blinn-Phong shading and
 * perspective-correct texture mapping. Enough fidelity for asset review, and
 * fast enough to re-render live while orbiting.
 */
public final class SoftRenderer {

    /** Orbit-style camera looking at a target point. */
    public static final class Camera {
        public float azimuth   = 0.7f;   // radians, around +Y
        public float elevation = 0.35f;  // radians, 0 = horizon
        public float distance  = 3f;     // multiples of model radius
        public float[] target  = { 0, 0, 0 };
        public float fov = (float) Math.toRadians(45);

        public float[] eye(float radius) {
            float d = distance * radius;
            float ce = (float) Math.cos(elevation), se = (float) Math.sin(elevation);
            return new float[] {
                target[0] + d * ce * (float) Math.sin(azimuth),
                target[1] + d * se,
                target[2] + d * ce * (float) Math.cos(azimuth)
            };
        }
    }

    public static final class Options {
        public boolean shaded = true;
        public boolean textured = true;
        public boolean wireframe = false;
        public boolean grid = false;
        public boolean backfaceCull = true;
        // Backdrop follows the UI theme: a vertical ramp from just above
        // --surface down to --panel.
        public Color bgTop = new Color(0x2E303B);
        public Color bgBottom = new Color(0x1B1D24);
        public Color gridColor = new Color(0x44485A);
        // Single-argument on purpose. Color(int, true) takes the alpha from the
        // top byte of the value, and 0x66D9EF has a zero top byte -- so the
        // "has alpha" form made this fully transparent, and blend() then reduced
        // to f = 0, drawing nothing at all.
        public Color wireColor = new Color(0x66D9EF);
        public float lightAzimuth = 0.9f;
        public float lightElevation = 0.8f;
    }

    private final int w, h;
    private final int[] color;
    private final float[] depth;
    private final Options opt;

    private SoftRenderer(int w, int h, Options opt) {
        this.w = w; this.h = h; this.opt = opt;
        this.color = new int[w * h];
        this.depth = new float[w * h];
    }

    public static BufferedImage render(ModelData model, Camera cam, Options opt, int w, int h) {
        if (w < 1) w = 1;
        if (h < 1) h = 1;
        if (model == null || model.meshes.isEmpty()) return null;
        try {
            SoftRenderer r = new SoftRenderer(w, h, opt);
            r.draw(model, cam);
            return r.toImage();
        } catch (RuntimeException e) {
            Log.error("render failed", e);
            return null;
        }
    }

    // ------------------------------------------------------------------ frame

    private void draw(ModelData model, Camera cam) {
        fillBackground();

        float radius = model.radius();
        float[] centre = model.center();
        float dist = cam.distance * radius;
        float near = Math.max(radius * 0.01f, 0.01f);
        float far = Math.max(dist + radius * 4f, near * 100f);

        float[] eye = cam.eye(radius);
        float[] target = new float[] { cam.target[0], cam.target[1], cam.target[2] };
        if (opt.grid) drawGrid(eye, target, radius, near, far);

        Mat4 view = Mat4.lookAt(eye, target, new float[] { 0, 1, 0 });
        Mat4 proj = Mat4.perspective(cam.fov, (float) w / h, near, far);
        Mat4 viewProj = Mat4.multiply(proj, view);

        // Key light in view space so it follows the camera; looks like a headlamp rig.
        float lx = (float) (Math.cos(opt.lightElevation) * Math.sin(opt.lightAzimuth));
        float ly = (float) Math.sin(opt.lightElevation);
        float lz = (float) (Math.cos(opt.lightElevation) * Math.cos(opt.lightAzimuth));
        float[] lightView = Mat4.norm(Mat4.transformVector(view, lx, ly, lz));

        for (Mesh mesh : model.meshes) rasterise(mesh, view, viewProj, lightView);

        if (opt.wireframe) for (Mesh mesh : model.meshes) drawWire(mesh, viewProj);
    }

    private void rasterise(Mesh mesh, Mat4 view, Mat4 viewProj, float[] lightView) {
        if (mesh.positions == null || mesh.indices == null) return;
        Material mat = mesh.material;
        BufferedImage tex = (opt.textured && mat != null) ? mat.texture() : null;
        boolean useVertexColor = mesh.hasColors() && tex == null;
        boolean cull = opt.backfaceCull && mat != null && !mat.doubleSided;
        boolean hasNormals = mesh.hasNormals();
        int vc = mesh.vertexCount();

        int texW = 0, texH = 0;
        int[] texPixels = null;
        if (tex != null) { texW = tex.getWidth(); texH = tex.getHeight(); texPixels = pixels(tex); }

        float[] clip = new float[3 * 4];
        float[] poly = new float[4 * 8];   // near clipping a triangle yields at most 4 vertices

        for (int t = 0; t + 2 < mesh.indices.length; t += 3) {
            int i0 = mesh.indices[t], i1 = mesh.indices[t+1], i2 = mesh.indices[t+2];
            if (i0 < 0 || i1 < 0 || i2 < 0 || i0 >= vc || i1 >= vc || i2 >= vc) continue;

            // one transform gives both the clip position and its w
            for (int k = 0; k < 3; k++) {
                int vi = mesh.indices[t + k];
                float[] c = viewProj.transform(
                        mesh.positions[vi*3], mesh.positions[vi*3+1], mesh.positions[vi*3+2], 1f);
                clip[k*4] = c[0]; clip[k*4+1] = c[1]; clip[k*4+2] = c[2]; clip[k*4+3] = c[3];
            }

            // Backface culling from the signed area in NDC (y up, CCW front-facing).
            // Dividing by w is what turns clip coords into NDC; using x*w here
            // would both be wrong and flip the sign for triangles behind the eye.
            if (cull && clip[3] > 1e-5f && clip[7] > 1e-5f && clip[11] > 1e-5f) {
                float a0x = clip[0]/clip[3],  a0y = clip[1]/clip[3];
                float a1x = clip[4]/clip[7],  a1y = clip[5]/clip[7];
                float a2x = clip[8]/clip[11], a2y = clip[9]/clip[11];
                float area = (a1x-a0x)*(a2y-a0y) - (a2x-a0x)*(a1y-a0y);
                if (area <= 0f) continue;
            }

            int n = clipNear(clip, poly);
            if (n < 3) continue;

            float[] sx = new float[n], sy = new float[n], sz = new float[n], iw = new float[n];
            for (int k = 0; k < n; k++) {
                float ww = Math.max(1e-6f, poly[k*4+3]);
                sx[k] = (poly[k*4]   / ww * 0.5f + 0.5f) * w;
                sy[k] = (1f - (poly[k*4+1] / ww * 0.5f + 0.5f)) * h;
                sz[k] =  poly[k*4+2] / ww;
                iw[k] = 1f / ww;
            }

            // perspective-correct texturing: interpolate (uv / w) then divide by (1 / w)
            float u0 = 0, v0 = 0, u1 = 0, v1 = 0, u2 = 0, v2 = 0;
            if (mesh.hasUVs()) {
                u0 = mesh.uvs[i0*2]; v0 = mesh.uvs[i0*2+1];
                u1 = mesh.uvs[i1*2]; v1 = mesh.uvs[i1*2+1];
                u2 = mesh.uvs[i2*2]; v2 = mesh.uvs[i2*2+1];
            }
            float tu0 = u0 * iw[0], tv0 = v0 * iw[0];
            float tu1 = u1 * iw[1], tv1 = v1 * iw[1];
            float tu2 = u2 * iw[2], tv2 = v2 * iw[2];

            for (int f = 1; f + 1 < n; f++) {
                triangle(
                    sx[0], sy[0], sz[0], iw[0],
                    sx[f], sy[f], sz[f], iw[f],
                    sx[f+1], sy[f+1], sz[f+1], iw[f+1],
                    view, mesh, i0, i1, i2,
                    tu0, tv0, tu1, tv1, tu2, tv2,
                    lightView, hasNormals, mat, texPixels, texW, texH, useVertexColor);
            }
        }
    }

    private void triangle(
            float x0, float y0, float z0, float iw0,
            float x1, float y1, float z1, float iw1,
            float x2, float y2, float z2, float iw2,
            Mat4 view, Mesh mesh, int v0, int v1, int v2,
            float tu0, float tv0, float tu1, float tv1, float tu2, float tv2,
            float[] lightView, boolean hasNormals,
            Material mat, int[] texPixels, int texW, int texH, boolean useVertexColor) {

        float area = (x1-x0)*(y2-y0) - (x2-x0)*(y1-y0);
        if (Math.abs(area) < 1e-7f) return;
        float inv = 1f / area;

        int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(w-1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(h-1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
        if (minX > maxX || minY > maxY) return;

        for (int py = minY; py <= maxY; py++) {
            float fy = py + 0.5f;
            for (int px = minX; px <= maxX; px++) {
                float fx = px + 0.5f;
                float l0 = ((x1-fx)*(y2-fy) - (x2-fx)*(y1-fy)) * inv;
                float l1 = ((x2-fx)*(y0-fy) - (x0-fx)*(y2-fy)) * inv;
                float l2 = 1f - l0 - l1;
                if (l0 < 0f || l1 < 0f || l2 < 0f) continue;

                float z = l0*z0 + l1*z1 + l2*z2;
                int idx = py * w + px;
                if (z >= depth[idx]) continue;

                float iw = l0*iw0 + l1*iw1 + l2*iw2;
                if (iw <= 0f) continue;

                float[] nrm = normalAt(mesh, view, hasNormals, v0, v1, v2, l0, l1, l2);
                float[] base = baseColour(mat, mesh, useVertexColor, v0, v1, v2, l0, l1, l2);

                float r = base[0], g = base[1], b = base[2];
                float alpha = mat == null ? 1f : mat.alpha;
                if (texPixels != null) {
                    float uu = (l0*tu0 + l1*tu1 + l2*tu2) / iw;
                    float vv = (l0*tv0 + l1*tv1 + l2*tv2) / iw;
                    int[] s = sampleBilinear(texPixels, texW, texH, uu, vv);
                    float texAlpha = (s[3] & 0xFF) / 255f;
                    if (texAlpha < 0.04f) continue;
                    if (alpha < 0.99f) alpha *= texAlpha;
                    r *= s[0] / 255f; g *= s[1] / 255f; b *= s[2] / 255f;
                }

                if (opt.shaded && !(mat != null && mat.unlit)) {
                    float diff = Math.max(0f, nrm[0]*lightView[0] + nrm[1]*lightView[1] + nrm[2]*lightView[2]);
                    // half vector: the view direction is +Z in view space
                    float hx = lightView[0], hy = lightView[1], hz = lightView[2] + 1f;
                    float hl = (float) Math.sqrt(hx*hx + hy*hy + hz*hz);
                    if (hl > 1e-6f) { hx /= hl; hy /= hl; hz /= hl; }
                    float spec = Math.max(0f, nrm[0]*hx + nrm[1]*hy + nrm[2]*hz);
                    int shine = Math.max(1, mat == null ? 24 : mat.shininess);
                    spec = (float) Math.pow(spec, shine);

                    float amb = mat == null ? 0.22f : Math.max(0.18f, mat.ambient[0]);
                    float k = (0.18f + 0.82f * diff) * (1f + amb);
                    float sp = mat == null ? 0.35f : mat.specular[0];
                    r = r * k + sp * spec;
                    g = g * k + sp * spec;
                    b = b * k + sp * spec;
                }
                if (mat != null && (mat.emissive[0] > 0 || mat.emissive[1] > 0 || mat.emissive[2] > 0)) {
                    r += mat.emissive[0]; g += mat.emissive[1]; b += mat.emissive[2];
                }

                depth[idx] = z;
                color[idx] = pack(clamp255(r), clamp255(g), clamp255(b), alpha);
            }
        }
    }

    private static float[] normalAt(Mesh mesh, Mat4 view, boolean hasNormals,
                                    int v0, int v1, int v2, float l0, float l1, float l2) {
        if (!hasNormals) return new float[] { 0, 0, 1 };
        float x = mesh.normals[v0*3]  *l0 + mesh.normals[v1*3]  *l1 + mesh.normals[v2*3]  *l2;
        float y = mesh.normals[v0*3+1]*l0 + mesh.normals[v1*3+1]*l1 + mesh.normals[v2*3+1]*l2;
        float z = mesh.normals[v0*3+2]*l0 + mesh.normals[v1*3+2]*l1 + mesh.normals[v2*3+2]*l2;
        float[] n = Mat4.norm(Mat4.transformVector(view, x, y, z));
        // A degenerate normal would otherwise poison the whole pixel with NaN.
        if (Float.isNaN(n[0]) || Float.isNaN(n[1]) || Float.isNaN(n[2])) return new float[] { 0, 0, 1 };
        return n;
    }

    private static float[] baseColour(Material mat, Mesh mesh, boolean useVertexColor,
                                      int v0, int v1, int v2, float l0, float l1, float l2) {
        if (useVertexColor) {
            float r = mesh.colors[v0*4]*l0 + mesh.colors[v1*4]*l1 + mesh.colors[v2*4]*l2;
            float g = mesh.colors[v0*4+1]*l0 + mesh.colors[v1*4+1]*l1 + mesh.colors[v2*4+1]*l2;
            float b = mesh.colors[v0*4+2]*l0 + mesh.colors[v1*4+2]*l1 + mesh.colors[v2*4+2]*l2;
            return new float[] { r, g, b };
        }
        if (mat == null) return new float[] { 0.8f, 0.8f, 0.8f };
        return mat.diffuse;
    }

    /** Bilinear sample with REPEAT wrap, matching glTF's default sampler state. */
    private static int[] sampleBilinear(int[] px, int tw, int th, float u, float v) {
        float fx = u * tw - 0.5f, fy = v * th - 0.5f;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        float ax = fx - x0, ay = fy - y0;
        int x1 = wrap(x0 + 1, tw), y1 = wrap(y0 + 1, th);
        x0 = wrap(x0, tw); y0 = wrap(y0, th);
        int c00 = px[y0*tw + x0], c10 = px[y0*tw + x1];
        int c01 = px[y1*tw + x0], c11 = px[y1*tw + x1];
        int r = lerp2(c00 >> 16 & 0xFF, c10 >> 16 & 0xFF, c01 >> 16 & 0xFF, c11 >> 16 & 0xFF, ax, ay);
        int g = lerp2(c00 >> 8  & 0xFF, c10 >> 8  & 0xFF, c01 >> 8  & 0xFF, c11 >> 8  & 0xFF, ax, ay);
        int b = lerp2(c00       & 0xFF, c10       & 0xFF, c01       & 0xFF, c11       & 0xFF, ax, ay);
        int a = lerp2(c00 >> 24 & 0xFF, c10 >> 24 & 0xFF, c01 >> 24 & 0xFF, c11 >> 24 & 0xFF, ax, ay);
        return new int[] { r, g, b, a };
    }

    private static int lerp2(int a00, int a10, int a01, int a11, float ax, float ay) {
        float top = a00 + (a10 - a00) * ax;
        float bot = a01 + (a11 - a01) * ax;
        return (int) (top + (bot - top) * ay);
    }

    private static int wrap(int v, int size) {
        if (size <= 0) return 0;
        int m = v % size;
        return m < 0 ? m + size : m;
    }

    private static int[] pixels(BufferedImage bi) {
        if (bi.getType() == BufferedImage.TYPE_INT_ARGB
                && bi.getRaster().getDataBuffer() instanceof DataBufferInt) {
            return ((DataBufferInt) bi.getRaster().getDataBuffer()).getData();
        }
        return bi.getRGB(0, 0, bi.getWidth(), bi.getHeight(), null, 0, bi.getWidth());
    }

    /** Sutherland-Hodgman clip against the near plane (z >= -w). Returns vertex count. */
    private static int clipNear(float[] in, float[] out) {
        int n = 3;
        int o = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float ax = in[i*4], ay = in[i*4+1], az = in[i*4+2], aw = in[i*4+3];
            float bx = in[j*4], by = in[j*4+1], bz = in[j*4+2], bw = in[j*4+3];
            float da = az + aw, db = bz + bw;
            boolean ina = da >= 0, inb = db >= 0;
            if (ina && o + 4 <= out.length) {
                out[o*4] = ax; out[o*4+1] = ay; out[o*4+2] = az; out[o*4+3] = aw; o++;
            }
            if (ina != inb && o + 4 <= out.length) {
                float t = da / (da - db);
                out[o*4]   = ax + (bx - ax) * t;
                out[o*4+1] = ay + (by - ay) * t;
                out[o*4+2] = az + (bz - az) * t;
                out[o*4+3] = aw + (bw - aw) * t;
                o++;
            }
            if (o + 2 > out.length / 4) break;   // pathological geometry: bail rather than overflow
        }
        return o;
    }

    private void drawWire(Mesh mesh, Mat4 viewProj) {
        if (mesh.positions == null || mesh.indices == null) return;
        List<int[]> lines = new ArrayList<>();
        for (int t = 0; t + 2 < mesh.indices.length; t += 3) {
            int a = mesh.indices[t], b = mesh.indices[t+1], c = mesh.indices[t+2];
            if (a < 0 || b < 0 || c < 0
                    || a >= mesh.vertexCount() || b >= mesh.vertexCount() || c >= mesh.vertexCount()) continue;
            lines.add(new int[] { a, b });
            lines.add(new int[] { b, c });
            lines.add(new int[] { c, a });
        }
        float[] img = new float[w * h];
        for (int[] seg : lines) {
            float[] p0 = toNdc(viewProj, mesh.positions[seg[0]*3], mesh.positions[seg[0]*3+1], mesh.positions[seg[0]*3+2]);
            float[] p1 = toNdc(viewProj, mesh.positions[seg[1]*3], mesh.positions[seg[1]*3+1], mesh.positions[seg[1]*3+2]);
            if (p0 == null || p1 == null) continue;   // an edge behind the eye
            // No depth test: a wireframe is an overlay, and seeing the hidden
            // edges is the point of turning it on when reviewing topology.
            drawLine(img, p0, p1, false);
        }
        for (int i = 0; i < img.length; i++) {
            if (img[i] > 0) color[i] = blend(color[i], opt.wireColor, 0.85f);
        }
    }

    /**
     * Projects a world point to normalised device coordinates.
     *
     * Returns null when the point is at or behind the eye, which is the only case
     * that cannot be divided through.
     *
     * Note this deliberately avoids {@link Mat4#transformPoint}: that helper
     * divides x/y/z by w but leaves the w slot holding the un-divided w, so a
     * caller that then divides by [3] as well scales every coordinate by 1/w a
     * second time.
     */
    private static float[] toNdc(Mat4 vp, float x, float y, float z) {
        float[] clip = vp.transform(x, y, z, 1f);
        if (clip[3] < 1e-4f) return null;
        return new float[] { clip[0] / clip[3], clip[1] / clip[3], clip[2] / clip[3] };
    }

    /**
     * Walks a line into a coverage buffer. Both endpoints are NDC: x and y in
     * [-1, 1] across the viewport, z in [-1, 1] from near to far plane.
     *
     * Coverage is 1 wherever the line lands, and occlusion is decided against the
     * z-buffer rather than by fading with depth. Fading by depth cannot work here:
     * NDC z is non-linear and sits close to 1 for anything not right at the near
     * plane, so 1-|z| comes out around 0.006 and the line composites at well under
     * one 255th of its colour — invisible.
     *
     * @param depthTest discard the line where the shaded model is already in
     *                  front. A ground grid wants this; a wireframe overlay does not.
     */
    private void drawLine(float[] img, float[] p0, float[] p1, boolean depthTest) {
        float ax = (p0[0] * 0.5f + 0.5f) * w, ay = (1f - (p0[1] * 0.5f + 0.5f)) * h;
        float bx = (p1[0] * 0.5f + 0.5f) * w, by = (1f - (p1[1] * 0.5f + 0.5f)) * h;
        int steps = (int) Math.ceil(Math.max(Math.abs(bx-ax), Math.abs(by-ay)));
        if (steps <= 0 || steps > 8192) return;
        for (int s = 0; s <= steps; s++) {
            float tt = s / (float) steps;
            int px = (int) (ax + (bx-ax)*tt), py = (int) (ay + (by-ay)*tt);
            if (px < 0 || py < 0 || px >= w || py >= h) continue;
            int idx = py*w + px;
            if (depthTest) {
                // Same convention as the triangle rasteriser: NDC z, smaller is nearer.
                float z = p0[2] + (p1[2]-p0[2])*tt;
                if (z >= depth[idx]) continue;
            }
            img[idx] = 1f;
        }
    }

    private void drawGrid(float[] eye, float[] target, float radius, float near, float far) {
        Mat4 view = Mat4.lookAt(eye, target, new float[] { 0, 1, 0 });
        Mat4 vp = Mat4.multiply(Mat4.perspective(45f * (float) Math.PI / 180f, (float) w / h, near, far), view);
        float[] img = new float[w * h];
        int divisions = 10;
        float step = radius * 2f / divisions;
        float lim = radius * 1.6f;
        for (int i = -divisions; i <= divisions; i++) {
            float o = i * step;
            gridLine(img, vp, new float[] { o, 0, -lim }, new float[] { o, 0, lim });
            gridLine(img, vp, new float[] { -lim, 0, o }, new float[] { lim, 0, o });
        }
        for (int i = 0; i < img.length; i++) {
            if (img[i] > 0) color[i] = blend(color[i], opt.gridColor, 0.7f);
        }
    }

    private void gridLine(float[] img, Mat4 vp, float[] a, float[] b) {
        float[] p0 = toNdc(vp, a[0], a[1], a[2]);
        float[] p1 = toNdc(vp, b[0], b[1], b[2]);
        if (p0 == null || p1 == null) return;   // the grid plane runs past the eye
        drawLine(img, p0, p1, true);           // the model occludes the ground
    }

    // -------------------------------------------------------------- framebuffer

    private void fillBackground() {
        for (int y = 0; y < h; y++) {
            float t = (float) y / Math.max(1, h - 1);
            int c = lerpPacked(opt.bgTop.getRGB(), opt.bgBottom.getRGB(), t);
            for (int x = 0; x < w; x++) color[y*w+x] = c;
        }
        java.util.Arrays.fill(depth, Float.MAX_VALUE);
    }

    private BufferedImage toImage() {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, color, 0, w);
        return img;
    }

    // ------------------------------------------------------------------ colour

    private static int clamp255(float v) {
        int i = (int) (v * 255f + 0.5f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    private static int pack(int r, int g, int b, float alpha) {
        int a = clamp255(alpha);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Interpolates two packed ARGB colours. */
    private static int lerpPacked(int a, int b, float t) {
        int r = (int) lerp2(a >> 16 & 0xFF, b >> 16 & 0xFF, a >> 16 & 0xFF, b >> 16 & 0xFF, t, 0);
        int g = (int) lerp2(a >> 8  & 0xFF, b >> 8  & 0xFF, a >> 8  & 0xFF, b >> 8  & 0xFF, t, 0);
        int bl = (int) lerp2(a & 0xFF, b & 0xFF, a & 0xFF, b & 0xFF, t, 0);
        return pack(r, g, bl, 1f);
    }

    private static int blend(int base, Color over, float alpha) {
        int a = (over.getRGB() >>> 24) & 0xFF;
        float f = alpha * (a / 255f);
        int r = (int) (((base >> 16) & 0xFF) * (1 - f) + over.getRed() * f);
        int g = (int) (((base >> 8) & 0xFF) * (1 - f) + over.getGreen() * f);
        int b = (int) ((base & 0xFF) * (1 - f) + over.getBlue() * f);
        return pack(r, g, b, 1f);
    }

    /** Antialiased outline used by the detail panel. */
    public static BufferedImage outlined(BufferedImage src, Color border) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, null);
        g.setColor(border);
        g.setStroke(new BasicStroke(1f));
        g.drawRect(0, 0, src.getWidth() - 1, src.getHeight() - 1);
        g.dispose();
        return out;
    }
}
