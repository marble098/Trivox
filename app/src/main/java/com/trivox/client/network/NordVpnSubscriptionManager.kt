package com.trivox.client.network

import com.trivox.client.data.ConfigProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class NordVpnSubscriptionManager {
    fun fetchAllCountries(
        token: String
    ): List<ConfigProfile> {
        val normalizedToken =
            token.trim()

        require(
            normalizedToken.isNotBlank()
        ) {
            "NordVPN access token is missing"
        }

        val privateKey =
            fetchPrivateKey(
                normalizedToken
            )
        val countries =
            countryMap(
                JSONArray(
                    request(
                        COUNTRIES_URL
                    )
                )
            )
        val serverData =
            JSONObject(
                request(
                    SERVERS_URL
                )
            )
        val locations =
            locationMap(
                serverData.optJSONArray(
                    "locations"
                ) ?: JSONArray(),
                countries
            )
        val selected =
            selectBestServersByCity(
                serverData.optJSONArray(
                    "servers"
                ) ?: JSONArray(),
                locations
            )

        val profiles =
            selected.values
                .sortedWith(
                    compareBy<CityServer> {
                        it.location.countryName
                            .lowercase()
                    }.thenBy {
                        it.location.cityName
                            .lowercase()
                    }.thenBy {
                        it.server.load
                    }
                )
                .map { value ->
                    buildProfile(
                        privateKey,
                        value
                    )
                }

        check(profiles.isNotEmpty()) {
            "NordVPN returned no usable NordLynx city servers"
        }

        return profiles
    }

    private fun buildProfile(
        privateKey: String,
        selected: CityServer
    ): ConfigProfile {
        val location =
            selected.location
        val server =
            selected.server
        val endpoint =
            formatEndpoint(
                server.station,
                NORDLYNX_PORT
            )
        val outbound =
            JSONObject()
                .put("tag", "proxy")
                .put("protocol", "wireguard")
                .put(
                    "settings",
                    JSONObject()
                        .put(
                            "secretKey",
                            privateKey
                        )
                        .put(
                            "address",
                            JSONArray()
                                .put("10.5.0.2/32")
                        )
                        .put(
                            "peers",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put(
                                            "publicKey",
                                            server.publicKey
                                        )
                                        .put(
                                            "endpoint",
                                            endpoint
                                        )
                                )
                        )
                        .put("mtu", 1420)
                        .put(
                            "domainStrategy",
                            "ForceIP"
                        )
                        .put(
                            "noKernelTun",
                            true
                        )
                )
        val displayCountry =
            friendlyCountryName(
                location.countryName
            )
        val city =
            location.cityName
                .ifBlank {
                    "Best"
                }
        val flag =
            countryFlag(
                location.countryCode
            )
        val name =
            buildString {
                if (flag.isNotBlank()) {
                    append(flag)
                    append(' ')
                }
                append("NordVPN ")
                append(displayCountry)
                append(" - ")
                append(city)
            }
        val raw =
            portableJson(outbound)

        return ConfigProfile(
            name = name,
            protocol = "wireguard",
            server = server.station,
            port = NORDLYNX_PORT,
            raw = raw,
            outboundJson =
                outbound.toString(),
            probeServer =
                server.hostname
                    .ifBlank {
                        server.station
                    },
            probePort = 443
        )
    }

    private fun portableJson(
        outbound: JSONObject
    ): String {
        val inbounds =
            JSONArray()
                .put(
                    JSONObject()
                        .put("tag", "socks-in")
                        .put("listen", "127.0.0.1")
                        .put("port", 10808)
                        .put("protocol", "socks")
                        .put(
                            "settings",
                            JSONObject()
                                .put("auth", "noauth")
                                .put("udp", true)
                        )
                )
                .put(
                    JSONObject()
                        .put("tag", "http-in")
                        .put("listen", "127.0.0.1")
                        .put("port", 10809)
                        .put("protocol", "http")
                        .put(
                            "settings",
                            JSONObject()
                        )
                )
        val outbounds =
            JSONArray()
                .put(
                    JSONObject(
                        outbound.toString()
                    )
                )
                .put(
                    JSONObject()
                        .put("tag", "direct")
                        .put("protocol", "freedom")
                )
                .put(
                    JSONObject()
                        .put("tag", "block")
                        .put("protocol", "blackhole")
                )
        val privateNetworks =
            JSONArray(
                listOf(
                    "0.0.0.0/8",
                    "10.0.0.0/8",
                    "100.64.0.0/10",
                    "127.0.0.0/8",
                    "169.254.0.0/16",
                    "172.16.0.0/12",
                    "192.168.0.0/16",
                    "224.0.0.0/4",
                    "::1/128",
                    "fc00::/7",
                    "fe80::/10"
                )
            )

        return JSONObject()
            .put(
                "log",
                JSONObject().put(
                    "loglevel",
                    "warning"
                )
            )
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put(
                "dns",
                JSONObject()
                    .put(
                        "servers",
                        JSONArray()
                            .put("1.1.1.1")
                            .put("8.8.8.8")
                    )
                    .put(
                        "queryStrategy",
                        "UseIPv4"
                    )
            )
            .put(
                "routing",
                JSONObject()
                    .put(
                        "domainStrategy",
                        "IPIfNonMatch"
                    )
                    .put(
                        "rules",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("type", "field")
                                    .put("network", "udp,tcp")
                                    .put("port", "53")
                                    .put(
                                        "outboundTag",
                                        "direct"
                                    )
                            )
                            .put(
                                JSONObject()
                                    .put("type", "field")
                                    .put("ip", privateNetworks)
                                    .put(
                                        "outboundTag",
                                        "direct"
                                    )
                            )
                    )
            )
            .toString(2)
    }

    private fun fetchPrivateKey(
        token: String
    ): String {
        val credentials =
            JSONObject(
                request(
                    CREDENTIALS_URL,
                    token
                )
            )

        return credentials
            .optString(
                "nordlynx_private_key"
            )
            .trim()
            .ifBlank {
                error(
                    "NordVPN did not return a NordLynx private key"
                )
            }
    }

    private fun request(
        url: String,
        token: String? = null
    ): String {
        val uri = URI(url)
        val host =
            uri.host
                ?: error(
                    "NordVPN API URL has no host"
                )
        val candidates =
            LinkedHashSet<String>()

        runCatching {
            InetAddress
                .getAllByName(host)
                .filterNot(
                    ::isPrivateOrLocal
                )
                .mapNotNull {
                    it.hostAddress
                }
        }.getOrNull()
            ?.let(candidates::addAll)

        if (candidates.isEmpty()) {
            candidates.addAll(
                resolveWithDoh(host)
            )
        }

        check(candidates.isNotEmpty()) {
            "NordVPN API hostname resolved only to private or unusable addresses"
        }

        var lastError: Throwable? = null

        repeat(REQUEST_ATTEMPTS) {
            for (address in candidates) {
                try {
                    return requestMapped(
                        uri = uri,
                        connectAddress =
                            address,
                        token = token
                    )
                } catch (error: Throwable) {
                    lastError = error
                }
            }

            if (it + 1 < REQUEST_ATTEMPTS) {
                Thread.sleep(
                    RETRY_DELAY_MS
                )
            }
        }

        throw IllegalStateException(
            "NordVPN API request failed: " +
                (
                    lastError?.message
                        ?: "unavailable"
                    ),
            lastError
        )
    }

    private fun requestMapped(
        uri: URI,
        connectAddress: String,
        token: String?
    ): String {
        val originalHost =
            uri.host
                ?: error("Missing API host")
        val mappedHost =
            if (':' in connectAddress) {
                "[$connectAddress]"
            } else {
                connectAddress
            }
        val mapped =
            URL(
                "https://$mappedHost" +
                    (uri.rawPath ?: "/") +
                    uri.rawQuery
                        ?.let {
                            "?$it"
                        }
                        .orEmpty()
            )
        val connection =
            mapped.openConnection() as
                HttpsURLConnection

        try {
            connection.connectTimeout =
                CONNECT_TIMEOUT_MS
            connection.readTimeout =
                READ_TIMEOUT_MS
            connection.instanceFollowRedirects =
                false
            connection.requestMethod = "GET"
            connection.sslSocketFactory =
                SniSocketFactory(
                    HttpsURLConnection
                        .getDefaultSSLSocketFactory(),
                    originalHost
                )
            connection.hostnameVerifier =
                HostnameVerifier {
                        _,
                        session ->
                    HttpsURLConnection
                        .getDefaultHostnameVerifier()
                        .verify(
                            originalHost,
                            session
                        )
                }
            connection.setRequestProperty(
                "Host",
                originalHost
            )
            connection.setRequestProperty(
                "Accept",
                "application/json"
            )
            connection.setRequestProperty(
                "User-Agent",
                "Trivox-NordVPN/2"
            )

            if (token != null) {
                val credentials =
                    "token:$token"
                        .toByteArray(
                            Charsets.UTF_8
                        )

                connection.setRequestProperty(
                    "Authorization",
                    "Basic " +
                        Base64
                            .getEncoder()
                            .encodeToString(
                                credentials
                            )
                )
            }

            val status =
                connection.responseCode
            val stream =
                if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
            val body =
                stream
                    ?.use(::readLimited)
                    .orEmpty()

            if (
                status !=
                HttpURLConnection.HTTP_OK
            ) {
                error(
                    "NordVPN API returned HTTP $status"
                )
            }

            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveWithDoh(
        host: String
    ): List<String> {
        val encoded =
            URLEncoder.encode(
                host,
                StandardCharsets.UTF_8.name()
            )
        val uri =
            URI(
                "https://cloudflare-dns.com/" +
                    "dns-query?name=$encoded&type=A"
            )
        val result =
            LinkedHashSet<String>()

        for (resolverIp in DOH_RESOLVER_IPS) {
            runCatching {
                val connection =
                    URL(
                        "https://$resolverIp/" +
                            "dns-query?name=$encoded&type=A"
                    ).openConnection() as
                        HttpsURLConnection

                try {
                    connection.connectTimeout =
                        CONNECT_TIMEOUT_MS
                    connection.readTimeout =
                        READ_TIMEOUT_MS
                    connection.sslSocketFactory =
                        SniSocketFactory(
                            HttpsURLConnection
                                .getDefaultSSLSocketFactory(),
                            uri.host
                        )
                    connection.hostnameVerifier =
                        HostnameVerifier {
                                _,
                                session ->
                            HttpsURLConnection
                                .getDefaultHostnameVerifier()
                                .verify(
                                    uri.host,
                                    session
                                )
                        }
                    connection.setRequestProperty(
                        "Host",
                        uri.host
                    )
                    connection.setRequestProperty(
                        "Accept",
                        "application/dns-json"
                    )

                    if (
                        connection.responseCode !in
                        200..299
                    ) {
                        return@runCatching
                    }

                    val json =
                        JSONObject(
                            connection.inputStream
                                .use(::readLimited)
                        )
                    val answers =
                        json.optJSONArray(
                            "Answer"
                        ) ?: JSONArray()

                    for (
                        index in
                        0 until answers.length()
                    ) {
                        val value =
                            answers
                                .optJSONObject(index)
                                ?.optString("data")
                                ?.trim()
                                .orEmpty()
                        val address =
                            runCatching {
                                InetAddress
                                    .getByName(value)
                            }.getOrNull()

                        if (
                            address != null &&
                            !isPrivateOrLocal(
                                address
                            )
                        ) {
                            result += value
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }

            if (result.isNotEmpty()) {
                break
            }
        }

        return result.toList()
    }

    private fun readLimited(
        input: java.io.InputStream
    ): String {
        val output =
            ByteArrayOutputStream()
        val buffer =
            ByteArray(8192)
        var total = 0

        while (true) {
            val count =
                input.read(buffer)

            if (count < 0) {
                break
            }

            total += count

            check(
                total <= MAX_RESPONSE_BYTES
            ) {
                "NordVPN API response exceeded the size limit"
            }

            output.write(
                buffer,
                0,
                count
            )
        }

        return output
            .toByteArray()
            .toString(Charsets.UTF_8)
    }

    private fun countryMap(
        values: JSONArray
    ): Map<Int, CountryInfo> {
        val result =
            HashMap<Int, CountryInfo>()

        for (
            index in
            0 until values.length()
        ) {
            val country =
                values.optJSONObject(index)
                    ?: continue
            val id =
                country.optInt("id", -1)

            if (id < 0) {
                continue
            }

            result[id] =
                CountryInfo(
                    code =
                        country.optString("code")
                            .uppercase(),
                    name =
                        country.optString(
                            "name",
                            "Country $id"
                        )
                )
        }

        return result
    }

    private fun locationMap(
        values: JSONArray,
        countries: Map<Int, CountryInfo>
    ): Map<Int, LocationInfo> {
        val result =
            HashMap<Int, LocationInfo>()

        for (
            index in
            0 until values.length()
        ) {
            val location =
                values.optJSONObject(index)
                    ?: continue
            val locationId =
                location.optInt("id", -1)
            val countryObject =
                location.optJSONObject(
                    "country"
                )
            val countryId =
                countryObject
                    ?.optInt("id", -1)
                    ?: -1

            if (
                locationId < 0 ||
                countryId < 0
            ) {
                continue
            }

            val known =
                countries[countryId]
            val cityObject =
                location.optJSONObject(
                    "city"
                )
            val cityName =
                cityObject
                    ?.optString("name")
                    ?.trim()
                    .orEmpty()
                    .ifBlank {
                        location.optString(
                            "city"
                        ).trim()
                    }

            val countryCode =
                countryObject
                    ?.optString("code")
                    ?.uppercase()
                    ?.takeIf(String::isNotBlank)
                    ?: known?.code
                        .orEmpty()
            val countryName =
                countryObject
                    ?.optString("name")
                    ?.takeIf(String::isNotBlank)
                    ?: known?.name
                    ?: "Country $countryId"

            result[locationId] =
                LocationInfo(
                    countryId = countryId,
                    countryCode = countryCode,
                    countryName = countryName,
                    cityName = cityName
                )
        }

        return result
    }

    private fun selectBestServersByCity(
        servers: JSONArray,
        locations: Map<Int, LocationInfo>
    ): Map<String, CityServer> {
        val result =
            HashMap<String, CityServer>()

        for (
            index in
            0 until servers.length()
        ) {
            val value =
                servers.optJSONObject(index)
                    ?: continue
            val station =
                value.optString("station")
                    .trim()
            val hostname =
                value.optString("hostname")
                    .trim()
            val publicKey =
                nordLynxPublicKey(
                    value.optJSONArray(
                        "technologies"
                    ) ?: JSONArray()
                )

            if (
                station.isBlank() ||
                publicKey.isBlank()
            ) {
                continue
            }

            val server =
                NordServer(
                    station = station,
                    hostname = hostname,
                    publicKey = publicKey,
                    load =
                        value.optInt(
                            "load",
                            100
                        )
                )
            val locationIds =
                value.optJSONArray(
                    "location_ids"
                ) ?: JSONArray()

            for (
                locationIndex in
                0 until locationIds.length()
            ) {
                val location =
                    locations[
                        locationIds.optInt(
                            locationIndex,
                            -1
                        )
                    ] ?: continue
                val key =
                    location.countryId
                        .toString() +
                        "|" +
                        location.cityName
                            .trim()
                            .lowercase()
                            .ifBlank {
                                "best"
                            }
                val previous =
                    result[key]
                val candidate =
                    CityServer(
                        location,
                        server
                    )

                if (
                    previous == null ||
                    server.load <
                    previous.server.load ||
                    (
                        server.load ==
                        previous.server.load &&
                        server.hostname <
                        previous.server.hostname
                        )
                ) {
                    result[key] = candidate
                }
            }
        }

        return result
    }

    private fun nordLynxPublicKey(
        technologies: JSONArray
    ): String {
        for (
            index in
            0 until technologies.length()
        ) {
            val technology =
                technologies.optJSONObject(index)
                    ?: continue

            if (
                technology.optInt("id", -1) !=
                NORDLYNX_TECHNOLOGY_ID
            ) {
                continue
            }

            val metadata =
                technology.optJSONArray(
                    "metadata"
                ) ?: JSONArray()

            for (
                item in
                0 until metadata.length()
            ) {
                val entry =
                    metadata.optJSONObject(item)
                        ?: continue

                if (
                    entry.optString("name")
                        .equals(
                            "public_key",
                            ignoreCase = true
                        )
                ) {
                    return entry
                        .optString("value")
                        .trim()
                }
            }
        }

        return ""
    }

    private fun isPrivateOrLocal(
        address: InetAddress
    ): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }

        return when (address) {
            is Inet4Address -> {
                val bytes =
                    address.address.map {
                        it.toInt() and 0xff
                    }
                bytes[0] == 0 ||
                    bytes[0] == 10 ||
                    bytes[0] == 127 ||
                    (
                        bytes[0] == 100 &&
                        bytes[1] in 64..127
                        ) ||
                    (
                        bytes[0] == 169 &&
                        bytes[1] == 254
                        ) ||
                    (
                        bytes[0] == 172 &&
                        bytes[1] in 16..31
                        ) ||
                    (
                        bytes[0] == 192 &&
                        bytes[1] == 168
                        )
            }

            is Inet6Address ->
                address.isIPv4CompatibleAddress ||
                    (
                        address.address
                            .firstOrNull()
                            ?.toInt()
                            ?.and(0xfe) ==
                        0xfc
                        )

            else -> true
        }
    }

    private fun formatEndpoint(
        host: String,
        port: Int
    ): String =
        if (':' in host) {
            "[$host]:$port"
        } else {
            "$host:$port"
        }

    private fun friendlyCountryName(
        value: String
    ): String =
        when (
            value.trim()
        ) {
            "United Arab Emirates" ->
                "Emirates"
            else -> value.trim()
        }

    private fun countryFlag(
        countryCode: String
    ): String {
        val code =
            countryCode.uppercase()

        if (
            code.length != 2 ||
            code.any {
                it !in 'A'..'Z'
            }
        ) {
            return ""
        }

        return code
            .map {
                Character.toChars(
                    0x1F1E6 +
                        (
                            it.code -
                                'A'.code
                            )
                ).concatToString()
            }
            .joinToString("")
    }

    private class SniSocketFactory(
        private val delegate:
            SSLSocketFactory,
        private val serverName: String
    ) : SSLSocketFactory() {
        override fun getDefaultCipherSuites():
            Array<String> =
            delegate.defaultCipherSuites

        override fun getSupportedCipherSuites():
            Array<String> =
            delegate.supportedCipherSuites

        override fun createSocket(
            socket: java.net.Socket,
            host: String,
            port: Int,
            autoClose: Boolean
        ): java.net.Socket =
            configure(
                delegate.createSocket(
                    socket,
                    host,
                    port,
                    autoClose
                )
            )

        override fun createSocket(
            host: String,
            port: Int
        ): java.net.Socket =
            configure(
                delegate.createSocket(
                    host,
                    port
                )
            )

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int
        ): java.net.Socket =
            configure(
                delegate.createSocket(
                    host,
                    port,
                    localHost,
                    localPort
                )
            )

        override fun createSocket(
            host: InetAddress,
            port: Int
        ): java.net.Socket =
            configure(
                delegate.createSocket(
                    host,
                    port
                )
            )

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int
        ): java.net.Socket =
            configure(
                delegate.createSocket(
                    address,
                    port,
                    localAddress,
                    localPort
                )
            )

        private fun configure(
            socket: java.net.Socket
        ): java.net.Socket {
            val ssl =
                socket as? SSLSocket
                    ?: return socket
            val parameters =
                ssl.sslParameters
                    ?: SSLParameters()

            parameters.serverNames =
                listOf(
                    SNIHostName(serverName)
                )
            ssl.sslParameters = parameters

            return ssl
        }
    }

    private data class CountryInfo(
        val code: String,
        val name: String
    )

    private data class LocationInfo(
        val countryId: Int,
        val countryCode: String,
        val countryName: String,
        val cityName: String
    )

    private data class NordServer(
        val station: String,
        val hostname: String,
        val publicKey: String,
        val load: Int
    )

    private data class CityServer(
        val location: LocationInfo,
        val server: NordServer
    )

    companion object {
        private const val COUNTRIES_URL =
            "https://api.nordvpn.com/v1/countries"
        private const val CREDENTIALS_URL =
            "https://api.nordvpn.com/v1/users/" +
                "services/credentials"
        private const val SERVERS_URL =
            "https://api.nordvpn.com/v2/servers" +
                "?limit=0&filters%5Bservers_" +
                "technologies%5D%5Bid%5D=35"
        private const val NORDLYNX_TECHNOLOGY_ID = 35
        private const val NORDLYNX_PORT = 51820
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 35_000
        private const val MAX_RESPONSE_BYTES =
            24 * 1024 * 1024
        private const val REQUEST_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 400L

        private val DOH_RESOLVER_IPS =
            listOf(
                "1.1.1.1",
                "1.0.0.1"
            )
    }
}
