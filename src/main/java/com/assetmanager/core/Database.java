package com.assetmanager.core;

import com.assetmanager.util.Log;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/** Owns the SQLite connection and schema. */
public final class Database implements AutoCloseable {

    public static final int SCHEMA_VERSION = 1;

    private final Connection conn;
    private final Path file;

    private Database(Connection conn, Path file) {
        this.conn = conn;
        this.file = file;
    }

    public static Database open(Path dbFile) throws SQLException {
        File f = dbFile.toFile();
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        // FULLMUTEX: the scanner thread and the EDT both touch this connection
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
        }
        Database db = new Database(c, dbFile);
        db.migrate();
        Log.info("SQLite open: " + f.getAbsolutePath() + " (" + c.getMetaData().getDatabaseProductVersion() + ")");
        return db;
    }

    public static Database openMemory() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
        }
        Database db = new Database(c, Path.of(":memory:"));
        db.migrate();
        return db;
    }

    public Connection conn() { return conn; }
    public Path file() { return file; }

    private void migrate() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS assets ("
              + "  id             INTEGER PRIMARY KEY AUTOINCREMENT,"
              + "  path           TEXT    NOT NULL UNIQUE,"
              + "  name           TEXT    NOT NULL,"
              + "  ext            TEXT    NOT NULL DEFAULT '',"
              + "  category       TEXT    NOT NULL DEFAULT 'OTHER',"
              + "  size           INTEGER NOT NULL DEFAULT 0,"
              + "  mtime          INTEGER NOT NULL DEFAULT 0,"
              + "  content_hash   TEXT,"
              + "  width          INTEGER NOT NULL DEFAULT 0,"
              + "  height         INTEGER NOT NULL DEFAULT 0,"
              + "  duration       REAL    NOT NULL DEFAULT 0,"
              + "  sample_rate    INTEGER NOT NULL DEFAULT 0,"
              + "  channels       INTEGER NOT NULL DEFAULT 0,"
              + "  bit_depth      INTEGER NOT NULL DEFAULT 0,"
              + "  mesh_vertices  INTEGER NOT NULL DEFAULT 0,"
              + "  mesh_triangles INTEGER NOT NULL DEFAULT 0,"
              + "  note           TEXT,"
              + "  added_at       INTEGER NOT NULL DEFAULT 0)");

            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_assets_category ON assets(category)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_assets_hash     ON assets(content_hash)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_assets_size     ON assets(size)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_assets_mtime    ON assets(mtime)");

            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS tags ("
              + "  id    INTEGER PRIMARY KEY AUTOINCREMENT,"
              + "  name  TEXT NOT NULL UNIQUE COLLATE NOCASE,"
              + "  color TEXT)");

            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS asset_tags ("
              + "  asset_id INTEGER NOT NULL REFERENCES assets(id) ON DELETE CASCADE,"
              + "  tag_id   INTEGER NOT NULL REFERENCES tags(id)   ON DELETE CASCADE,"
              + "  PRIMARY KEY (asset_id, tag_id))");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_at_tag ON asset_tags(tag_id)");

            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS collections ("
              + "  id    INTEGER PRIMARY KEY AUTOINCREMENT,"
              + "  name  TEXT NOT NULL UNIQUE COLLATE NOCASE,"
              + "  color TEXT)");

            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS collection_assets ("
              + "  collection_id INTEGER NOT NULL REFERENCES collections(id) ON DELETE CASCADE,"
              + "  asset_id      INTEGER NOT NULL REFERENCES assets(id)      ON DELETE CASCADE,"
              + "  PRIMARY KEY (collection_id, asset_id))");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ca_asset ON collection_assets(asset_id)");

            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS roots ("
              + "  id       INTEGER PRIMARY KEY AUTOINCREMENT,"
              + "  path     TEXT    NOT NULL UNIQUE,"
              + "  added_at INTEGER NOT NULL DEFAULT 0,"
              + "  watch    INTEGER NOT NULL DEFAULT 1)");

            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS settings ("
              + "  key   TEXT PRIMARY KEY,"
              + "  value TEXT)");

            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS thumbs ("
              + "  path  TEXT PRIMARY KEY,"
              + "  mtime INTEGER NOT NULL,"
              + "  size  INTEGER NOT NULL,"
              + "  thumb TEXT,"
              + "  peaks TEXT)");

            s.executeUpdate("INSERT OR IGNORE INTO settings(key, value) VALUES ('schema_version', '"
                    + SCHEMA_VERSION + "')");
        }
    }

    /** Serialises writes; SQLite only allows one writer at a time. */
    public synchronized <T> T tx(java.util.concurrent.Callable<T> work) {
        try {
            conn.setAutoCommit(false);
            try {
                T r = work.call();
                conn.commit();
                return r;
            } catch (Exception e) {
                conn.rollback();
                throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void exec(String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) { ps.executeUpdate(); }
    }

    @Override public void close() {
        try { conn.close(); } catch (SQLException e) { Log.warn("db close: " + e.getMessage()); }
    }
}
