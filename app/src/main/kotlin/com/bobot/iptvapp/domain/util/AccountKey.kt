package com.bobot.iptvapp.domain.util

import com.bobot.iptvapp.domain.model.XtreamCredentials
import java.net.URI
import java.security.MessageDigest

/**
 * Derives a stable, account-scoped cache key from Xtream Codes credentials.
 *
 * The key is computed from the normalised [XtreamCredentials.baseUrl] and [XtreamCredentials.username]
 * (not the password), hashed with SHA-256 to provide hygiene benefits (avoid storing credentials
 * in plaintext in a secondary data structure). However, this is **not** a security measure — see the
 * "Security Note" below.
 *
 * ## Usage and Purpose
 * - **Cache isolation**: Room databases and preference caches can be scoped per account,
 *   such that switching credentials clears old cached data without cross-account leakage.
 * - **Hygiene only**: The hash is a convenience to avoid duplicating baseUrl/username
 *   in a second data structure (e.g., a database table). Password is never included;
 *   ADR-003 accepts password storage in plaintext in DataStore and is not reopened.
 *
 * ## Security Note
 * The account key is **not** a cryptographic secret and is **not** part of the
 * authentication flow. It is safe to log and expose in database or preference schemas.
 * The hash offers no protection against account enumeration or credential recovery.
 *
 * ## Normalisation of baseUrl
 * The URL is normalised as follows:
 * 1. Trim whitespace on both ends.
 * 2. Parse with [java.net.URI] if possible; if parsing fails, fall back to trimming
 *    trailing slashes from the raw string.
 * 3. Lowercase the scheme and host (case-insensitive per RFC 3986).
 * 4. Preserve the path exactly as-is (case-sensitive; `"http://x/Path"` ≠ `"http://x/path"`).
 * 5. Remove trailing slashes from the path only.
 * 6. Drop default ports: `:80` for `http`, `:443` for `https`. Non-default ports are preserved.
 * 7. **Preserve query parameters** — if present, included as-is (e.g. `?param=value`).
 * 8. **Preserve fragment** — if present, included as-is (e.g. `#section`).
 * 9. **Preserve userInfo** — if the URL embeds credentials (e.g. `http://user:pass@host`),
 *    the `userInfo` substring is reinjected as-is (no lowercasing, no trimming) into the
 *    reconstructed URL. A `baseUrl` with embedded credentials therefore yields a different
 *    account key than the same server without them. In practice, [XtreamCredentials.baseUrl]
 *    is not expected to carry embedded credentials (username/password are supplied as
 *    separate fields), so this mostly matters if a user pastes a URL with embedded
 *    credentials into the base URL field.
 *
 * Examples of normalisation:
 * - `" http://EXAMPLE.com:8080 "` → `"http://example.com:8080"`
 * - `"http://example.com:80"` → `"http://example.com"` (default port dropped)
 * - `"https://example.com:443/"` → `"https://example.com"` (default port + trailing slash dropped)
 * - `"http://example.com/Path/"` → `"http://example.com/Path"` (path case preserved, trailing slash dropped)
 * - `"http://example.com/path"` → `"http://example.com/path"` (path case matters)
 * - `"http://example.com/api?v=2"` → `"http://example.com/api?v=2"` (query preserved)
 * - `"http://example.com#top"` → `"http://example.com#top"` (fragment preserved)
 * - Unparsable URL → apply `trim().trimEnd('/')` as fallback (scheme/host lowercasing not applied).
 *
 * ## Username Normalisation
 * The username is **not** case-normalised; only whitespace is trimmed. This asymmetry
 * reflects a risk trade-off:
 * - Lowercasing would simplify merging equivalent accounts but risks **silently fusing**
 *   two distinct accounts (e.g., `"Alice"` and `"alice"`) into one cache key, causing
 *   data corruption if they happen to use the same baseUrl.
 * - Not normalising risks splitting one logical account across multiple cache keys (e.g.,
 *   if the user types `"Alice"` once and `"alice"` another time), which causes a benign
 *   re-fetch but no data loss — the cache self-heals over time as credentials stabilise.
 *
 * ## Hash Format
 * The result is SHA-256 in lowercase hexadecimal, always 64 characters long.
 * Input is the concatenation `"<normalized-base-url>|<trimmed-username>"`.
 *
 * ## Edge cases — Hostnames with underscores
 * An underscore is a valid `reg-name` character per RFC 3986, but it violates the stricter
 * *server-based* authority grammar that [java.net.URI] uses to populate [java.net.URI.getHost].
 * As a result, `URI(...)` does **not** throw for a hostname containing an underscore (e.g.
 * `http://my_server:8080`); it silently falls back to parsing the authority as
 * *registry-based* (RFC 3986), leaving `uri.host` `null` and `uri.port` `-1`. With an empty
 * `host`, the `if (scheme.isNotEmpty() && host.isNotEmpty())` check below is false, so the
 * normaliser takes the same `trimmed.trimEnd('/')` fallback branch **from within the `try`
 * block** as any other URL whose scheme/host cannot be reconstructed — the `catch` block is
 * never reached for this case. In this fallback mode, **scheme/host lowercasing is not
 * applied**, so the case is preserved as typed. (Verified empirically in
 * `AccountKeyTest` — see the `java.net.URI` platform-behaviour lock tests.)
 *
 * **Risk and mitigation**: If the same account is accessed via `HTTP://my_server` (uppercase)
 * and later `http://my_server` (lowercase), this creates a partition scission — the cache
 * is split into two keys. The app refetches the catalog for the second URL (benign, automatic),
 * and once data is fetched, the scission is resolved. This is a **recoverable race condition**,
 * never a **fusion** of two distinct accounts, and remains within the assumed risk model.
 */
