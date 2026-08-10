package com.trivox.client.config

import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * Property-style fuzzing for the subscription/URI parser: it is fed
 * arbitrary and randomly-mutated input and must never throw anything other
 * than [ConfigParseException] (no NPE, IndexOutOfBounds, StackOverflow,
 * etc.) and must never hang. A malformed subscription body is untrusted
 * input from a remote server, so the parser's only contract is "reject
 * cleanly," never "crash the app."
 */
class ConfigParserFuzzTest {
    private val printableAscii =
        (0x20..0x7e).map { it.toChar() }.joinToString("") +
            "\n\t\r\u0000\uFEFF"

    @Test(timeout = 30_000)
    fun neverThrowsUnexpectedExceptionOnRandomBytes() {
        val random = Random(20260810)

        repeat(3_000) { iteration ->
            val length = random.nextInt(0, 500)
            val input = buildString(length) {
                repeat(length) {
                    append(printableAscii[random.nextInt(printableAscii.length)])
                }
            }

            assertParsesWithoutCrashing(input, "random#$iteration")
        }
    }

    @Test(timeout = 30_000)
    fun neverThrowsUnexpectedExceptionOnMutatedRealisticInputs() {
        val random = Random(1337)
        val seeds = listOf(
            "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
                "?type=ws&security=tls&path=%2Fws#My+Server",
            "vmess://eyJ2IjoiMiIsInBzIjoibiIsImFkZCI6ImUuY29tIiwicG9ydCI6IjQ0MyIsImlkIjoidSIsImFpZCI6IjAiLCJuZXQiOiJ3cyIsInR5cGUiOiJub25lIiwiaG9zdCI6IiIsInBhdGgiOiIiLCJ0bHMiOiJ0bHMifQ==",
            "trojan://password@example.com:443?sni=example.com&type=tcp#name",
            "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@example.com:8388#name",
            "wireguard://example.com:51820?publickey=YWJjZGVm&privatekey=Z2hpams=",
            "hysteria2://password@example.com:443?sni=example.com#h2",
            "{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"e.com\",\"port\":443," +
                "\"users\":[{\"id\":\"u\"}]}]}}",
            "[Interface]\nPrivateKey = abcd\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = efgh\n" +
                "Endpoint = example.com:51820"
        )

        seeds.forEach { seed ->
            repeat(300) { iteration ->
                val mutated = mutate(seed, random)
                assertParsesWithoutCrashing(mutated, "mutated-of[$seed]#$iteration")
            }
        }
    }

    @Test(timeout = 10_000)
    fun neverThrowsUnexpectedExceptionOnTruncatedRealisticInputs() {
        val seeds = listOf(
            "vless://11111111-1111-1111-1111-111111111111@example.com:443?type=ws#name",
            "vmess://eyJ2IjoiMiIsInBzIjoibiJ9",
            "{\"protocol\":\"vless\"}"
        )

        seeds.forEach { seed ->
            for (cut in 0..seed.length) {
                assertParsesWithoutCrashing(seed.substring(0, cut), "truncated[$seed]@$cut")
            }
        }
    }

    private fun assertParsesWithoutCrashing(input: String, label: String) {
        try {
            ConfigParser.parseText(input)
        } catch (expected: ConfigParseException) {
            // Malformed/unsupported input must be rejected, never crash.
        } catch (unexpected: Throwable) {
            fail(
                "parseText threw unexpected " +
                    "${unexpected::class.simpleName} for $label: ${unexpected.message}"
            )
        }
    }

    private fun mutate(seed: String, random: Random): String {
        val chars = seed.toCharArray().toMutableList()
        val mutations = random.nextInt(1, 6)

        repeat(mutations) {
            if (chars.isEmpty()) return@repeat
            when (random.nextInt(4)) {
                0 -> chars.removeAt(random.nextInt(chars.size))
                1 -> chars.add(random.nextInt(chars.size + 1), randomChar(random))
                2 -> chars[random.nextInt(chars.size)] = randomChar(random)
                else -> {
                    val start = random.nextInt(chars.size)
                    val end = (start + random.nextInt(1, 10)).coerceAtMost(chars.size)
                    chars.addAll(start, chars.subList(start, end).toList())
                }
            }
        }

        return chars.joinToString("")
    }

    private fun randomChar(random: Random): Char {
        val pool = "://?#@:.,;=&%+ \n\t{}[]\"'\\-_~!\$^*()<>|abcXYZ019"
        return pool[random.nextInt(pool.length)]
    }
}
