package com.trivox.client.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointBootstrapResolverP5Test {
    @Test
    fun recognizesRealIpv4AndIpv6Literals() {
        assertTrue(EndpointBootstrapResolver.isIpLiteral("1.1.1.1"))
        assertTrue(EndpointBootstrapResolver.isIpLiteral("[2606:4700:4700::1111]"))
    }

    @Test
    fun rejectsMalformedHexColonTextAsIpv6() {
        assertFalse(EndpointBootstrapResolver.isIpLiteral("bad:face"))
        assertFalse(EndpointBootstrapResolver.isIpLiteral("1.1.1.999"))
        assertFalse(EndpointBootstrapResolver.isIpLiteral("example.com"))
    }
}
