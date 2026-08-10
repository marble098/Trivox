package com.trivox.client.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsSanitizeTest {
    @Test
    fun redactsQuotedJsonPassword() {
        val sanitized = Diagnostics.sanitize("{\"password\":\"hunter2\"}")
        assertFalse(sanitized.contains("hunter2"))
        assertTrue(sanitized.contains("<redacted>"))
    }

    @Test
    fun redactsQuotedJsonPrivateKey() {
        val sanitized = Diagnostics.sanitize(
            "{\"id\":\"vless-1\",\"privateKey\":\"MC4CAQAwBQYDK2VuBCIEIA==\"}"
        )
        assertFalse(sanitized.contains("MC4CAQAwBQYDK2VuBCIEIA=="))
    }

    @Test
    fun redactsAuthorizationHeader() {
        val sanitized = Diagnostics.sanitize("Authorization: Bearer abc.def.ghi")
        assertFalse(sanitized.contains("abc.def.ghi"))
    }

    @Test
    fun redactsPemPrivateKeyBlock() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
            "b3BlbnNzaC1rZXktdjEAAAAA\n" +
            "-----END OPENSSH PRIVATE KEY-----"
        val sanitized = Diagnostics.sanitize("before\n$pem\nafter")
        assertFalse(sanitized.contains("b3BlbnNzaC1rZXktdjEAAAAA"))
        assertTrue(sanitized.contains("<redacted-private-key>"))
    }

    @Test
    fun redactsWireGuardIniPrivateKey() {
        val ini = "[Interface]\nPrivateKey = kO2S+1cV9example==\nAddress = 10.0.0.2/32"
        val sanitized = Diagnostics.sanitize(ini)
        assertFalse(sanitized.contains("kO2S+1cV9example=="))
        assertTrue(sanitized.contains("PrivateKey ="))
    }

    @Test
    fun redactsFullUriIncludingSshAndWireGuardSchemes() {
        val sanitized = Diagnostics.sanitize(
            "connecting to ssh://user:pass@host:22 and vless://uuid@host:443?type=ws"
        )
        assertFalse(sanitized.contains("user:pass@host"))
        assertFalse(sanitized.contains("uuid@host"))
    }

    @Test
    fun redactsBareUuid() {
        val sanitized = Diagnostics.sanitize("session id 123e4567-e89b-12d3-a456-426614174000 ok")
        assertFalse(sanitized.contains("123e4567-e89b-12d3-a456-426614174000"))
    }

    @Test
    fun leavesNonSensitiveTextUnchanged() {
        val sanitized = Diagnostics.sanitize("Xray started; state=CONNECTED; latencyMs=42")
        assertTrue(sanitized.contains("state=CONNECTED"))
        assertTrue(sanitized.contains("latencyMs=42"))
    }
}
