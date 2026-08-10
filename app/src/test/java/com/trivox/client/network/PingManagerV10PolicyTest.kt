package com.trivox.client.network

import com.trivox.client.core.CoreAdapter
import com.trivox.client.core.CoreCapabilities
import com.trivox.client.core.CoreResult
import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PingManagerV10PolicyTest {
    @Test
    fun realDelayNeverStopsOrReusesAnActiveLiveCore() {
        val adapter = object : CoreAdapter {
            override val id = "fake"
            override val capabilities = CoreCapabilities(
                protocols = emptySet(),
                transports = emptySet(),
                androidTun = true,
                configValidation = true,
                realDelayTest = true
            )

            override fun isAvailable() = true
            override fun version() = "test"
            override fun validate(configJson: String) = CoreResult(true)

            override fun start(
                configJson: String,
                protectSocket: ((Int) -> Boolean)?
            ) = error("must not start while a live core is active")

            override fun stop() = error("must not stop a live core")

            override fun state() = CoreResult(
                true,
                data = JSONObject().put(
                    "data",
                    JSONObject().put("running", true)
                )
            )

            override fun realDelay(
                configPath: String,
                timeoutSeconds: Int,
                url: String
            ) = error("native MeasureDelay is not a verified result")
        }

        val profile = ConfigProfile(
            name = "test",
            protocol = "vless",
            server = "example.com",
            port = 443,
            raw = "",
            outboundJson = "{}"
        )

        PingManager(adapter).use { manager ->
            val result = manager.realXray(
                profile,
                AppSettings(),
                File(System.getProperty("java.io.tmpdir"))
            )
            assertFalse(result.success)
            assertNull(result.latencyMs)
            assertTrue(result.errorCategory == "live_core_busy")
        }
    }
}
