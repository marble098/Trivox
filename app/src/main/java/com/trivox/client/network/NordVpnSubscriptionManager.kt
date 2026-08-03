package com.trivox.client.network

import com.trivox.client.data.ConfigProfile
import com.trivox.client.util.Diagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Imports one deterministic NordLynx profile for every distinct NordVPN
 * location returned by the country-filtered API. Network bootstrap is resolved
 * only through certificate-verified DNS-over-HTTPS endpoints, so a poisoned
 * Android/system resolver cannot redirect API credentials to a private host.
 */
class NordVpnSubscriptionManager {
    private val resolver = TrustedDohResolver()

    fun fetchAllCountries(
        token: String
    ): List<ConfigProfile> {
        val normalizedToken = token.trim()

        require(normalizedToken.isNotBlank()) {
            "NordVPN access token is missing"
        }

        val apiAddresses =
            resolver.resolveIpv4(API_HOST)
        val privateKey =
            fetchPrivateKey(
                token = normalizedToken,
                apiAddresses = apiAddresses
            )
        val countries =
            parseCountries(
                JSONArray(
                    request(
                        uri = URI(COUNTRIES_URL),
                        token = null,
                        resolvedAddresses = apiAddresses,
                        maxResponseBytes =
                            MAX_COUNTRIES_RESPONSE_BYTES
                    )
                )
            )

        check(countries.isNotEmpty()) {
            "NordVPN returned no countries"
        }

        val executor =
            Executors.newFixedThreadPool(
                COUNTRY_WORKERS
            ) { runnable ->
                Thread(
                    runnable,
                    "trivox-nord-country"
                ).apply {
                    isDaemon = true
                }
            }
        val completion =
            ExecutorCompletionService<
                CountryFetchResult
                >(executor)
        val failures =
            mutableListOf<String>()

        return try {
            countries.forEach { country ->
                completion.submit(
                    Callable {
                        runCatching {
                            CountryFetchResult(
                                country = country,
                                profiles =
                                    fetchCountryProfiles(
                                        country = country,
                                        privateKey =
                                            privateKey,
                                        apiAddresses =
                                            apiAddresses
                                    )
                            )
                        }.getOrElse {
                            CountryFetchResult(
                                country = country,
                                error = unwrap(it)
                            )
                        }
                    }
                )
            }

            val profiles =
                ArrayList<ConfigProfile>()
            val deadline =
                System.nanoTime() +
                    TimeUnit.SECONDS.toNanos(
                        ALL_COUNTRIES_TIMEOUT_SECONDS
                    )
            var completed = 0

            while (completed < countries.size) {
                val remaining =
                    deadline -
                        System.nanoTime()

                if (remaining <= 0L) {
                    break
                }

                val future =
                    completion.poll(
                        remaining,
                        TimeUnit.NANOSECONDS
                    ) ?: break
                completed += 1

                val result =
                    runCatching {
                        future.get()
                    }.getOrElse {
                        CountryFetchResult(
                            country = CountryInfo(
                                id = -1,
                                code = "unknown",
                                name = "Unknown"
                            ),
                            error = unwrap(it)
                        )
                    }

                if (result.error == null) {
                    profiles += result.profiles
                } else {
                    failures +=
                        "${result.country.code.ifBlank {
                            result.country.id.toString()
                        }}: ${result.error.message ?: result.error.javaClass.simpleName}"
                }
            }

            val timedOut =
                countries.size - completed

            if (timedOut > 0) {
                failures +=
                    "$timedOut country requests exceeded the global ${ALL_COUNTRIES_TIMEOUT_SECONDS}s deadline"
            }

            val distinct =
                profiles
                    .distinctBy(ConfigProfile::id)
                    .sortedWith(
                        compareBy<ConfigProfile> {
                            it.name.lowercase(
                                Locale.ROOT
                            )
                        }.thenBy {
                            it.server
                        }
                    )

            check(distinct.isNotEmpty()) {
                "NordVPN returned no usable NordLynx locations" +
                    failures
                        .takeIf(List<String>::isNotEmpty)
                        ?.joinToString(
                            prefix = " (",
                            postfix = ")",
                            limit = 5
                        )
                        .orEmpty()
            }

            if (failures.isNotEmpty()) {
                Diagnostics.warning(
                    "NordVPN imported ${distinct.size} locations; " +
                        "${failures.size} country issues: " +
                        failures.take(5)
                            .joinToString(" | ")
                )
            }

