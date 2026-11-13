package com.quickpaste.sink;

import io.micronaut.core.annotation.Nullable;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps a quick-paste text clip to a Steward Mind ingest body (SPEC FR-62).
 * Steward rejects unknown fields — only emit kind/text/url/tags/idempotency_key.
 */
public final class StewardMindMapper {

    private static final Pattern BARE_URL = Pattern.compile(
            "^\\s*(https?://\\S+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED = Pattern.compile(
            "^\\s*[\"“]([\\s\\S]+)[\"”]\\s*$");

    private StewardMindMapper() {
    }

    public record Payload(
            String kind,
            @Nullable String text,
            @Nullable String url,
            @Nullable List<String> tags,
            String idempotencyKey) {
    }

    /**
     * @param mindKind optional explicit override ({@code note}/{@code quote}/{@code link})
     * @param text     clip text (required)
     * @param tags     optional default tags from the channel sink
     * @param clipId   used as Steward idempotency_key
     */
    public static Payload map(@Nullable String mindKind, String text,
                              @Nullable List<String> tags, String clipId) {
        String kind = normalizeKind(mindKind);
        if (kind == null) {
            kind = inferKind(text);
        }
        return switch (kind) {
            case "link" -> {
                String url = extractUrl(text);
                if (url == null) {
                    throw new IllegalArgumentException(
                            "mindKind=link requires the clip text to be an http(s) URL.");
                }
                // Optional note after the URL is not supported by Steward for links via
                // a second field when text is also the URL; send URL only.
                yield new Payload("link", null, url, emptyToNull(tags), clipId);
            }
            case "quote" -> new Payload("quote", text, null, emptyToNull(tags), clipId);
            default -> new Payload("note", text, null, emptyToNull(tags), clipId);
        };
    }

    @Nullable
    static String normalizeKind(@Nullable String mindKind) {
        if (mindKind == null || mindKind.isBlank()) {
            return null;
        }
        String k = mindKind.trim().toLowerCase(Locale.ROOT);
        if (!k.equals("note") && !k.equals("quote") && !k.equals("link")) {
            throw new IllegalArgumentException("mindKind must be note, quote, or link.");
        }
        return k;
    }

    static String inferKind(String text) {
        // Bare URL → link. A quoted bare URL is still a link (quote is for prose).
        if (extractUrl(text) != null) {
            return "link";
        }
        Matcher q = QUOTED.matcher(text);
        if (q.matches()) {
            String inner = q.group(1).trim();
            if (extractUrl(inner) != null) {
                return "link";
            }
            if (!inner.isEmpty()) {
                return "quote";
            }
        }
        return "note";
    }

    @Nullable
    static String extractUrl(String text) {
        Matcher m = BARE_URL.matcher(text);
        if (!m.matches()) {
            // Also accept a URL wrapped only in quotes.
            Matcher q = QUOTED.matcher(text);
            if (q.matches()) {
                return extractUrl(q.group(1).trim());
            }
            return null;
        }
        String candidate = m.group(1);
        try {
            URI uri = URI.create(candidate);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return null;
            }
            String lower = scheme.toLowerCase(Locale.ROOT);
            if (!lower.equals("http") && !lower.equals("https")) {
                return null;
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return null;
            }
            return candidate;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Nullable
    private static List<String> emptyToNull(@Nullable List<String> tags) {
        return tags == null || tags.isEmpty() ? null : tags;
    }
}
