package com.bobot.iptvapp.domain.model

/**
 * A named grouping of content items of a single [ContentType].
 *
 * Categories are the top-level organisational unit returned by the Xtream Codes
 * API (one call per content type: live, VOD, series). The home screen renders
 * each category as a horizontal scrolling row.
 *
 * Sourced from: Xtream Codes `get_live_categories`, `get_vod_categories`, and
 * `get_series_categories` endpoints. Mapped from network DTOs in Task 6.
 * Persisted as Room entities in Task 10.
 *
 * @property id   Xtream Codes `category_id` (integer, carried as String to keep
 *                the domain type-system independent of the API's numeric contract).
 * @property name Human-readable category label displayed in the home row header.
 * @property type Content type this category belongs to — determines which API
 *                endpoint was used to fetch it and which detail screen to navigate to.
 */
data class Category(
    val id: String,
    val name: String,
    val type: ContentType,
)
