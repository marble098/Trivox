package com.trivox.client.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.os.LocaleListCompat
import com.trivox.client.BuildConfig
import com.trivox.client.R
import com.trivox.client.ui.compose.LegacyLayoutBridge
import com.trivox.client.ui.compose.showLegacyMirror
import com.trivox.client.config.Validators
import com.trivox.client.data.AppSettings
import com.trivox.client.data.DnsMode
import com.trivox.client.data.PingMethod
import com.trivox.client.data.ProfileSortMode
import com.trivox.client.data.RealDelayProfile
import com.trivox.client.data.SettingsRepository
import com.trivox.client.data.SubscriptionRepository
import com.trivox.client.data.ThemeMode
import com.trivox.client.update.UpdateChecker
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

class SettingsActivity : ThemedActivity() {
    private lateinit var repository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val composeHost = LegacyLayoutBridge.install(this, "activity_settings")
        composeHost.compose.showLegacyMirror(this, composeHost.legacy, getString(R.string.settings))
        findViewById<Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        repository = SettingsRepository(this)
        val settings = repository.load()

        val language = spinner(R.id.languageSpinner)
        val darkMode = switch(R.id.darkModeSwitch)
        val hideIp = check(R.id.hideIpOnMainCheck)
        val sortMode = spinner(R.id.sortModeSpinner)
        val pingAttempts = spinner(R.id.pingAttemptsSpinner)
        val testUrl = edit(R.id.testUrlInput)
        val realDelayProfile = spinner(R.id.realDelayProfileSpinner)
        val realDelayGroupSize = edit(R.id.realDelayGroupSizeInput)
        val realDelayWorkers = edit(R.id.realDelayWorkersInput)
        val realDelayTimeout = edit(R.id.realDelayTimeoutInput)
        val realDelayStartGrace = edit(R.id.realDelayStartGraceInput)
        val realDelayTargetCount = edit(R.id.realDelayTargetCountInput)
        val realDelayRequiredProofs = edit(R.id.realDelayRequiredProofsInput)
        val networkTuning = switch(R.id.networkTuningEnabledSwitch)
        val adaptiveHandshake = switch(R.id.adaptiveHandshakeSwitch)
        val tcpFastOpen = switch(R.id.tcpFastOpenSwitch)
        val tcpKeepAliveIdle = edit(R.id.tcpKeepAliveIdleInput)
        val tcpKeepAliveInterval = edit(R.id.tcpKeepAliveIntervalInput)
        val tcpUserTimeout = edit(R.id.tcpUserTimeoutInput)
        val networkBufferSize = edit(R.id.networkBufferSizeInput)
        val mixedPort = edit(R.id.socksPort)
        val localProxyInVpn = check(R.id.localProxyInVpnCheck)
        val mtu = edit(R.id.mtuInput)
        val ipv6 = check(R.id.ipv6Check)
        val blocking = check(R.id.blockingCheck)
        val wireGuardMtu = edit(R.id.wireGuardMtuInput)
        val wireGuardWorkers = spinner(R.id.wireGuardWorkersSpinner)
        val wireGuardKeepAlive = edit(R.id.wireGuardKeepAliveInput)
        val wireGuardHandshakeTimeout = edit(R.id.wireGuardHandshakeTimeoutInput)
        val wireGuardDomainStrategy = spinner(R.id.wireGuardDomainStrategySpinner)
        val dnsMode = spinner(R.id.dnsMode)
        val customDns = edit(R.id.customDns)
        val reconnectNetwork = check(R.id.reconnectNetwork)
        val reconnectBoot = check(R.id.reconnectBoot)
        val autoUpdate = check(R.id.autoUpdateCheck)
        val updateStatus = text(R.id.updateStatus)

        language.adapter = compactAdapter(
            arrayOf(
                getString(R.string.language_system),
                getString(R.string.language_persian),
                getString(R.string.language_english)
            )
        )
        val currentTag = AppCompatDelegate.getApplicationLocales()
            .toLanguageTags()
            .substringBefore(',')
            .lowercase()
        language.setSelection(
            when {
                currentTag.startsWith("fa") -> 1
                currentTag.startsWith("en") -> 2
                else -> 0
            }
        )

