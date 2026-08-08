package com.trivox.client.ui.compose

import android.app.Activity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.trivox.client.R
import com.trivox.client.config.Validators
import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.DnsMode
import com.trivox.client.data.PingMethod
import com.trivox.client.data.ProfileSortMode
import com.trivox.client.data.RealDelayProfile
import com.trivox.client.data.ThemeMode
import java.net.URI

@Composable
internal fun UnifiedSettingsScreen(
    activity: Activity,
    actions: MainComposeActions,
    uiRevision: Int,
    modifier: Modifier = Modifier
) {
    val settings = remember(uiRevision) { actions.composeSettings() }
    val language = remember(uiRevision) { actions.composeLanguageTag() }
    val updateStatus = remember(uiRevision) { actions.composeUpdateStatus() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SettingsSection(activity.getString(R.string.v15_settings_interface)) {
                ChoiceSetting(
                    label = activity.getString(R.string.v15_language),
                    value = language,
                    choices = listOf(
                        "" to activity.getString(R.string.language_system),
                        "fa" to activity.getString(R.string.language_persian),
                        "en" to activity.getString(R.string.language_english)
                    ),
                    onSelected = actions::composeSetLanguage
                )
                ChoiceSetting(
                    label = activity.getString(R.string.v15_theme),
                    value = settings.themeMode,
                    choices = listOf(
                        ThemeMode.LIGHT to activity.getString(R.string.v15_theme_light),
                        ThemeMode.DARK to activity.getString(R.string.v15_theme_dark)
                    )
                ) { selected ->
                    actions.composeSaveSettings(
                        settings.copy(
                            themeMode = selected,
                            darkMode = selected == ThemeMode.DARK
                        )
                    )
                }
                BooleanSetting(
                    activity.getString(R.string.hide_ip_on_main),
                    settings.hideIpOnMain
                ) {
                    actions.composeSaveSettings(settings.copy(hideIpOnMain = it))
                }
                BooleanSetting(
                    activity.getString(R.string.compose_grid_layout),
                    settings.gridMode
                ) {
                    actions.composeSaveSettings(settings.copy(gridMode = it))
                }
                ChoiceSetting(
                    label = activity.getString(R.string.v15_sort_mode),
                    value = settings.sortMode,
                    choices = listOf(
                        ProfileSortMode.SMART to activity.getString(R.string.sort_smart),
                        ProfileSortMode.LOWEST_LATENCY to activity.getString(R.string.sort_latency),
                        ProfileSortMode.NAME to activity.getString(R.string.sort_name),
                        ProfileSortMode.LAST_TESTED to activity.getString(R.string.sort_recent),
                        ProfileSortMode.GROUP to activity.getString(R.string.sort_group)
                    )
                ) {
                    actions.composeSaveSettings(settings.copy(sortMode = it))
                }
            }
        }

        item {
            SettingsSection(activity.getString(R.string.v15_settings_connection)) {
                ChoiceSetting(
                    label = activity.getString(R.string.connection_mode),
                    value = settings.mode,
                    choices = listOf(
                        ConnectionMode.VPN to activity.getString(R.string.vpn_mode),
                        ConnectionMode.PROXY to activity.getString(R.string.proxy_mode)
                    )
                ) {
                    actions.composeSaveSettings(settings.copy(mode = it))
                }
                NumberSetting(
                    label = activity.getString(R.string.v15_mixed_port),
                    value = settings.socksPort,
                    range = 1..65535
                ) {
                    actions.composeSaveSettings(
                        settings.copy(socksPort = it, httpPort = it)
                    )
                }
                Text(
                    actions.composeLocalProxyStatus(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                BooleanSetting(
                    activity.getString(R.string.v15_local_proxy_in_vpn),
                    settings.localProxyInVpn
                ) {
                    actions.composeSaveSettings(settings.copy(localProxyInVpn = it))
                }
                NumberSetting(
                    label = activity.getString(R.string.v15_mtu),
                    value = settings.mtu,
                    range = 576..9000
                ) {
                    actions.composeSaveSettings(settings.copy(mtu = it))
                }
                BooleanSetting(
                    activity.getString(R.string.v15_ipv6),
                    settings.ipv6
                ) {
                    actions.composeSaveSettings(settings.copy(ipv6 = it))
                }
                BooleanSetting(
                    activity.getString(R.string.v15_blocking),
                    settings.blocking
                ) {
                    actions.composeSaveSettings(settings.copy(blocking = it))
                }
                BooleanSetting(
                    activity.getString(R.string.v15_reconnect_network),
                    settings.reconnectOnNetworkChange
                ) {
                    actions.composeSaveSettings(
                        settings.copy(reconnectOnNetworkChange = it)
                    )
                }
                BooleanSetting(
                    activity.getString(R.string.v15_reconnect_boot),
                    settings.reconnectOnBoot
                ) {
                    actions.composeSaveSettings(settings.copy(reconnectOnBoot = it))
                }
            }
        }

        item {
            SettingsSection(activity.getString(R.string.v15_settings_latency)) {
                BooleanSetting(
                    activity.getString(R.string.live_ping_enabled),
                    settings.livePingEnabled
                ) {
                    actions.composeSaveSettings(settings.copy(livePingEnabled = it))
                }
                ChoiceSetting(
                    label = activity.getString(R.string.v15_live_ping_method),
                    value = settings.livePingMethod,
                    choices = listOf(
                        PingMethod.TCP_CONNECT to activity.getString(R.string.ping_method_tcp),
                        PingMethod.XRAY_HTTP to activity.getString(R.string.ping_method_xray)
                    )
                ) {
                    actions.composeSaveSettings(settings.copy(livePingMethod = it))
                }
                NumberSetting(
                    label = activity.getString(R.string.v15_live_ping_interval),
                    value = settings.livePingIntervalSeconds,
                    range = 3..300
                ) {
                    actions.composeSaveSettings(
                        settings.copy(livePingIntervalSeconds = it)
                    )
                }
                ChoiceSetting(
                    label = activity.getString(R.string.v15_test_attempts),
                    value = settings.testAttempts,
                    choices = (2..5).map { it to it.toString() }
                ) {
                    actions.composeSaveSettings(settings.copy(testAttempts = it))
                }
                StringSetting(
                    label = activity.getString(R.string.v15_test_url),
                    value = settings.testUrl,
                    validator = ::validHttpUrl
                ) {
                    actions.composeSaveSettings(settings.copy(testUrl = it))
                }
            }
        }

        item {
            SettingsSection(activity.getString(R.string.v15_settings_real_delay)) {
                ChoiceSetting(
                    label = activity.getString(R.string.v15_real_profile),
                    value = settings.realDelayProfile,
                    choices = listOf(
                        RealDelayProfile.TURBO to activity.getString(R.string.real_delay_profile_turbo),
                        RealDelayProfile.BALANCED to activity.getString(R.string.real_delay_profile_balanced),
                        RealDelayProfile.ACCURATE to activity.getString(R.string.real_delay_profile_accurate),
                        RealDelayProfile.CUSTOM to activity.getString(R.string.real_delay_profile_custom)
                    )
                ) {
                    actions.composeSaveSettings(settings.copy(realDelayProfile = it))
                }
                NumberSetting(
                    activity.getString(R.string.v15_real_group_size),
                    settings.realDelayGroupSize,
                    2..16
                ) {
                    actions.composeSaveSettings(settings.copy(realDelayGroupSize = it))
                }
                NumberSetting(
                    activity.getString(R.string.v15_real_workers),
                    settings.realDelayWorkers,
                    1..6
                ) {
                    actions.composeSaveSettings(settings.copy(realDelayWorkers = it))
                }
                NumberSetting(
                    activity.getString(R.string.v15_real_timeout),
                    settings.realDelayProbeTimeoutMs,
                    1500..10000
                ) {
                    actions.composeSaveSettings(
                        settings.copy(realDelayProbeTimeoutMs = it)
                    )
                }
                NumberSetting(
                    activity.getString(R.string.v15_real_grace),
                    settings.realDelayStartGraceMs,
                    0..1000
                ) {
                    actions.composeSaveSettings(
                        settings.copy(realDelayStartGraceMs = it)
                    )
                }
                NumberSetting(
                    activity.getString(R.string.v15_real_targets),
                    settings.realDelayTargetCount,
                    1..4
                ) {
                    val proofs = settings.realDelayRequiredProofs.coerceAtMost(it)
                    actions.composeSaveSettings(
                        settings.copy(
                            realDelayTargetCount = it,
                            realDelayRequiredProofs = proofs
                        )
                    )
                }
                NumberSetting(
                    activity.getString(R.string.v15_real_proofs),
                    settings.realDelayRequiredProofs,
                    1..settings.realDelayTargetCount.coerceAtLeast(1)
                ) {
                    actions.composeSaveSettings(
                        settings.copy(realDelayRequiredProofs = it)
                    )
                }
            }
        }

        item {
            SettingsSection(activity.getString(R.string.v15_settings_dns)) {
                ChoiceSetting(
                    label = activity.getString(R.string.v15_dns_mode),
                    value = settings.dnsMode,
                    choices = listOf(
                        DnsMode.IMPORTED to activity.getString(R.string.dns_imported),
                        DnsMode.TRIVOX_DEFAULT to activity.getString(R.string.dns_default),
                        DnsMode.CUSTOM to activity.getString(R.string.dns_custom),
                        DnsMode.SYSTEM to activity.getString(R.string.dns_system),
                        DnsMode.DIRECT to activity.getString(R.string.dns_direct),
                        DnsMode.THROUGH_PROXY to activity.getString(R.string.dns_proxy)
                    )
                ) {
                    actions.composeSaveSettings(settings.copy(dnsMode = it))
                }
                if (settings.dnsMode == DnsMode.CUSTOM) {
                    StringSetting(
                        label = activity.getString(R.string.v15_custom_dns),
                        value = settings.customDns.joinToString("\n"),
                        singleLine = false,
                        validator = ::validDnsList
                    ) { text ->
                        val values = text.lineSequence()
                            .map(String::trim)
                            .filter(String::isNotBlank)
                            .distinct()
                            .toList()
                        actions.composeSaveSettings(settings.copy(customDns = values))
                    }
                }
            }
        }

        item {
            SettingsSection(activity.getString(R.string.v15_settings_network_tuning)) {
                BooleanSetting(
                    activity.getString(R.string.v15_network_tuning),
                    settings.networkTuningEnabled
                ) {
                    actions.composeSaveSettings(settings.copy(networkTuningEnabled = it))
                }
                BooleanSetting(
                    activity.getString(R.string.v15_adaptive_handshake),
                    settings.adaptiveHandshake
                ) {
                    actions.composeSaveSettings(settings.copy(adaptiveHandshake = it))
                }
                BooleanSetting(
                    activity.getString(R.string.v15_tcp_fast_open),
                    settings.tcpFastOpen
                ) {
                    actions.composeSaveSettings(settings.copy(tcpFastOpen = it))
                }
                NumberSetting(
                    activity.getString(R.string.v15_tcp_keep_idle),
                    settings.tcpKeepAliveIdleSeconds,
                    0..3600
                ) {
                    actions.composeSaveSettings(
                        settings.copy(tcpKeepAliveIdleSeconds = it)
                    )
                }
                NumberSetting(
                    activity.getString(R.string.v15_tcp_keep_interval),
                    settings.tcpKeepAliveIntervalSeconds,
                    0..600
                ) {
                    actions.composeSaveSettings(
                        settings.copy(tcpKeepAliveIntervalSeconds = it)
                    )
                }
                NumberSetting(
                    activity.getString(R.string.v15_tcp_user_timeout),
                    settings.tcpUserTimeoutMs,
                    0..120000
                ) {
                    actions.composeSaveSettings(settings.copy(tcpUserTimeoutMs = it))
                }
                NumberSetting(
                    activity.getString(R.string.v15_network_buffer),
                    settings.networkBufferSizeKb,
                    8..256
                ) {
                    actions.composeSaveSettings(settings.copy(networkBufferSizeKb = it))
                }
            }
        }

        item {
            SettingsSection(activity.getString(R.string.v15_settings_wireguard)) {
                NumberSetting(
                    activity.getString(R.string.v15_wireguard_mtu),
                    settings.wireGuardMtu,
                    576..9000
                ) {
                    actions.composeSaveSettings(settings.copy(wireGuardMtu = it))
                }
                ChoiceSetting(
                    label = activity.getString(R.string.v15_wireguard_workers),
                    value = settings.wireGuardWorkers,
                    choices = (1..8).map { it to it.toString() }
                ) {
                    actions.composeSaveSettings(settings.copy(wireGuardWorkers = it))
                }
                NumberSetting(
                    activity.getString(R.string.v15_wireguard_keepalive),
                    settings.wireGuardKeepAliveSeconds,
                    0..300
                ) {
                    actions.composeSaveSettings(
                        settings.copy(wireGuardKeepAliveSeconds = it)
                    )
                }
                NumberSetting(
                    activity.getString(R.string.v15_wireguard_handshake),
                    settings.wireGuardHandshakeTimeoutMs,
                    5000..60000
                ) {
                    actions.composeSaveSettings(
                        settings.copy(wireGuardHandshakeTimeoutMs = it)
                    )
                }
                ChoiceSetting(
                    label = activity.getString(R.string.v15_wireguard_strategy),
                    value = settings.wireGuardDomainStrategy,
                    choices = AppSettings.WIREGUARD_DOMAIN_STRATEGIES.map {
                        it to it
                    }
                ) {
                    actions.composeSaveSettings(
                        settings.copy(wireGuardDomainStrategy = it)
                    )
                }
            }
        }

        item {
            SettingsSection(activity.getString(R.string.v15_settings_updates)) {
                BooleanSetting(
                    activity.getString(R.string.v15_auto_update),
                    settings.autoUpdateCheck
                ) {
                    actions.composeSaveSettings(settings.copy(autoUpdateCheck = it))
                }
                OutlinedButton(
                    onClick = actions::composeCheckForUpdates,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(activity.getString(R.string.v15_check_update))
                }
                if (updateStatus.isNotBlank()) {
                    Text(
                        updateStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable
private fun BooleanSetting(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun <T> ChoiceSetting(
    label: String,
    value: T,
    choices: List<Pair<T, String>>,
    onSelected: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            choices.forEach { (candidate, display) ->
                FilterChip(
                    selected = value == candidate,
                    onClick = { if (value != candidate) onSelected(candidate) },
                    label = { Text(display) }
                )
            }
        }
    }
}

@Composable
private fun NumberSetting(
    label: String,
    value: Int,
    range: IntRange,
    onValidChange: (Int) -> Unit
) {
    var text by rememberSaveable(value) { mutableStateOf(value.toString()) }
    val parsed = text.trim().toIntOrNull()
    val valid = parsed != null && parsed in range

    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            text = next.filter { it.isDigit() }.take(7)
            val number = text.toIntOrNull()
            if (number != null && number in range && number != value) {
                onValidChange(number)
            }
        },
        label = { Text(label) },
        supportingText = {
            if (!valid) {
                Text("${range.first} … ${range.last}")
            }
        },
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun StringSetting(
    label: String,
    value: String,
    singleLine: Boolean = true,
    validator: (String) -> Boolean,
    onValidChange: (String) -> Unit
) {
    var text by rememberSaveable(value) { mutableStateOf(value) }
    val valid = validator(text)

    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            text = next
            if (validator(next) && next != value) {
                onValidChange(next.trim())
            }
        },
        label = { Text(label) },
        isError = !valid,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun validHttpUrl(value: String): Boolean =
    runCatching { URI(value.trim()) }
        .getOrNull()
        ?.let { uri ->
            uri.scheme?.lowercase() in setOf("http", "https") &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null
        } == true

private fun validDnsList(value: String): Boolean {
    val items = value.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .toList()
    return items.isNotEmpty() &&
        items.size <= 8 &&
        items.all(Validators::validateDns)
}
