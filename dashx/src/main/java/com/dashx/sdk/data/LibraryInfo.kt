package com.dashx.sdk.data

import kotlinx.serialization.Serializable

@Serializable
data class LibraryInfo(
    val name: String,
    val version: String,
)