            distinct
        } finally {
            executor.shutdownNow()
            runCatching {
                executor.awaitTermination(
                    EXECUTOR_SHUTDOWN_WAIT_SECONDS,
                    TimeUnit.SECONDS
                )
            }
        }
    }

    private fun fetchCountryProfiles(
        country: CountryInfo,
        privateKey: String,
        apiAddresses: List<String>
    ): List<ConfigProfile> {
        if (Thread.currentThread().isInterrupted) {
            return emptyList()
        }

        val uri =
            URI(
                "$SERVERS_BY_COUNTRY_URL" +
                    country.id
            )
        val root =
            JSONObject(
                request(
                    uri = uri,
                    token = null,
                    resolvedAddresses =
                        apiAddresses,
                    maxResponseBytes =
                        MAX_COUNTRY_RESPONSE_BYTES
                )
            )
        val selections =
            NordVpnCatalogParser
                .selectBestLocationServers(
                    root = root,
                    fallbackCountry = country
                )
        val duplicateCityCounts =
            selections.groupingBy {
                it.location.cityName
                    .trim()
                    .lowercase(Locale.ROOT)
            }.eachCount()

        return selections.map { selected ->
            buildProfile(
                privateKey = privateKey,
                selected = selected,
                duplicateCity =
                    duplicateCityCounts[
                        selected.location.cityName
                            .trim()
                            .lowercase(Locale.ROOT)
                    ].orZero() > 1
            )
        }
    }

    private fun buildProfile(
        privateKey: String,
        selected: LocationSelection,
        duplicateCity: Boolean
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
                    "Location ${location.id}"
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

                if (duplicateCity) {
                    append(" • ")
                    append(
                        server.hostname
                            .ifBlank {
                                "location-${location.id}"
                            }
                    )
                }
            }
        val stableId =
            UUID.nameUUIDFromBytes(
                (
                    "trivox:nordvpn:" +
                        location.countryId +
                        ":" +
                        location.id
                    ).toByteArray(
                    StandardCharsets.UTF_8
                )
            ).toString()

        return ConfigProfile(
            id = stableId,
            name = name,
            protocol = "wireguard",
            server = server.station,
            port = NORDLYNX_PORT,
            raw = portableJson(outbound),
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
                                    .put(
                                        "network",
                                        "udp,tcp"
                                    )
                                    .put("port", "53")
                                    .put(
                                        "outboundTag",
                                        "direct"
                                    )
                            )
                            .put(
                                JSONObject()
                                    .put("type", "field")
                                    .put(
                                        "ip",
                                        privateNetworks
                                    )
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
        token: String,
        apiAddresses: List<String>
    ): String {
        val credentials =
            JSONObject(
                request(
                    uri =
                        URI(CREDENTIALS_URL),
                    token = token,
                    resolvedAddresses =
                        apiAddresses,
                    maxResponseBytes =
                        MAX_CREDENTIALS_RESPONSE_BYTES
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
        uri: URI,
        token: String?,
        resolvedAddresses: List<String>,
        maxResponseBytes: Int
    ): String {
        val host =
            uri.host
                ?: error(
                    "NordVPN API URL has no host"
                )

        check(
            host.equals(
                API_HOST,
                ignoreCase = true
            )
        ) {
            "Unexpected NordVPN API host"
        }
        check(resolvedAddresses.isNotEmpty()) {
            "NordVPN API has no trusted addresses"
        }

        var lastError: Throwable? = null

        repeat(REQUEST_ATTEMPTS) {
                attempt ->
            resolvedAddresses.forEach {
                    address ->
                if (
                    Thread.currentThread()
                        .isInterrupted
                ) {
                    throw InterruptedException(
                        "NordVPN update cancelled"
                    )
                }

                try {
                    return requestMapped(
                        uri = uri,
                        connectAddress =
                            address,
                        token = token,
                        maxResponseBytes =
                            maxResponseBytes
                    )
                } catch (
                    throwable: Throwable
                ) {
                    lastError = throwable

                    if (
                        throwable is
                        NonRetryableHttpException
                    ) {
                        throw throwable
                    }
                }
            }

            if (
                attempt + 1 <
                REQUEST_ATTEMPTS
            ) {
                try {
                    Thread.sleep(
                        RETRY_DELAY_MS *
                            (attempt + 1L)
                    )
                } catch (
                    interrupted:
                        InterruptedException
                ) {
                    Thread.currentThread()
                        .interrupt()
                    throw interrupted
                }
            }
        }

        throw IOException(
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
        token: String?,
        maxResponseBytes: Int
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
            connection.useCaches = false
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
                "Accept-Encoding",
                "identity"
            )
            connection.setRequestProperty(
                "Connection",
                "close"
            )
            connection.setRequestProperty(
                "User-Agent",
                "Trivox-NordVPN/3"
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
                    ?.use {
                        readLimited(
                            input = it,
                            limit =
                                if (
                                    status in
                                    200..299
                                ) {
                                    maxResponseBytes
                                } else {
                                    MAX_ERROR_RESPONSE_BYTES
                                }
                        )
                    }
                    .orEmpty()

            if (
                status !=
                HttpURLConnection.HTTP_OK
            ) {
                val message =
                    "NordVPN API returned HTTP $status" +
                        body
                            .take(160)
                            .replace(
                                Regex("\\s+"),
                                " "
                            )
                            .takeIf(String::isNotBlank)
                            ?.let {
                                ": $it"
                            }
                            .orEmpty()

                if (
                    status in 400..499 &&
                    status != 408 &&
                    status != 429
                ) {
                    throw NonRetryableHttpException(
                        message
                    )
                }

                throw IOException(message)
            }

            check(body.isNotBlank()) {
                "NordVPN API returned an empty response"
            }

            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(
        input: java.io.InputStream,
        limit: Int
    ): String {
        val output =
            ByteArrayOutputStream()
        val buffer =
            ByteArray(16 * 1024)
        var total = 0

        while (true) {
            val count =
                input.read(buffer)

            if (count < 0) {
                break
            }

            total += count

            check(total <= limit) {
                "NordVPN API response exceeded " +
                    "$limit bytes"
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

    private fun parseCountries(
        values: JSONArray
    ): List<CountryInfo> {
        val result =
            ArrayList<CountryInfo>()

        for (
            index in
            0 until values.length()
        ) {
            val country =
                values.optJSONObject(index)
                    ?: continue
            val id =
                country.optInt("id", -1)
            val code =
                country.optString("code")
                    .trim()
                    .uppercase(Locale.ROOT)
            val name =
                country.optString("name")
                    .trim()

            if (
                id < 0 ||
                code.length != 2 ||
                name.isBlank()
            ) {
                continue
            }

            result +=
                CountryInfo(
                    id = id,
                    code = code,
                    name = name
                )
        }

        return result
            .distinctBy(CountryInfo::id)
            .sortedBy {
                it.name.lowercase(
                    Locale.ROOT
                )
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
        when (value.trim()) {
            "United Arab Emirates" ->
                "Emirates"

            else -> value.trim()
        }

    private fun countryFlag(
        countryCode: String
    ): String {
        val code =
            countryCode.uppercase(
                Locale.ROOT
            )

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

    private fun unwrap(
        throwable: Throwable
    ): Throwable {
        var current =
            if (
                throwable is ExecutionException
            ) {
                throwable.cause
                    ?: throwable
            } else {
                throwable
            }
        val visited =
            HashSet<Throwable>()

        while (
            current.cause != null &&
            visited.add(current)
        ) {
            current = current.cause!!
        }

        return current
    }

    private fun Int?.orZero(): Int =
        this ?: 0

    private class NonRetryableHttpException(
        message: String
    ) : IOException(message)

    companion object {
        private const val API_HOST =
            "api.nordvpn.com"
        private const val COUNTRIES_URL =
            "https://api.nordvpn.com/v1/countries"
        private const val CREDENTIALS_URL =
            "https://api.nordvpn.com/v1/users/" +
                "services/credentials"
        private const val SERVERS_BY_COUNTRY_URL =
            "https://api.nordvpn.com/v2/servers" +
                "?limit=0" +
                "&filters%5Bservers_technologies%5D" +
                "%5Bid%5D=35" +
                "&filters%5Bcountry_id%5D="
        private const val NORDLYNX_PORT = 51820
        private const val COUNTRY_WORKERS = 4
        private const val ALL_COUNTRIES_TIMEOUT_SECONDS =
            240L
        private const val EXECUTOR_SHUTDOWN_WAIT_SECONDS =
            2L
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 25_000
        private const val REQUEST_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 350L
        private const val MAX_CREDENTIALS_RESPONSE_BYTES =
            1024 * 1024
        private const val MAX_COUNTRIES_RESPONSE_BYTES =
            2 * 1024 * 1024
        private const val MAX_COUNTRY_RESPONSE_BYTES =
            8 * 1024 * 1024
        private const val MAX_ERROR_RESPONSE_BYTES =
            32 * 1024
    }
}

private data class CountryFetchResult(
    val country: CountryInfo,
    val profiles: List<ConfigProfile> =
        emptyList(),
    val error: Throwable? = null
)

internal data class CountryInfo(
    val id: Int,
    val code: String,
    val name: String
)

internal data class NordLocation(
    val id: Int,
    val countryId: Int,
    val countryCode: String,
    val countryName: String,
    val cityId: Int,
    val cityName: String
)

internal data class NordServer(
    val station: String,
    val hostname: String,
    val publicKey: String,
    val load: Int
)

internal data class LocationSelection(
    val location: NordLocation,
    val server: NordServer
)

/**
 * Pure catalog parser kept separate from networking so schema changes can be
 * covered by unit tests. It supports the current Nord schema used by 3x-ui:
 * `location.country.city`, with a legacy top-level city fallback.
 */
internal object NordVpnCatalogParser {
    fun selectBestLocationServers(
        root: JSONObject,
        fallbackCountry: CountryInfo
    ): List<LocationSelection> {
        val locations =
            parseLocations(
                values =
                    root.optJSONArray(
                        "locations"
                    ) ?: JSONArray(),
                fallbackCountry =
                    fallbackCountry
            )
        val selected =
            LinkedHashMap<Int, LocationSelection>()
        val servers =
            root.optJSONArray(
                "servers"
            ) ?: JSONArray()

        for (
            index in
            0 until servers.length()
        ) {
            val value =
                servers.optJSONObject(index)
                    ?: continue
            val status =
                value.optString("status")
                    .trim()

            if (
                status.isNotBlank() &&
                !status.equals(
                    "online",
                    ignoreCase = true
                )
            ) {
                continue
            }

            val station =
                value.optString("station")
                    .trim()
                    .takeIf(
                        ::isPublicLiteralAddress
                    ) ?: continue
            val publicKey =
                nordLynxPublicKey(
                    value.optJSONArray(
                        "technologies"
                    ) ?: JSONArray()
                )

            if (publicKey.isBlank()) {
                continue
            }

            val server =
                NordServer(
                    station = station,
                    hostname =
                        value.optString(
                            "hostname"
                        ).trim(),
                    publicKey = publicKey,
                    load =
                        value.optInt(
                            "load",
                            100
                        ).coerceIn(
                            0,
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
                val locationId =
                    locationIds.optInt(
                        locationIndex,
                        -1
                    )
                val location =
                    locations[locationId]
                        ?: continue
                val previous =
                    selected[locationId]
                val candidate =
                    LocationSelection(
                        location = location,
                        server = server
                    )

                if (
                    previous == null ||
                    isBetter(
                        candidate,
                        previous
                    )
                ) {
                    selected[locationId] =
                        candidate
                }
            }
        }

        return selected.values
            .sortedWith(
                compareBy<LocationSelection> {
                    it.location.cityName
                        .lowercase(Locale.ROOT)
                }.thenBy {
                    it.location.id
                }.thenBy {
                    it.server.load
                }
            )
    }

    internal fun parseLocations(
        values: JSONArray,
        fallbackCountry: CountryInfo
    ): Map<Int, NordLocation> {
        val result =
            LinkedHashMap<Int, NordLocation>()

        for (
            index in
            0 until values.length()
        ) {
            val location =
                values.optJSONObject(index)
                    ?: continue
            val locationId =
                location.optInt("id", -1)

            if (locationId < 0) {
                continue
            }

            val countryObject =
                location.optJSONObject(
                    "country"
                )
            val countryId =
                countryObject
                    ?.optInt(
                        "id",
                        fallbackCountry.id
                    )
                    ?: fallbackCountry.id
            val cityObject =
                countryObject
                    ?.optJSONObject("city")
                    ?: location
                        .optJSONObject("city")
            val cityId =
                cityObject
                    ?.optInt("id", -1)
                    ?: -1
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
                    .ifBlank {
                        "Location $locationId"
                    }
            val countryCode =
                countryObject
                    ?.optString("code")
                    ?.trim()
                    ?.uppercase(Locale.ROOT)
                    ?.takeIf(String::isNotBlank)
                    ?: fallbackCountry.code
            val countryName =
                countryObject
                    ?.optString("name")
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: fallbackCountry.name

            result[locationId] =
                NordLocation(
                    id = locationId,
                    countryId = countryId,
                    countryCode = countryCode,
                    countryName = countryName,
                    cityId = cityId,
                    cityName = cityName
                )
        }

        return result
    }

    private fun isBetter(
        candidate: LocationSelection,
        current: LocationSelection
    ): Boolean =
        candidate.server.load <
            current.server.load ||
            (
                candidate.server.load ==
                current.server.load &&
                candidate.server.hostname <
                current.server.hostname
                )

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
                technology.optInt(
                    "id",
                    -1
                ) != NORDLYNX_TECHNOLOGY_ID
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

    private fun isPublicLiteralAddress(
        value: String
    ): Boolean {
        val candidate =
            value
                .removePrefix("[")
                .removeSuffix("]")
                .trim()
        val looksLiteral =
            IPV4_PATTERN.matches(candidate) ||
                ':' in candidate

        if (!looksLiteral) {
            return false
        }

        val address =
            runCatching {
                InetAddress.getByName(
                    candidate
                )
            }.getOrNull()
                ?: return false

        return !isPrivateOrLocal(
            address
        )
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
                        ) ||
                    bytes[0] >= 224
            }

            is Inet6Address -> {
                val bytes =
                    address.address
                val isUniqueLocal =
                    bytes.isNotEmpty() &&
                        (
                            bytes[0].toInt() and
                                0xfe
                            ) == 0xfc
                val isDocumentation =
                    bytes.size == 16 &&
                        bytes[0].toInt() and 0xff ==
                        0x20 &&
                        bytes[1].toInt() and 0xff ==
                        0x01 &&
                        bytes[2].toInt() and 0xff ==
                        0x0d &&
                        bytes[3].toInt() and 0xff ==
                        0xb8

                address.isIPv4CompatibleAddress ||
                    isUniqueLocal ||
                    isDocumentation
            }

            else -> true
        }
    }

    private const val NORDLYNX_TECHNOLOGY_ID =
        35
    private val IPV4_PATTERN =
        Regex(
            """(?:\d{1,3}\.){3}\d{1,3}"""
        )
}

/**
 * Resolves API hostnames through multiple certificate-verified DoH providers.
 * Only literal A answers are accepted. CNAME data is never passed back to the
 * system resolver, preventing the poisoning path visible in diagnostics.
 */
internal class TrustedDohResolver {
    fun resolveIpv4(
        host: String
    ): List<String> {
        val normalized =
            host.trim()
                .lowercase(Locale.ROOT)
        val answers =
            PROVIDERS.mapNotNull {
                    provider ->
                runCatching {
                    query(
                        provider,
                        normalized
                    )
                }.getOrNull()
                    ?.takeIf(
                        Set<String>::isNotEmpty
                    )
            }

        check(answers.isNotEmpty()) {
            "Trusted DNS could not resolve $host"
        }

        val consensus =
            answers
                .drop(1)
                .fold(
                    answers.first()
                        .toMutableSet()
                ) {
                        common,
                        current ->
                    common.apply {
                        retainAll(current)
                    }
                }
        val selected =
            if (consensus.isNotEmpty()) {
                consensus
            } else {
                answers.flatten()
                    .toSet()
            }

        return selected
            .filter(::isStrictPublicIpv4)
            .distinct()
            .take(MAX_API_ADDRESSES)
            .also {
                check(it.isNotEmpty()) {
                    "Trusted DNS returned no public IPv4 addresses for $host"
                }
            }
    }

    private fun query(
        provider: DohProvider,
        host: String
    ): Set<String> {
        val encoded =
            URLEncoder.encode(
                host,
                StandardCharsets.UTF_8.name()
            )
        var lastError: Throwable? = null

        provider.addresses.forEach {
                address ->
            try {
                val mapped =
                    URL(
                        "https://$address" +
                            provider.path +
                            "?name=$encoded&type=A"
                    )
                val connection =
                    mapped.openConnection() as
                        HttpsURLConnection

                try {
                    connection.connectTimeout =
                        DOH_CONNECT_TIMEOUT_MS
                    connection.readTimeout =
                        DOH_READ_TIMEOUT_MS
                    connection.instanceFollowRedirects =
                        false
                    connection.requestMethod = "GET"
                    connection.useCaches = false
                    connection.sslSocketFactory =
                        SniSocketFactory(
                            HttpsURLConnection
                                .getDefaultSSLSocketFactory(),
                            provider.host
                        )
                    connection.hostnameVerifier =
                        HostnameVerifier {
                                _,
                                session ->
                            HttpsURLConnection
                                .getDefaultHostnameVerifier()
                                .verify(
                                    provider.host,
                                    session
                                )
                        }
                    connection.setRequestProperty(
                        "Host",
                        provider.host
                    )
                    connection.setRequestProperty(
                        "Accept",
                        "application/dns-json"
                    )
                    connection.setRequestProperty(
                        "Accept-Encoding",
                        "identity"
                    )
                    connection.setRequestProperty(
                        "Connection",
                        "close"
                    )

                    val status =
                        connection.responseCode

                    if (
                        status !=
                        HttpURLConnection.HTTP_OK
                    ) {
                        throw IOException(
                            "DoH ${provider.host} returned HTTP $status"
                        )
                    }

                    val body =
                        connection.inputStream
                            .use {
                                readLimited(
                                    input = it,
                                    limit =
                                        MAX_DOH_RESPONSE_BYTES
                                )
                            }
                    val root =
                        JSONObject(body)

                    if (
                        root.optInt(
                            "Status",
                            -1
                        ) != 0
                    ) {
                        throw IOException(
                            "DoH ${provider.host} returned DNS status " +
                                root.optInt("Status")
                        )
                    }

                    val result =
                        LinkedHashSet<String>()
                    val answer =
                        root.optJSONArray(
                            "Answer"
                        ) ?: JSONArray()

                    for (
                        index in
                        0 until answer.length()
                    ) {
                        val item =
                            answer.optJSONObject(
                                index
                            ) ?: continue

                        if (
                            item.optInt(
                                "type",
                                -1
                            ) != 1
                        ) {
                            continue
                        }

                        val value =
                            item.optString(
                                "data"
                            ).trim()

                        if (
                            isStrictPublicIpv4(
                                value
                            )
                        ) {
                            result += value
                        }
                    }

                    if (result.isNotEmpty()) {
                        return result
                    }

                    throw IOException(
                        "DoH ${provider.host} returned no public A answers"
                    )
                } finally {
                    connection.disconnect()
                }
            } catch (
                throwable: Throwable
            ) {
                lastError = throwable
            }
        }

        throw IOException(
            "DoH ${provider.host} failed: " +
                (
                    lastError?.message
                        ?: "unavailable"
                    ),
            lastError
        )
    }

    private fun readLimited(
        input: java.io.InputStream,
        limit: Int
    ): String {
        val output =
            ByteArrayOutputStream()
        val buffer =
            ByteArray(4 * 1024)
        var total = 0

        while (true) {
            val count =
                input.read(buffer)

            if (count < 0) {
                break
            }

            total += count
            check(total <= limit) {
                "DoH response exceeded the size limit"
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

    private data class DohProvider(
        val host: String,
        val path: String,
        val addresses: List<String>
    )

    companion object {
        private const val DOH_CONNECT_TIMEOUT_MS =
            5_000
        private const val DOH_READ_TIMEOUT_MS =
            7_000
        private const val MAX_DOH_RESPONSE_BYTES =
            128 * 1024
        private const val MAX_API_ADDRESSES = 4

        private val PROVIDERS =
            listOf(
                DohProvider(
                    host =
                        "cloudflare-dns.com",
                    path = "/dns-query",
                    addresses =
                        listOf(
                            "1.1.1.1",
                            "1.0.0.1"
                        )
                ),
                DohProvider(
                    host = "dns.google",
                    path = "/resolve",
                    addresses =
                        listOf(
                            "8.8.8.8",
                            "8.8.4.4"
                        )
                )
            )
    }
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

private fun isStrictPublicIpv4(
    value: String
): Boolean {
    val parts =
        value.split('.')

    if (
        parts.size != 4 ||
        parts.any {
            it.isBlank() ||
                (
                    it.length > 1 &&
                    it.startsWith('0')
                    ) ||
                it.toIntOrNull() !in 0..255
        }
    ) {
        return false
    }

    val bytes =
        parts.map(String::toInt)

    return !(
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
                ) ||
            bytes[0] >= 224
        )
}
