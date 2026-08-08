package com.trivox.client.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeakProtectionManagerV21Test {
    @Test
    fun incompletePublicProbeAloneIsNotTreatedAsLeak() {
        val report = LeakProtectionManager.Report(
            ipLeak = false,
            dnsLeak = false,
            ipv6LeakRisk = false,
            vpnExitIp = null,
            underlyingIp = null,
            vpnDns = listOf("1.1.1.1"),
            probeIncomplete = true
        )

        assertFalse(report.hasLeak)
    }

    @Test
    fun actualRouteOrDnsRiskStillTriggersProtection() {
        assertTrue(
            LeakProtectionManager.Report(
                ipLeak = true,
                dnsLeak = false,
                ipv6LeakRisk = false,
                vpnExitIp = "203.0.113.10",
                underlyingIp = "203.0.113.10",
                vpnDns = emptyList()
            ).hasLeak
        )
        assertTrue(
            LeakProtectionManager.Report(
                ipLeak = false,
                dnsLeak = true,
                ipv6LeakRisk = false,
                vpnExitIp = "198.51.100.2",
                underlyingIp = "203.0.113.2",
                vpnDns = emptyList()
            ).hasLeak
        )
    }
}
