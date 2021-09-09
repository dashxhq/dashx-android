package com.dashx.sdk

data class FetchContentOptions(
    val preview: Boolean,
    val language: String,
    val fields: List<String>,
    val include: List<String>,
    val exclude: List<String>
)

data class SearchContentOptions(
    val returnType: String?,
    val filter: Any,
    val order: Any,
    val limit: Int,
    val preview: Boolean,
    val language: String,
    val fields: List<String>,
    val include: List<String>,
    val exclude: List<String>
)

data class IndentifyOptions(
    val email: String?,
    val phone: String?,
    val name: String?,
    val firstName: String?,
    val lastName: String?
)
