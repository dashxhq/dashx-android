package com.dashx.sdk.data

data class Preference(
    var enabled: Boolean,
    var email: Boolean,
    var push: Boolean,
    var sms: Boolean,
    var whatsapp: Boolean
)
