package com.trivox.client.config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ManualConfigFactoryWireGuardV9Test {
    @Test
    fun manualWireGuardLeavesGlobalRuntimeDefaultsToBuilder() {
        val profile = ManualConfigFactory.wireGuard(
            name = "manual-wg",
            endpoint = "example.com:51820",
            privateKey = "private",
            publicKey = "public",
            address = "10.0.0.2/32",
            allowed = "0.0.0.0/0,::/0",
            preShared = "",
            mtu = 900
        )
        val settings = JSONObject(profile.outboundJson)
            .getJSONObject("settings")

        assertEquals(900, settings.getInt("mtu"))
        assertFalse(settings.has("domainStrategy"))
        assertFalse(settings.has("workers"))
        assertFalse(
            settings.getJSONArray("peers")
                .getJSONObject(0)
                .has("keepAlive")
        )
    }
}
