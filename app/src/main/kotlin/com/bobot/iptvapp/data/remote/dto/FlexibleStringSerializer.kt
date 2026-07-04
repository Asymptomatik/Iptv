package com.bobot.iptvapp.data.remote.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tolerant serializer for JSON fields that the Xtream Codes API returns as either
 * an integer OR a quoted string, depending on the server implementation.
 *
 * Common offenders: stream_id, category_id, series_id.
 *
 * - JSON integer  123   → Kotlin String "123"
 * - JSON string  "123"  → Kotlin String "123"
 * - JSON null           → Kotlin String ""   (non-null contract preserved)
 *
 * Use [NullableFlexibleStringSerializer] for nullable fields.
 */
internal object FlexibleStringSerializer : KSerializer<String> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonNull -> ""
            is JsonPrimitive -> element.content
            else -> element.toString()
        }
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

/**
 * Nullable variant of [FlexibleStringSerializer].
 *
 * - JSON integer  123   → Kotlin String "123"
 * - JSON string  "123"  → Kotlin String "123"
 * - JSON string  ""     → Kotlin null  (blank strings treated as absent)
 * - JSON null           → Kotlin null
 * - Field absent        → Kotlin null  (when field has default = null)
 *
 * `@OptIn` is required because [SerialDescriptor.nullable] is marked
 * `@ExperimentalSerializationApi` in kotlinx-serialization 1.7.x.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object NullableFlexibleStringSerializer : KSerializer<String?> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("NullableFlexibleString", PrimitiveKind.STRING).nullable

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return decoder.decodeString().ifBlank { null }
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> element.content.ifBlank { null }
            else -> element.toString().ifBlank { null }
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}
