package com.yeedar.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class UpdateCheckerTest {

    @Test
    @DisplayName("a normal release payload yields tag and url")
    void parsesRelease() {
        String body = """
                {"tag_name":"v1.7.0",
                 "html_url":"https://github.com/agilekevin/yeedar/releases/tag/v1.7.0",
                 "name":"1.7.0","draft":false,"prerelease":false}
                """;
        UpdateChecker.Release r = UpdateChecker.parseRelease(body);
        assertNotNull(r);
        assertEquals("v1.7.0", r.version());
        assertEquals("https://github.com/agilekevin/yeedar/releases/tag/v1.7.0", r.url());
    }

    @Test
    @DisplayName("a rate-limit body parses to nothing rather than throwing")
    void rateLimitBodyIsNotARelease() {
        // What a 403 actually returns. It is valid JSON and an object, so it
        // reaches the parser rather than being filtered by status alone.
        String body = "{\"message\":\"API rate limit exceeded\",\"documentation_url\":\"https://docs.github.com\"}";
        assertNull(UpdateChecker.parseRelease(body));
    }

    @Test
    @DisplayName("a payload missing either field yields nothing")
    void requiresBothFields() {
        assertNull(UpdateChecker.parseRelease("{\"tag_name\":\"v1.7.0\"}"));
        assertNull(UpdateChecker.parseRelease("{\"html_url\":\"https://example.invalid\"}"));
        assertNull(UpdateChecker.parseRelease("{\"tag_name\":\"\",\"html_url\":\"https://example.invalid\"}"));
    }

    @Test
    @DisplayName("malformed, empty and null bodies yield nothing")
    void garbageYieldsNothing() {
        // Every one of these must be silence, not an exception. The check is
        // the least important thing the mod does and must never be the
        // loudest — a truncated response should cost nothing.
        assertNull(UpdateChecker.parseRelease("not json at all"));
        assertNull(UpdateChecker.parseRelease("{\"tag_name\":"));
        assertNull(UpdateChecker.parseRelease("[]"));
        assertNull(UpdateChecker.parseRelease(""));
        assertNull(UpdateChecker.parseRelease(null));
    }
}
