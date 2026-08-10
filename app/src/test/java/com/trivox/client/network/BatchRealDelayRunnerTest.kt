package com.trivox.client.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchRealDelayRunnerTest {
    @Test
    fun fragmentDialerDependencyIsPreservedAndNamespaced() {
        val root = JSONObject().put(
            "outbounds",
            JSONArray()
                .put(
                    JSONObject()
                        .put("protocol", "freedom")
                        .put("tag", "fragment-out")
                )
                .put(
                    JSONObject()
                        .put("protocol", "vless")
                        .put("tag", "proxy")
                        .put(
                            "streamSettings",
                            JSONObject().put(
                                "sockopt",
                                JSONObject().put(
                                    "dialerProxy",
                                    "fragment-out"
                                )
                            )
                        )
                )
                .put(
                    JSONObject()
                        .put("protocol", "freedom")
                        .put("tag", "direct")
                )
        )

        val selection =
            BatchOutboundGraph.select(
                root,
                4
            )

        assertEquals(
            "batch-proxy-4",
            selection.primaryTag
        )
        assertEquals(
            2,
            selection.outbounds.size
        )
        assertFalse(
            selection.outbounds.any {
                it.optString("tag") ==
                    "direct"
            }
        )

        val proxy =
            selection.outbounds.first {
                it.optString("tag") ==
                    selection.primaryTag
            }

        val dependencyTag =
            proxy
                .getJSONObject("streamSettings")
                .getJSONObject("sockopt")
                .getString("dialerProxy")

        assertTrue(
            dependencyTag.startsWith(
                "batch-dep-4-"
            )
        )

        assertNotNull(
            selection.outbounds
                .firstOrNull {
                    it.optString("tag") ==
                        dependencyTag
                }
        )
    }

    @Test
    fun proxySettingsDependencyIsPreservedAndRewritten() {
        val root = JSONObject().put(
            "outbounds",
            JSONArray()
                .put(
                    JSONObject()
                        .put("protocol", "vless")
                        .put("tag", "proxy")
                        .put(
                            "proxySettings",
                            JSONObject()
                                .put(
                                    "tag",
                                    "chain-bridge"
                                )
                                .put(
                                    "transportLayer",
                                    true
                                )
                        )
                )
                .put(
                    JSONObject()
                        .put("protocol", "socks")
                        .put(
                            "tag",
                            "chain-bridge"
                        )
                )
        )

        val selection =
            BatchOutboundGraph.select(
                root,
                1
            )

        val proxy =
            selection.outbounds.first {
                it.optString("tag") ==
                    selection.primaryTag
            }

        val bridgeTag =
            proxy
                .getJSONObject("proxySettings")
                .getString("tag")

        assertTrue(
            bridgeTag.startsWith(
                "batch-dep-1-"
            )
        )

        assertTrue(
            selection.outbounds.any {
                it.optString("tag") ==
                    bridgeTag
            }
        )
    }
}
