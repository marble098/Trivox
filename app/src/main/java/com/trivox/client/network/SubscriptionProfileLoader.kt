package com.trivox.client.network

import android.content.Context
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.SubscriptionKind
import com.trivox.client.data.SubscriptionSource
import com.trivox.client.util.SecretStore

object SubscriptionProfileLoader {
    data class Result(
        val profiles:
            List<ConfigProfile>,
        val finalUrl: String
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
                            source.url
                        )

                Result(
                    profiles =
                        result.profiles,
                    finalUrl =
                        result.finalUrl
                )
            }

            SubscriptionKind
                .NORDVPN -> {
                val token =
                    SecretStore.get(
                        context,
                        source.secretAlias
                    )
                        ?.trim()
                        .orEmpty()

                check(token.isNotBlank()) {
                    "NordVPN token is unavailable. Edit the subscription and enter it again."
                }

                Result(
                    profiles =
                        NordVpnSubscriptionManager()
                            .fetchAllCountries(
                                token
                            ),
                    finalUrl =
                        source.url
                )
            }
        }
}
