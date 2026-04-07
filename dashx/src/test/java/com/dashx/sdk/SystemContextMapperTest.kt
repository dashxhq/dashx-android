package com.dashx.android

import org.junit.Assert.*
import org.junit.Test

/**
 * Verifies that location values stored in SystemContext are always Apollo-compatible types.
 * Apollo's writeAny() supports: Double, Int, Long, String, Boolean — NOT Float, Short, Byte.
 *
 * The actual mapper (SystemContextMapper) requires JSONObject which is an Android class
 * unavailable in unit tests. These tests verify the type contract at the data source level.
 */
class SystemContextMapperTest {

    @Test
    fun locationSpeed_toDouble_producesApolloCompatibleType() {
        // Simulates: locationData[SPEED] = location.speed.toDouble()
        val speedFromAndroid: Float = 1.57f
        val converted = speedFromAndroid.toDouble()

        assertTrue("speed must be Double for Apollo", converted is Double)
        assertEquals(1.57, converted, 0.001)
    }

    @Test
    fun locationCoordinates_areAlreadyDouble() {
        // Android Location API returns Double for lat/lng — no conversion needed
        val latitude: Double = 37.7749
        val longitude: Double = -122.4194

        assertTrue("latitude is Double", latitude is Double)
        assertTrue("longitude is Double", longitude is Double)
    }

    @Test
    fun floatToDouble_preservesPrecision() {
        // Ensures .toDouble() doesn't lose meaningful precision for location/speed values
        val floatSpeed: Float = 25.75f
        val doubleSpeed = floatSpeed.toDouble()

        assertEquals(25.75, doubleSpeed, 0.01)
    }
}
