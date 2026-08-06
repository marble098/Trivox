package com.trivox.client.config

import com.trivox.client.data.ConfigProfile

object NativeProfileDocument {
    data class Parsed(
        val profiles: List<ConfigProfile>,
        val errors: List<String> = emptyList(),
        val kind: String = "xray"
    ) : List<ConfigProfile> by profiles {
        val configs: List<ConfigProfile> get() = profiles
        val items: List<ConfigProfile> get() = profiles
        val profileCount: Int get() = profiles.size
        val hasProfiles: Boolean get() = profiles.isNotEmpty()
    }

    fun parse(input: String): Parsed = Parsed(ConfigParser.parseText(input))

    fun parseOrNull(input: String): Parsed? = runCatching { parse(input) }.getOrNull()

    fun fromText(input: String): Parsed = parse(input)

    fun fromJson(input: String): Parsed = parse(input)

    fun parseProfiles(input: String): List<ConfigProfile> = parse(input).profiles

    fun convertibleProfiles(input: String): List<ConfigProfile> = parseOrNull(input)?.profiles.orEmpty()

    fun isNativeProfile(input: String): Boolean = parseOrNull(input)?.profiles?.isNotEmpty() == true
}
