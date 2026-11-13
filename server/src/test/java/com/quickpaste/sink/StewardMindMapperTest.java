package com.quickpaste.sink;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StewardMindMapperTest {

    @Test
    void infersLinkQuoteNote() {
        assertEquals("link", StewardMindMapper.map(null, "https://example.com/x", null, "id1").kind());
        assertEquals("link", StewardMindMapper.map(null, "\"https://example.com/y\"", null, "id1b").kind());
        assertEquals("quote", StewardMindMapper.map(null, "\"hello\"", null, "id2").kind());
        assertEquals("note", StewardMindMapper.map(null, "plain thought", null, "id3").kind());
    }

    @Test
    void explicitKindWins() {
        StewardMindMapper.Payload p = StewardMindMapper.map("note", "https://example.com/x", null, "id");
        assertEquals("note", p.kind());
        assertEquals("https://example.com/x", p.text());
        assertNull(p.url());
    }

    @Test
    void linkRequiresUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> StewardMindMapper.map("link", "not a url", null, "id"));
    }
}