        darkMode.isChecked = settings.themeMode == ThemeMode.DARK
        darkMode.setOnCheckedChangeListener { _, checked ->
            val selected = if (checked) ThemeMode.DARK else ThemeMode.LIGHT
            val latest = repository.load()
            if (latest.themeMode != selected) {
                latest.themeMode = selected
                latest.darkMode = checked
                repository.save(latest)
                applyNightModeWithMotion(
                    if (checked) AppCompatDelegate.MODE_NIGHT_YES
                    else AppCompatDelegate.MODE_NIGHT_NO
                )
            }
        }

        val sortModes = ProfileSortMode.entries
        sortMode.adapter = compactAdapter(
            arrayOf(
                getString(R.string.sort_smart),
                getString(R.string.sort_latency),
                getString(R.string.sort_name),
                getString(R.string.sort_recent),
                getString(R.string.sort_group)
            )
        )
        sortMode.setSelection(sortModes.indexOf(settings.sortMode).coerceAtLeast(0))

        val attempts = arrayOf("2", "3", "4", "5")
        pingAttempts.adapter = compactAdapter(attempts)
        pingAttempts.setSelection(settings.testAttempts.coerceIn(2, 5) - 2)

        val realDelayProfiles = RealDelayProfile.entries
        realDelayProfile.adapter = compactAdapter(
            arrayOf(
                getString(R.string.real_delay_profile_turbo),
                getString(R.string.real_delay_profile_balanced),
                getString(R.string.real_delay_profile_accurate),
                getString(R.string.real_delay_profile_custom)
            )
        )
        realDelayProfile.setSelection(
            realDelayProfiles.indexOf(settings.realDelayProfile).coerceAtLeast(0)
        )

        val workerValues = (1..8).map(Int::toString).toTypedArray()
        wireGuardWorkers.adapter = compactAdapter(workerValues)
        wireGuardWorkers.setSelection(settings.wireGuardWorkers.coerceIn(1, 8) - 1)

        val wireGuardStrategies = AppSettings.WIREGUARD_DOMAIN_STRATEGIES
        wireGuardDomainStrategy.adapter = compactAdapter(wireGuardStrategies.toTypedArray())
        wireGuardDomainStrategy.setSelection(
            wireGuardStrategies.indexOf(settings.wireGuardDomainStrategy)
                .coerceAtLeast(0)
        )

        val dnsModes = DnsMode.entries
        dnsMode.adapter = compactAdapter(
            arrayOf(
                getString(R.string.dns_imported),
                getString(R.string.dns_default),
                getString(R.string.dns_custom),
                getString(R.string.dns_system),
                getString(R.string.dns_direct),
                getString(R.string.dns_proxy)
            )
        )
        dnsMode.setSelection(dnsModes.indexOf(settings.dnsMode).coerceAtLeast(0))

        hideIp.isChecked = settings.hideIpOnMain
        testUrl.setText(settings.testUrl)
        realDelayGroupSize.setText(settings.realDelayGroupSize.toString())
        realDelayWorkers.setText(settings.realDelayWorkers.toString())
        realDelayTimeout.setText(settings.realDelayProbeTimeoutMs.toString())
        realDelayStartGrace.setText(settings.realDelayStartGraceMs.toString())
        realDelayTargetCount.setText(settings.realDelayTargetCount.toString())
        realDelayRequiredProofs.setText(settings.realDelayRequiredProofs.toString())
        networkTuning.isChecked = settings.networkTuningEnabled
        adaptiveHandshake.isChecked = settings.adaptiveHandshake
        tcpFastOpen.isChecked = settings.tcpFastOpen
        tcpKeepAliveIdle.setText(settings.tcpKeepAliveIdleSeconds.toString())
        tcpKeepAliveInterval.setText(settings.tcpKeepAliveIntervalSeconds.toString())
        tcpUserTimeout.setText(settings.tcpUserTimeoutMs.toString())
        networkBufferSize.setText(settings.networkBufferSizeKb.toString())
        mixedPort.setText(settings.socksPort.toString())
        localProxyInVpn.isChecked = settings.localProxyInVpn
        mtu.setText(settings.mtu.toString())
        ipv6.isChecked = settings.ipv6
        blocking.isChecked = settings.blocking
        wireGuardMtu.setText(settings.wireGuardMtu.toString())
        wireGuardKeepAlive.setText(settings.wireGuardKeepAliveSeconds.toString())
        wireGuardHandshakeTimeout.setText(settings.wireGuardHandshakeTimeoutMs.toString())
        customDns.setText(settings.customDns.joinToString("\n"))
        reconnectNetwork.isChecked = settings.reconnectOnNetworkChange
        reconnectBoot.isChecked = settings.reconnectOnBoot
        autoUpdate.isChecked = settings.autoUpdateCheck

