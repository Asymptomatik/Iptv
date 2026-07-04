package com.bobot.iptvapp.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Top-level response from the Xtream Codes authentication endpoint:
 *   GET player_api.php?username=X&password=Y  (no action param)
 *
 * A successful response contains user account details and server metadata.
 * An authentication failure typically returns `{"user_info": {"auth": 0, ...}}`.
 */
@Serializable
data class AccountInfoDto(
    @SerialName("user_info") val userInfo: UserInfoDto? = null,
    @SerialName("server_info") val serverInfo: ServerInfoDto? = null,
)

@Serializable
data class UserInfoDto(
    @SerialName("username") val username: String? = null,
    @SerialName("password") val password: String? = null,
    /** 1 = authenticated, 0 = failed. */
    @SerialName("auth") val auth: Int? = null,
    /** e.g. "Active", "Banned", "Expired". */
    @SerialName("status") val status: String? = null,
    /** Epoch SECONDS of account expiry as string (or null for unlimited). */
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("exp_date") val expDate: String? = null,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("is_trial") val isTrial: String? = null,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("active_cons") val activeCons: String? = null,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("created_at") val createdAt: String? = null,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("max_connections") val maxConnections: String? = null,
    /** Permitted stream container formats, e.g. ["ts", "m3u8"]. */
    @SerialName("allowed_output_formats") val allowedOutputFormats: List<String> = emptyList(),
    @SerialName("message") val message: String? = null,
)

@Serializable
data class ServerInfoDto(
    @SerialName("url") val url: String? = null,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("port") val port: String? = null,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("https_port") val httpsPort: String? = null,
    @SerialName("server_protocol") val serverProtocol: String? = null,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("rtmp_port") val rtmpPort: String? = null,
    @SerialName("timezone") val timezone: String? = null,
    /** Current server epoch timestamp (integer or string depending on server). */
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("timestamp_now") val timestampNow: String? = null,
    /** Human-readable server time, e.g. "2024-01-15 10:30:00". */
    @SerialName("time_now") val timeNow: String? = null,
)
