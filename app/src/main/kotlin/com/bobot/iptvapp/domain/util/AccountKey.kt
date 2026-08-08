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
 * 4. Preserve the path exactly as-is (case-sensitive; `"http://x/Path"` ≠ `"http://x/path"`), and
 *    **percent-encoding intact** — `"http://x/a%2Fb"` ≠ `"http://x/a/b"`, because an encoded slash
 *    and a segment separator address two different server paths. This is why the reconstruction
 *    below reads `rawPath`/`rawQuery`/`rawFragment`/`rawUserInfo` rather than the decoding
 *    accessors: decoding would *fuse* two distinct accounts onto one key, the one outcome this
 *    key exists to prevent (contrast with a scission, which merely costs a refetch).
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
/**
 * The cache partition key of one Xtream account — see [accountKeyOf] for how it is derived.
 *
 * This is a [JvmInline] value class rather than a bare `String` for one reason: `accountKey` and
 * the various content ids threaded alongside it are all opaque strings, so a bare `String` lets
 * `toEntity(seriesId, accountKey)` be called with its arguments inverted and still compile. That
 * mistake writes rows into a partition no query ever reads and no targeted delete ever purges —
 * a cache that silently stops filling, with no error anywhere. Wrapping the key makes every such
 * inversion a compile error, at zero runtime cost (the wrapper is erased to its [value]).
 *
 * ## Where the wrapper stops: the Room boundary
 * The seven cache entities declare `accountKey` as a plain `String` column, and the
 * [com.bobot.iptvapp.data.local.dao.CatalogCacheDao] / [com.bobot.iptvapp.data.local.dao.EpgDao]
 * query methods take a plain `String` too. Callers unwrap with [value] at the DAO call site.
 *
 * This is a hard constraint, not a preference. **Room 2.6.1 cannot bind a value class as a
 * `@Query` parameter.** Kotlin mangles the JVM name of any function taking one
 * (`clearEpisodesBySeriesId` becomes `clearEpisodesBySeriesId-GRyQvLA`), and Room's processor
 * neither handles the mangled name nor reports an error: it *silently omits* every affected
 * method from the generated `_Impl`, which then fails to compile as an incomplete implementation
 * of the DAO interface. A database-level `@TypeConverter` does not help — the omission happens
 * before conversion is ever considered. Should a future Room version add support, moving the
 * boundary down to the DAO is a mechanical change.
 *
 * Keeping the entity columns as raw `String` has a second benefit worth preserving regardless:
 * it guarantees this wrapper cannot perturb the exported schema or the identity hash that
 * [com.bobot.iptvapp.data.local.DatabaseMigrations.MIGRATION_2_3] is verified against.
 *
 * The protection that matters is still in force: the mappers
 * ([com.bobot.iptvapp.data.local.mapper.toEntity]) take an [AccountKey], so the argument-inversion
 * failure described above — the one that silently corrupts *writes* — is a compile error. A DAO
 * call site can still transpose two strings, but that failure mode is a read miss, which merely
 * costs a refetch.
 */
@JvmInline
value class AccountKey(val value: String)

fun accountKeyOf(credentials: XtreamCredentials): AccountKey {
    val normalised = normalizeBaseUrl(credentials.baseUrl)
    val trimmedUsername = credentials.username.trim()
    val input = "$normalised|$trimmedUsername"

    val digest = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))

    // Hand-rolled hex rather than `joinToString { String.format("%02x", it) }`: the latter
    // instantiates a Formatter and parses the format string once per byte — 32 times per key —
    // on a path taken once per catalog operation (see CatalogRepositoryImpl.currentAccountKey).
    val hex = CharArray(hashBytes.size * 2)
    for (i in hashBytes.indices) {
        val byte = hashBytes[i].toInt() and 0xFF
        hex[i * 2] = HEX_DIGITS[byte ushr 4]
        hex[i * 2 + 1] = HEX_DIGITS[byte and 0x0F]
    }
    return AccountKey(String(hex))
}

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

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
        // `raw*` accessors throughout: the non-raw ones return the *percent-decoded* form,
        // which would collapse `http://x/a%2Fb` and `http://x/a/b` onto the same key even
        // though they address two distinct server paths. Decoding here would therefore
        // *fuse* two partitions — the one failure mode this key exists to prevent.
        val userInfo = uri.rawUserInfo?.let { "$it@" } ?: ""

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

        // Preserve path as-is (case-sensitive, percent-encoding intact), but remove trailing slash
        val path = (uri.rawPath ?: "").trimEnd('/')
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        val fragment = uri.rawFragment?.let { "#$it" } ?: ""

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
