package com.trivox.client.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IcmpProbeP2Test {
    @Test
    fun acceptsOnlyVerifiedEchoReplyAddresses() {
        val output = listOf(
            "PING target (203.0.113.8) 56(84) bytes of data.",
            "64 bytes from 203.0.113.8: icmp_seq=1 ttl=56 time=0.42 ms",
            "64 bytes from 203.0.113.8: icmp_seq=2 ttl=56 time=1.20 ms",
            "64 bytes from 198.51.100.7: icmp_seq=3 ttl=56 time=2.00 ms"
        ).joinToString("\n")

        val parsed = IcmpProbe.parseOutput(
            output,
            setOf("203.0.113.8")
        )

        assertEquals(2, parsed.samplesMs.size)
        assertEquals(
            "203.0.113.8",
            parsed.replyAddress
        )
        assertTrue(parsed.sawReply)
        assertTrue(parsed.sawUnexpectedReply)
    }

    @Test
    fun subMillisecondReplyIsNotForcedToOneMillisecond() {
        val output =
            "64 bytes from 203.0.113.8: " +
                "icmp_seq=1 ttl=56 time<1 ms"

        val parsed = IcmpProbe.parseOutput(
            output,
            setOf("203.0.113.8")
        )

        assertEquals(1, parsed.samplesMs.size)
        assertTrue(
            parsed.samplesMs.single() < 1.0
        )
        assertFalse(parsed.sawUnexpectedReply)
    }

    @Test
    fun acceptsHostnameWithParenthesizedVerifiedAddress() {
        val output =
            "64 bytes from edge.example (203.0.113.8): " +
                "icmp_seq=1 ttl=56 time=3.25 ms"

        val parsed = IcmpProbe.parseOutput(
            output,
            setOf("203.0.113.8")
        )

        assertEquals(1, parsed.samplesMs.size)
        assertEquals(
            "203.0.113.8",
            parsed.replyAddress
        )
        assertFalse(parsed.sawUnexpectedReply)
    }

    @Test
    fun acceptsVerifiedIpv6WithReplySeparatorColon() {
        val output =
            "64 bytes from 2001:db8::8: " +
                "icmp_seq=1 ttl=56 time=4.75 ms"

        val parsed = IcmpProbe.parseOutput(
            output,
            setOf("2001:db8::8")
        )

        assertEquals(1, parsed.samplesMs.size)
        assertFalse(parsed.sawUnexpectedReply)
    }
}
