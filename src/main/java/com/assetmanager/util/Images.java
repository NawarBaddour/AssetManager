package com.assetmanager.util;

import org.apache.commons.imaging.Imaging;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/** Image loading/scaling helpers. ImageIO plugins (WebP/PSD/TIFF/HDR/Batik) come from ./lib. */
public final class Images {

    private static volatile boolean initialised = false;

    private Images() {}

    public static synchronized void init() {
        if (initialised) return;
        initialised = true;
        ImageIO.setUseCache(false);
        ImageIO.scanForPlugins();
        Log.info("ImageIO readers: " + describeReaders());
    }

    private static String describeReaders() {
        StringBuilder sb = new StringBuilder();
        for (String f : ImageIO.getReaderFormatNames()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(f);
        }
        return sb.toString();
    }

    public static boolean canRead(String ext) {
        return hasReaderFor(ext);
    }

    private static boolean hasReaderFor(String ext) {
        return ImageIO.getImageReadersByFormatName(ext).hasNext();
    }

    /** Loads any supported image; falls back to Commons Imaging for TGA/PCX/PSD/DDS. */
    public static BufferedImage read(Path file) throws IOException {
        init();
        String ext = Formats.extOf(file.getFileName().toString());
        if (Formats.IMAGE_COMMONS.contains(ext)) {
            try {
                BufferedImage bi = Imaging.getBufferedImage(file.toFile());
                if (bi != null) return normalise(bi);
            } catch (Exception e) {
                Log.warn("commons-imaging failed on " + file.getFileName() + ", trying ImageIO: " + e);
            }
        }
        BufferedImage bi = readViaImageIo(file);
        if (bi == null) throw new IOException("No decoder for ." + ext);
        return normalise(bi);
    }

    private static BufferedImage readViaImageIo(Path file) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(Files.newInputStream(file))) {
            if (in == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return reader.read(0, reader.getDefaultReadParam());
            } finally {
                reader.dispose();
            }
        }
    }

    /** Reads dimensions without decoding pixels where possible. Never throws. */
    public static int[] readDimensions(Path file) {
        init();
        try (ImageInputStream in = ImageIO.createImageInputStream(Files.newInputStream(file))) {
            if (in == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                return new int[] { reader.getWidth(0), reader.getHeight(0) };
            } finally {
                reader.dispose();
            }
        } catch (Throwable t) {
            // A broken third-party plugin surfaces as NoClassDefFoundError, not Exception.
            Log.debug("dimension probe failed for " + file.getFileName() + ": " + t);
        }
        if (Formats.IMAGE_COMMONS.contains(Formats.extOf(file.getFileName().toString()))) {
            try {
                java.awt.image.BufferedImage bi = Imaging.getBufferedImage(file.toFile());
                if (bi != null) return new int[] { bi.getWidth(), bi.getHeight() };
            } catch (Throwable ignored) { }
        }
        return null;
    }

    /** Converts odd types (indexed, 4-byte gray, premultiplied) into plain ARGB. */
    public static BufferedImage normalise(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setComposite(java.awt.AlphaComposite.Src);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    /** High-quality downscale; essential for thumbnail generation. */
    public static BufferedImage scale(BufferedImage src, int targetW, int targetH) {
        if (targetW < 1) targetW = 1;
        if (targetH < 1) targetH = 1;
        BufferedImage out = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setComposite(java.awt.AlphaComposite.Src);
        g.drawImage(src, 0, 0, targetW, targetH, null);
        g.dispose();
        return out;
    }

    /** Fits inside a box preserving aspect ratio. */
    public static BufferedImage scaleToFit(BufferedImage src, int box) {
        double s = Math.min((double) box / src.getWidth(), (double) box / src.getHeight());
        if (s > 1) s = 1; // never upscale for thumbnails
        int w = Math.max(1, (int) Math.round(src.getWidth() * s));
        int h = Math.max(1, (int) Math.round(src.getHeight() * s));
        return scale(src, w, h);
    }

    /** Checkerboard so transparent pixels are visible. */
    public static BufferedImage onCheckerboard(BufferedImage src, int cell) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Color light = new Color(0xFFFFFF), dark = new Color(0xCCCCCC);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean even = (((x / cell) + (y / cell)) & 1) == 0;
                out.setRGB(x, y, (even ? light : dark).getRGB());
            }
        }
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    /** Flattens transparency onto a solid colour (JPEG can't store alpha). */
    public static BufferedImage flatten(BufferedImage src, Color bg) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(bg);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    /** Writes an image, choosing PNG when alpha is present. */
    public static void write(BufferedImage img, Path target, String formatName) throws IOException {
        BufferedImage toWrite = img;
        if (isOpaqueFormat(formatName) && img.getColorModel().hasAlpha()) {
            toWrite = flatten(img, Color.BLACK);
        }
        if (!ImageIO.write(toWrite, formatName, target.toFile())) {
            throw new IOException("No writer available for format '" + formatName + "'");
        }
    }

    public static boolean isOpaqueFormat(String formatName) {
        return "jpeg".equalsIgnoreCase(formatName) || "bmp".equalsIgnoreCase(formatName);
    }

    /** Which writer name to use for a file extension. */
    public static String writerForExt(String ext) {
        switch (ext) {
            case "jpg": case "jpeg": return "jpeg";
            case "tif": case "tiff": return "tiff";
            default: return ext;
        }
    }

    /** Image formats that actually have a reader on this classpath. */
    public static String[] readableFormats() {
        init();
        java.util.Set<String> s = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        s.addAll(java.util.Arrays.asList(ImageIO.getReaderFormatNames()));
        return s.toArray(new String[0]);
    }

    /** Image formats that have a writer (used by the texture tools' save menu). */
    public static String[] writableFormats() {
        init();
        java.util.Set<String> s = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        s.addAll(java.util.Arrays.asList(ImageIO.getWriterFormatNames()));
        return s.toArray(new String[0]);
    }

    public static String formatLabel(String ext) {
        switch (ext) {
            case "jpg": case "jpeg": return "JPEG";
            case "tif": case "tiff": return "TIFF";
            case "webp": return "WebP";
            case "psd": return "PSD (flattened)";
            default: return ext.toUpperCase(java.util.Locale.ROOT);
        }
    }
}
