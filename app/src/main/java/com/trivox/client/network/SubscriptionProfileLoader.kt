package com.trivox.client.network

import android.content.Context
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.SubscriptionKind
import com.trivox.client.data.SubscriptionSource
import com.trivox.client.util.SecretStore

object SubscriptionProfileLoader {
    data class Result(
        val profiles: List<ConfigProfile>,
        val finalUrl: String,
        val complete: Boolean = true,
        val warnings: List<String> =
            emptyList(),
        val notModified: Boolean = false,
        val etag: String? = null,
        val lastModified: String? = null
    )

    fun load(
        context: Context,
        source: SubscriptionSource
    ): Result =
        when (source.kind) {
            SubscriptionKind.URL -> {
                val result =
                    SubscriptionManager()
                        .fetch(
                            url = source.url,
                            etag = source.etag.takeIf(String::isNotBlank),
                            lastModified = source.lastModified.takeIf(String::isNotBlank)
                        )

                Result(
                    profiles =
                        result.profiles,
                    finalUrl =
                        result.finalUrl,
                    notModified =
                        result.notModified,
                    etag = result.etag,
                    lastModified = result.lastModified
                )
            }

            SubscriptionKind.NORDVPN -> {
                val token =
                    SecretStore.get(
                        context.applicationContext,
                        source.secretAlias
                    )
                        ?.trim()
                        .orEmpty()

                check(token.isNotBlank()) {
                    "NordVPN token is unavailable. " +
                        "Edit the subscription and enter it again."
                }

                val result =
                    NordVpnSubscriptionManager()
                        .fetchCatalog(token)

                Result(
                    profiles =
                        result.profiles,
                    finalUrl =
                        source.url,
                    complete =
                        result.complete,
                    warnings =
                        result.warnings
                )
            }
        }
}
