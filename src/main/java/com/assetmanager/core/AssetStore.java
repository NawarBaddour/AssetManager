package com.assetmanager.core;

import com.assetmanager.util.Formats;
import com.assetmanager.util.Log;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** All SQL lives here. */
public final class AssetStore {

    private final Database db;

    public AssetStore(Database db) { this.db = db; }

    public Database db() { return db; }

    // ------------------------------------------------------------------ query

    /** A structured search request built by the UI filter bar. */
    public static final class Query {
        public String text = "";
        public Formats.Category category;          // null = any
        public Set<Long> tagIds = new LinkedHashSet<>();
        public Set<Long> collectionIds = new LinkedHashSet<>();
        /** Assets carrying *all* of these tags. */
        public Set<String> tagNamesAll = new LinkedHashSet<>();
        public Set<String> tagNamesAny = new LinkedHashSet<>();
        public long minSize = -1, maxSize = -1;
        public Integer minDim;
        public Boolean onlyUntagged;
        public Boolean onlyDuplicates;
        public Boolean onlyProblems;
        public Sort sort = Sort.NAME;
        public int limit = 5000;
        public int offset = 0;

        public enum Sort {
            NAME("Name"), DATE("Date added"), MODIFIED("Date modified"), SIZE("Size"),
            DIMENSION("Resolution"), DURATION("Duration"), TRIANGLES("Triangles"), KIND("Type");
            public final String label;
            Sort(String l) { this.label = l; }
            @Override public String toString() { return label; }
        }
    }

