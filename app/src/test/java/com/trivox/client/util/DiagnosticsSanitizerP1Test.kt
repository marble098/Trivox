package com.trivox.client.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsSanitizerP1Test {
    @Test
    fun redactsSshUriQuotedSecretsAndAuthorization() {
        val source =
            "ssh://alice:secret@example.com:22\n" +
                "\"privateKey\":\"very-private-value\"\n" +
                "token=very-secret-token\n" +
                "Authorization: Bearer very-secret-bearer\n"

        val clean = Diagnostics.sanitize(source)

        assertTrue(clean.contains("<redacted-uri>"))
        assertTrue(clean.contains("privateKey=<redacted>"))
        assertTrue(clean.contains("token=<redacted>"))
        assertTrue(
            clean.contains(
                "Authorization: <redacted>",
                ignoreCase = true
            )
        )
        assertFalse(clean.contains("alice:secret"))
        assertFalse(clean.contains("very-private-value"))
        assertFalse(clean.contains("very-secret-token"))
        assertFalse(clean.contains("very-secret-bearer"))
    }
}
