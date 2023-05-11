package com.dashx.sdk.data

import kotlinx.serialization.Serializable

@Serializable
data class Preference(
    var enabled: Boolean = false,
    var email: Boolean = false,
    var push: Boolean = false,
    var sms: Boolean = false,
    var whatsapp: Boolean = false
)
