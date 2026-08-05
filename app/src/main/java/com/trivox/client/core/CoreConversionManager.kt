package com.trivox.client.core

import android.content.Context
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConfigRepository
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.CoreId
import com.trivox.client.data.SettingsRepository
import com.trivox.client.data.SubscriptionSource
import com.trivox.client.util.Diagnostics
import java.util.UUID

class CoreConversionManager(context: Context) {
    private val appContext = context.applicationContext
    private val settingsRepository = SettingsRepository(appContext)
    private val repository = ConfigRepository(appContext)
    private val coreManager = CoreManager(appContext)

    data class ConversionSummary(
        val target: CoreId,
        val total: Int,
        val added: Int,
        val failed: Int,
        val firstError: String = ""
    )

    fun convertProfile(profile: ConfigProfile, target: CoreId, subscriptionId: String? = profile.subscriptionId): Result<ConfigProfile> = runCatching {
        val settings = settingsRepository.load().copy().also {
            it.coreId = target
            it.smartCoreSelection = false
            it.mode = ConnectionMode.PROXY
        }
        val request = CoreStartRequest(profile, settings, ConnectionMode.PROXY)
        val native = CoreConfigTranslator.buildNativeProfile(profile, target, settings.socksPort)
        val runtime = CoreConfigTranslator.build(request, target)
        val validation = coreManager.validateWith(target, runtime)
        if (!validation.success) error(validation.error.ifBlank { "${target.label} rejected converted config" })
        profile.copy(
            id = UUID.randomUUID().toString(),
            name = profile.name.withCoreSuffix(target),
            raw = native,
            outboundJson = profile.outboundJson,
            subscriptionId = subscriptionId,
            latencyMs = null,
            latencyJitterMs = null,
            latencySuccessRatio = 0.0,
            lastTestAt = 0L,
            tcpLatencyMs = null,
            realLatencyMs = null,
            exitIp = "",
            exitCountry = "",
            exitCountryCode = "",
            exitFlag = "",
            exitIsp = "",
            lastExitCheckAt = 0L
        )
    }

    fun convertAndSave(profile: ConfigProfile, target: CoreId): Result<ConfigProfile> =
        convertProfile(profile, target).onSuccess { repository.save(it) }

    fun convertSubscription(source: SubscriptionSource, profiles: List<ConfigProfile>, target: CoreId): ConversionSummary {
        var added = 0
        var failed = 0
        var firstError = ""
        profiles.forEach { profile ->
            val result = convertProfile(profile, target, source.id)
            if (result.isSuccess) {
                repository.save(result.getOrThrow())
                added += 1
            } else {
                failed += 1
                if (firstError.isBlank()) firstError = result.exceptionOrNull()?.message.orEmpty()
                Diagnostics.warning("Subscription conversion failed for ${profile.name}: ${result.exceptionOrNull()?.message}")
            }
        }
        return ConversionSummary(target, profiles.size, added, failed, firstError)
    }

    private fun String.withCoreSuffix(coreId: CoreId): String {
        val suffix = "[${coreId.label}]"
        val cleaned = replace(Regex("\\s*\\[(Xray|sing-box|mihomo)]\\s*$", RegexOption.IGNORE_CASE), "").trim()
        return "$cleaned $suffix"
    }
}
