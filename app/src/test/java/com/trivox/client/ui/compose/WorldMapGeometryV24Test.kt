package com.trivox.client.ui.compose

import org.junit.Assert.assertTrue
import org.junit.Test

class WorldMapGeometryV24Test {
    @Test
    fun naturalEarthGeometryStaysDetailedAndBounded() {
        assertTrue(
            "World map lost too many land polygons",
            WORLD_MAP_POLYGONS.size >= 220
        )

        val coordinateValues =
            WORLD_MAP_POLYGONS
                .sumOf { it.size }

        assertTrue(
            "World map geometry is unexpectedly sparse",
            coordinateValues >= 3_400
        )

        WORLD_MAP_POLYGONS.forEach {
                polygon ->
            assertTrue(
                "Each polygon needs at least three points",
                polygon.size >= 6
            )
            assertTrue(
                "Coordinates must be x/y pairs",
                polygon.size % 2 == 0
            )
            polygon.forEach {
                    value ->
                assertTrue(
                    "Map coordinate must be normalized",
                    value in 0f..1f
                )
            }
        }
    }
}
