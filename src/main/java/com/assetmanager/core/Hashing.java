package com.assetmanager.core;

import com.assetmanager.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Content hashing for duplicate detection. */
public final class Hashing {

    private Hashing() {}

    /**
     * Full SHA-256 of the file. Deliberately not a partial hash: two different
     * files that share a head and a tail would collide, and a false duplicate
     * is far more disruptive here than the time a large model costs to read.
     */
    public static String sha256(Path file) throws IOException {
        MessageDigest md = digest();
        byte[] buf = new byte[65536];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    public static String sha256(byte[] data) {
        MessageDigest md = digest();
        md.update(data);
        return hex(md.digest());
    }

    /** Never throws; returns null so the caller can mark the asset as un-hashed. */
    public static String trySha256(Path file) {
        try { return sha256(file); }
        catch (IOException e) { Log.debug("hash failed for " + file.getFileName() + ": " + e); return null; }
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }
}
