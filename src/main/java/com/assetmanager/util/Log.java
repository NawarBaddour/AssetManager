package com.assetmanager.util;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Tiny ring-buffered logger; the UI can dump the tail into a log window. */
public final class Log {

    public static final int MAX = 400;

    /** One log line. Plain class rather than a record to stay on Java 11. */
    public static final class Entry {
        public final long time;
        public final String level;
        public final String msg;
        public final Throwable error;

        public Entry(long time, String level, String msg, Throwable error) {
            this.time = time; this.level = level; this.msg = msg; this.error = error;
        }

        @Override public String toString() {
            return time + " " + level + " " + msg + (error == null ? "" : " :: " + error);
        }
    }

    private static final List<Entry> TAIL = Collections.synchronizedList(new ArrayList<>());
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static volatile boolean debug = Boolean.getBoolean("assetmanager.debug");

    private Log() {}

    public static void setDebug(boolean on) { debug = on; }
    public static boolean isDebug() { return debug; }

    public static void info(String msg)  { add("INFO",  msg, null); }
    public static void warn(String msg)  { add("WARN",  msg, null); }
    public static void warn(String msg, Throwable t) { add("WARN", msg, t); }
    public static void error(String msg, Throwable t) { add("ERROR", msg, t); }
    public static void error(String msg) { add("ERROR", msg, null); }
    public static void debug(String msg) { if (debug) add("DEBUG", msg, null); }
    public static void debug(String msg, Throwable t) { if (debug) add("DEBUG", msg, t); }

    private static void add(String level, String msg, Throwable t) {
        Entry e = new Entry(System.currentTimeMillis(), level, msg, t);
        synchronized (TAIL) {
            TAIL.add(e);
            while (TAIL.size() > MAX) TAIL.remove(0);
        }
        System.err.println(LocalTime.now().format(TS) + " " + level + " " + msg);
        if (t != null) t.printStackTrace();
    }

    public static List<Entry> tail() {
        synchronized (TAIL) { return new ArrayList<>(TAIL); }
    }
}
