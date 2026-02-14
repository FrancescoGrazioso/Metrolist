package com.metrolist.spotify.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SpotifyErrorResponse(
    val error: SpotifyErrorBody,
)

@Serializable
data class SpotifyErrorBody(
    val status: Int,
    val message: String,
    @SerialName("reason") val reason: String? = null,
)
