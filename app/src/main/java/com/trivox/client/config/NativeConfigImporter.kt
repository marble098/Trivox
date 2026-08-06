package com.trivox.client.config

import com.trivox.client.data.ConfigProfile

object NativeConfigImporter {
    fun nativeInstallSubscriptionUrl(input: String): String? = null
    fun parseTextOrNull(input: String): List<ConfigProfile>? = null
    fun convertibleProfiles(input: String): List<ConfigProfile> = emptyList()
}
