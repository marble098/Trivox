package com.trivox.client.config

import java.util.Base64

object Shadowsocks2022 {
    fun normalizePassword(
        method: String,
        password: String
    ): String {
        val normalizedMethod =
            method.trim().lowercase()

        if (
            !normalizedMethod.startsWith(
                "2022-blake3-"
            )
        ) {
            return password
        }

        val keySize =
            when {
                "aes-128" in normalizedMethod -> 16
                "aes-256" in normalizedMethod -> 32
                "chacha20" in normalizedMethod -> 32
                else -> throw ConfigParseException(
                    "Unsupported Shadowsocks 2022 method: $method"
                )
            }

        val parts =
            password
                .trim()
                .split(':')
                .filter(String::isNotBlank)

        if (parts.isEmpty()) {
            throw ConfigParseException(
                "Shadowsocks 2022 key is missing"
            )
        }

        return parts.joinToString(":") {
            normalizeKey(
                it,
                keySize
            )
        }
    }

    private fun normalizeKey(
        value: String,
        expectedSize: Int
    ): String {
        val compact =
            value.trim()
                .replace('-', '+')
                .replace('_', '/')
                .filterNot(Char::isWhitespace)
        val padded =
            compact +
                "=".repeat(
                    (4 - compact.length % 4) % 4
                )
        val decoded =
            runCatching {
                Base64
                    .getDecoder()
                    .decode(padded)
            }.getOrElse {
                throw ConfigParseException(
                    "Shadowsocks 2022 key is not valid Base64"
                )
            }

        if (decoded.size != expectedSize) {
            throw ConfigParseException(
                "Shadowsocks 2022 key must decode to " +
                    "$expectedSize bytes, but decoded to " +
                    "${decoded.size} bytes"
            )
        }

        return Base64
            .getEncoder()
            .encodeToString(decoded)
    }
}
