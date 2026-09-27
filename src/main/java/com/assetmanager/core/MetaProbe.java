package com.assetmanager.core;

import com.assetmanager.core.mesh.MeshIo;
import com.assetmanager.core.mesh.ModelData;
import com.assetmanager.util.AudioPlugins;
import com.assetmanager.util.Formats;
import com.assetmanager.util.Images;
import com.assetmanager.util.Log;

import javax.sound.sampled.AudioFormat;
import java.nio.file.Path;

/** Fills in an Asset's technical metadata (dimensions, duration, poly counts). */
public final class MetaProbe {

    private MetaProbe() {}

    /** Mutates {@code a} in place. Never throws. */
    public static void probe(Asset a) {
        Path p = a.file();
        switch (a.category) {
            case IMAGE: probeImage(a, p); break;
            case AUDIO: probeAudio(a, p); break;
            case MODEL: probeModel(a, p); break;
            default: break;
        }
    }

    private static void probeImage(Asset a, Path p) {
        int[] dim = Images.readDimensions(p);
        if (dim != null) { a.width = dim[0]; a.height = dim[1]; return; }
        // header parse failed; fall back to a full decode (slower but definitive)
        try {
            var bi = Images.read(p);
            a.width = bi.getWidth();
            a.height = bi.getHeight();
        } catch (Throwable t) {
            // A mis-declared image plugin raises NoClassDefFoundError, which is an
            // Error: one unreadable file must never abort a whole library scan.
            Log.debug("image probe failed " + p.getFileName() + ": " + t);
        }
    }

    private static void probeAudio(Asset a, Path p) {
        if (!AudioPlugins.canDecode(p)) {
            Log.debug("no decoder for " + p.getFileName());
            return;
        }
        AudioFormat f = AudioPlugins.probe(p);
        if (f != null) {
            a.sampleRate = (int) f.getSampleRate();
            a.channels = f.getChannels();
            a.bitDepth = (int) f.getSampleSizeInBits();
        }
        a.duration = AudioPlugins.durationSeconds(p);
    }

    private static void probeModel(Asset a, Path p) {
        if (!MeshIo.supports(p)) return;
        try {
            ModelData m = MeshIo.load(p);
            a.meshVertices = m.vertexCount();
            a.meshTriangles = m.triangleCount();
        } catch (Exception e) {
            // Counts stay 0, which the "problems" filter treats as a decode failure.
            Log.debug("model probe failed " + p.getFileName() + ": " + e.getMessage());
        }
    }

    /** True when the asset could not be fully understood. */
    public static boolean isProblem(Asset a) {
        if (a.contentHash == null || a.contentHash.isEmpty()) return true;
        if (a.category == Formats.Category.IMAGE && a.width <= 0) return true;
        if (a.category == Formats.Category.AUDIO && a.duration <= 0) return true;
        if (a.category == Formats.Category.MODEL) {
            if (Formats.isModelLoadable(a.ext) && a.meshTriangles <= 0) return true;
        }
        return false;
    }
}
