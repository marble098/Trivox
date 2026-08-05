package com.trivox.client.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import com.trivox.client.BuildConfig
import com.trivox.client.R
import com.trivox.client.core.MihomoCoreAdapter
import com.trivox.client.core.SingBoxCoreAdapter
import com.trivox.client.core.XrayCoreAdapter
import com.trivox.client.data.SettingsRepository
import com.trivox.client.ui.screens.AboutInfo
import com.trivox.client.ui.screens.SettingsComposeScreen
import com.trivox.client.ui.theme.TrivoxTheme
import com.trivox.client.update.UpdateChecker
import java.security.MessageDigest

class SettingsActivity : ThemedActivity() {
    private lateinit var repository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SettingsRepository(this)

        val xrayVersion = runCatching { XrayCoreAdapter(this).version() }.getOrDefault("v1.8.8")
        val singBoxVersion = runCatching { SingBoxCoreAdapter(this).version() }.getOrDefault("v1.8.10")
        val mihomoVersion = runCatching { MihomoCoreAdapter(this).version() }.getOrDefault("v1.18.1")

        val aboutInfo = AboutInfo(
            appVersion = BuildConfig.VERSION_NAME,
            buildNumber = BuildConfig.BUILD_NUMBER,
            gitSha = BuildConfig.GIT_SHA,
            xrayVersion = xrayVersion,
            singBoxVersion = singBoxVersion,
            mihomoVersion = mihomoVersion,
            packageName = packageName,
            signingSha = signingCertificateSha256()
        )

        setContent {
            val settings = repository.load()
            TrivoxTheme(darkTheme = settings.darkMode) {
                SettingsComposeScreen(
                    initialSettings = settings,
                    aboutInfo = aboutInfo,
                    onSave = { updated ->
                        repository.save(updated.normalize())
                        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onBack = { finish() },
                    onManageSubscriptions = {
                        startActivity(Intent(this, SubscriptionManagementActivity::class.java))
                    },
                    onCheckUpdate = {
                        UpdateChecker.check(this, showCurrentResult = true) { result ->
                            val msg = when {
                                result.available -> getString(R.string.update_found, result.version)
                                result.error.isNotBlank() -> getString(R.string.update_check_failed, result.error)
                                else -> getString(R.string.update_current)
                            }
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
        }
    }

    private fun signingCertificateSha256(): String = runCatching {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        }
        val certificate = signatures?.firstOrNull()?.toByteArray()
            ?: error("No APK signer")
        MessageDigest.getInstance("SHA-256")
            .digest(certificate)
            .joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }.getOrDefault(getString(R.string.about_unavailable))
}