fun accountKeyOf(credentials: XtreamCredentials): String {
    val normalised = normalizeBaseUrl(credentials.baseUrl)
    val trimmedUsername = credentials.username.trim()
    val input = "$normalised|$trimmedUsername"

    val digest = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))

    return hashBytes.joinToString("") { byte ->
        String.format("%02x", byte)
    }
}

/**
 * Normalises a baseUrl for use in account key derivation.
 *
 * See [accountKeyOf] for the full normalisation spec.
 */
private fun normalizeBaseUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim()
    if (trimmed.isBlank()) return ""

    val normalized = try {
        val uri = URI(trimmed)

        // Reconstruct the URL with lowercase scheme and host
        val scheme = uri.scheme?.lowercase() ?: ""
        val host = uri.host?.lowercase() ?: ""
        val userInfo = uri.userInfo?.let { "$it@" } ?: ""

        // Determine default port based on scheme
        val defaultPort = when (scheme) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }

        // Include port only if it's not the default for the scheme
        val port = if (uri.port == -1 || uri.port == defaultPort) {
            ""
        } else {
            ":${uri.port}"
        }

        // Preserve path as-is (case-sensitive), but remove trailing slash
        val path = (uri.path ?: "").trimEnd('/')
        val query = uri.query?.let { "?$it" } ?: ""
        val fragment = uri.fragment?.let { "#$it" } ?: ""

        // Reconstruct: scheme + "://" + userinfo + host + port + path (no trailing slash) + query + fragment
        val reconstructed = if (scheme.isNotEmpty() && host.isNotEmpty()) {
            "$scheme://$userInfo$host$port$path$query$fragment"
        } else {
            trimmed.trimEnd('/')
        }

        reconstructed
    } catch (e: Exception) {
        // Reached only for baseUrl strings that URI truly cannot parse (e.g. "not a valid
        // url//"), not for underscore hostnames — those are handled above, inside the `try`
        // block, because URI silently resolves them to a registry-based authority (host
        // null) instead of throwing. Here, fall back to trimming trailing slashes. In this
        // case, scheme/host lowercasing is not applied — the URL is used as-is (trimmed).
        // This may cause partition scission if the same unparsable string is accessed with
        // different casing across calls. This remains safe: scission causes a re-fetch
        // (benign, auto-repairing), never fusion of distinct accounts, and is rare in practice.
        trimmed.trimEnd('/')
    }

    return normalized
}
