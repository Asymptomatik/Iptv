package com.bobot.iptvapp.data.local

import androidx.room.TypeConverter
import com.bobot.iptvapp.domain.model.ContentType

/**
 * Room [TypeConverter]s for types that Room cannot persist natively.
 *
 * Registered at the database level via `@TypeConverters(Converters::class)` on
 * [IptvDatabase], making all converters available to every entity and DAO in the
 * database without repeating the annotation on individual elements.
 *
 * ## Conversions provided
 *
 * | Kotlin type    | Column type | Strategy                   |
 * |----------------|-------------|----------------------------|
 * | [ContentType]  | TEXT        | `ContentType.name` (enum name) |
 *
 * ## ContentType storage
 * [ContentType] values are stored as their enum [name] strings (`"LIVE"`, `"MOVIE"`,
 * `"SERIES"`). The name is stable as long as enum entries are not renamed. If an enum
 * entry is renamed, a Room migration must UPDATE existing rows to use the new name.
 *
 * ## Which entities use this converter
 * [com.bobot.iptvapp.data.local.entity.CategoryEntity] stores its `contentType` field as
 * [ContentType] and relies on this converter for transparent serialisation.
 *
 * ## Which entities store contentType as String directly
 * [com.bobot.iptvapp.data.local.entity.FavoriteEntity] and
 * [com.bobot.iptvapp.data.local.entity.PlaybackProgressEntity] store their `contentType`
 * column as a plain `String` (the enum name). This is intentional: those columns are
 * part of composite primary keys, and using raw strings avoids any converter indirection
 * in PK column matching. DAO query parameters for those tables accept `String`
 * (callers pass `ContentType.name`).
 */
class Converters {

    /**
     * Converts a [ContentType] enum value to its stored [String] representation.
     * Called by Room when writing a [ContentType] field to the database.
     */
    @TypeConverter
    fun fromContentType(contentType: ContentType): String = contentType.name

    /**
     * Reconstructs a [ContentType] from its stored [String] representation.
     * Called by Room when reading a [ContentType] field from the database.
     *
     * @throws [IllegalArgumentException] if [value] does not match any [ContentType] name.
     *   This would indicate data corruption or a missing migration after an enum rename.
     */
    @TypeConverter
    fun toContentType(value: String): ContentType = ContentType.valueOf(value)
}
