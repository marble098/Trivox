package com.trivox.client.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedHttpProbeV10Test {
    @Test fun traceRequiresServerGeneratedMarkers() {
        val target = VerifiedHttpProbe.strongTraceTarget
        assertTrue(
            VerifiedHttpProbe.accepts(
                target,
                200,
                "fl=1f2\nip=203.0.113.10\ntls=TLSv1.3\ncolo=AMS\n"
            )
        )
        assertFalse(
            VerifiedHttpProbe.accepts(target, 200, "hello world")
        )
        assertFalse(
            VerifiedHttpProbe.accepts(target, 302, "ip=203.0.113.10\ncolo=AMS\ntls=TLSv1.3")
        )
    }

    @Test fun exact204RejectsOrdinarySuccessAndRedirects() {
        val target = VerifiedHttpProbe.Target(
            "https://example.test/generate_204",
            VerifiedHttpProbe.Proof.EXACT_204
        )
        assertTrue(VerifiedHttpProbe.accepts(target, 204, ""))
        assertFalse(VerifiedHttpProbe.accepts(target, 200, ""))
        assertFalse(VerifiedHttpProbe.accepts(target, 302, ""))
    }
}
