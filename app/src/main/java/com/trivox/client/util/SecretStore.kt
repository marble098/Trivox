package com.trivox.client.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecretStore {
    private const val PREFS = "trivox_secure_secrets"
    private const val MASTER_KEY_ALIAS =
        "trivox_subscription_secret_master_v1"
    private const val TRANSFORMATION =
        "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun put(
        context: Context,
        alias: String,
        value: String
    ) {
        require(alias.isNotBlank()) {
            "Secret alias cannot be blank"
        }
        require(value.isNotBlank()) {
            "Secret value cannot be blank"
        }

        val cipher =
            Cipher.getInstance(
                TRANSFORMATION
            )
        cipher.init(
            Cipher.ENCRYPT_MODE,
            getOrCreateKey()
        )

        val encrypted =
            cipher.doFinal(
                value.toByteArray(
                    Charsets.UTF_8
                )
            )
        val payload =
            Base64.encodeToString(
                cipher.iv,
                Base64.NO_WRAP
            ) +
                "." +
                Base64.encodeToString(
                    encrypted,
                    Base64.NO_WRAP
                )

        context.applicationContext
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .edit()
            .putString(alias, payload)
            .commit()
    }

    fun get(
        context: Context,
        alias: String
    ): String? {
        if (alias.isBlank()) {
            return null
        }

        val payload =
            context.applicationContext
                .getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                )
                .getString(alias, null)
                ?: return null
        val separator =
            payload.indexOf('.')

        if (
            separator <= 0 ||
            separator >=
            payload.lastIndex
        ) {
            return null
        }

        return runCatching {
            val iv =
                Base64.decode(
                    payload.substring(
                        0,
                        separator
                    ),
                    Base64.NO_WRAP
                )
            val encrypted =
                Base64.decode(
                    payload.substring(
                        separator + 1
                    ),
                    Base64.NO_WRAP
                )
            val cipher =
                Cipher.getInstance(
                    TRANSFORMATION
                )

            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(
                    TAG_BITS,
                    iv
                )
            )

            cipher.doFinal(encrypted)
                .toString(
                    Charsets.UTF_8
                )
        }.getOrNull()
    }

    fun delete(
        context: Context,
        alias: String
    ) {
        if (alias.isBlank()) {
            return
        }

        context.applicationContext
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .edit()
            .remove(alias)
            .apply()
    }

    private fun getOrCreateKey():
        SecretKey {
        val store =
            KeyStore.getInstance(
                "AndroidKeyStore"
            ).apply {
                load(null)
            }

        val existing =
            store.getKey(
                MASTER_KEY_ALIAS,
                null
            ) as? SecretKey

        if (existing != null) {
            return existing
        }

        return KeyGenerator
            .getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder(
                            MASTER_KEY_ALIAS,
                            KeyProperties
                                .PURPOSE_ENCRYPT or
                                KeyProperties
                                    .PURPOSE_DECRYPT
                        )
                        .setBlockModes(
                            KeyProperties
                                .BLOCK_MODE_GCM
                        )
                        .setEncryptionPaddings(
                            KeyProperties
                                .ENCRYPTION_PADDING_NONE
                        )
                        .setRandomizedEncryptionRequired(
                            true
                        )
                        .build()
                )
            }
            .generateKey()
    }
}
