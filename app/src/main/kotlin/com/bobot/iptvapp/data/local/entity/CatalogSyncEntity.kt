package com.bobot.iptvapp.data.local.entity

import androidx.room.Entity

/**
 * Records **when** a slice of the catalog cache was last filled from the Xtream Codes API.
 *
 * ## Why this exists
 * Until schema v4 the catalog tables were written on every successful fetch but read back
 * only from the `catch` branches of `CatalogRepositoryImpl` — a pure network-failure
 * fallback. A successful cold start therefore refetched the entire catalog, category by
 * category, even though a complete copy was already sitting in Room. Serving those rows on
 * the happy path needs one thing the catalog entities do not carry: a notion of *how old*
 * they are. None of [ChannelEntity], [MovieEntity] or [SeriesEntity] has a timestamp, and
 * adding one to each would put a column on every row to express a fact that belongs to the
 * *slice*, not to the item. This side table holds that fact once per slice instead.
 *
 * ## Grain
 * One row per (account, content type, [scope]) triple, where [scope] is either a category id
 * or one of the two sentinels below. That is the exact grain at which the repository fetches:
 * the category list of a type, the unfiltered stream list, or one category's stream list.
 * A partial catalog load is therefore represented honestly — the categories that made it are
 * marked, the ones that did not are not, and the next open only refetches the latter.
 *
 * ## Migration policy
 * Catalog cache — destructive fallback is acceptable. Losing these markers costs one extra
 * refetch and nothing else, since an absent marker reads as "never synced".
 */
@Entity(tableName = "catalog_sync", primaryKeys = ["accountKey", "contentType", "scope"])
data class CatalogSyncEntity(
    /** Cache partition, see [com.bobot.iptvapp.domain.util.accountKeyOf]. */
    val accountKey: String,
    /** [com.bobot.iptvapp.domain.model.ContentType] name — `LIVE`, `MOVIE` or `SERIES`. */
    val contentType: String,
    /** A category id, [SCOPE_CATEGORIES] or [SCOPE_ALL]. */
    val scope: String,
    /** `System.currentTimeMillis()` at the moment the slice was written. */
    val syncedAtMillis: Long,
) {
    companion object {
        /**
         * The category *list* of a content type, as opposed to any category's contents.
         *
         * Both sentinels start with `#`, which no Xtream category id uses (they are numeric
         * strings), so they can never collide with a real [scope] value.
         */
        const val SCOPE_CATEGORIES = "#categories"

        /** The unfiltered stream list of a content type (`categoryId = null`). */
        const val SCOPE_ALL = "#all"
    }
}
