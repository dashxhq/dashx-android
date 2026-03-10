package com.dashx.android.utils

import com.apollographql.apollo.api.Optional
import com.dashx.android.graphql.generated.type.SystemContextAppInput
import com.dashx.android.graphql.generated.type.SystemContextCampaignInput
import com.dashx.android.graphql.generated.type.SystemContextDeviceInput
import com.dashx.android.graphql.generated.type.SystemContextInput
import com.dashx.android.graphql.generated.type.SystemContextLibraryInput
import com.dashx.android.graphql.generated.type.SystemContextLocationInput
import com.dashx.android.graphql.generated.type.SystemContextNetworkInput
import com.dashx.android.graphql.generated.type.SystemContextOsInput
import com.dashx.android.graphql.generated.type.SystemContextScreenInput
import org.json.JSONObject

/**
 * Builds Apollo-generated [SystemContextInput] from the SDK's [JSONObject]
 * (from [com.dashx.android.SystemContext.fetchSystemContext]).
 * Apollo's generated input types are not @Serializable, so we cannot use
 * kotlinx.serialization; this mapper constructs the type by hand.
 */
object SystemContextMapper {

    fun toSystemContextInput(json: JSONObject): SystemContextInput {
        return SystemContextInput(
            ipV4 = json.optString(SystemContextConstants.IPV4, ""),
            ipV6 = optStringOrAbsent(json, SystemContextConstants.IPV6),
            locale = json.optString(SystemContextConstants.LOCALE, ""),
            timeZone = json.optString(SystemContextConstants.TIME_ZONE, ""),
            userAgent = json.optString(SystemContextConstants.USER_AGENT, ""),
            app = optJsonObject(json, SystemContextConstants.APP)?.let { o -> Optional.Present(toApp(o)) } ?: Optional.Absent,
            device = optJsonObject(json, SystemContextConstants.DEVICE)?.let { o -> Optional.Present(toDevice(o)) } ?: Optional.Absent,
            os = optJsonObject(json, SystemContextConstants.OS)?.let { o -> Optional.Present(toOs(o)) } ?: Optional.Absent,
            library = optJsonObject(json, SystemContextConstants.LIBRARY)?.let { o -> Optional.Present(toLibrary(o)) } ?: Optional.Absent,
            network = optJsonObject(json, SystemContextConstants.NETWORK)?.let { o -> Optional.Present(toNetwork(o)) } ?: Optional.Absent,
            screen = optJsonObject(json, SystemContextConstants.SCREEN)?.let { o -> Optional.Present(toScreen(o)) } ?: Optional.Absent,
            campaign = optJsonObject(json, "campaign")?.let { o -> Optional.Present(toCampaign(o)) } ?: Optional.Absent,
            location = optJsonObject(json, SystemContextConstants.LOCATION)?.let { o -> Optional.Present(toLocation(o)) } ?: Optional.Absent
        )
    }

    private fun optStringOrAbsent(json: JSONObject, key: String): Optional<String?> =
        if (json.has(key)) Optional.Present(json.optString(key).takeIf { it.isNotEmpty() }) else Optional.Absent

    private fun optJsonObject(json: JSONObject, key: String): JSONObject? =
        if (json.has(key) && !json.isNull(key)) json.optJSONObject(key) else null

    private fun toApp(o: JSONObject): SystemContextAppInput = SystemContextAppInput(
        name = o.optString(SystemContextConstants.NAME, ""),
        version = o.optString(SystemContextConstants.VERSION_NUMBER, ""),
        build = o.optString(SystemContextConstants.BUILD, ""),
        namespace = o.optString(SystemContextConstants.NAMESPACE, "")
    )

    private fun toDevice(o: JSONObject): SystemContextDeviceInput = SystemContextDeviceInput(
        id = o.optString(SystemContextConstants.ID, ""),
        advertisingId = o.optString(SystemContextConstants.ADVERTISING_ID, ""),
        adTrackingEnabled = o.optBoolean(SystemContextConstants.AD_TRACKING_ENABLED, false),
        manufacturer = o.optString(SystemContextConstants.MANUFACTURER, ""),
        model = o.optString(SystemContextConstants.MODEL, ""),
        name = o.optString(SystemContextConstants.NAME, ""),
        kind = o.optString(SystemContextConstants.KIND, "")
    )

    private fun toOs(o: JSONObject): SystemContextOsInput = SystemContextOsInput(
        name = o.optString(SystemContextConstants.OS_NAME, ""),
        version = o.optString(SystemContextConstants.OS_VERSION, "")
    )

    private fun toLibrary(o: JSONObject): SystemContextLibraryInput = SystemContextLibraryInput(
        name = o.optString(SystemContextConstants.NAME, ""),
        version = o.optString(SystemContextConstants.VERSION, "")
    )

    private fun toNetwork(o: JSONObject): SystemContextNetworkInput = SystemContextNetworkInput(
        bluetooth = o.optBoolean(SystemContextConstants.BLUETOOTH, false),
        carrier = o.optString(SystemContextConstants.CARRIER, ""),
        cellular = o.optBoolean(SystemContextConstants.CELLULAR, false),
        wifi = o.optBoolean(SystemContextConstants.WIFI, false)
    )

    private fun toScreen(o: JSONObject): SystemContextScreenInput = SystemContextScreenInput(
        width = o.optInt(SystemContextConstants.WIDTH, 0),
        height = o.optInt(SystemContextConstants.HEIGHT, 0),
        density = o.optInt(SystemContextConstants.DENSITY, 0)
    )

    private fun toCampaign(o: JSONObject): SystemContextCampaignInput = SystemContextCampaignInput(
        name = o.optString("name", ""),
        source = o.optString("source", ""),
        medium = o.optString("medium", ""),
        term = o.optString("term", ""),
        content = o.optString("content", "")
    )

    private fun toLocation(o: JSONObject): SystemContextLocationInput {
        fun optAny(key: String): Optional<Any?> =
            if (o.has(key) && !o.isNull(key)) Optional.Present(o.opt(key)) else Optional.Absent
        fun optString(key: String): Optional<String?> =
            if (o.has(key)) Optional.Present(o.optString(key).takeIf { it.isNotEmpty() }) else Optional.Absent
        return SystemContextLocationInput(
            latitude = optAny(SystemContextConstants.LATITUDE),
            longitude = optAny(SystemContextConstants.LONGITUDE),
            city = optString(SystemContextConstants.CITY),
            country = optString(SystemContextConstants.COUNTRY),
            speed = optAny(SystemContextConstants.SPEED)
        )
    }
}
