package com.trivox.client.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XrayProbeLogInspectorV10Test {
    @Test fun classifiesRealityAndWebsocketFailures() {
        assertEquals(
            "reality_mismatch",
            XrayProbeLogInspector.classify(
                "transport/internet/reality: REALITY: received real certificate"
            )
        )
        assertEquals(
            "websocket_handshake",
            XrayProbeLogInspector.classify(
                "transport/internet/websocket: failed to dial to example:443"
            )
        )
    }

    @Test fun cleanStartLogDoesNotBecomeFailure() {
        assertNull(XrayProbeLogInspector.classify("core: Xray 26.7.28 started"))
    }
}
