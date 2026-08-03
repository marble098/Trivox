package com.trivox.client.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigParserSafetyTest {
    @Test
    fun literalPlusIsNotConvertedToSpace() {
        val profile =
            ConfigParser.parseUri(
                "http://user:pa+ss@example.com:8080#Name+One"
            )

        assertTrue(
            profile.name.contains(
                "Name+One"
            )
        )
        assertTrue(
            profile.outboundJson.contains(
                "pa+ss"
            )
        )
    }

    @Test
    fun oversizedInputIsRejectedBeforeParsing() {
        val oversized =
            "a".repeat(
                4 * 1024 * 1024 + 1
            )

        val error =
            runCatching {
                ConfigParser.parseText(
                    oversized
                )
            }.exceptionOrNull()

        assertTrue(
            error is ConfigParseException
        )
    }

    @Test
    fun unsupportedSchemeErrorDoesNotEchoCredentials() {
        val secret =
            "secret-password"
        val error =
            runCatching {
                ConfigParser.parseText(
                    "unknown://user:$secret@example.com:443"
                )
            }.exceptionOrNull()
                ?.message
                .orEmpty()

        assertFalse(
            error.contains(secret)
        )
    }
}
