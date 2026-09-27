package com.assetmanager.tools;

import com.assetmanager.util.Log;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Skyline atlas packer. Sorts by height and packs into the smallest power-of-two
 * square that fits.
 *
 * <p>Padding is a gutter on all four sides of every tile, not just between rows.
 * That matters for real use: neighbours that touch bleed into each other under
 * bilinear filtering and mipmaps, which is why texture pipelines extrude or inset
 * every tile. A 1-D skyline cannot express a horizontal exclusion on its own, so
 * each tile is placed by its <em>footprint</em> -- the image plus the gutter --
 * and the image is then drawn at the footprint's corner. The columns the
 * footprint covers are raised, so the next tile cannot start flush against this
 * one's right edge.
 */
public final class AtlasPacker {

    /** One input image to place. */
    public static final class Entry {
        public final String name;
        public final BufferedImage image;
        public Entry(String name, BufferedImage image) {
            this.name = name; this.image = image;
        }
        public String name() { return name; }
        public BufferedImage image() { return image; }
    }

    public static final class Result {
        public final BufferedImage atlas;
        /** Where each input landed, in atlas pixels, origin top-left. */
        public final List<Rectangle> placements;
        public final int usedArea;
        public final int totalArea;
        public final int atlasArea;
        public final int padding;

        Result(BufferedImage atlas, List<Rectangle> placements, int usedArea, int totalArea,
               int atlasArea, int padding) {
            this.atlas = atlas;
            this.placements = placements;
            this.usedArea = usedArea;
            this.totalArea = totalArea;
            this.atlasArea = atlasArea;
            this.padding = padding;
        }

        /**
         * How much of the atlas the tiles actually occupy, 0..1.
         *
         * The denominator is the atlas area, not the sum of the tile areas. Those
         * two are the same number by construction -- nothing is scaled -- so
         * dividing one by the other reported 100% no matter how much was wasted.
         */
        public double efficiency() {
            return atlasArea == 0 ? 0 : usedArea / (double) atlasArea;
        }
    }

    public static final class Rectangle {
        public final int x, y, w, h;
        public final String name;
        Rectangle(int x, int y, int w, int h, String name) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.name = name;
        }
        public int x() { return x; }
        public int y() { return y; }
        public int w() { return w; }
        public int h() { return h; }
        public String name() { return name; }
    }

    private AtlasPacker() {}

    public static Result pack(List<Entry> entries, int padding) {
        if (entries.isEmpty()) {
            return new Result(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
                    List.of(), 0, 0, 1, 0);
        }
        padding = Math.max(0, padding);

        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt((Entry e) -> e.image().getHeight()).reversed()
                .thenComparing(Comparator.comparingInt((Entry e) -> e.image().getWidth()).reversed()));

        // A tile cannot be placed at all unless its whole footprint -- image plus
        // the gutter on its trailing edges, plus the leading margin -- fits.
        int maxW = 0, maxH = 0, total = 0;
        for (Entry e : sorted) {
            maxW = Math.max(maxW, e.image().getWidth() + padding * 2);
            maxH = Math.max(maxH, e.image().getHeight() + padding * 2);
            total += e.image().getWidth() * e.image().getHeight();
        }

        int size = (int) Math.max(Math.ceil(Math.sqrt(total * 1.35)), Math.max(maxW, maxH));
        size = ImageOps.nextPot(size);
        size = Math.max(size, 16);

        // grow until everything fits
        for (int attempt = 0; attempt < 12; attempt++) {
            List<Rectangle> placed = tryPack(sorted, size, padding);
            if (placed != null) return build(sorted, placed, size, total, padding);
            size *= 2;
            if (size > 16384) break;
        }

        Log.warn("Atlas packer gave up at 16384px; falling back to a grid");
        List<Rectangle> grid = gridFallback(sorted, padding);
        return build(sorted, grid, size, total, padding);
    }

    private static List<Rectangle> tryPack(List<Entry> entries, int size, int padding) {
        List<Rectangle> placed = new ArrayList<>();
        // skyline: for each column track the lowest y a tile could start at
        int[] skyline = new int[size];
        // A leading margin, so the first row is not flush against the top edge.
        java.util.Arrays.fill(skyline, padding);

        for (Entry e : entries) {
            int w = e.image().getWidth();
            int h = e.image().getHeight();
            // The footprint is the image plus the gutter it leaves on its right
            // and bottom edges. Claiming those columns is what stops the next
            // tile being placed flush against this one's right edge.
            int fw = w + padding, fh = h + padding;
            if (padding * 2 + w > size || padding * 2 + h > size) return null;

            int x = findX(skyline, fw, size, padding);
            if (x < 0) return null;
            int y = lowest(skyline, x, x + fw);
            if (y + fh > size) return null;

            placed.add(new Rectangle(x, y, w, h, e.name()));
            for (int i = x; i < x + fw && i < size; i++) skyline[i] = y + fh;
        }
        return placed;
    }

    /** Leftmost column at which a footprint of {@code w} sits lowest. */
    private static int findX(int[] skyline, int w, int size, int margin) {
        int bestX = -1, bestY = Integer.MAX_VALUE;
        for (int x = margin; x + w <= size; x++) {
            int y = lowest(skyline, x, x + w);
            if (y >= bestY) continue;
            bestY = y;
            bestX = x;
            if (y == margin) break;   // cannot do better than the top margin
        }
        return bestX;
    }

    private static int lowest(int[] skyline, int from, int to) {
        int m = 0;
        for (int i = from; i < to && i < skyline.length; i++) m = Math.max(m, skyline[i]);
        return m;
    }

    private static List<Rectangle> gridFallback(List<Entry> entries, int padding) {
        List<Rectangle> out = new ArrayList<>();
        int cols = (int) Math.ceil(Math.sqrt(entries.size()));
        int cw = 0, ch = 0;
        for (Entry e : entries) {
            cw = Math.max(cw, e.image().getWidth());
            ch = Math.max(ch, e.image().getHeight());
        }
        // Cell pitch leaves a gutter on the right and below, and the origin is
        // offset by one gutter so the first row is not flush with the edge.
        cw += padding; ch += padding;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            out.add(new Rectangle(padding + (i % cols) * cw, padding + (i / cols) * ch,
                    e.image().getWidth(), e.image().getHeight(), e.name()));
        }
        return out;
    }

    private static Result build(List<Entry> entries, List<Rectangle> placed, int size,
                                int total, int padding) {
        BufferedImage atlas = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = atlas.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        int used = 0;
        for (int i = 0; i < placed.size() && i < entries.size(); i++) {
            Rectangle r = placed.get(i);
            g.drawImage(entries.get(i).image(), r.x(), r.y(), null);
            used += r.w() * r.h();
        }
        g.dispose();
        return new Result(atlas, placed, used, total, size * size, padding);
    }
}
