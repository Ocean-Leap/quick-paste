package com.quickpaste.util;

import com.quickpaste.error.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostSafetyTest {

    @Test
    void acceptsPublicHttps() {
        assertEquals("https://steward.example",
                HostSafety.requirePublicHttpsBase("https://steward.example/"));
    }

    @Test
    void rejectsHttpAndPrivate() {
        assertThrows(ApiException.class, () -> HostSafety.requirePublicHttpsBase("http://steward.example"));
        assertThrows(ApiException.class, () -> HostSafety.requirePublicHttpsBase("https://127.0.0.1"));
        assertThrows(ApiException.class, () -> HostSafety.requirePublicHttpsBase("https://192.168.1.10"));
        assertThrows(ApiException.class, () -> HostSafety.requirePublicHttpsBase("https://10.0.0.5"));
        assertThrows(ApiException.class, () -> HostSafety.requirePublicHttpsBase("https://localhost"));
        assertTrue(HostSafety.isBlockedLiteralHost("169.254.169.254"));
    }
}