    public List<Asset> search(Query q) {
        StringBuilder sql = new StringBuilder("SELECT DISTINCT a.* FROM assets a");
        List<Object> args = new ArrayList<>();
        buildFilter(sql, args, q);

        String order;
        switch (q.sort) {
            case DATE:      order = "a.added_at DESC, a.name COLLATE NOCASE ASC"; break;
            case MODIFIED:  order = "a.mtime DESC, a.name COLLATE NOCASE ASC"; break;
            case SIZE:      order = "a.size DESC, a.name COLLATE NOCASE ASC"; break;
            case DIMENSION: order = "(a.width * a.height) DESC, a.name COLLATE NOCASE ASC"; break;
            case DURATION:  order = "a.duration DESC, a.name COLLATE NOCASE ASC"; break;
            case TRIANGLES: order = "a.mesh_triangles DESC, a.name COLLATE NOCASE ASC"; break;
            case KIND:      order = "a.category ASC, a.ext ASC, a.name COLLATE NOCASE ASC"; break;
            case NAME:
            default:        order = "a.name COLLATE NOCASE ASC"; break;
        }
        sql.append(" ORDER BY ").append(order).append(" LIMIT ? OFFSET ?");
        args.add(q.limit);
        args.add(q.offset);

        List<Asset> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(sql.toString())) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(read(rs));
            }
        } catch (SQLException e) {
            Log.error("search failed", e);
        }
        hydrateTags(out);
        return out;
    }

    /**
     * Appends the JOINs and WHERE clause implied by a query to an in-progress
     * statement, and its bound values to {@code args}. Shared by search and
     * count so the two can never drift apart.
     */
    private static void buildFilter(StringBuilder sql, List<Object> args, Query q) {
        List<String> joins = new ArrayList<>();
        List<String> where = new ArrayList<>();

        if (!q.tagIds.isEmpty()) {
            joins.add("JOIN asset_tags ft ON ft.asset_id = a.id");
            where.add("ft.tag_id IN (" + placeholders(q.tagIds.size()) + ")");
            for (Long id : q.tagIds) args.add(id);
        }
        if (!q.collectionIds.isEmpty()) {
            joins.add("JOIN collection_assets fc ON fc.asset_id = a.id");
            where.add("fc.collection_id IN (" + placeholders(q.collectionIds.size()) + ")");
            for (Long id : q.collectionIds) args.add(id);
        }
        if (!q.tagNamesAll.isEmpty()) {
            for (String t : q.tagNamesAll) {
                where.add("EXISTS (SELECT 1 FROM asset_tags x JOIN tags t ON t.id = x.tag_id"
                        + " WHERE x.asset_id = a.id AND t.name = ? COLLATE NOCASE)");
                args.add(t);
            }
        }
        if (!q.tagNamesAny.isEmpty()) {
            where.add("EXISTS (SELECT 1 FROM asset_tags x JOIN tags t ON t.id = x.tag_id"
                    + " WHERE x.asset_id = a.id AND (" + tagNameOrPlaceholders(q.tagNamesAny) + "))");
            args.addAll(q.tagNamesAny);
        }
        if (q.category != null) {
            where.add("a.category = ?");
            args.add(q.category.name());
        }
        if (q.minSize >= 0) { where.add("a.size >= ?"); args.add(q.minSize); }
        if (q.maxSize >= 0) { where.add("a.size <= ?"); args.add(q.maxSize); }
        if (q.minDim != null && q.minDim > 0) {
            where.add("(a.width >= ? AND a.height >= ?)");
            args.add(q.minDim); args.add(q.minDim);
        }
        if (Boolean.TRUE.equals(q.onlyUntagged)) {
            where.add("NOT EXISTS (SELECT 1 FROM asset_tags x WHERE x.asset_id = a.id)");
        }
        if (Boolean.TRUE.equals(q.onlyDuplicates)) {
            where.add("a.content_hash IS NOT NULL AND a.content_hash IN"
                    + " (SELECT content_hash FROM assets WHERE content_hash IS NOT NULL GROUP BY content_hash HAVING COUNT(*) > 1)");
        }
        if (Boolean.TRUE.equals(q.onlyProblems)) {
            where.add("(a.content_hash IS NULL OR a.content_hash = '' OR (a.category = 'IMAGE' AND a.width <= 0)"
                    + " OR (a.category = 'MODEL' AND a.mesh_triangles <= 0)"
                    + " OR (a.category = 'AUDIO' AND a.duration <= 0))");
        }

        String text = q.text == null ? "" : q.text.trim();
        if (!text.isEmpty()) {
            List<String> terms = new ArrayList<>();
            for (String t : text.split("\\s+")) if (!t.isEmpty()) terms.add(t);
            for (String t : terms) {
                where.add("(a.name LIKE ? ESCAPE '\\' OR a.path LIKE ? ESCAPE '\\'"
                        + " OR a.note LIKE ? ESCAPE '\\'"
                        + " OR EXISTS (SELECT 1 FROM asset_tags x JOIN tags g ON g.id = x.tag_id"
                        + "     WHERE x.asset_id = a.id AND g.name LIKE ? ESCAPE '\\'))");
                String like = "%" + escapeLike(t) + "%";
                args.add(like); args.add(like); args.add(like); args.add(like);
            }
        }

        for (String j : joins) sql.append(' ').append(j);
        if (!where.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", where));
    }

    private static String tagNameOrPlaceholders(Set<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(" OR ");
            sb.append("g.name = ? COLLATE NOCASE");
        }
        return sb.toString();
    }

    public Asset byId(long id) {
        try (PreparedStatement ps = db.conn().prepareStatement("SELECT * FROM assets WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Asset a = read(rs);
                    hydrateTags(List.of(a));
                    return a;
                }
            }
        } catch (SQLException e) { Log.error("byId", e); }
        return null;
    }

    public Asset byPath(String path) {
        try (PreparedStatement ps = db.conn().prepareStatement("SELECT * FROM assets WHERE path = ?")) {
            ps.setString(1, path);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Asset a = read(rs);
                    hydrateTags(List.of(a));
                    return a;
                }
            }
        } catch (SQLException e) { Log.error("byPath", e); }
        return null;
    }

    public List<Asset> all() {
        Query q = new Query();
        q.limit = Integer.MAX_VALUE;
        return search(q);
    }

    /**
     * How many rows a query matches, ignoring its LIMIT/OFFSET. Done in SQL so a
     * large library is not pulled into memory just to be counted.
     */
    public long count(Query q) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(DISTINCT a.id) FROM assets a");
        List<Object> args = new ArrayList<>();
        buildFilter(sql, args, q);
        try (PreparedStatement ps = db.conn().prepareStatement(sql.toString())) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            Log.error("count failed", e);
            return 0L;
        }
    }

    // ------------------------------------------------------------------ write

    /** Inserts or updates by path. Returns the row id. */
    public long upsert(Asset a) {
        String sql =
            "INSERT INTO assets (path, name, ext, category, size, mtime, content_hash, width, height,"
          + " duration, sample_rate, channels, bit_depth, mesh_vertices, mesh_triangles, note, added_at)"
          + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
          + " ON CONFLICT(path) DO UPDATE SET"
          + " name=excluded.name, ext=excluded.ext, category=excluded.category, size=excluded.size,"
          + " mtime=excluded.mtime, content_hash=excluded.content_hash, width=excluded.width,"
          + " height=excluded.height, duration=excluded.duration, sample_rate=excluded.sample_rate,"
          + " channels=excluded.channels, bit_depth=excluded.bit_depth,"
          + " mesh_vertices=excluded.mesh_vertices, mesh_triangles=excluded.mesh_triangles";
        return db.tx(() -> {
            try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
                ps.setString(1, a.path);
                ps.setString(2, a.name);
                ps.setString(3, a.ext);
                ps.setString(4, a.category.name());
                ps.setLong(5, a.size);
                ps.setLong(6, a.mtime);
                ps.setString(7, a.contentHash);
                ps.setInt(8, a.width);
                ps.setInt(9, a.height);
                ps.setDouble(10, a.duration);
                ps.setInt(11, a.sampleRate);
                ps.setInt(12, a.channels);
                ps.setInt(13, a.bitDepth);
                ps.setInt(14, a.meshVertices);
                ps.setInt(15, a.meshTriangles);
                ps.setString(16, a.note);
                ps.setLong(17, a.addedAt == 0 ? System.currentTimeMillis() : a.addedAt);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = db.conn().prepareStatement("SELECT id FROM assets WHERE path = ?")) {
                ps.setString(1, a.path);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }
            return 0L;
        });
    }

    public void deleteByPaths(Collection<String> paths) {
        if (paths.isEmpty()) return;
        db.tx(() -> {
            try (PreparedStatement ps = db.conn()
                    .prepareStatement("DELETE FROM assets WHERE path = ?")) {
                for (String p : paths) {
                    // Two things are easy to get wrong here, and both fail silently
                    // rather than loudly. The parameter must be bound before
                    // addBatch, or it is sent as NULL and nothing is deleted. And the
                    // index restarts at 1 for every batch entry: batch parameter
                    // numbering is per-statement, not cumulative across the batch.
                    ps.setString(1, p);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    public void setNote(long id, String note) {
        db.tx(() -> {
            try (PreparedStatement ps = db.conn().prepareStatement("UPDATE assets SET note = ? WHERE id = ?")) {
                ps.setString(1, note);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ tags

    /** A tag plus how many assets carry it. */
    public static final class TagRow {
        public final long id; public final String name, color; public final int count;
        public TagRow(long id, String name, String color, int count) {
            this.id = id; this.name = name; this.color = color; this.count = count;
        }
        public long getId() { return id; }
        public String getName() { return name; }
        public String getColor() { return color; }
        public int getCount() { return count; }
    }

    public List<TagRow> allTags() {
        List<TagRow> out = new ArrayList<>();
        String sql =
            "SELECT t.id, t.name, t.color,"
          + " (SELECT COUNT(*) FROM asset_tags x WHERE x.tag_id = t.id) AS cnt"
          + " FROM tags t ORDER BY t.name COLLATE NOCASE";
        try (PreparedStatement ps = db.conn().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(new TagRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
        } catch (SQLException e) { Log.error("allTags", e); }
        return out;
    }

    public long ensureTag(String name) {
        String clean = name.trim();
        if (clean.isEmpty()) return 0;
        return db.tx(() -> {
            try (PreparedStatement ps = db.conn()
                    .prepareStatement("INSERT OR IGNORE INTO tags(name) VALUES (?)")) {
                ps.setString(1, clean);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = db.conn().prepareStatement("SELECT id FROM tags WHERE name = ? COLLATE NOCASE")) {
                ps.setString(1, clean);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }
            return 0L;
        });
    }

    public void addTagToAssets(Collection<Long> assetIds, String tagName) {
        if (assetIds.isEmpty()) return;
        long tagId = ensureTag(tagName);
        if (tagId == 0) return;
        db.tx(() -> {
            try (PreparedStatement ps = db.conn()
                    .prepareStatement("INSERT OR IGNORE INTO asset_tags(asset_id, tag_id) VALUES (?,?)")) {
                for (Long id : assetIds) { ps.setLong(1, id); ps.setLong(2, tagId); ps.addBatch(); }
                ps.executeBatch();
            }
            return null;
        });
    }

    public void removeTagFromAssets(Collection<Long> assetIds, String tagName) {
        if (assetIds.isEmpty()) return;
        db.tx(() -> {
            try (PreparedStatement ps = db.conn().prepareStatement(
                    "DELETE FROM asset_tags WHERE tag_id = (SELECT id FROM tags WHERE name = ? COLLATE NOCASE) AND asset_id = ?")) {
                for (Long id : assetIds) { ps.setString(1, tagName); ps.setLong(2, id); ps.addBatch(); }
                ps.executeBatch();
            }
            return null;
        });
    }

    public void setTagColor(String tagName, String color) {
        db.tx(() -> {
            try (PreparedStatement ps = db.conn().prepareStatement("UPDATE tags SET color = ? WHERE name = ? COLLATE NOCASE")) {
                ps.setString(1, color);
                ps.setString(2, tagName);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void deleteTag(String tagName) {
        db.tx(() -> {
            try (PreparedStatement ps = db.conn().prepareStatement("DELETE FROM tags WHERE name = ? COLLATE NOCASE")) {
                ps.setString(1, tagName);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Map<Long, Set<String>> tagsFor(Collection<Long> ids) {
        Map<Long, Set<String>> map = new LinkedHashMap<>();
        if (ids.isEmpty()) return map;
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) { if (i > 0) in.append(','); in.append(ids.toArray()[i]); }
        String sql = "SELECT x.asset_id, t.name FROM asset_tags x JOIN tags t ON t.id = x.tag_id"
                + " WHERE x.asset_id IN (" + in + ") ORDER BY t.name COLLATE NOCASE";
        try (PreparedStatement ps = db.conn().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) map.computeIfAbsent(rs.getLong(1), k -> new LinkedHashSet<>()).add(rs.getString(2));
        } catch (SQLException e) { Log.error("tagsFor", e); }
        return map;
    }

    private void hydrateTags(List<Asset> assets) {
        if (assets.isEmpty()) return;
        List<Long> ids = new java.util.ArrayList<>(assets.size());
        for (Asset a : assets) ids.add(a.id);
        Map<Long, Set<String>> tmap = tagsFor(ids);
        for (Asset a : assets) a.setTags(tmap.getOrDefault(a.id, java.util.Collections.emptySet()));
    }

    public Set<String> tagsOf(long assetId) {
        return tagsFor(List.of(assetId)).getOrDefault(assetId, new LinkedHashSet<>());
    }

    // ------------------------------------------------------------ collections

    /** A collection plus how many assets it holds. */
    public static final class CollectionRow {
        public final long id; public final String name, color; public final int count;
        public CollectionRow(long id, String name, String color, int count) {
            this.id = id; this.name = name; this.color = color; this.count = count;
        }
        public long getId() { return id; }
        public String getName() { return name; }
        public String getColor() { return color; }
        public int getCount() { return count; }
    }

    public List<CollectionRow> allCollections() {
        List<CollectionRow> out = new ArrayList<>();
        String sql =
            "SELECT c.id, c.name, c.color,"
          + " (SELECT COUNT(*) FROM collection_assets x WHERE x.collection_id = c.id) AS cnt"
          + " FROM collections c ORDER BY c.name COLLATE NOCASE";
        try (PreparedStatement ps = db.conn().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(new CollectionRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
        } catch (SQLException e) { Log.error("allCollections", e); }
        return out;
    }

    public long ensureCollection(String name) {
        return db.tx(() -> {
            try (PreparedStatement ps = db.conn().prepareStatement("INSERT OR IGNORE INTO collections(name) VALUES (?)")) {
                ps.setString(1, name.trim());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = db.conn().prepareStatement("SELECT id FROM collections WHERE name = ? COLLATE NOCASE")) {
                ps.setString(1, name.trim());
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getLong(1); }
            }
            return 0L;
        });
    }

    public void addToCollections(Collection<Long> assetIds, Collection<String> collNames) {
        if (assetIds.isEmpty() || collNames.isEmpty()) return;
        Map<Long, Long> ids = new LinkedHashMap<>();
        for (String c : collNames) {
            long cid = ensureCollection(c);
            if (cid > 0) ids.put(cid, 1L);
        }
        db.tx(() -> {
            try (PreparedStatement ps = db.conn()
                    .prepareStatement("INSERT OR IGNORE INTO collection_assets(collection_id, asset_id) VALUES (?,?)")) {
                for (Long cid : ids.keySet())
                    for (Long aid : assetIds) { ps.setLong(1, cid); ps.setLong(2, aid); ps.addBatch(); }
                ps.executeBatch();
            }
            return null;
        });
    }

    public void removeFromCollections(Collection<Long> assetIds, String collName) {
        db.tx(() -> {
            try (PreparedStatement ps = db.conn().prepareStatement(
                    "DELETE FROM collection_assets WHERE collection_id = (SELECT id FROM collections WHERE name = ? COLLATE NOCASE) AND asset_id = ?")) {
                for (Long aid : assetIds) { ps.setString(1, collName); ps.setLong(2, aid); ps.addBatch(); }
                ps.executeBatch();
            }
            return null;
        });
    }

    public Set<String> collectionsOf(long assetId) {
        Set<String> out = new LinkedHashSet<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT c.name FROM collection_assets x JOIN collections c ON c.id = x.collection_id WHERE x.asset_id = ?")) {
            ps.setLong(1, assetId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(rs.getString(1)); }
        } catch (SQLException e) { Log.error("collectionsOf", e); }
        return out;
    }

    // ------------------------------------------------------------------ roots

    /** An indexed folder. */
    public static final class RootRow {
        public final long id; public final String path; public final boolean watch;
        public RootRow(long id, String path, boolean watch) {
            this.id = id; this.path = path; this.watch = watch;
        }
        public long getId() { return id; }
        public String getPath() { return path; }
        public boolean isWatch() { return watch; }
    }

    public List<RootRow> roots() {
        List<RootRow> out = new ArrayList<>();
        try (Statement st = db.conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT id, path, watch FROM roots ORDER BY path")) {
            while (rs.next()) out.add(new RootRow(rs.getLong(1), rs.getString(2), rs.getInt(3) != 0));
        } catch (SQLException e) { Log.error("roots", e); }
        return out;
    }

    public void addRoot(String path) {
        db.tx(() -> {
            try (PreparedStatement ps = db.conn().prepareStatement("INSERT OR IGNORE INTO roots(path, added_at, watch) VALUES (?,?,1)")) {
                ps.setString(1, path);
                ps.setLong(2, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void removeRoot(String path) {
        db.tx(() -> {
            try (PreparedStatement ps = db.conn().prepareStatement("DELETE FROM roots WHERE path = ?")) {
                ps.setString(1, path);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void setRootWatch(String path, boolean watch) {
        db.tx(() -> {
            try (PreparedStatement ps = db.conn().prepareStatement("UPDATE roots SET watch = ? WHERE path = ?")) {
                ps.setInt(1, watch ? 1 : 0);
                ps.setString(2, path);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ thumbs

    /** Cached sidecar filenames for an asset, valid only when mtime/size still match. */
    public static final class ThumbRow {
        public final String thumb, peaks; public final long mtime, size;
        public ThumbRow(String thumb, String peaks, long mtime, long size) {
            this.thumb = thumb; this.peaks = peaks; this.mtime = mtime; this.size = size;
        }
        public String getThumb() { return thumb; }
        public String getPeaks() { return peaks; }
    }

    public ThumbRow thumbFor(String path, long mtime, long size) {
        try (PreparedStatement ps = db.conn()
                .prepareStatement("SELECT thumb, peaks, mtime, size FROM thumbs WHERE path = ?")) {
            ps.setString(1, path);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getLong(3) == mtime && rs.getLong(4) == size)
                    return new ThumbRow(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4));
            }
        } catch (SQLException e) { Log.error("thumbFor", e); }
        return null;
    }

    public void putThumb(String path, long mtime, long size, String thumbFile, String peaksFile) {
        db.tx(() -> {
            try (PreparedStatement ps = db.conn().prepareStatement(
                    "INSERT INTO thumbs(path, mtime, size, thumb, peaks) VALUES (?,?,?,?,?)"
                  + " ON CONFLICT(path) DO UPDATE SET mtime=excluded.mtime, size=excluded.size,"
                  + " thumb=excluded.thumb, peaks=excluded.peaks")) {
                ps.setString(1, path);
                ps.setLong(2, mtime);
                ps.setLong(3, size);
                ps.setString(4, thumbFile);
                ps.setString(5, peaksFile);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void clearThumbs() { try { db.exec("DELETE FROM thumbs"); } catch (SQLException e) { Log.error("clearThumbs", e); } }

    // -------------------------------------------------------------- duplicates

    public Map<String, List<Asset>> duplicates() {
        Map<String, List<Asset>> out = new LinkedHashMap<>();
        try (Statement st = db.conn().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT content_hash FROM assets WHERE content_hash IS NOT NULL AND content_hash <> ''"
                   + " GROUP BY content_hash HAVING COUNT(*) > 1 ORDER BY COUNT(*) DESC")) {
            List<String> hashes = new ArrayList<>();
            while (rs.next()) hashes.add(rs.getString(1));
            for (String h : hashes) {
                Query q = new Query();
                // direct lookup by hash
                try (PreparedStatement ps = db.conn().prepareStatement("SELECT * FROM assets WHERE content_hash = ?")) {
                    ps.setString(1, h);
                    try (ResultSet r2 = ps.executeQuery()) {
                        List<Asset> group = new ArrayList<>();
                        while (r2.next()) group.add(read(r2));
                        if (group.size() > 1) { hydrateTags(group); out.put(h, group); }
                    }
                }
            }
        } catch (SQLException e) { Log.error("duplicates", e); }
        return out;
    }

    // ------------------------------------------------------------------ stats

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        try (Statement st = db.conn().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*), COALESCE(SUM(size),0), COALESCE(SUM(width*height),0),"
                   + " COALESCE(SUM(duration),0), COALESCE(SUM(mesh_triangles),0) FROM assets")) {
            if (rs.next()) {
                m.put("count", rs.getLong(1));
                m.put("bytes", rs.getLong(2));
                m.put("pixels", rs.getLong(3));
                m.put("seconds", rs.getDouble(4));
                m.put("tris", rs.getLong(5));
            }
        } catch (SQLException e) { Log.error("stats", e); }
        try (Statement st = db.conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT category, COUNT(*) FROM assets GROUP BY category")) {
            Map<String, Long> byCat = new LinkedHashMap<>();
            while (rs.next()) byCat.put(rs.getString(1), rs.getLong(2));
            m.put("byCategory", byCat);
        } catch (SQLException e) { Log.error("stats cat", e); }
        return m;
    }

    // ------------------------------------------------------------------ misc

    public String setting(String key, String def) {
        try (PreparedStatement ps = db.conn().prepareStatement("SELECT value FROM settings WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getString(1); }
        } catch (SQLException e) { Log.error("setting", e); }
        return def;
    }

    public void putSetting(String key, String value) {
        db.tx(() -> {
            try (PreparedStatement ps = db.conn()
                    .prepareStatement("INSERT INTO settings(key,value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Assets whose file vanished from disk. */
    public List<Asset> missingFiles() {
        List<Asset> out = new ArrayList<>();
        try (Statement st = db.conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM assets")) {
            while (rs.next()) {
                Asset a = read(rs);
                if (!a.exists()) out.add(a);
            }
        } catch (SQLException e) { Log.error("missingFiles", e); }
        return out;
    }

    public void setCategory(Collection<Long> ids, Formats.Category c) {
        db.tx(() -> {
            try (PreparedStatement ps = db.conn().prepareStatement("UPDATE assets SET category = ? WHERE id = ?")) {
                for (Long id : ids) { ps.setString(1, c.name()); ps.setLong(2, id); ps.addBatch(); }
                ps.executeBatch();
            }
            return null;
        });
    }

    // --------------------------------------------------------------- internals

    private static Asset read(ResultSet rs) throws SQLException {
        Asset a = new Asset();
        a.id        = rs.getLong("id");
        a.path      = rs.getString("path");
        a.name      = rs.getString("name");
        a.ext       = rs.getString("ext");
        a.category  = Formats.Category.valueOf(rs.getString("category"));
        a.size      = rs.getLong("size");
        a.mtime     = rs.getLong("mtime");
        a.contentHash = rs.getString("content_hash");
        a.width     = rs.getInt("width");
        a.height    = rs.getInt("height");
        a.duration  = rs.getDouble("duration");
        a.sampleRate = rs.getInt("sample_rate");
        a.channels  = rs.getInt("channels");
        a.bitDepth  = rs.getInt("bit_depth");
        a.meshVertices = rs.getInt("mesh_vertices");
        a.meshTriangles = rs.getInt("mesh_triangles");
        a.note      = rs.getString("note");
        a.addedAt   = rs.getLong("added_at");
        return a;
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private static void bind(PreparedStatement ps, List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            Object o = args.get(i);
            if (o instanceof Long) ps.setLong(i + 1, (Long) o);
            else if (o instanceof Integer) ps.setInt(i + 1, (Integer) o);
            else ps.setString(i + 1, String.valueOf(o));
        }
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
