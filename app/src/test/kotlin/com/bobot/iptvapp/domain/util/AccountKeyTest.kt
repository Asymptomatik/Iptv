package com.bobot.iptvapp.domain.util

import com.bobot.iptvapp.domain.model.XtreamCredentials
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AccountKeyTest {

    // ── Trailing slash and URL formatting ─────────────────────────────────────

    @Test
    fun `accountKeyOf returns same key with and without trailing slash`() {
        val credsWithSlash = XtreamCredentials("http://x:8080/", "user", "pass")
        val credsWithoutSlash = XtreamCredentials("http://x:8080", "user", "pass")

        val keyWithSlash = accountKeyOf(credsWithSlash)
        val keyWithoutSlash = accountKeyOf(credsWithoutSlash)

        assertEquals(keyWithSlash, keyWithoutSlash)
    }

    // ── Case normalization of scheme and host ────────────────────────────────

    @Test
    fun `accountKeyOf returns same key for uppercase and lowercase host`() {
        val credsLowercase = XtreamCredentials("http://x:8080", "user", "pass")
        val credsUppercase = XtreamCredentials("http://X:8080", "user", "pass")

        val keyLowercase = accountKeyOf(credsLowercase)
        val keyUppercase = accountKeyOf(credsUppercase)

        assertEquals(keyLowercase, keyUppercase)
    }

    @Test
    fun `accountKeyOf returns same key for uppercase and lowercase scheme`() {
        val credsLowercase = XtreamCredentials("http://x:8080", "user", "pass")
        val credsUppercase = XtreamCredentials("HTTP://x:8080", "user", "pass")

        val keyLowercase = accountKeyOf(credsLowercase)
        val keyUppercase = accountKeyOf(credsUppercase)

        assertEquals(keyLowercase, keyUppercase)
    }

    // ── Default port handling ────────────────────────────────────────────────

    @Test
    fun `accountKeyOf returns same key for HTTP with explicit port 80 and implicit port`() {
        val credsWithExplicitPort = XtreamCredentials("http://x:80", "user", "pass")
        val credsWithoutPort = XtreamCredentials("http://x", "user", "pass")

        val keyWithExplicitPort = accountKeyOf(credsWithExplicitPort)
        val keyWithoutPort = accountKeyOf(credsWithoutPort)

        assertEquals(keyWithExplicitPort, keyWithoutPort)
    }

    @Test
    fun `accountKeyOf returns same key for HTTPS with explicit port 443 and implicit port`() {
        val credsWithExplicitPort = XtreamCredentials("https://x:443", "user", "pass")
        val credsWithoutPort = XtreamCredentials("https://x", "user", "pass")

        val keyWithExplicitPort = accountKeyOf(credsWithExplicitPort)
        val keyWithoutPort = accountKeyOf(credsWithoutPort)

        assertEquals(keyWithExplicitPort, keyWithoutPort)
    }

    // ── Non-default port preservation ────────────────────────────────────────

    @Test
    fun `accountKeyOf returns different keys for different non-default ports`() {
        val creds8080 = XtreamCredentials("http://x:8080", "user", "pass")
        val creds9090 = XtreamCredentials("http://x:9090", "user", "pass")

        val key8080 = accountKeyOf(creds8080)
        val key9090 = accountKeyOf(creds9090)

        assertNotEquals(key8080, key9090)
    }

    // ── Path preservation (case-sensitive) ───────────────────────────────────

    @Test
    fun `accountKeyOf preserves path case sensitivity`() {
        val credsLowercasePath = XtreamCredentials("http://x/path", "user", "pass")
        val credsUppercasePath = XtreamCredentials("http://x/Path", "user", "pass")

        val keyLowercasePath = accountKeyOf(credsLowercasePath)
        val keyUppercasePath = accountKeyOf(credsUppercasePath)

        assertNotEquals("paths with different cases should produce different keys",
            keyLowercasePath, keyUppercasePath)
    }

    @Test
    fun `accountKeyOf removes trailing slash from path`() {
        val credsWithTrailingSlash = XtreamCredentials("http://x/path/", "user", "pass")
        val credsWithoutTrailingSlash = XtreamCredentials("http://x/path", "user", "pass")

        val keyWithTrailingSlash = accountKeyOf(credsWithTrailingSlash)
        val keyWithoutTrailingSlash = accountKeyOf(credsWithoutTrailingSlash)

        assertEquals(keyWithTrailingSlash, keyWithoutTrailingSlash)
    }

    // ── Username case sensitivity ────────────────────────────────────────────

    @Test
    fun `accountKeyOf returns different keys for usernames with different cases`() {
        val credsLowercase = XtreamCredentials("http://x", "alice", "pass")
        val credsUppercase = XtreamCredentials("http://x", "Alice", "pass")

        val keyLowercase = accountKeyOf(credsLowercase)
        val keyUppercase = accountKeyOf(credsUppercase)

        assertNotEquals("usernames with different cases should produce different keys",
            keyLowercase, keyUppercase)
    }

    // ── Password independence ────────────────────────────────────────────────

    @Test
    fun `accountKeyOf returns same key regardless of password`() {
        val credsPassword1 = XtreamCredentials("http://x", "user", "password1")
        val credsPassword2 = XtreamCredentials("http://x", "user", "password2")

        val keyPassword1 = accountKeyOf(credsPassword1)
        val keyPassword2 = accountKeyOf(credsPassword2)

        assertEquals(keyPassword1, keyPassword2)
    }

    @Test
    fun `accountKeyOf returns same key for empty and non-empty password`() {
        val credsEmptyPassword = XtreamCredentials("http://x", "user", "")
        val credsWithPassword = XtreamCredentials("http://x", "user", "password")

        val keyEmptyPassword = accountKeyOf(credsEmptyPassword)
        val keyWithPassword = accountKeyOf(credsWithPassword)

        assertEquals(keyEmptyPassword, keyWithPassword)
    }

    // ── Hash format and stability ────────────────────────────────────────────

    @Test
    fun `accountKeyOf returns lowercase hexadecimal hash`() {
        val creds = XtreamCredentials("http://x", "user", "pass")
        val key = accountKeyOf(creds)

        // Should match lowercase hex pattern and be exactly 64 characters (SHA-256)
        assertEquals("Key should be 64 characters", 64, key.length)
        assertEquals("Key should be lowercase hexadecimal",
            key, key.lowercase())
        assertEquals("Key should only contain hex digits",
            key, key.filter { it in '0'..'9' || it in 'a'..'f' }.take(key.length))
    }

    @Test
    fun `accountKeyOf produces stable hash across multiple calls`() {
        val creds = XtreamCredentials("http://example.com:8080/api", "testuser", "testpass")

        val key1 = accountKeyOf(creds)
        val key2 = accountKeyOf(creds)
        val key3 = accountKeyOf(creds)

        assertEquals(key1, key2)
        assertEquals(key2, key3)
    }

    // ── Complex scenarios ────────────────────────────────────────────────────

    @Test
    fun `accountKeyOf handles baseUrl with path and query string`() {
        val creds1 = XtreamCredentials("http://x/api?format=json", "user", "pass")
        val creds2 = XtreamCredentials("http://x/api", "user", "pass")

        val key1 = accountKeyOf(creds1)
        val key2 = accountKeyOf(creds2)

        assertNotEquals("URLs with different query strings should produce different keys",
            key1, key2)
    }

    @Test
    fun `accountKeyOf trims whitespace from baseUrl`() {
        val creds1 = XtreamCredentials("  http://x:8080  ", "user", "pass")
        val creds2 = XtreamCredentials("http://x:8080", "user", "pass")

        val key1 = accountKeyOf(creds1)
        val key2 = accountKeyOf(creds2)

        assertEquals(key1, key2)
    }

    @Test
    fun `accountKeyOf trims whitespace from username`() {
        val creds1 = XtreamCredentials("http://x", "  user  ", "pass")
        val creds2 = XtreamCredentials("http://x", "user", "pass")

        val key1 = accountKeyOf(creds1)
        val key2 = accountKeyOf(creds2)

        assertEquals(key1, key2)
    }

    @Test
    fun `accountKeyOf handles unparsable URLs gracefully by trimming trailing slashes`() {
        val credsInvalidUrl = XtreamCredentials("not a valid url//", "user", "pass")
        val key = accountKeyOf(credsInvalidUrl)

        // Should not throw, and should produce a valid 64-char hex hash
        assertEquals("Key should be 64 characters even for invalid URLs", 64, key.length)
        assertEquals("Key should be lowercase hexadecimal",
            key, key.filter { it in '0'..'9' || it in 'a'..'f' }.take(key.length))
    }

    @Test
    fun `accountKeyOf distinguishes between two different servers`() {
        val creds1 = XtreamCredentials("http://server1.com", "user", "pass")
        val creds2 = XtreamCredentials("http://server2.com", "user", "pass")

        val key1 = accountKeyOf(creds1)
        val key2 = accountKeyOf(creds2)

        assertNotEquals("Different servers should produce different keys", key1, key2)
    }

    @Test
    fun `accountKeyOf distinguishes between same server but different usernames`() {
        val creds1 = XtreamCredentials("http://server.com", "user1", "pass")
        val creds2 = XtreamCredentials("http://server.com", "user2", "pass")

        val key1 = accountKeyOf(creds1)
        val key2 = accountKeyOf(creds2)

        assertNotEquals("Different usernames on same server should produce different keys",
            key1, key2)
    }

    // ── Platform behaviour lock: java.net.URI with an underscore in the host ──
    //
    // These tests exist to establish (and lock) the *actual* behaviour of
    // `java.net.URI` for hostnames containing an underscore, which the
    // normalisation logic and its documentation depend on. An underscore is a
    // valid `reg-name` character per RFC 3986 but violates the stricter
    // server-based authority grammar, so `URI` does NOT throw for such input:
    // it silently falls back to a registry-based authority, leaving `host`
    // null and `port` -1. This is verified empirically below (via
    // `runCatching`, so the test reveals the outcome rather than presupposing
    // it) so that any future JDK behaviour change is caught by a test failure
    // instead of stale prose.

    @Test
    fun `java net URI does not throw for a hostname with an underscore`() {
        val result = runCatching { URI("http://my_server:8080") }

        assertEquals("URI construction should not throw for an underscore host",
            true, result.isSuccess)
    }

    @Test
    fun `java net URI leaves host null and port -1 for a hostname with an underscore`() {
        val uri = URI("http://my_server:8080")

        assertEquals("host should be null (registry-based authority, RFC 3986)", null, uri.host)
        assertEquals("port should be -1 (unparsed)", -1, uri.port)
    }

    @Test
    fun `accountKeyOf does not lowercase an underscore host and splits on case difference`() {
        // End-to-end consequence: since URI parsing does not throw and host
        // resolves empty, normalizeBaseUrl falls back to the raw trimmed
        // string from within the `try` block (not the `catch` block) for
        // this input, so scheme/host case is preserved as typed. Two
        // differently-cased variants of the same underscore host therefore
        // produce two distinct account keys (a benign split), never a fusion.
        val credsUppercase = XtreamCredentials("HTTP://my_server:8080", "user", "pass")
        val credsLowercase = XtreamCredentials("http://my_server:8080", "user", "pass")

        val keyUppercase = accountKeyOf(credsUppercase)
        val keyLowercase = accountKeyOf(credsLowercase)

        assertNotEquals(
            "differently-cased underscore hosts should split into distinct keys, not merge",
            keyUppercase, keyLowercase)
    }
}
