package com.assetmanager.util;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.spi.AudioFileReader;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * jlayer and jorbis predate the {@code java.util.ServiceLoader} convention, and
 * {@code AudioSystem} offers no public way to register a provider on Java 9+. So instead of
 * fighting the registry we talk to the plugin readers directly and fall back to
 * {@code AudioSystem} for the formats the JDK handles natively (WAVE, AIFF, AU).
 */
public final class AudioPlugins {

    /**
     * Readers that ship no META-INF/services entry, so AudioSystem will never find them.
     * vorbissspi is one of these; mp3spi does register itself and needs no special handling.
     */
    private static final String[] EXTRA_READERS = {
        "javazoom.spi.vorbis.sampled.file.VorbisAudioFileReader",
    };

    private static volatile List<AudioFileReader> extraReaders;

    private AudioPlugins() {}

    private static List<AudioFileReader> extras() {
        List<AudioFileReader> r = extraReaders;
        if (r != null) return r;
        synchronized (AudioPlugins.class) {
            if (extraReaders != null) return extraReaders;
            List<AudioFileReader> found = new ArrayList<>();
            for (String cn : EXTRA_READERS) {
                try {
                    Class<?> c = Class.forName(cn);
                    Object o = c.getDeclaredConstructor().newInstance();
                    if (o instanceof AudioFileReader) {
                        found.add((AudioFileReader) o);
                        Log.info("Using extra audio reader " + cn);
                    }
                } catch (ClassNotFoundException e) {
                    Log.debug("audio reader not on classpath: " + cn);
                } catch (Throwable t) {
                    Log.warn("Could not load audio reader " + cn + ": " + t);
                }
            }
            extraReaders = found;
            return found;
        }
    }

    /**
     * Human-readable list of what this machine can actually open.
     * AudioSystem.getAudioFileTypes() does not enumerate SPI-supplied types
     * (mp3spi registers a reader but no top-level format type), so MP3 is added
     * explicitly when its classes are present.
     */
    public static List<String> supported() {
        List<String> out = new ArrayList<>();
        for (AudioFileFormat.Type t : AudioSystem.getAudioFileTypes()) out.add(t.toString());
        if (hasClass("javazoom.spi.mpeg.sampled.file.MpegAudioFileReader")) out.add("MP3");
        for (AudioFileReader r : extras()) {
            String n = r.getClass().getSimpleName().replace("AudioFileReader", "");
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    public static AudioFileFormat format(File f) throws IOException, UnsupportedAudioFileException {
        try {
            return AudioSystem.getAudioFileFormat(f);
        } catch (UnsupportedAudioFileException | IOException e) {
            for (AudioFileReader r : extras()) {
                try {
                    return r.getAudioFileFormat(f);
                } catch (IOException | UnsupportedAudioFileException ignored) {
                    // try the next plugin
                }
            }
            throw e;
        }
    }

    public static AudioInputStream open(File f) throws IOException, UnsupportedAudioFileException {
        try {
            return AudioSystem.getAudioInputStream(f);
        } catch (UnsupportedAudioFileException | IOException e) {
            for (AudioFileReader r : extras()) {
                // AudioFileReader only takes streams, so spool the file and hand it over.
                try (InputStream in = Files.newInputStream(f.toPath())) {
                    return r.getAudioInputStream(in);
                } catch (IOException | UnsupportedAudioFileException ignored) {
                    // try the next plugin
                }
            }
            throw e;
        }
    }

    /** Same as {@link #open} but from bytes, for previews of generated buffers. */
    public static AudioInputStream open(InputStream in) throws IOException, UnsupportedAudioFileException {
        return AudioSystem.getAudioInputStream(in);
    }

    public static boolean canDecode(Path p) {
        String ext = Formats.extOf(p.getFileName().toString());
        if (Formats.AUDIO_DECODABLE.contains(ext)) return true;
        if (ext.equals("ogg") || ext.equals("oga")) return hasClass("javazoom.spi.vorbis.sampled.file.VorbisAudioFileReader");
        return false;
    }

    private static boolean hasClass(String cn) {
        try { Class.forName(cn); return true; } catch (Throwable t) { return false; }
    }

    /** Decoded stream format (sample rate / channels / bits / frames), or null if unreadable. */
    public static AudioFormat probe(Path p) {
        try (AudioInputStream in = open(p.toFile())) {
            return in.getFormat();
        } catch (Exception e) {
            try {
                return format(p.toFile()).getFormat();
            } catch (Exception e2) {
                return null;
            }
        }
    }

    /**
     * Duration in seconds, or -1 if unknown.
     * Prefers the container's frame count, but compressed formats often report
     * NOT_AVAILABLE, so fall back to counting decoded PCM bytes.
     */
    public static double durationSeconds(Path p) {
        try (AudioInputStream in = open(p.toFile())) {
            AudioFormat fmt = in.getFormat();
            long frames = in.getFrameLength();
            if (frames != AudioSystem.NOT_SPECIFIED && frames > 0 && fmt.getFrameRate() > 0) {
                return frames / fmt.getFrameRate();
            }
            // decode and measure
            int frameSize = fmt.getFrameSize() == AudioSystem.NOT_SPECIFIED
                    ? 2 : Math.max(1, fmt.getFrameSize());
            long bytes = 0;
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bytes += n;
            if (fmt.getFrameRate() <= 0) return -1;
            return (bytes / (double) frameSize) / fmt.getFrameRate();
        } catch (Exception e) {
            return -1;
        }
    }

    public static long sizeOf(Path p) {
        try { return Files.size(p); } catch (IOException e) { return 0; }
    }
}
