package com.trivox.client.network

import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConnectionMode
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SmartConnectionOptimizerP3Test {
    private fun profile(
        network: String,
        security: String = "tls"
    ) = ConfigProfile(
        name = "test",
        protocol = "vless",
        server = "example.com",
        port = 443,
        raw = "",
        outboundJson = JSONObject()
            .put("protocol", "vless")
            .put(
                "streamSettings",
                JSONObject()
                    .put("network", network)
                    .put("security", security)
            )
            .toString()
    )

    @Before
    fun setUp() {
        SmartConnectionOptimizer.resetForTests()
    }

    @After
    fun tearDown() {
        SmartConnectionOptimizer.resetForTests()
    }

    @Test
    fun websocketGetsMoreHandshakeGraceThanRawProxy() {
        val settings = AppSettings(
            smartConnectionOptimizer = true
        )
        val ws = SmartConnectionOptimizer.verificationPlan(
            profile("ws"),
            settings,
            ConnectionMode.VPN
        )
        val raw = SmartConnectionOptimizer.verificationPlan(
            profile("tcp"),
            settings,
            ConnectionMode.PROXY
        )

        assertTrue(ws.initialDelayMs > raw.initialDelayMs)
        assertTrue(ws.budgetMs >= 8_000)
    }

    @Test
    fun repeatedTransientFailureLearnsMorePatientPlan() {
        val settings = AppSettings(
            smartConnectionOptimizer = true
        )
        val target = profile("ws")
        val cold = SmartConnectionOptimizer.verificationPlan(
            target,
            settings,
            ConnectionMode.VPN
        )

        SmartConnectionOptimizer.recordOutcome(
            target,
            success = false,
            errorCategory = "connection_reset"
        )
        SmartConnectionOptimizer.recordOutcome(
            target,
            success = false,
            errorCategory = "connection_reset"
        )

        val learned = SmartConnectionOptimizer.verificationPlan(
            target,
            settings,
            ConnectionMode.VPN
        )

        assertTrue(learned.budgetMs > cold.budgetMs)
        assertTrue(learned.initialDelayMs > cold.initialDelayMs)
        assertTrue(learned.attempts >= cold.attempts)
        assertTrue(learned.strategy.contains(":learned:"))
    }

    @Test
    fun successfulRoutesConvergeBackTowardFastPath() {
        val settings = AppSettings(
            smartConnectionOptimizer = true
        )
        val target = profile("tcp")

        SmartConnectionOptimizer.recordOutcome(
            target,
            success = false,
            errorCategory = "dns_timeout"
        )
        val cautious = SmartConnectionOptimizer.verificationPlan(
            target,
            settings,
            ConnectionMode.PROXY
        )

        repeat(3) {
            SmartConnectionOptimizer.recordOutcome(
                target,
                success = true,
                errorCategory = null
            )
        }
        val recovered = SmartConnectionOptimizer.verificationPlan(
            target,
            settings,
            ConnectionMode.PROXY
        )

        assertTrue(recovered.budgetMs <= cautious.budgetMs)
        assertTrue(recovered.initialDelayMs <= cautious.initialDelayMs)
    }
}
