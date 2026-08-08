package com.trivox.client.importing

import android.content.Context
import com.trivox.client.config.ConfigParser
import com.trivox.client.config.OpenSshProfileCodec
import com.trivox.client.data.ConfigRepository
import com.trivox.client.data.ProfileImportResult
import com.trivox.client.data.SubscriptionKind
import com.trivox.client.data.SubscriptionRepository
import com.trivox.client.data.SubscriptionSource
import com.trivox.client.network.NordVpnSubscriptionManager
import com.trivox.client.network.SubscriptionManager
import com.trivox.client.util.Diagnostics
import com.trivox.client.util.SecretStore
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

object SmartClipboardImporter {
    enum class Kind {
        URL_SUBSCRIPTION,
        CONFIGS,
        NORDVPN
    }

    data class Result(
        val kind: Kind,
        val sourceId: String? = null,
        val sourceName: String = "",
        val received: Int = 0,
        val added: Int = 0,
        val updated: Int = 0,
        val moved: Int = 0,
        val skipped: Int = 0,
        val warningCount: Int = 0
    ) {
        val changed: Int
            get() = added + updated + moved
    }

    fun import(
        context: Context,
        rawText: String,
        targetSubscriptionId: String?
    ): Result {
        val appContext = context.applicationContext
        val text = rawText.trim()
        require(text.isNotBlank()) { "Clipboard is empty" }
        require(text.length <= MAX_INPUT_CHARS) { "Clipboard content is too large" }

        val configRepository = ConfigRepository(appContext)
        val sourceRepository = SubscriptionRepository(appContext)
        val singleLine = text.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()
            .singleOrNull()
        var subscriptionFailure: Throwable? = null

        if (singleLine != null && looksLikeWebUrl(singleLine)) {
            val subscriptionResult = runCatching {
                importUrlSubscription(
                    context = appContext,
                    rawUrl = singleLine,
                    configRepository = configRepository,
                    sourceRepository = sourceRepository
                )
            }
            if (subscriptionResult.isSuccess) {
                return checkNotNull(subscriptionResult.getOrNull())
            }
            subscriptionFailure = subscriptionResult.exceptionOrNull()
            Diagnostics.debug(
                "Magic import URL did not resolve as a subscription: " +
                    (subscriptionFailure?.message ?: "unknown")
            )
        }

        val configResult = runCatching {
            val destination = targetSubscriptionId?.let { id ->
                sourceRepository.find(id)
                    ?: error("The selected subscription no longer exists")
            }
            val parsed = ConfigParser.parseText(text)
            check(parsed.isNotEmpty()) { "No supported configurations were found" }
            val secured = parsed.map { profile ->
                OpenSshProfileCodec.secureForStorage(appContext, profile)
            }
            val imported = configRepository.importProfiles(
                profiles = secured,
                subscriptionId = destination?.id,
                groupName = destination?.name
            )
            imported.toResult(
                kind = Kind.CONFIGS,
                sourceId = destination?.id,
                sourceName = destination?.name.orEmpty()
            )
        }
        if (configResult.isSuccess) {
            return checkNotNull(configResult.getOrNull())
        }

        if (singleLine != null && looksLikeNordToken(singleLine)) {
            return importNordVpn(
                context = appContext,
                token = singleLine,
                configRepository = configRepository,
                sourceRepository = sourceRepository
            )
        }

        val configError = configResult.exceptionOrNull()
        throw IllegalArgumentException(
            listOfNotNull(
                configError?.message,
                subscriptionFailure?.message
            ).firstOrNull { !it.isNullOrBlank() }
                ?: "Clipboard content is not a supported subscription, configuration, or NordVPN token"
        )
    }

