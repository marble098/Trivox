package com.trivox.client.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionManagerTest {
    @Test
    fun missingSchemeBecomesHttps() {
        val uri =
            SubscriptionManager.normalizeUrl(
                "example.com/sub?id=1"
            )

        assertEquals(
            "https",
            uri.scheme
        )
        assertEquals(
            "example.com",
            uri.host
        )
        assertEquals(
            "/sub",
            uri.path
        )
        assertEquals(
            "id=1",
            uri.query
        )
    }

    @Test
    fun fragmentIsRemovedInsteadOfRejectingUrl() {
        val uri =
            SubscriptionManager.normalizeUrl(
                "https://example.com/sub?token=x#profile-name"
            )

        assertEquals(
            "https://example.com/sub?token=x",
            uri.toASCIIString()
        )
        assertEquals(
            null,
            uri.fragment
        )
    }

    @Test
    fun unicodeHostIsConvertedToAscii() {
        val uri =
            SubscriptionManager.normalizeUrl(
                "https://مثال.إختبار/sub"
            )

        assertFalse(
            uri.host.isNullOrBlank()
        )
        assertTrue(
            uri.host.startsWith("xn--")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun embeddedCredentialsAreRejected() {
        SubscriptionManager.normalizeUrl(
            "https://user:pass@example.com/sub"
        )
    }

    @Test
    fun interruptedExceptionIsCancellation() {
        assertTrue(
            InterruptedException()
                .isSubscriptionCancellation()
        )
    }
}
