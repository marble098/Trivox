package com.trivox.client.ui

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.os.LocaleListCompat
import com.trivox.client.BuildConfig
import com.trivox.client.R
import com.trivox.client.config.Validators
import com.trivox.client.data.DnsMode
import com.trivox.client.data.SettingsRepository
import org.json.JSONObject
import java.security.MessageDigest

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)
        setContentView(
            R.layout.activity_settings
        )

        findViewById<Toolbar>(
            R.id.toolbar
        ).setNavigationOnClickListener {
            finish()
        }

        val repository =
            SettingsRepository(this)
        val settings =
            repository.load()

        val language =
            findViewById<Spinner>(
                R.id.languageSpinner
            )
        val mixedPort =
            findViewById<EditText>(
                R.id.socksPort
            )
        val mtu =
            findViewById<EditText>(
                R.id.mtuInput
            )
        val ipv6 =
            findViewById<CheckBox>(
                R.id.ipv6Check
            )
        val dns =
            findViewById<Spinner>(
                R.id.dnsMode
            )
        val custom =
            findViewById<EditText>(
                R.id.customDns
            )
        val network =
            findViewById<CheckBox>(
                R.id.reconnectNetwork
            )
        val boot =
            findViewById<CheckBox>(
                R.id.reconnectBoot
            )
        val blocking =
            findViewById<CheckBox>(
                R.id.blockingCheck
            )

        language.adapter =
            compactAdapter(
                arrayOf(
                    getString(
                        R.string.language_system
                    ),
                    getString(
                        R.string.language_persian
                    ),
                    getString(
                        R.string.language_english
                    )
                )
            )

        val currentTag =
            AppCompatDelegate
                .getApplicationLocales()
                .toLanguageTags()
                .substringBefore(',')
                .lowercase()

        language.setSelection(
            when {
                currentTag
                    .startsWith("fa") -> 1
                currentTag
                    .startsWith("en") -> 2
                else -> 0
            }
        )

        mixedPort.setText(
            settings.socksPort.toString()
        )
        mtu.setText(
            settings.mtu.toString()
        )
        ipv6.isChecked =
            settings.ipv6
        custom.setText(
            settings.customDns
                .joinToString("\n")
        )
        network.isChecked =
            settings
                .reconnectOnNetworkChange
        boot.isChecked =
            settings.reconnectOnBoot
        blocking.isChecked =
            settings.blocking

        val modes = DnsMode.entries

        dns.adapter =
            compactAdapter(
                arrayOf(
                    getString(
                        R.string.dns_imported
                    ),
                    getString(
                        R.string.dns_default
                    ),
                    getString(
                        R.string.dns_custom
                    ),
                    getString(
                        R.string.dns_system
                    ),
                    getString(
                        R.string.dns_direct
                    ),
                    getString(
                        R.string.dns_proxy
                    )
                )
            )

        dns.setSelection(
            modes.indexOf(
                settings.dnsMode
            )
        )

        renderAbout()

        findViewById<Button>(
            R.id.saveButton
        ).setOnClickListener {
            val port =
                mixedPort.text
                    .toString()
                    .toIntOrNull()
            val mtuValue =
                mtu.text
                    .toString()
                    .toIntOrNull()

            val dnsValues =
                custom.text
                    .lineSequence()
                    .map(String::trim)
                    .filter(
                        String::isNotEmpty
                    )
                    .toList()

            if (
                port == null ||
                !Validators
                    .validPort(port) ||
                mtuValue !in 576..9000
            ) {
                Toast.makeText(
                    this,
                    R.string.invalid_port,
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            if (
                modes[
                    dns.selectedItemPosition
                ] == DnsMode.CUSTOM &&
                (
                    dnsValues.isEmpty() ||
                        !dnsValues.all(
                            Validators::validateDns
                        )
                    )
            ) {
                Toast.makeText(
                    this,
                    R.string.invalid_dns,
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            settings.socksPort = port
            settings.httpPort = port
            settings.mtu = mtuValue!!
            settings.ipv6 =
                ipv6.isChecked
            settings.dnsMode =
                modes[
                    dns.selectedItemPosition
                ]
            settings.customDns =
                dnsValues
            settings
                .reconnectOnNetworkChange =
                network.isChecked
            settings.reconnectOnBoot =
                boot.isChecked
            settings.blocking =
                blocking.isChecked

            repository.save(settings)

            val localeTags =
                when (
                    language
                        .selectedItemPosition
                ) {
                    1 -> "fa"
                    2 -> "en"
                    else -> ""
                }

            getSharedPreferences(
                "locale",
                MODE_PRIVATE
            ).edit()
                .putBoolean(
                    "initialized",
                    true
                )
                .apply()

            Toast.makeText(
                this,
                R.string.settings_saved,
                Toast.LENGTH_SHORT
            ).show()

            AppCompatDelegate
                .setApplicationLocales(
                    LocaleListCompat
                        .forLanguageTags(
                            localeTags
                        )
                )

            finish()
        }
    }

    private fun renderAbout() {
        findViewById<TextView>(
            R.id.aboutCreators
        ).text =
            getString(
                R.string.about_creators_value
            )

        findViewById<TextView>(
            R.id.aboutAppVersion
        ).text =
            getString(
                R.string.about_app_version_value,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                BuildConfig.BUILD_TYPE,
                BuildConfig.GIT_SHA
            )

        findViewById<TextView>(
            R.id.aboutCoreVersion
        ).text =
            getString(
                R.string.about_core_version_value,
                readCoreVersion()
            )

        findViewById<TextView>(
            R.id.aboutSigning
        ).text =
            getString(
                R.string.about_signing_value,
                signingCertificateSha256()
            )

        findViewById<TextView>(
            R.id.aboutPackage
        ).text =
            getString(
                R.string.about_package_value,
                packageName
            )
    }

    private fun readCoreVersion(): String =
        runCatching {
            assets
                .open(
                    "core-manifest.json"
                )
                .bufferedReader()
                .use {
                    JSONObject(
                        it.readText()
                    ).optString(
                        "version",
                        "unknown"
                    )
                }
        }.getOrDefault("unknown")

    private fun signingCertificateSha256():
        String =
        runCatching {
            val packageInfo =
                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.P
                ) {
                    packageManager
                        .getPackageInfo(
                            packageName,
                            PackageManager
                                .GET_SIGNING_CERTIFICATES
                        )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager
                        .getPackageInfo(
                            packageName,
                            PackageManager
                                .GET_SIGNATURES
                        )
                }

            val signatures =
                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.P
                ) {
                    packageInfo
                        .signingInfo
                        ?.apkContentsSigners
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.signatures
                }

            val certificate =
                signatures
                    ?.firstOrNull()
                    ?.toByteArray()
                    ?: error(
                        "No APK signer"
                    )

            MessageDigest
                .getInstance("SHA-256")
                .digest(certificate)
                .joinToString(":") {
                    "%02X".format(
                        it.toInt() and 0xff
                    )
                }
        }.getOrDefault(
            getString(
                R.string.about_unavailable
            )
        )

    private fun compactAdapter(
        values: Array<String>
    ): ArrayAdapter<String> =
        ArrayAdapter(
            this,
            R.layout.spinner_item,
            values
        ).also {
            it.setDropDownViewResource(
                R.layout
                    .spinner_dropdown_item
            )
        }
}
