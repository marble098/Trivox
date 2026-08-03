package com.trivox.client.ui

import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.os.LocaleListCompat
import com.trivox.client.BuildConfig
import com.trivox.client.R
import com.trivox.client.config.Validators
import com.trivox.client.data.DnsMode
import com.trivox.client.data.PingMethod
import com.trivox.client.data.ProfileSortMode
import com.trivox.client.data.SettingsRepository
import com.trivox.client.data.SubscriptionRepository
import com.trivox.client.data.ThemeMode
import com.trivox.client.update.UpdateChecker
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

class SettingsActivity :
    ThemedActivity() {
    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(
            savedInstanceState
        )
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
        val darkMode =
            findViewById<SwitchCompat>(
                R.id.darkModeSwitch
            )
        val localProxyInVpn =
            findViewById<CheckBox>(
                R.id.localProxyInVpnCheck
            )
        val autoUpdate =
            findViewById<CheckBox>(
                R.id.autoUpdateCheck
            )
        val updateStatus =
            findViewById<TextView>(
                R.id.updateStatus
            )
        val sortMode =
            findViewById<Spinner>(
                R.id.sortModeSpinner
            )
        val pingMethod =
            findViewById<Spinner>(
                R.id.pingMethodSpinner
            )
        val pingSummary =
            findViewById<TextView>(
                R.id.pingMethodSummary
            )
        val pingAttempts =
            findViewById<Spinner>(
                R.id.pingAttemptsSpinner
            )
        val testUrl =
            findViewById<EditText>(
                R.id.testUrlInput
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
                        R.string
                            .language_system
                    ),
                    getString(
                        R.string
                            .language_persian
                    ),
                    getString(
                        R.string
                            .language_english
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
                    .startsWith("fa") ->
                    1

                currentTag
                    .startsWith("en") ->
                    2

                else -> 0
            }
        )

        darkMode.isChecked =
            settings.themeMode == ThemeMode.DARK

        darkMode.setOnCheckedChangeListener {
                _, checked ->
            val selected =
                if (checked) {
                    ThemeMode.DARK
                } else {
                    ThemeMode.LIGHT
                }
            val latest =
                repository.load()

            if (latest.themeMode == selected) {
                return@setOnCheckedChangeListener
            }

            latest.themeMode = selected
            latest.darkMode = checked
            repository.save(latest)

            AppCompatDelegate.setDefaultNightMode(
                if (checked) {
                    AppCompatDelegate.MODE_NIGHT_YES
                } else {
                    AppCompatDelegate.MODE_NIGHT_NO
                }
            )
            recreate()
        }

        val sortModes =
            ProfileSortMode.entries

        sortMode.adapter =
            compactAdapter(
                arrayOf(
                    getString(
                        R.string
                            .sort_smart
                    ),
                    getString(
                        R.string
                            .sort_latency
                    ),
                    getString(
                        R.string
                            .sort_name
                    ),
                    getString(
                        R.string
                            .sort_recent
                    ),
                    getString(
                        R.string
                            .sort_group
                    )
                )
            )
        sortMode.setSelection(
            sortModes
                .indexOf(
                    settings.sortMode
                )
                .coerceAtLeast(0)
        )

        val pingMethods =
            PingMethod.entries

        pingMethod.adapter =
            compactAdapter(
                arrayOf(
                    getString(
                        R.string
                            .ping_method_tcp
                    ),
                    getString(
                        R.string
                            .ping_method_xray
                    )
                )
            )
        pingMethod.setSelection(
            pingMethods
                .indexOf(
                    settings.livePingMethod
                )
                .coerceAtLeast(0)
        )
        pingMethod
            .onItemSelectedListener =
            object :
                AdapterView
                    .OnItemSelectedListener {
                override fun onItemSelected(
                    parent:
                        AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    pingSummary.setText(
                        if (
                            pingMethods[
                                position
                            ] ==
                            PingMethod
                                .TCP_CONNECT
                        ) {
                            R.string
                                .ping_method_tcp_summary
                        } else {
                            R.string
                                .ping_method_xray_summary
                        }
                    )
                }

                override fun onNothingSelected(
                    parent:
                        AdapterView<*>?
                ) = Unit
            }

        val attempts =
            arrayOf(
                "2",
                "3",
                "4",
                "5"
            )

        pingAttempts.adapter =
            compactAdapter(attempts)
        pingAttempts.setSelection(
            settings
                .testAttempts
                .coerceIn(2, 5) -
                2
        )
        testUrl.setText(
            settings.testUrl
        )
        mixedPort.setText(
            settings.socksPort
                .toString()
        )
        mtu.setText(
            settings.mtu
                .toString()
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
        localProxyInVpn.isChecked =
            settings.localProxyInVpn
        autoUpdate.isChecked =
            settings.autoUpdateCheck

        findViewById<Button>(
            R.id.telegramProxyButton
        ).setOnClickListener {
            val port = mixedPort.text.toString()
                .toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: settings.socksPort
            val uri = Uri.parse(
                "tg://socks?server=localhost&port=$port"
            )
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW, uri)
                )
            }.onFailure {
                Toast.makeText(
                    this,
                    R.string.telegram_not_available,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        findViewById<Button>(
            R.id.checkUpdateButton
        ).setOnClickListener {
            updateStatus.setText(
                R.string.update_checking
            )
            UpdateChecker.check(
                this,
                showCurrentResult = true
            ) { result ->
                updateStatus.text = when {
                    result.available ->
                        getString(
                            R.string.update_found,
                            result.version
                        )
                    result.error.isNotBlank() ->
                        getString(
                            R.string.update_check_failed,
                            result.error
                        )
                    else ->
                        getString(
                            R.string.update_current
                        )
                }
            }
        }

        val dnsModes =
            DnsMode.entries

        dns.adapter =
            compactAdapter(
                arrayOf(
                    getString(
                        R.string
                            .dns_imported
                    ),
                    getString(
                        R.string
                            .dns_default
                    ),
                    getString(
                        R.string
                            .dns_custom
                    ),
                    getString(
                        R.string
                            .dns_system
                    ),
                    getString(
                        R.string
                            .dns_direct
                    ),
                    getString(
                        R.string
                            .dns_proxy
                    )
                )
            )

        dns.setSelection(
            dnsModes
                .indexOf(
                    settings.dnsMode
                )
                .coerceAtLeast(0)
        )

        val subscriptionCount =
            SubscriptionRepository(this)
                .all()
                .size

        findViewById<Button>(
            R.id.manageSubscriptionsButton
        ).apply {
            text =
                getString(
                    R.string
                        .manage_subscriptions_count,
                    subscriptionCount
                )
            setOnClickListener {
                startActivity(
                    Intent(
                        this@SettingsActivity,
                        SubscriptionManagementActivity::
                            class.java
                    )
                )
            }
        }

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
            val testUrlValue =
                testUrl.text
                    .toString()
                    .trim()

            val dnsValues =
                custom.text
                    .lineSequence()
                    .map(
                        String::trim
                    )
                    .filter(
                        String::isNotEmpty
                    )
                    .toList()

            if (
                port == null ||
                !Validators
                    .validPort(port) ||
                mtuValue !in
                576..9000
            ) {
                Toast.makeText(
                    this,
                    R.string.invalid_port,
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            if (
                !validTestUrl(
                    testUrlValue
                )
            ) {
                Toast.makeText(
                    this,
                    R.string
                        .invalid_test_url,
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            if (
                dnsModes[
                    dns.selectedItemPosition
                ] ==
                DnsMode.CUSTOM &&
                (
                    dnsValues.isEmpty() ||
                        !dnsValues.all(
                            Validators::
                                validateDns
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

            settings.socksPort =
                port
            settings.httpPort =
                port
            settings.mtu =
                mtuValue!!
            settings.ipv6 =
                ipv6.isChecked
            settings.dnsMode =
                dnsModes[
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
            settings.themeMode =
                if (darkMode.isChecked) {
                    ThemeMode.DARK
                } else {
                    ThemeMode.LIGHT
                }
            settings.darkMode =
                darkMode.isChecked
            settings.localProxyInVpn =
                localProxyInVpn.isChecked
            settings.autoUpdateCheck =
                autoUpdate.isChecked
            settings.sortMode =
                sortModes[
                    sortMode
                        .selectedItemPosition
                ]
            settings.livePingMethod =
                pingMethods[
                    pingMethod
                        .selectedItemPosition
                ]
            settings.pingMethod =
                PingMethod.TCP_CONNECT
            settings.testAttempts =
                attempts[
                    pingAttempts
                        .selectedItemPosition
                ].toInt()
            settings.testUrl =
                testUrlValue

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

    override fun onResume() {
        super.onResume()

        val button =
            findViewById<Button>(
                R.id.manageSubscriptionsButton
            )
        val count =
            SubscriptionRepository(this)
                .all()
                .size
        button.text =
            getString(
                R.string
                    .manage_subscriptions_count,
                count
            )
    }

    private fun validTestUrl(
        value: String
    ): Boolean =
        runCatching {
            URI(value)
        }.getOrNull()
            ?.let { uri ->
                uri.scheme
                    ?.lowercase() in
                    setOf(
                        "http",
                        "https"
                    ) &&
                    !uri.host
                        .isNullOrBlank() &&
                    uri.userInfo == null
            } == true

    private fun renderAbout() {
        findViewById<TextView>(
            R.id.aboutCreators
        ).text =
            getString(
                R.string
                    .about_creators_value
            )

        findViewById<TextView>(
            R.id.aboutAppVersion
        ).text =
            getString(
                R.string
                    .about_app_version_value,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                BuildConfig.BUILD_TYPE,
                BuildConfig.GIT_SHA
            )

        findViewById<TextView>(
            R.id.aboutCoreVersion
        ).text =
            getString(
                R.string
                    .about_core_version_value,
                readCoreVersion()
            )

        findViewById<TextView>(
            R.id.aboutSigning
        ).text =
            getString(
                R.string
                    .about_signing_value,
                signingCertificateSha256()
            )

        findViewById<TextView>(
            R.id.aboutPackage
        ).text =
            getString(
                R.string
                    .about_package_value,
                packageName
            )
    }

    private fun readCoreVersion():
        String =
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
        }.getOrDefault(
            "unknown"
        )

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
                    @Suppress(
                        "DEPRECATION"
                    )
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
                    @Suppress(
                        "DEPRECATION"
                    )
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
                .getInstance(
                    "SHA-256"
                )
                .digest(certificate)
                .joinToString(":") {
                    "%02X".format(
                        it.toInt() and
                            0xff
                    )
                }
        }.getOrDefault(
            getString(
                R.string
                    .about_unavailable
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
