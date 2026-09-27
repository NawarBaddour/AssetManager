package com.assetmanager.tools;

import com.assetmanager.util.Images;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Pure image operations behind the texture tools. All return new images. */
public final class ImageOps {

    public enum Channel { R, G, B, A }

    public static BufferedImage resize(BufferedImage src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        return Images.scale(src, w, h);
    }

    /** Nearest-power-of-two downscale, the usual requirement for GPU textures. */
    public static int nextPot(int v) {
        if (v <= 1) return 1;
        int p = 1;
        while (p < v) p <<= 1;
        return p;
    }

    public static BufferedImage toPowerOfTwo(BufferedImage src, boolean allowUpscale) {
        int w = nextPot(src.getWidth());
        int h = nextPot(src.getHeight());
        if (!allowUpscale) {
            w = Math.min(w, src.getWidth());
            h = Math.min(h, src.getHeight());
        }
        return resize(src, w, h);
    }

    public static BufferedImage crop(BufferedImage src, int x, int y, int w, int h) {
        x = Math.max(0, Math.min(x, src.getWidth() - 1));
        y = Math.max(0, Math.min(y, src.getHeight() - 1));
        w = Math.max(1, Math.min(w, src.getWidth() - x));
        h = Math.max(1, Math.min(h, src.getHeight() - y));
        return src.getSubimage(x, y, w, h);
    }

    public static BufferedImage flipH(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, src.getWidth(), 0, -src.getWidth(), src.getHeight(), null);
        g.dispose();
        return out;
    }

    public static BufferedImage flipV(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, src.getHeight(), src.getWidth(), -src.getHeight(), null);
        g.dispose();
        return out;
    }

    public static BufferedImage rotate90(BufferedImage src, boolean clockwise) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(clockwise ? h : w, clockwise ? w : h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.translate((clockwise ? h : w) / 2.0, (clockwise ? w : h) / 2.0);
        g.rotate(clockwise ? Math.PI / 2 : -Math.PI / 2);
        g.drawImage(src, -w / 2, -h / 2, null);
        g.dispose();
        return out;
    }

    /** Isolate one channel as greyscale, keeping alpha when it is the alpha channel. */
    public static BufferedImage extractChannel(BufferedImage src, Channel ch) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            src.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int c = row[x];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF, a = (c >>> 24) & 0xFF;
                int v;
                switch (ch) {
                    case R: v = r; break;
                    case G: v = g; break;
                    case B: v = b; break;
                    default: v = a; break;
                }
                int oa = ch == Channel.A ? 255 : a;
                row[x] = (oa << 24) | (v << 16) | (v << 8) | v;
            }
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        return out;
    }

    /** Pack selected channels into R/G/B/A, e.g. ORM-style maps. */
    public static BufferedImage packChannels(BufferedImage src, Channel r, Channel g, Channel b, Channel a) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        int[] outRow = new int[w];
        for (int y = 0; y < h; y++) {
            src.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int c = row[x];
                outRow[x] = (pick(c, a) << 24) | (pick(c, r) << 16) | (pick(c, g) << 8) | pick(c, b);
            }
            out.setRGB(0, y, w, 1, outRow, 0, w);
        }
        return out;
    }

    private static int pick(int c, Channel ch) {
        switch (ch) {
            case R: return (c >> 16) & 0xFF;
            case G: return (c >> 8) & 0xFF;
            case B: return c & 0xFF;
            default: return (c >>> 24) & 0xFF;
        }
    }

    /**
     * Sobel height-to-normal conversion. {@code strength} scales the gradient;
     * higher values give a more pronounced relief.
     */
    public static BufferedImage heightToNormal(BufferedImage src, double strength) {
        int w = src.getWidth(), h = src.getHeight();
        if (w < 3 || h < 3) return src;

        float[] height = new float[w * h];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            src.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) height[y * w + x] = luminance(row[x]);
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] outRow = new int[w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // Sobel kernels, with the border clamped to the edge sample
                float tl = height[at(y-1, x-1, w, h)], tc = height[at(y-1, x, w, h)], tr = height[at(y-1, x+1, w, h)];
                float ml = height[at(y,   x-1, w, h)],                          mr = height[at(y,   x+1, w, h)];
                float bl = height[at(y+1, x-1, w, h)], bc = height[at(y+1, x, w, h)], br = height[at(y+1, x+1, w, h)];

                float gx = (tr + 2*mr + br) - (tl + 2*ml + bl);
                float gy = (bl + 2*bc + br) - (tl + 2*tc + tr);

                float nx = (float) (-gx * strength);
                float ny = (float) (-gy * strength);
                float nz = 1f;
                float len = (float) Math.sqrt(nx*nx + ny*ny + nz*nz);
                int r = (int) ((nx / len * 0.5f + 0.5f) * 255);
                int g = (int) ((ny / len * 0.5f + 0.5f) * 255);
                int b = (int) ((nz / len * 0.5f + 0.5f) * 255);
                outRow[x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            out.setRGB(0, y, w, 1, outRow, 0, w);
        }
        return out;
    }

    private static int at(int y, int x, int w, int h) {
        int yy = y < 0 ? 0 : (y >= h ? h - 1 : y);
        int xx = x < 0 ? 0 : (x >= w ? w - 1 : x);
        return yy * w + xx;
    }

    private static float luminance(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
    }

    /** Opacity adjustment; {@code factor} > 1 increases alpha. */
    public static BufferedImage setOpacity(BufferedImage src, float factor) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            src.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int a = (row[x] >>> 24) & 0xFF;
                int na = (int) Math.max(0, Math.min(255, a * factor));
                row[x] = (na << 24) | (row[x] & 0x00FFFFFF);
            }
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        return out;
    }

    /** Adds a solid border, useful for atlases that rely on edge clamping. */
    public static BufferedImage addBorder(BufferedImage src, Color color, int thickness) {
        if (thickness <= 0) return src;
        int w = src.getWidth() + thickness * 2;
        int h = src.getHeight() + thickness * 2;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.drawImage(src, thickness, thickness, null);
        g.dispose();
        return out;
    }
}
