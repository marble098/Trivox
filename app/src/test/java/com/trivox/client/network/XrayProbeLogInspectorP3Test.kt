package com.trivox.client.network

import org.junit.Assert.assertEquals
import org.junit.Test

class XrayProbeLogInspectorP3Test {
    @Test
    fun websocketResetIsTransientResetNotGenericHandshakeFailure() {
        val text = "transport/internet/websocket: failed to dial > read tcp: connection reset by peer"
        assertEquals("connection_reset", XrayProbeLogInspector.classify(text))
    }

    @Test
    fun dnsClosedPipeAndRecordNotFoundAreActionable() {
        assertEquals("dns_pipe_closed", XrayProbeLogInspector.classify("app/dns: read/write on closed pipe"))
        assertEquals("dns_failure", XrayProbeLogInspector.classify("failed to resolve ip > record not found"))
    }
}
