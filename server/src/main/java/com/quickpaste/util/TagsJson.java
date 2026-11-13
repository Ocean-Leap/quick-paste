package com.quickpaste.util;

import java.util.ArrayList;
import java.util.List;

/** Minimal JSON string-array codec for sink default tags (no payload logging). */
public final class TagsJson {

    private TagsJson() {
    }

    public static String write(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(tags.get(i))).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    public static List<String> read(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String s = raw.trim();
        if (s.length() < 2 || s.charAt(0) != '[' || s.charAt(s.length() - 1) != ']') {
            return List.of();
        }
        s = s.substring(1, s.length() - 1).trim();
        if (s.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) {
                cur.append(c);
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (c == ',' && !inString) {
                String tag = cur.toString().trim();
                if (!tag.isEmpty()) {
                    out.add(tag);
                }
                cur.setLength(0);
                continue;
            }
            if (inString || !Character.isWhitespace(c)) {
                cur.append(c);
            }
        }
        String last = cur.toString().trim();
        if (!last.isEmpty()) {
            out.add(last);
        }
        return out;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
