package com.assetmanager.core;

import com.assetmanager.util.Formats;
import com.assetmanager.util.FileUtil;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** One indexed file on disk. Mutable POJO; {@code id == 0} means not yet persisted. */
public class Asset {

    public long id;
    public String path;
    public String name;
    public String ext = "";
    public Formats.Category category = Formats.Category.OTHER;

    public long size;
    public long mtime;
    public String contentHash;

    // image
    public int width;
    public int height;

    // audio
    public double duration;
    public int sampleRate;
    public int channels;
    public int bitDepth;

    // 3d
    public int meshVertices;
    public int meshTriangles;

    public long addedAt;
    public String note;

    private Set<String> tags = new LinkedHashSet<>();
    private Set<String> collections = new LinkedHashSet<>();

    public Asset() {}

    public static Asset of(Path p) {
        Asset a = new Asset();
        a.path = p.toAbsolutePath().toString();
        a.name = p.getFileName().toString();
        a.ext = Formats.extOf(a.name);
        a.category = Formats.categoryOf(a.name);
        return a;
    }

    public Path file() { return Path.of(path); }
    public String displayName() { return Formats.baseName(name); }
    public String parent() { return FileUtil.shortenHome(file().getParent() == null ? "" : file().getParent().toString()); }
    public String parentPath() { return file().getParent() == null ? "" : file().getParent().toString(); }
    public boolean exists() { return path != null && file().toFile().exists(); }
    public boolean isImage()  { return category == Formats.Category.IMAGE; }
    public boolean isAudio()  { return category == Formats.Category.AUDIO; }
    public boolean isModel()  { return category == Formats.Category.MODEL; }

    public Set<String> tags() { return Collections.unmodifiableSet(tags); }
    public Set<String> collections() { return Collections.unmodifiableSet(collections); }
    public void setTags(Set<String> t) { tags = new LinkedHashSet<>(t); }
    public void setCollections(Set<String> c) { collections = new LinkedHashSet<>(c); }
    public void addTag(String t) { tags.add(t); }
    public void removeTag(String t) { tags.remove(t); }
    public void addCollection(String c) { collections.add(c); }

    /** One-line summary shown in the grid tooltip. */
    public String tooltip() {
        StringBuilder sb = new StringBuilder("<html>");
        sb.append("<b>").append(esc(displayName())).append("</b><br>");
        sb.append(esc(name)).append("<br>");
        sb.append(esc(parent())).append("<br><br>");
        sb.append("Type: ").append(category.label).append(" (").append(ext.toUpperCase(java.util.Locale.ROOT)).append(")<br>");
        sb.append("Size: ").append(Formats.humanSize(size));
        if (isImage() && width > 0) sb.append("<br>Dimensions: ").append(width).append(" x ").append(height);
        if (isAudio()) {
            sb.append("<br>Duration: ").append(Formats.humanDuration(duration));
            if (sampleRate > 0) sb.append("<br>Sample rate: ").append(sampleRate).append(" Hz");
            if (channels > 0) sb.append(" &middot; Channels: ").append(channels);
        }
        if (isModel()) {
            if (meshTriangles > 0) sb.append("<br>Triangles: ").append(Formats.humanCount(meshTriangles));
            if (meshVertices > 0) sb.append(" &middot; Verts: ").append(Formats.humanCount(meshVertices));
        }
        if (contentHash != null) sb.append("<br>Hash: ").append(contentHash.substring(0, Math.min(12, contentHash.length())));
        if (!tags.isEmpty()) sb.append("<br>Tags: ").append(esc(String.join(", ", tags)));
        if (!collections.isEmpty()) sb.append("<br>Collections: ").append(esc(String.join(", ", collections)));
        return sb.append("</html>").toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override public String toString() { return name + " [" + id + "]"; }
}