        findViewById<Button>(R.id.manageSubscriptionsButton).setOnClickListener {
            startActivity(Intent(this, SubscriptionManagementActivity::class.java))
        }
        updateSubscriptionCount()

        findViewById<Button>(R.id.telegramProxyButton).setOnClickListener {
            val port = mixedPort.text.toString().toIntOrNull()
                ?.takeIf(Validators::validPort)
                ?: settings.socksPort
            runCatching {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("tg://socks?server=localhost&port=$port")
                    )
                )
            }.onFailure {
                toast(R.string.telegram_not_available)
            }
        }

        findViewById<Button>(R.id.checkUpdateButton).setOnClickListener {
            updateStatus.setText(R.string.update_checking)
            UpdateChecker.check(this, showCurrentResult = true) { result ->
                updateStatus.text = when {
                    result.available -> getString(R.string.update_found, result.version)
                    result.error.isNotBlank() ->
                        getString(R.string.update_check_failed, result.error)
                    else -> getString(R.string.update_current)
                }
            }
        }

        renderAbout()

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            val port = mixedPort.intValue()
            val mtuValue = mtu.intValue()
            val keepIdleValue = tcpKeepAliveIdle.intValue()
            val keepIntervalValue = tcpKeepAliveInterval.intValue()
            val userTimeoutValue = tcpUserTimeout.intValue()
            val bufferValue = networkBufferSize.intValue()
            val wgMtuValue = wireGuardMtu.intValue()
            val wgKeepAliveValue = wireGuardKeepAlive.intValue()
            val wgHandshakeValue = wireGuardHandshakeTimeout.intValue()
            val testUrlValue = testUrl.text.toString().trim()
            val realGroupValue = realDelayGroupSize.intValue()
            val realWorkersValue = realDelayWorkers.intValue()
            val realTimeoutValue = realDelayTimeout.intValue()
            val realGraceValue = realDelayStartGrace.intValue()
            val realTargetsValue = realDelayTargetCount.intValue()
            val realProofsValue = realDelayRequiredProofs.intValue()
            val dnsValues = customDns.text.lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .toList()

            val basicValid =
                port != null && Validators.validPort(port) &&
                    mtuValue != null && mtuValue in 576..9000 &&
                    validTestUrl(testUrlValue)
            if (!basicValid) {
                toast(R.string.invalid_port)
                return@setOnClickListener
            }

            val realDelayValid =
                realGroupValue != null && realGroupValue in 2..16 &&
                    realWorkersValue != null && realWorkersValue in 1..6 &&
                    realTimeoutValue != null && realTimeoutValue in 1_500..10_000 &&
                    realGraceValue != null && realGraceValue in 0..1_000 &&
                    realTargetsValue != null && realTargetsValue in 1..4 &&
                    realProofsValue != null && realProofsValue in 1..realTargetsValue
            if (!realDelayValid) {
                toast(R.string.real_delay_settings_invalid)
                return@setOnClickListener
            }

            val tuningValid =
                keepIdleValue != null && keepIdleValue in 0..3600 &&
                    keepIntervalValue != null && keepIntervalValue in 0..600 &&
                    userTimeoutValue != null && userTimeoutValue in 0..120_000 &&
                    bufferValue != null && bufferValue in 8..256 &&
                    wgMtuValue != null && wgMtuValue in 576..9000 &&
                    wgKeepAliveValue != null && wgKeepAliveValue in 0..300 &&
                    wgHandshakeValue != null && wgHandshakeValue in 5_000..60_000
            if (!tuningValid) {
                toast(R.string.invalid_network_tuning_v9)
                return@setOnClickListener
            }

            val selectedDnsMode = dnsModes[dnsMode.selectedItemPosition]
            if (
                selectedDnsMode == DnsMode.CUSTOM &&
                (dnsValues.isEmpty() || !dnsValues.all(Validators::validateDns))
            ) {
                toast(R.string.invalid_dns)
                return@setOnClickListener
            }

            settings.socksPort = port!!
            settings.httpPort = port
            settings.mtu = mtuValue!!
            settings.ipv6 = ipv6.isChecked
            settings.dnsMode = selectedDnsMode
            settings.customDns = dnsValues
            settings.reconnectOnNetworkChange = reconnectNetwork.isChecked
            settings.reconnectOnBoot = reconnectBoot.isChecked
            settings.blocking = blocking.isChecked
            settings.themeMode = if (darkMode.isChecked) ThemeMode.DARK else ThemeMode.LIGHT
            settings.darkMode = darkMode.isChecked
            settings.hideIpOnMain = hideIp.isChecked
            settings.localProxyInVpn = localProxyInVpn.isChecked
            settings.autoUpdateCheck = autoUpdate.isChecked
            settings.sortMode = sortModes[sortMode.selectedItemPosition]
            settings.pingMethod = PingMethod.XRAY_HTTP
            settings.testAttempts = attempts[pingAttempts.selectedItemPosition].toInt()
            settings.testUrl = testUrlValue
            settings.realDelayProfile = realDelayProfiles[
                realDelayProfile.selectedItemPosition
            ]
            settings.realDelayGroupSize = realGroupValue!!
            settings.realDelayWorkers = realWorkersValue!!
            settings.realDelayProbeTimeoutMs = realTimeoutValue!!
            settings.realDelayStartGraceMs = realGraceValue!!
            settings.realDelayTargetCount = realTargetsValue!!
            settings.realDelayRequiredProofs = realProofsValue!!
            settings.networkTuningEnabled = networkTuning.isChecked
            settings.adaptiveHandshake = adaptiveHandshake.isChecked
            settings.tcpFastOpen = tcpFastOpen.isChecked
            settings.tcpKeepAliveIdleSeconds = keepIdleValue!!
            settings.tcpKeepAliveIntervalSeconds = keepIntervalValue!!
            settings.tcpUserTimeoutMs = userTimeoutValue!!
            settings.networkBufferSizeKb = bufferValue!!
            settings.wireGuardMtu = wgMtuValue!!
            settings.wireGuardWorkers = workerValues[
                wireGuardWorkers.selectedItemPosition
            ].toInt()
            settings.wireGuardKeepAliveSeconds = wgKeepAliveValue!!
            settings.wireGuardHandshakeTimeoutMs = wgHandshakeValue!!
            settings.wireGuardDomainStrategy = wireGuardStrategies[
                wireGuardDomainStrategy.selectedItemPosition
            ]
            repository.save(settings.normalize())

            val localeTags = when (language.selectedItemPosition) {
                1 -> "fa"
                2 -> "en"
                else -> ""
            }
            getSharedPreferences("locale", MODE_PRIVATE)
                .edit()
                .putBoolean("initialized", true)
                .apply()
            toast(R.string.settings_saved, Toast.LENGTH_SHORT)
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(localeTags)
            )
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::repository.isInitialized) updateSubscriptionCount()
    }

    private fun updateSubscriptionCount() {
        findViewById<Button>(R.id.manageSubscriptionsButton).text = getString(
            R.string.manage_subscriptions_count,
            SubscriptionRepository(this).all().size
        )
    }

    private fun validTestUrl(value: String): Boolean = runCatching {
        URI(value)
    }.getOrNull()?.let { uri ->
        uri.scheme?.lowercase() in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null
    } == true

    private fun renderAbout() {
        text(R.id.aboutCreators).text = getString(R.string.about_creators_value)
        text(R.id.aboutAppVersion).text = getString(
            R.string.about_app_version_value,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            BuildConfig.BUILD_TYPE,
            BuildConfig.GIT_SHA
        )
        text(R.id.aboutCoreVersion).text = getString(
            R.string.about_core_version_value,
            readCoreVersion()
        )
        text(R.id.aboutSigning).text = getString(
            R.string.about_signing_value,
            signingCertificateSha256()
        )
        text(R.id.aboutPackage).text = getString(
            R.string.about_package_value,
            packageName
        )
    }

    private fun readCoreVersion(): String = runCatching {
        assets.open("core-manifest.json").bufferedReader().use {
            JSONObject(it.readText()).optString("version", "unknown")
        }
    }.getOrDefault("unknown")

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

    private fun compactAdapter(values: Array<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, values).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun EditText.intValue(): Int? = text.toString().trim().toIntOrNull()
    private fun spinner(id: Int): Spinner = findViewById(id)
    private fun edit(id: Int): EditText = findViewById(id)
    private fun check(id: Int): CheckBox = findViewById(id)
    private fun switch(id: Int): SwitchCompat = findViewById(id)
    private fun text(id: Int): TextView = findViewById(id)

    private fun toast(message: Int, duration: Int = Toast.LENGTH_LONG) {
        Toast.makeText(this, message, duration).show()
    }
}
