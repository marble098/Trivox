package com.trivox.client.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldMapGeoV22Test {
    @Test
    fun resolvesFlagAndKnownCountriesWithoutNetworkData() {
        assertEquals(
            "AE",
            WorldMapGeo.resolveCode(
                "",
                "🇦🇪 NordVPN Emirates - Dubai"
            )
        )
        assertEquals(
            "NL",
            WorldMapGeo.resolveCode(
                "nl",
                ""
            )
        )
        assertEquals(
            "🇫🇷",
            WorldMapGeo.flagFor("FR")
        )
    }

    @Test
    fun projectedCoordinatesStayInsideMap() {
        listOf("AE", "NL", "FR", "US", "JP", "BR", "AU")
            .forEach { code ->
                val point =
                    WorldMapGeo.pointFor(code)
                assertNotNull(point)
                assertTrue(point!!.x in 0f..1f)
                assertTrue(point.y in 0f..1f)
            }
    }
}