    private fun importUrlSubscription(
        context: Context,
        rawUrl: String,
        configRepository: ConfigRepository,
        sourceRepository: SubscriptionRepository
    ): Result {
        val normalized = SubscriptionManager.normalizeUrl(rawUrl)
        val fetched = SubscriptionManager().fetch(normalized)
        check(fetched.profiles.isNotEmpty()) { "Subscription returned no supported configurations" }

        val existing = sourceRepository.all().firstOrNull { source ->
            source.kind == SubscriptionKind.URL &&
                runCatching {
                    SubscriptionManager.normalizeUrl(source.url) ==
                        SubscriptionManager.normalizeUrl(fetched.finalUrl)
                }.getOrDefault(false)
        } ?: sourceRepository.all().firstOrNull { source ->
            source.kind == SubscriptionKind.URL &&
                runCatching {
                    SubscriptionManager.normalizeUrl(source.url) == normalized
                }.getOrDefault(false)
        }

        val id = existing?.id ?: UUID.randomUUID().toString()
        val name = existing?.name
            ?.takeIf(String::isNotBlank)
            ?: subscriptionDisplayName(rawUrl)
        val secured = fetched.profiles.map { profile ->
            OpenSshProfileCodec.secureForStorage(context, profile).copy(
                subscriptionId = id,
                group = name
            )
        }
        configRepository.replaceSubscription(id, secured)

        val source = (existing ?: SubscriptionSource(
            id = id,
            name = name,
            url = fetched.finalUrl,
            kind = SubscriptionKind.URL
        )).copy(
            name = name,
            url = fetched.finalUrl,
            enabled = existing?.enabled ?: true,
            lastSuccessAt = System.currentTimeMillis(),
            lastError = ""
        )
        sourceRepository.save(source)

        return Result(
            kind = Kind.URL_SUBSCRIPTION,
            sourceId = id,
            sourceName = name,
            received = secured.size,
            added = secured.size
        )
    }

    private fun importNordVpn(
        context: Context,
        token: String,
        configRepository: ConfigRepository,
        sourceRepository: SubscriptionRepository
    ): Result {
        val catalog = NordVpnSubscriptionManager().fetchCatalog(token)
        check(catalog.profiles.isNotEmpty()) { "NordVPN returned no usable locations" }

        val existing = sourceRepository.all()
            .firstOrNull { it.kind == SubscriptionKind.NORDVPN }
        val id = existing?.id ?: UUID.randomUUID().toString()
        val name = existing?.name
            ?.takeIf(String::isNotBlank)
            ?: "NordVPN"
        val secretAlias = existing?.secretAlias
            ?.takeIf(String::isNotBlank)
            ?: "nordvpn_$id"

        SecretStore.put(context, secretAlias, token)

        val profiles = catalog.profiles.map { profile ->
            OpenSshProfileCodec.secureForStorage(context, profile).copy(
                subscriptionId = id,
                group = name
            )
        }
        if (catalog.complete) {
            configRepository.replaceSubscription(id, profiles)
        } else {
            configRepository.mergeSubscription(id, profiles)
        }

        val source = (existing ?: SubscriptionSource(
            id = id,
            name = name,
            url = "nordvpn://all-countries",
            kind = SubscriptionKind.NORDVPN,
            secretAlias = secretAlias
        )).copy(
            name = name,
            url = "nordvpn://all-countries",
            enabled = existing?.enabled ?: true,
            lastSuccessAt = System.currentTimeMillis(),
            lastError = catalog.warnings.take(3).joinToString(" • "),
            kind = SubscriptionKind.NORDVPN,
            secretAlias = secretAlias
        )
        sourceRepository.save(source)

        return Result(
            kind = Kind.NORDVPN,
            sourceId = id,
            sourceName = name,
            received = profiles.size,
            added = profiles.size,
            warningCount = catalog.warnings.size
        )
    }

    private fun ProfileImportResult.toResult(
        kind: Kind,
        sourceId: String?,
        sourceName: String
    ): Result = Result(
        kind = kind,
        sourceId = sourceId,
        sourceName = sourceName,
        received = received,
        added = added,
        updated = updated,
        moved = moved,
        skipped = skipped
    )

    private fun looksLikeWebUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null
    }

    private fun looksLikeNordToken(value: String): Boolean =
        value.length in 16..512 &&
            "://" !in value &&
            !value.any(Char::isWhitespace) &&
            value.matches(Regex("[A-Za-z0-9._~-]+"))

    private fun subscriptionDisplayName(rawUrl: String): String {
        val uri = runCatching { URI(rawUrl) }.getOrNull()
        val fragment = uri?.rawFragment
            ?.takeIf(String::isNotBlank)
            ?.let {
                runCatching {
                    URLDecoder.decode(it, StandardCharsets.UTF_8.name())
                }.getOrDefault(it)
            }
            ?.trim()
            .orEmpty()

        return fragment.takeIf(String::isNotBlank)
            ?: uri?.host
                ?.takeIf(String::isNotBlank)
            ?: "Subscription"
    }

    private const val MAX_INPUT_CHARS = 4 * 1024 * 1024
}
