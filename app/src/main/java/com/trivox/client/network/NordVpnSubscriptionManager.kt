package com.trivox.client.network

import android.util.Base64
import com.trivox.client.data.ConfigProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

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
            JSONArray(
                request(
                    COUNTRIES_URL
                )
            )
        val serverData =
            JSONObject(
                request(
                    SERVERS_URL
                )
            )
        val locationCountries =
            locationCountryMap(
                serverData
                    .optJSONArray(
                        "locations"
                    )
                    ?: JSONArray()
            )
        val bestServers =
            selectBestServers(
                serverData
                    .optJSONArray(
                        "servers"
                    )
                    ?: JSONArray(),
                locationCountries
            )
        val profiles =
            ArrayList<ConfigProfile>()

        for (
            index in
            0 until countries.length()
        ) {
            val country =
                countries
                    .optJSONObject(index)
                    ?: continue
            val countryId =
                country.optInt(
                    "id",
                    -1
                )
            val server =
                bestServers[
                    countryId
                ] ?: continue
            val code =
                country
                    .optString("code")
                    .uppercase()
            val name =
                country
                    .optString(
                        "name",
                        code
                    )
            val endpoint =
                "${server.station}:" +
                    NORDLYNX_PORT
            val outbound =
                JSONObject()
                    .put(
                        "tag",
                        "proxy"
                    )
                    .put(
                        "protocol",
                        "wireguard"
                    )
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
                                    .put(
                                        "10.5.0.2/32"
                                    )
                            )
                            .put(
                                "peers",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put(
                                                "publicKey",
                                                server
                                                    .publicKey
                                            )
                                            .put(
                                                "endpoint",
                                                endpoint
                                            )
                                    )
                            )
                            .put(
                                "noKernelTun",
                                true
                            )
                    )
            val raw =
                JSONObject()
                    .put(
                        "outbounds",
                        JSONArray()
                            .put(
                                JSONObject(
                                    outbound
                                        .toString()
                                )
                            )
                    )
                    .toString()

            profiles +=
                ConfigProfile(
                    name =
                        buildString {
                            val flag =
                                countryFlag(
                                    code
                                )
                            if (
                                flag.isNotBlank()
                            ) {
                                append(flag)
                                append(' ')
                            }
                            append(
                                "NordVPN "
                            )
                            append(name)
                        },
                    protocol =
                        "wireguard",
                    server =
                        server.station,
                    port =
                        NORDLYNX_PORT,
                    raw = raw,
                    outboundJson =
                        outbound.toString()
                )
        }

        check(profiles.isNotEmpty()) {
            "NordVPN returned no usable NordLynx servers"
        }

        return profiles
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
        val connection =
            URL(url)
                .openConnection() as
                HttpsURLConnection

        try {
            connection
                .connectTimeout =
                CONNECT_TIMEOUT_MS
            connection
                .readTimeout =
                READ_TIMEOUT_MS
            connection
                .instanceFollowRedirects =
                false
            connection
                .requestMethod =
                "GET"
            connection
                .setRequestProperty(
                    "Accept",
                    "application/json"
                )
            connection
                .setRequestProperty(
                    "User-Agent",
                    "Trivox-NordVPN/1"
                )

            if (token != null) {
                val credentials =
                    "token:$token"
                        .toByteArray(
                            Charsets.UTF_8
                        )

                connection
                    .setRequestProperty(
                        "Authorization",
                        "Basic " +
                            Base64
                                .encodeToString(
                                    credentials,
                                    Base64
                                        .NO_WRAP
                                )
                    )
            }

            val status =
                connection.responseCode
            val stream =
                if (
                    status in
                    200..299
                ) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
            val body =
                stream
                    ?.use(
                        ::readLimited
                    )
                    .orEmpty()

            if (
                status !=
                HttpURLConnection
                    .HTTP_OK
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

    private fun readLimited(
        input:
            java.io.InputStream
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
                total <=
                MAX_RESPONSE_BYTES
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
            .toString(
                Charsets.UTF_8
            )
    }

    private fun locationCountryMap(
        locations: JSONArray
    ): Map<Int, Int> {
        val result =
            HashMap<Int, Int>()

        for (
            index in
            0 until locations.length()
        ) {
            val location =
                locations
                    .optJSONObject(index)
                    ?: continue
            val locationId =
                location.optInt(
                    "id",
                    -1
                )
            val countryId =
                location
                    .optJSONObject(
                        "country"
                    )
                    ?.optInt(
                        "id",
                        -1
                    )
                    ?: -1

            if (
                locationId >= 0 &&
                countryId >= 0
            ) {
                result[
                    locationId
                ] = countryId
            }
        }

        return result
    }

    private fun selectBestServers(
        servers: JSONArray,
        locationCountries:
            Map<Int, Int>
    ): Map<Int, NordServer> {
        val result =
            HashMap<
                Int,
                NordServer
                >()

        for (
            index in
            0 until servers.length()
        ) {
            val value =
                servers
                    .optJSONObject(index)
                    ?: continue
            val station =
                value
                    .optString(
                        "station"
                    )
                    .trim()
            val hostname =
                value
                    .optString(
                        "hostname"
                    )
                    .trim()
            val publicKey =
                nordLynxPublicKey(
                    value
                        .optJSONArray(
                            "technologies"
                        )
                        ?: JSONArray()
                )
            val locationIds =
                value
                    .optJSONArray(
                        "location_ids"
                    )
                    ?: JSONArray()
            val countryId =
                (
                    0 until
                        locationIds
                            .length()
                    )
                    .asSequence()
                    .map {
                        locationIds
                            .optInt(
                                it,
                                -1
                            )
                    }
                    .mapNotNull {
                        locationCountries[
                            it
                        ]
                    }
                    .firstOrNull()
                    ?: continue

            if (
                station.isBlank() ||
                publicKey.isBlank()
            ) {
                continue
            }

            val candidate =
                NordServer(
                    station =
                        station,
                    hostname =
                        hostname,
                    publicKey =
                        publicKey,
                    load =
                        value.optInt(
                            "load",
                            100
                        )
                )
            val previous =
                result[countryId]

            if (
                previous == null ||
                candidate.load <
                previous.load ||
                (
                    candidate.load ==
                    previous.load &&
                    candidate.hostname <
                    previous.hostname
                    )
            ) {
                result[countryId] =
                    candidate
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
                technologies
                    .optJSONObject(index)
                    ?: continue

            if (
                technology.optInt(
                    "id",
                    -1
                ) !=
                NORDLYNX_TECHNOLOGY_ID
            ) {
                continue
            }

            val metadata =
                technology
                    .optJSONArray(
                        "metadata"
                    )
                    ?: JSONArray()

            for (
                item in
                0 until metadata.length()
            ) {
                val entry =
                    metadata
                        .optJSONObject(
                            item
                        )
                        ?: continue

                if (
                    entry
                        .optString(
                            "name"
                        )
                        .equals(
                            "public_key",
                            ignoreCase =
                                true
                        )
                ) {
                    return entry
                        .optString(
                            "value"
                        )
                        .trim()
                }
            }
        }

        return ""
    }

    private fun countryFlag(
        countryCode: String
    ): String {
        if (
            countryCode.length != 2 ||
            countryCode.any {
                it !in 'A'..'Z'
            }
        ) {
            return ""
        }

        return countryCode
            .map {
                Character
                    .toChars(
                        0x1F1E6 +
                            (
                                it.code -
                                    'A'.code
                                )
                    )
                    .concatToString()
            }
            .joinToString("")
    }

    private data class NordServer(
        val station: String,
        val hostname: String,
        val publicKey: String,
        val load: Int
    )

    companion object {
        private const val
            COUNTRIES_URL =
            "https://api.nordvpn.com/" +
                "v1/countries"
        private const val
            CREDENTIALS_URL =
            "https://api.nordvpn.com/" +
                "v1/users/services/" +
                "credentials"
        private const val
            SERVERS_URL =
            "https://api.nordvpn.com/" +
                "v2/servers?limit=0&" +
                "filters%5Bservers_" +
                "technologies%5D%5Bid%5D=35"
        private const val
            NORDLYNX_TECHNOLOGY_ID =
            35
        private const val
            NORDLYNX_PORT =
            51820
        private const val
            CONNECT_TIMEOUT_MS =
            15_000
        private const val
            READ_TIMEOUT_MS =
            30_000
        private const val
            MAX_RESPONSE_BYTES =
            10 * 1024 * 1024
    }
}
