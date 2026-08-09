package com.trivox.client.data

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsV37UiTest {
    @Test
    fun `home personalization defaults stay enabled for existing users`() {
        val settings = AppSettings.fromJson(JSONObject())

        assertTrue(settings.homeShowUsage)
        assertTrue(settings.homeShowMap)
        assertTrue(settings.homeShowConnectionDetails)
        assertTrue(settings.homeShowLeakGuard)
    }

    @Test
    fun `home personalization survives json round trip`() {
        val original = AppSettings(
            homeShowUsage = false,
            homeShowMap = true,
            homeShowConnectionDetails = false,
            homeShowLeakGuard = true
        )

        val restored = AppSettings.fromJson(original.toJson())

        assertFalse(restored.homeShowUsage)
        assertTrue(restored.homeShowMap)
        assertFalse(restored.homeShowConnectionDetails)
        assertTrue(restored.homeShowLeakGuard)
    }
}
