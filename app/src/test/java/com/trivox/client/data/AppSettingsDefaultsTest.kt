package com.trivox.client.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsDefaultsTest {
    @Test
    fun systemThemeIsTheFreshInstallDefault() {
        assertEquals(
            ThemeMode.SYSTEM,
            AppSettings().themeMode
        )
    }
}
