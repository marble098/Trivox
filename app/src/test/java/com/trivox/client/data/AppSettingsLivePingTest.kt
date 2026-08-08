package com.trivox.client.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsLivePingTest {
    @Test
    fun livePingSettingsRoundTrip() {
        val source = AppSettings(
            livePingEnabled = false,
            livePingIntervalSeconds = 17
        )
        val restored = AppSettings.fromJson(source.toJson())
        assertFalse(restored.livePingEnabled)
        assertEquals(17, restored.livePingIntervalSeconds)
    }

    @Test
    fun livePingIntervalIsBounded() {
        assertEquals(
            12,
            AppSettings(
                livePingIntervalSeconds = 0
            ).normalize().livePingIntervalSeconds
        )
        assertEquals(
            300,
            AppSettings(
                livePingIntervalSeconds = 999
            ).normalize().livePingIntervalSeconds
        )
        assertEquals(15, AppSettings().livePingIntervalSeconds)
        assertTrue(AppSettings().livePingEnabled)
    }

    @Test
    fun systemThemeIsTheFreshInstallDefault() {
        assertEquals(
            ThemeMode.SYSTEM,
            AppSettings().themeMode
        )
    }
}
