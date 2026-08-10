package com.trivox.client.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProfileIdentityTest {
    private fun profile(
        name: String,
        server: String = "example.com",
        port: Int = 443,
        outboundJson: String,
        raw: String = "vless://raw"
    ) = ConfigProfile(
        name = name,
        protocol = "vless",
        server = server,
        port = port,
        raw = raw,
        outboundJson = outboundJson
    )

    @Test
    fun renamingAProfileDoesNotChangeItsIdentity() {
        val json = """{"protocol":"vless","settings":{"vnext":[{"address":"example.com","port":443,"users":[{"id":"11111111-1111-1111-1111-111111111111"}]}]},"tag":"proxy"}"""
        val a = profile(name = "My Server", outboundJson = json)
        val b = profile(name = "Renamed Server", outboundJson = json)

        assertEquals(ProfileIdentity.of(a), ProfileIdentity.of(b))
    }

    @Test
    fun reorderedJsonKeysDoNotChangeIdentity() {
        val ordered = """{"protocol":"vless","tag":"proxy","settings":{"vnext":[{"address":"example.com","port":443,"users":[{"id":"abc","level":0}]}]}}"""
        val reordered = """{"tag":"proxy","settings":{"vnext":[{"port":443,"address":"example.com","users":[{"level":0,"id":"abc"}]}]},"protocol":"vless"}"""

        val a = profile(name = "A", outboundJson = ordered)
        val b = profile(name = "B", outboundJson = reordered)

        assertEquals(ProfileIdentity.of(a), ProfileIdentity.of(b))
    }

    @Test
    fun differentCredentialsProduceDifferentIdentity() {
        val a = profile(
            name = "A",
            outboundJson = """{"settings":{"id":"11111111-1111-1111-1111-111111111111"}}"""
        )
        val b = profile(
            name = "A",
            outboundJson = """{"settings":{"id":"22222222-2222-2222-2222-222222222222"}}"""
        )

        assertNotEquals(ProfileIdentity.of(a), ProfileIdentity.of(b))
    }

    @Test
    fun differentServerOrPortProducesDifferentIdentity() {
        val json = """{"settings":{"id":"same"}}"""
        val base = profile(name = "A", server = "one.example.com", port = 443, outboundJson = json)
        val differentServer = profile(name = "A", server = "two.example.com", port = 443, outboundJson = json)
        val differentPort = profile(name = "A", server = "one.example.com", port = 8443, outboundJson = json)

        assertNotEquals(ProfileIdentity.of(base), ProfileIdentity.of(differentServer))
        assertNotEquals(ProfileIdentity.of(base), ProfileIdentity.of(differentPort))
    }

    @Test
    fun identityNeverCarriesThePlaintextCredential() {
        val secretUuid = "deadbeef-dead-beef-dead-beefdeadbeef"
        val a = profile(
            name = "A",
            outboundJson = """{"settings":{"id":"$secretUuid"}}"""
        )

        assertFalse(ProfileIdentity.of(a).contains(secretUuid))
    }

    @Test
    fun blankOutboundJsonFallsBackToRawText() {
        val a = profile(name = "A", outboundJson = "", raw = "vless://same-raw-text")
        val b = profile(name = "B", outboundJson = "", raw = "vless://same-raw-text")
        val c = profile(name = "C", outboundJson = "", raw = "vless://different-raw-text")

        assertEquals(ProfileIdentity.of(a), ProfileIdentity.of(b))
        assertNotEquals(ProfileIdentity.of(a), ProfileIdentity.of(c))
    }
}
