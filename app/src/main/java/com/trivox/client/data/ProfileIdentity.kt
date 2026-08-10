package com.trivox.client.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * A canonical, order-independent identity for a profile's endpoint and
 * credentials. Built from protocol/server/port plus a SHA-256 digest of the
 * recursively key-sorted outbound JSON, so it is stable across:
 * - remark/name edits (the display name is never part of outboundJson; the
 *   parser always normalizes the outbound "tag" to a constant value)
 * - reordered query parameters or JSON key order coming from different
 *   parser runs, app versions, or subscription providers (canonicalization
 *   sorts every object's keys before hashing)
 *
 * The digest also means the identity string itself never carries plaintext
 * UUIDs/passwords/keys - only a one-way hash of them - so it is safe to log
 * or keep around as a map key.
 */
object ProfileIdentity {
    fun of(profile: ConfigProfile): String {
        val canonicalSettings =
            canonicalizeOutboundJson(profile.outboundJson)
                .ifBlank { profile.raw.trim() }

        return "${profile.protocol.lowercase()}|" +
            "${profile.server.lowercase()}|" +
            "${profile.port}|" +
            sha256Hex(canonicalSettings)
    }

    private fun canonicalizeOutboundJson(json: String): String =
        runCatching {
            canonicalizeJsonValue(JSONObject(json))
        }.getOrDefault("")

    private fun canonicalizeJsonValue(value: Any?): String =
        when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject ->
                value.keys().asSequence().sorted()
                    .joinToString(",", "{", "}") { key ->
                        "${JSONObject.quote(key)}:" +
                            canonicalizeJsonValue(value.get(key))
                    }
            is JSONArray ->
                (0 until value.length())
                    .joinToString(",", "[", "]") {
                        canonicalizeJsonValue(value.get(it))
                    }
            is String -> JSONObject.quote(value)
            else -> value.toString()
        }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
