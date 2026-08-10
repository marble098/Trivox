package com.trivox.client.network

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class SubscriptionManagerConditionalCacheTest {
    private lateinit var server: HttpsServer
    private var lastRequestHeaders: Map<String, String> = emptyMap()
    private var currentEtag = "\"v1\""
    private var currentBody = "vless://11111111-1111-1111-1111-111111111111@example.com:443?type=ws#one"
    private var requestCount = 0

    @Before
    fun startServer() {
        val ks = KeyStore.getInstance("JKS")
        javaClass.getResourceAsStream("/test-keystore.jks")!!.use {
            ks.load(it, "changeit".toCharArray())
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, "changeit".toCharArray())
        val serverContext = SSLContext.getInstance("TLS")
        serverContext.init(kmf.keyManagers, null, SecureRandom())

        server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(serverContext)
        server.createContext("/sub") { exchange ->
            requestCount += 1
            lastRequestHeaders = exchange.requestHeaders
                .entries.associate { it.key to it.value.firstOrNull().orEmpty() }

            val ifNoneMatch = exchange.requestHeaders.getFirst("If-None-Match")
            if (ifNoneMatch != null && ifNoneMatch == currentEtag) {
                exchange.responseHeaders.add("ETag", currentEtag)
                exchange.sendResponseHeaders(304, -1)
                exchange.close()
                return@createContext
            }

            exchange.responseHeaders.add("ETag", currentEtag)
            exchange.responseHeaders.add("Content-Type", "text/plain")
            val bytes = currentBody.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.executor = null
        server.start()

        // Trust-all client context: this is throwaway verification code
        // exercising a self-signed loopback server, not production code.
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val clientContext = SSLContext.getInstance("TLS")
        clientContext.init(null, arrayOf(trustAll), SecureRandom())
        HttpsURLConnection.setDefaultSSLSocketFactory(clientContext.socketFactory)
        HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun port() = server.address.port

    @Test
    fun firstFetchReturnsProfilesAndCapturesEtag() {
        val result = SubscriptionManager().fetch("https://127.0.0.1:${port()}/sub")

        assertFalse(result.notModified)
        assertEquals(1, result.profiles.size)
        assertEquals("\"v1\"", result.etag)
        assertEquals(1, requestCount)
    }

    @Test
    fun secondFetchWithMatchingEtagIsNotModifiedAndSendsNoUnexpectedBody() {
        val first = SubscriptionManager().fetch("https://127.0.0.1:${port()}/sub")
        val second = SubscriptionManager().fetch(
            "https://127.0.0.1:${port()}/sub",
            etag = first.etag
        )

        assertTrue(second.notModified)
        assertTrue(second.profiles.isEmpty())
        assertEquals("\"v1\"", lastRequestHeaders["If-none-match"] ?: lastRequestHeaders["If-None-Match"])
        assertEquals(2, requestCount)
    }

    @Test
    fun changedContentIsFetchedAgainDespiteStaleEtag() {
        val first = SubscriptionManager().fetch("https://127.0.0.1:${port()}/sub")

        currentEtag = "\"v2\""
        currentBody = "vless://22222222-2222-2222-2222-222222222222@example.com:443?type=ws#two"

        val second = SubscriptionManager().fetch(
            "https://127.0.0.1:${port()}/sub",
            etag = first.etag
        )

        assertFalse(second.notModified)
        assertEquals(1, second.profiles.size)
        assertEquals("\"v2\"", second.etag)
        assertTrue(second.profiles[0].outboundJson.contains("22222222"))
    }

    @Test
    fun noEtagSentWhenCallerHasNone() {
        SubscriptionManager().fetch("https://127.0.0.1:${port()}/sub")

        assertTrue(
            (lastRequestHeaders["If-none-match"] ?: lastRequestHeaders["If-None-Match"]).isNullOrEmpty()
        )
    }
}
