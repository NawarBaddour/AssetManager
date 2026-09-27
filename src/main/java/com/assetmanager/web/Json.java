package com.assetmanager.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collection;

/** Small helpers for shaping the JSON the browser UI consumes. */
public final class Json {

    private Json() {}

    public static JsonObject obj() { return new JsonObject(); }

    public static JsonArray arr() { return new JsonArray(); }

    public static void put(JsonObject o, String k, String v) {
        if (v != null) o.addProperty(k, v);
    }

    public static void put(JsonObject o, String k, long v) { o.addProperty(k, v); }

    public static void put(JsonObject o, String k, double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return;
        o.addProperty(k, v);
    }

    public static void put(JsonObject o, String k, boolean v) { o.addProperty(k, v); }

    public static JsonArray strings(Collection<String> values) {
        JsonArray a = arr();
        for (String v : values) a.add(v);
        return a;
    }

    /** RFC 4180 escaping, applied manually so a stray quote cannot break the response. */
    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20 || c == 0x7f) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Renders a bare JSON string literal, quotes included. */
    public static String str(String s) {
        return "\"" + escape(s) + "\"";
    }
}
