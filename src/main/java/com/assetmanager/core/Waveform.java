package com.assetmanager.core;

import com.assetmanager.util.AudioPlugins;
import com.assetmanager.util.Log;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Decodes audio once and reduces it to a min/max peak envelope, which is all a
 * waveform view or thumbnail needs. Cached to a small binary sidecar.
 */
public final class Waveform {

    /** Default number of buckets across the whole file. */
    public static final int PEAKS = 2048;

    private final float[] min;
    private final float[] max;
    private final double duration;
    private final int sampleRate;
    private final int channels;

    public Waveform(float[] min, float[] max, double duration, int sampleRate, int channels) {
        this.min = min; this.max = max;
        this.duration = duration; this.sampleRate = sampleRate; this.channels = channels;
    }

    public int bucketCount() { return min.length; }
    public float minAt(int i) { return min[Math.max(0, Math.min(min.length - 1, i))]; }
    public float maxAt(int i) { return max[Math.max(0, Math.min(max.length - 1, i))]; }
    public double duration() { return duration; }
    public int sampleRate() { return sampleRate; }
    public int channels() { return channels; }

    /** Decodes {@code file} down to a fixed-width peak envelope. */
    public static Waveform analyse(Path file, int buckets) {
        int n = Math.max(16, buckets);
        float[] mins = new float[n], maxs = new float[n];
        double duration = 0;
        int rate = 0, ch = 0;

        try (AudioInputStream in = AudioPlugins.open(file.toFile())) {
            AudioFormat fmt = in.getFormat();
            rate = (int) fmt.getSampleRate();
            ch = fmt.getChannels();

            if (!AudioFormat.Encoding.PCM_SIGNED.equals(fmt.getEncoding())) {
                Log.debug("unsupported encoding for waveform: " + fmt.getEncoding());
                return new Waveform(mins, maxs, 0, rate, ch);
            }

            int bits = fmt.getSampleSizeInBits();
            if (bits != 8 && bits != 16 && bits != 24 && bits != 32) {
                Log.debug("unsupported bit depth for waveform: " + bits);
                return new Waveform(mins, maxs, 0, rate, ch);
            }
            int bytesPerSample = bits / 8;
            int frameSize = Math.max(1, fmt.getFrameSize());
            int frames = (int) fmt.getFrameRate();

            long totalFrames = in.getFrameLength();
            if (totalFrames <= 0) totalFrames = estimateFrames(file, frames);
            duration = frames > 0 ? totalFrames / (double) frames : 0;

            java.util.Arrays.fill(mins, 0f);
            java.util.Arrays.fill(maxs, 0f);
            if (totalFrames <= 0) return new Waveform(mins, maxs, 0, rate, ch);

            // Read a bounded number of frames: peaks only need enough resolution to look right.
            long stride = Math.max(1, totalFrames / n);
            byte[] buf = new byte[Math.min(1 << 16, (int) Math.max(4096, stride * frameSize))];
            long framePos = 0;
            boolean[] first = new boolean[n];

            while (framePos < totalFrames) {
                int want = (int) Math.min(buf.length / frameSize, Math.max(1, stride));
                int got = in.read(buf, 0, Math.min(buf.length, want * frameSize));
                if (got <= 0) break;
                int samples = got / bytesPerSample;
                for (int s = 0; s < samples; s++) {
                    int bucket = (int) ((framePos + s / ch) * n / totalFrames);
                    if (bucket >= n) bucket = n - 1;
                    float v = readSample(buf, s, bytesPerSample);
                    if (!first[bucket]) { mins[bucket] = v; maxs[bucket] = v; first[bucket] = true; }
                    else {
                        if (v < mins[bucket]) mins[bucket] = v;
                        if (v > maxs[bucket]) maxs[bucket] = v;
                    }
                }
                framePos += got / frameSize;
            }
        } catch (Exception e) {
            Log.debug("waveform failed for " + file.getFileName() + ": " + e.getMessage());
        }
        return new Waveform(mins, maxs, duration, rate, ch);
    }

    private static long estimateFrames(Path file, int frameRate) {
        if (frameRate <= 0) return 0;
        try (javax.sound.sampled.AudioInputStream in = AudioPlugins.open(file.toFile())) {
            long n = in.getFrameLength();
            if (n > 0) return n;
        } catch (Exception ignored) { }
        return 0;
    }

    private static float readSample(byte[] b, int index, int bytes) {
        int o = index * bytes;
        if (o + bytes > b.length) return 0f;
        switch (bytes) {
            case 1: return (b[o] & 0xFF) / 128f - 1f;
            case 2: {
                int v = (short) ((b[o] & 0xFF) | (b[o+1] << 8));
                return v / 32768f;
            }
            case 3: {
                int v = (b[o] & 0xFF) | ((b[o+1] & 0xFF) << 8) | ((b[o+2] & 0xFF) << 16);
                if ((v & 0x800000) != 0) v |= ~0xFFFFFF;
                return v / 8388608f;
            }
            default: {
                int v = (b[o] & 0xFF) | ((b[o+1] & 0xFF) << 8)
                      | ((b[o+2] & 0xFF) << 16) | (b[o+3] << 24);
                return v / 2147483648f;
            }
        }
    }

    // ----------------------------------------------------------------- caching

    /** Compact sidecar: header + interleaved min/max pairs. */
    public void save(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(target))) {
            out.writeInt(min.length);
            out.writeFloat((float) duration);
            out.writeInt(sampleRate);
            out.writeInt(channels);
            for (int i = 0; i < min.length; i++) {
                out.writeFloat(min[i]);
                out.writeFloat(max[i]);
            }
        }
    }

    public static Waveform load(Path cacheFile) {
        if (cacheFile == null || !Files.isReadable(cacheFile)) return null;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(cacheFile))) {
            int n = in.readInt();
            if (n <= 0 || n > 1 << 22) return null;
            double dur = in.readFloat();
            int rate = in.readInt();
            int ch = in.readInt();
            float[] mins = new float[n], maxs = new float[n];
            for (int i = 0; i < n; i++) {
                mins[i] = in.readFloat();
                maxs[i] = in.readFloat();
            }
            return new Waveform(mins, maxs, dur, rate, ch);
        } catch (EOFException e) {
            return null;
        } catch (IOException e) {
            Log.debug("waveform cache unreadable: " + e.getMessage());
            return null;
        }
    }
}
