package com.assetmanager.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** File-extension knowledge: what category an asset is in, and can we decode it. */
public final class Formats {

    public enum Category {
        IMAGE("Images", "IMG"),
        AUDIO("Audio", "AUD"),
        MODEL("3D Models", "3D"),
        OTHER("Other", "OTH");

        public final String label;
        public final String badge;

        Category(String label, String badge) {
            this.label = label;
            this.badge = badge;
        }

        @Override public String toString() { return label; }
    }

    // ---- image ----
    public static final Set<String> IMAGE = set(
            "png", "jpg", "jpeg", "jpe", "jfif", "gif", "bmp", "wbmp", "ico", "cur",
            "tga", "targa", "pcx", "psd", "psb", "tif", "tiff", "webp", "dds",
            "exr", "hdr", "jp2", "j2k", "jpf", "svg");

    /**
     * Vector formats we recognise but cannot decode. SVG rendering needs Apache
     * Batik, whose batik-svgdom module is not published on Maven Central, so these
     * are indexed and reported rather than previewed.
     */
    public static final Set<String> IMAGE_NO_DECODER = set("svg", "eps", "ai");

    /** Formats only reachable through Apache Commons Imaging, not ImageIO. */
    public static final Set<String> IMAGE_COMMONS = set("tga", "targa", "pcx", "psd", "psb", "dds");

    public static final Set<String> IMAGE_WRITABLE = set(
            "png", "jpg", "jpeg", "bmp", "gif", "wbmp", "tif", "tiff", "webp", "ico");

    // ---- audio ----
    public static final Set<String> AUDIO = set(
            "wav", "wave", "aif", "aiff", "aifc", "au", "snd", "mp3",
            "ogg", "oga", "flac", "m4a", "aac", "opus", "wma", "mid", "midi");

    /** Audio containers we actually have a decoder for. */
    public static final Set<String> AUDIO_DECODABLE = set("wav", "wave", "aif", "aiff", "aifc", "au", "snd", "mp3");

    // ---- 3d models ----
    public static final Set<String> MODEL = set(
            "obj", "gltf", "glb", "stl", "ply", "fbx", "dae", "3ds", "blend", "x3d", "vrml");

    /** Mesh formats the built-in software renderer can load. */
    public static final Set<String> MODEL_LOADABLE = set("obj", "gltf", "glb", "stl", "ply");

    /** Sidecar material files, never shown as their own asset. */
    public static final Set<String> SIDECAR = set("mtl");

    /** Files that should never be indexed even if they match a category. */
    public static final Set<String> IGNORE_NAMES = set(".ds_store", "thumbs.db", "desktop.ini", ".gitkeep");

    private static Set<String> set(String... items) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(items)));
    }

    private Formats() {}

    public static String extOf(String fileName) {
        if (fileName == null) return "";
        int i = fileName.lastIndexOf('.');
        if (i < 0 || i == fileName.length() - 1) return "";
        return fileName.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    public static String baseName(String fileName) {
        if (fileName == null) return "";
        int i = fileName.lastIndexOf('.');
        return (i > 0) ? fileName.substring(0, i) : fileName;
    }

    public static Category categoryOf(String fileName) {
        String e = extOf(fileName);
        if (IMAGE.contains(e))  return Category.IMAGE;
        if (AUDIO.contains(e))  return Category.AUDIO;
        if (MODEL.contains(e))  return Category.MODEL;
        return Category.OTHER;
    }

    public static boolean isIndexable(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.startsWith(".")) return false;
        if (IGNORE_NAMES.contains(lower)) return false;
        if (SIDECAR.contains(extOf(lower))) return false;
        return categoryOf(lower) != Category.OTHER;
    }

    /** A single image file that the built-in loaders can open. */
    public static boolean isImageLoadable(String ext) {
        return IMAGE.contains(ext) && !IMAGE_NO_DECODER.contains(ext);
    }

    public static boolean isAudioDecodable(String ext) {
        return AUDIO_DECODABLE.contains(ext);
    }

    public static boolean isModelLoadable(String ext) {
        return MODEL_LOADABLE.contains(ext);
    }

    /** "1.4 MB" */
    public static String humanSize(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        String[] units = { "KB", "MB", "GB", "TB" };
        double v = bytes;
        int u = -1;
        while (v >= 1024 && u < units.length - 1) { v /= 1024; u++; }
        return (v >= 100 ? String.format(Locale.ROOT, "%.0f", v)
                : v >= 10 ? String.format(Locale.ROOT, "%.1f", v)
                : String.format(Locale.ROOT, "%.2f", v)) + " " + units[u];
    }

    /** "1:04.320" */
    public static String humanDuration(double seconds) {
        if (seconds < 0 || Double.isNaN(seconds) || Double.isInfinite(seconds)) return "--:--";
        long ms = Math.round(seconds * 1000);
        long m = ms / 60000, s = (ms % 60000) / 1000, r = ms % 1000;
        if (m > 0) return String.format(Locale.ROOT, "%d:%02d.%03d", m, s, r);
        return String.format(Locale.ROOT, "%d:%02d", s, r / 10);
    }

    public static String humanCount(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
