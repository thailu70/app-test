package com.example.data.api

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GpsSharingResponse(
    val success: Boolean = false,
    val intervalSeconds: Int? = null
)
