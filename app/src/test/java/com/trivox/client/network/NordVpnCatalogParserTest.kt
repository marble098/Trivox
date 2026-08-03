package com.trivox.client.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NordVpnCatalogParserTest {
    @Test
    fun readsNestedCountryCityAndKeepsEveryLocation() {
        val root =
            JSONObject()
                .put(
                    "locations",
                    JSONArray()
                        .put(
                            location(
                                id = 11,
                                cityId = 101,
                                city = "Amsterdam"
                            )
                        )
                        .put(
                            location(
                                id = 12,
                                cityId = 102,
                                city = "Rotterdam"
                            )
                        )
                )
                .put(
                    "servers",
                    JSONArray()
                        .put(
                            server(
                                hostname = "nl-high",
                                station = "8.8.8.8",
                                load = 55,
                                locationIds =
                                    intArrayOf(
                                        11,
                                        12
                                    )
                            )
                        )
                        .put(
                            server(
                                hostname = "nl-low",
                                station = "1.1.1.1",
                                load = 7,
                                locationIds =
                                    intArrayOf(11)
                            )
                        )
                )

        val result =
            NordVpnCatalogParser
                .selectBestLocationServers(
                    root,
                    CountryInfo(
                        id = 153,
                        code = "NL",
                        name = "Netherlands"
                    )
                )

        assertEquals(2, result.size)
        assertEquals(
            listOf(
                "Amsterdam",
                "Rotterdam"
            ),
            result.map {
                it.location.cityName
            }
        )
        assertEquals(
            "nl-low",
            result.first {
                it.location.cityName ==
                    "Amsterdam"
            }.server.hostname
        )
    }

    @Test
    fun rejectsPrivateStationsAndOfflineServers() {
        val root =
            JSONObject()
                .put(
                    "locations",
                    JSONArray().put(
                        location(
                            id = 11,
                            cityId = 101,
                            city = "Amsterdam"
                        )
                    )
                )
                .put(
                    "servers",
                    JSONArray()
                        .put(
                            server(
                                hostname = "private",
                                station = "10.10.34.35",
                                load = 1,
                                locationIds =
                                    intArrayOf(11)
                            )
                        )
                        .put(
                            server(
                                hostname = "offline",
                                station = "1.1.1.1",
                                load = 1,
                                locationIds =
                                    intArrayOf(11),
                                status = "offline"
                            )
                        )
                )

        val result =
            NordVpnCatalogParser
                .selectBestLocationServers(
                    root,
                    CountryInfo(
                        id = 153,
                        code = "NL",
                        name = "Netherlands"
                    )
                )

        assertTrue(result.isEmpty())
    }

    private fun location(
        id: Int,
        cityId: Int,
        city: String
    ) =
        JSONObject()
            .put("id", id)
            .put(
                "country",
                JSONObject()
                    .put("id", 153)
                    .put("code", "NL")
                    .put(
                        "name",
                        "Netherlands"
                    )
                    .put(
                        "city",
                        JSONObject()
                            .put("id", cityId)
                            .put("name", city)
                    )
            )

    private fun server(
        hostname: String,
        station: String,
        load: Int,
        locationIds: IntArray,
        status: String = "online"
    ) =
        JSONObject()
            .put("hostname", hostname)
            .put("station", station)
            .put("load", load)
            .put("status", status)
            .put(
                "location_ids",
                JSONArray().apply {
                    locationIds.forEach(
                        ::put
                    )
                }
            )
            .put(
                "technologies",
                JSONArray().put(
                    JSONObject()
                        .put("id", 35)
                        .put(
                            "metadata",
                            JSONArray().put(
                                JSONObject()
                                    .put(
                                        "name",
                                        "public_key"
                                    )
                                    .put(
                                        "value",
                                        "public-key"
                                    )
                            )
                        )
                )
            )
}
