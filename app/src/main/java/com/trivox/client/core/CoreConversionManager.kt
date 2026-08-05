package com.trivox.client.core

import android.content.Context
import com.trivox.client.config.NativeProfileDocument
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConfigRepository
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.CoreId
import com.trivox.client.data.SettingsRepository
import com.trivox.client.data.SubscriptionKind
import com.trivox.client.data.SubscriptionSource
import com.trivox.client.data.TestStatus
import com.trivox.client.network.SubscriptionExpansionManager
import com.trivox.client.util.Diagnostics
import java.util.UUID

/** Real target-core conversion with validation and native-document awareness. */
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
        val firstError: String = "",
        val warnings: List<String> = emptyList()
    )

    fun convertProfile(
        profile: ConfigProfile,
        target: CoreId,
        subscriptionId: String? = profile.subscriptionId
    ): Result<ConfigProfile> = runCatching {
        NativeProfileDocument.affinity(profile)?.let { required ->
            require(required == target) {
                "${profile.name} is a complete ${required.label} document. " +
                    "Its provider/outbound entries must be expanded before converting to ${target.label}."
            }
        }

        val settings = settingsRepository.load().copy().also {
            it.coreId = target
            it.smartCoreSelection = false
            it.mode = ConnectionMode.PROXY
        }
        val request = CoreStartRequest(profile, settings, ConnectionMode.PROXY)
        val native = CoreConfigTranslator.buildNativeProfile(profile, target, settings.socksPort)
        val runtime = CoreConfigTranslator.build(request, target)
        val validation = coreManager.validateWith(target, runtime)
        check(validation.success) {
            validation.error.ifBlank { "${target.label} rejected converted config" }
        }

        val canonicalXray = if (NativeProfileDocument.affinity(profile) == null) {
            CoreConfigTranslator.xrayOutboundFor(profile)
        } else {
            profile.outboundJson
        }
        profile.copy(
            id = UUID.randomUUID().toString(),
            name = profile.name.withCoreSuffix(target),
            raw = native,
            outboundJson = canonicalXray,
            subscriptionId = subscriptionId,
            group = profile.group.withCoreGroup(target),
            latencyMs = null,
            latencyJitterMs = null,
            latencySuccessRatio = 0.0,
            latencyMethod = "",
            testStatus = TestStatus.UNTESTED,
            lastTestAt = 0L,
            tcpLatencyMs = null,
            tcpLatencyJitterMs = null,
            tcpSuccessRatio = 0.0,
            tcpTestStatus = TestStatus.UNTESTED,
            tcpLastTestAt = 0L,
            realLatencyMs = null,
            realLatencyJitterMs = null,
            realSuccessRatio = 0.0,
            realTestStatus = TestStatus.UNTESTED,
            realLastTestAt = 0L,
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

    /**
     * Refetches URL subscriptions so provider-based Mihomo documents can be
     * expanded. Keeping the same core preserves the complete native document;
     * changing the core converts the provider proxies individually.
     */
    fun convertSubscriptionFromSource(source: SubscriptionSource, target: CoreId): ConversionSummary {
        if (source.kind != SubscriptionKind.URL) {
            return convertSubscription(
                source,
                repository.all().filter { it.subscriptionId == source.id },
                target
            )
        }

        val expansion = runCatching { SubscriptionExpansionManager().expand(source.url) }
            .getOrElse { error ->
                return ConversionSummary(
                    target = target,
                    total = 0,
                    added = 0,
                    failed = 1,
                    firstError = error.message ?: error.javaClass.simpleName
                )
            }

        val native = expansion.rootNative
        val nativeAffinity = native?.let(NativeProfileDocument::affinity)
        val input = when {
            native != null && nativeAffinity == target -> listOf(native)
            native != null && expansion.profiles.isEmpty() -> emptyList()
            else -> expansion.profiles
        }

        if (input.isEmpty()) {
            val reason = if (native != null && nativeAffinity != target) {
                "The native document has no expandable proxy entries/providers for ${target.label}."
            } else {
                "Subscription contains no convertible profiles."
            }
            return ConversionSummary(target, 0, 0, 1, reason, expansion.warnings)
        }

        val summary = convertProfiles(
            source = source,
            profiles = input,
            target = target,
            destinationSubscriptionId = null
        )
        return summary.copy(warnings = expansion.warnings)
    }

    fun convertSubscription(
        source: SubscriptionSource,
        profiles: List<ConfigProfile>,
        target: CoreId
    ): ConversionSummary = convertProfiles(source, profiles, target, source.id)

    private fun convertProfiles(
        source: SubscriptionSource,
        profiles: List<ConfigProfile>,
        target: CoreId,
        destinationSubscriptionId: String?
    ): ConversionSummary {
        val converted = mutableListOf<ConfigProfile>()
        var failed = 0
        var firstError = ""
        profiles.forEach { profile ->
            val result = convertProfile(profile, target, destinationSubscriptionId)
            if (result.isSuccess) {
                converted += result.getOrThrow()
            } else {
                failed += 1
                val error = result.exceptionOrNull()?.message.orEmpty()
                if (firstError.isBlank()) firstError = error
                Diagnostics.warning("Subscription conversion failed for ${profile.name}: $error")
            }
        }
        val import = repository.importProfiles(
            profiles = converted,
            subscriptionId = destinationSubscriptionId,
            groupName = "${source.name} • ${target.label}"
        )
        return ConversionSummary(
            target = target,
            total = profiles.size,
            added = import.changed,
            failed = failed,
            firstError = firstError
        )
    }

    private fun String.withCoreSuffix(coreId: CoreId): String {
        val suffix = "[${coreId.label}]"
        val cleaned = replace(
            Regex("\\s*\\[(Xray|sing-box|mihomo)]\\s*$", RegexOption.IGNORE_CASE),
            ""
        ).trim()
        return "$cleaned $suffix"
    }

    private fun String.withCoreGroup(coreId: CoreId): String {
        val cleaned = replace(
            Regex("\\s*•\\s*(Xray|sing-box|mihomo)\\s*$", RegexOption.IGNORE_CASE),
            ""
        ).trim().ifBlank { "Converted" }
        return "$cleaned • ${coreId.label}"
    }
}
