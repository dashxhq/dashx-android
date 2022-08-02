package com.dashx.sdk.data

data class PrepareExternalAssetResponse(
    val upload: UploadAssetUrl,
)

data class UploadAssetUrl(
    val url: String
)
