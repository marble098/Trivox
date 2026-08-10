package com.trivox.client.ui.compose

// TRIVOX_V21_STABILITY_AUTO_LEAK_UI

// TRIVOX_V20_SAFE_NATIVE_LIFECYCLE

// TRIVOX_V19_NATIVE_WIREGUARD_LEAK_GUARD

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.sp
import com.trivox.client.R
import com.trivox.client.config.Validators
import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.ExperienceMode
import com.trivox.client.data.LauncherIconStyle
import com.trivox.client.data.NotificationActionChoice
import com.trivox.client.data.DnsMode
import com.trivox.client.data.PingMethod
import com.trivox.client.data.ProfileSortMode
import com.trivox.client.data.RealDelayProfile
import com.trivox.client.data.ThemeMode
import com.trivox.client.data.VisualTheme
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
        contentPadding = PaddingValues(horizontal = TrivoxUiTokens.pagePadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ExperienceModeShowcaseSection(
                activity = activity,
                settings = settings,
                onSelect = { mode ->
                    actions.composeSaveSettings(settings.copy(experienceMode = mode))
                }
            )
        }

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
                        ThemeMode.SYSTEM to activity.getString(R.string.v20_theme_system),
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
                ChoiceSetting(
                    label = activity.getString(R.string.v26_visual_theme),
                    value = settings.visualTheme,
                    choices = listOf(
                        VisualTheme.CLASSIC to activity.getString(R.string.v26_theme_classic),
                        VisualTheme.OCEAN to activity.getString(R.string.v26_theme_ocean),
                        VisualTheme.AURORA to activity.getString(R.string.v26_theme_aurora),
                        VisualTheme.SUNSET to activity.getString(R.string.v26_theme_sunset),
                        VisualTheme.FOREST to activity.getString(R.string.v26_theme_forest),
                        VisualTheme.GRAPHITE to activity.getString(R.string.v26_theme_graphite)
                    )
                ) { selected ->
                    actions.composeSaveSettings(settings.copy(visualTheme = selected))
                }
                ChoiceSetting(
                    label = activity.getString(R.string.v26_launcher_icon),
                    value = settings.launcherIconStyle,
                    choices = listOf(
                        LauncherIconStyle.DEFAULT to activity.getString(R.string.v26_icon_default),
                        LauncherIconStyle.OCEAN to activity.getString(R.string.v26_icon_ocean),
                        LauncherIconStyle.AURORA to activity.getString(R.string.v26_icon_aurora),
                        LauncherIconStyle.MONO to activity.getString(R.string.v26_icon_mono)
                    )
                ) { selected ->
                    actions.composeSaveSettings(settings.copy(launcherIconStyle = selected))
                }
                BooleanSetting(
                    activity.getString(R.string.hide_ip_on_main),
                    settings.hideIpOnMain
                ) {
                    actions.composeSaveSettings(settings.copy(hideIpOnMain = it))
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

        if (settings.experienceMode == ExperienceMode.ADVANCED) {
            item {
                SettingsSection(activity.getString(R.string.v37_home_layout_section)) {
                    Text(
                        activity.getString(R.string.v37_home_layout_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    BooleanSetting(
                        activity.getString(R.string.v37_home_show_details),
                        settings.homeShowConnectionDetails
                    ) {
                        actions.composeSaveSettings(settings.copy(homeShowConnectionDetails = it))
                    }
                    BooleanSetting(
                        activity.getString(R.string.v37_home_show_map),
                        settings.homeShowMap
                    ) {
                        actions.composeSaveSettings(settings.copy(homeShowMap = it))
                    }
                    BooleanSetting(
                        activity.getString(R.string.v37_home_show_usage),
                        settings.homeShowUsage
                    ) {
                        actions.composeSaveSettings(settings.copy(homeShowUsage = it))
                    }
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
                    actions.composeSaveSettings(
                        settings.copy(livePingEnabled = it)
                    )
                }
                Text(
                    activity.getString(R.string.v20_live_ping_auto_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ChoiceSetting(
                    label = activity.getString(R.string.v15_real_profile),
                    value = settings.realDelayProfile,
                    choices = listOf(
                        RealDelayProfile.TURBO to activity.getString(
                            R.string.real_delay_profile_turbo
                        ),
                        RealDelayProfile.BALANCED to activity.getString(
                            R.string.real_delay_profile_balanced
                        ),
                        RealDelayProfile.ACCURATE to activity.getString(
                            R.string.real_delay_profile_accurate
                        )
                    )
                ) {
                    actions.composeSaveSettings(
                        settings.copy(realDelayProfile = it)
                    )
                }
            }
        }

        item {
            SettingsSection(activity.getString(R.string.v15_settings_dns)) {
                BooleanSetting(
                    activity.getString(R.string.v19_leak_guard_toggle),
                    settings.autoLeakProtection
                ) {
                    actions.composeSaveSettings(
                        settings.copy(autoLeakProtection = it)
                    )
                }
                Text(
                    activity.getString(R.string.v21_leak_auto_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
            SettingsSection(activity.getString(R.string.v15_settings_wireguard)) {
                NumberSetting(
                    activity.getString(R.string.v15_wireguard_mtu),
                    settings.wireGuardMtu,
                    1280..1420
                ) {
                    actions.composeSaveSettings(
                        settings.copy(wireGuardMtu = it)
                    )
                }
                NumberSetting(
                    activity.getString(R.string.v15_wireguard_keepalive),
                    settings.wireGuardKeepAliveSeconds,
                    0..120
                ) {
                    actions.composeSaveSettings(
                        settings.copy(wireGuardKeepAliveSeconds = it)
                    )
                }
                Text(
                    activity.getString(R.string.v20_wireguard_auto_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }


        item {
            SettingsSection(activity.getString(R.string.v38_fragment_section)) {
                BooleanSetting(
                    activity.getString(R.string.v38_fragment_enabled),
                    settings.fragmentEnabled
                ) {
                    actions.composeSaveSettings(settings.copy(fragmentEnabled = it))
                }
                if (settings.fragmentEnabled) {
                    ChoiceSetting(
                        label = activity.getString(R.string.v38_fragment_packets),
                        value = settings.fragmentPackets,
                        choices = listOf(
                            "tlshello" to "TLS Hello",
                            "1-3" to "Packets 1-3",
                            "1-5" to "Packets 1-5"
                        )
                    ) {
                        actions.composeSaveSettings(settings.copy(fragmentPackets = it))
                    }
                    StringSetting(
                        label = activity.getString(R.string.v38_fragment_length),
                        value = settings.fragmentLength,
                        singleLine = true,
                        validator = { it.isNotBlank() }
                    ) {
                        actions.composeSaveSettings(settings.copy(fragmentLength = it))
                    }
                    StringSetting(
                        label = activity.getString(R.string.v38_fragment_interval),
                        value = settings.fragmentInterval,
                        singleLine = true,
                        validator = { it.isNotBlank() }
                    ) {
                        actions.composeSaveSettings(settings.copy(fragmentInterval = it))
                    }
                    Text(
                        activity.getString(R.string.v38_fragment_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

                item {
            SettingsSection(activity.getString(R.string.v26_notification_section)) {
                val actionChoices = notificationActionChoices(activity)
                ChoiceSetting(
                    label = activity.getString(R.string.v26_notification_action_1),
                    value = settings.notificationAction1,
                    choices = actionChoices
                ) {
                    actions.composeSaveSettings(settings.copy(notificationAction1 = it))
                }
                ChoiceSetting(
                    label = activity.getString(R.string.v26_notification_action_2),
                    value = settings.notificationAction2,
                    choices = actionChoices
                ) {
                    actions.composeSaveSettings(settings.copy(notificationAction2 = it))
                }
                ChoiceSetting(
                    label = activity.getString(R.string.v26_notification_action_3),
                    value = settings.notificationAction3,
                    choices = actionChoices
                ) {
                    actions.composeSaveSettings(settings.copy(notificationAction3 = it))
                }
                NumberSetting(
                    label = activity.getString(R.string.v26_pause_minutes),
                    value = settings.notificationPauseMinutes,
                    range = 1..180
                ) {
                    actions.composeSaveSettings(settings.copy(notificationPauseMinutes = it))
                }
                Text(
                    activity.getString(R.string.v26_notification_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SettingsSection(activity.getString(R.string.v26_community_section)) {
                BooleanSetting(
                    activity.getString(R.string.v26_community_enabled),
                    settings.communitySourceEnabled
                ) {
                    actions.composeSaveSettings(settings.copy(communitySourceEnabled = it))
                }
                BooleanSetting(
                    activity.getString(R.string.v26_community_auto_sync),
                    settings.communityAutoSync
                ) {
                    actions.composeSaveSettings(settings.copy(communityAutoSync = it))
                }
                Text(
                    activity.getString(R.string.v26_community_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // trivox-v33-community-buttons: one full-width button per row
                OutlinedButton(
                    onClick = actions::composeCommunitySync,
                    enabled = settings.communitySourceEnabled &&
                        !actions.composeCommunitySyncBusy(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(activity.getString(R.string.v26_community_sync))
                }
                OutlinedButton(
                    onClick = actions::composeOpenCommunityChannel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(activity.getString(R.string.v26_community_open))
                }
                actions.composeCommunityStatus().takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
    TrivoxInsetGroup(title = title, content = content)
}

/**
 * Settings surface shown while the app is in Simple mode: only the choices a
 * casual user needs. Everything technical (ports, DNS, WireGuard tuning,
 * notification action mapping) lives in Advanced mode's full settings only.
 */
@Composable
internal fun SimpleSettingsScreen(
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
        contentPadding = PaddingValues(horizontal = TrivoxUiTokens.pagePadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                        ThemeMode.SYSTEM to activity.getString(R.string.v20_theme_system),
                        ThemeMode.LIGHT to activity.getString(R.string.v15_theme_light),
                        ThemeMode.DARK to activity.getString(R.string.v15_theme_dark)
                    )
                ) { selected ->
                    actions.composeSaveSettings(
                        settings.copy(themeMode = selected, darkMode = selected == ThemeMode.DARK)
                    )
                }
                ChoiceSetting(
                    label = activity.getString(R.string.v26_visual_theme),
                    value = settings.visualTheme,
                    choices = listOf(
                        VisualTheme.CLASSIC to activity.getString(R.string.v26_theme_classic),
                        VisualTheme.OCEAN to activity.getString(R.string.v26_theme_ocean),
                        VisualTheme.AURORA to activity.getString(R.string.v26_theme_aurora),
                        VisualTheme.SUNSET to activity.getString(R.string.v26_theme_sunset),
                        VisualTheme.FOREST to activity.getString(R.string.v26_theme_forest),
                        VisualTheme.GRAPHITE to activity.getString(R.string.v26_theme_graphite)
                    )
                ) { selected ->
                    actions.composeSaveSettings(settings.copy(visualTheme = selected))
                }
                BooleanSetting(
                    activity.getString(R.string.hide_ip_on_main),
                    settings.hideIpOnMain
                ) {
                    actions.composeSaveSettings(settings.copy(hideIpOnMain = it))
                }
            }
        }

        item {
            SettingsSection(activity.getString(R.string.v26_community_section)) {
                BooleanSetting(
                    activity.getString(R.string.v26_community_enabled),
                    settings.communitySourceEnabled
                ) {
                    actions.composeSaveSettings(settings.copy(communitySourceEnabled = it))
                }
                BooleanSetting(
                    activity.getString(R.string.v26_community_auto_sync),
                    settings.communityAutoSync
                ) {
                    actions.composeSaveSettings(settings.copy(communityAutoSync = it))
                }
                Text(
                    activity.getString(R.string.v26_community_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = actions::composeCommunitySync,
                    enabled = settings.communitySourceEnabled && !actions.composeCommunitySyncBusy(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(activity.getString(R.string.v26_community_sync))
                }
                OutlinedButton(
                    onClick = actions::composeOpenCommunityChannel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(activity.getString(R.string.v26_community_open))
                }
                actions.composeCommunityStatus().takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

        item {
            OutlinedButton(
                onClick = {
                    actions.composeSaveSettings(settings.copy(experienceMode = ExperienceMode.ADVANCED))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(activity.getString(R.string.v37_switch_to_advanced))
            }
        }
    }
}

@Composable
private fun ExperienceModeShowcaseSection(
    activity: Activity,
    settings: AppSettings,
    onSelect: (ExperienceMode) -> Unit
) {
    SettingsSection(activity.getString(R.string.v34_home_tools_title)) {
        Text(
            text = activity.getString(R.string.v34_home_tools_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ExperienceModeShowcaseCard(
                selected = settings.experienceMode == ExperienceMode.SIMPLE,
                title = activity.getString(R.string.v34_mode_simple_title),
                description = activity.getString(R.string.v34_mode_simple_desc),
                primaryGlyph = "◯",
                secondaryGlyph = "▭",
                onClick = { onSelect(ExperienceMode.SIMPLE) }
            )
            ExperienceModeShowcaseCard(
                selected = settings.experienceMode == ExperienceMode.ADVANCED,
                title = activity.getString(R.string.v34_mode_advanced_title),
                description = activity.getString(R.string.v34_mode_advanced_desc),
                primaryGlyph = "▦",
                secondaryGlyph = "◫",
                onClick = { onSelect(ExperienceMode.ADVANCED) }
            )
        }
        Text(
            text = activity.getString(R.string.v34_theme_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ExperienceModeShowcaseCard(
    selected: Boolean,
    title: String,
    description: String,
    primaryGlyph: String,
    secondaryGlyph: String,
    onClick: () -> Unit
) {
    val border = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.88f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.88f)
    }
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.52f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    Card(
        onClick = onClick,
        shape = TrivoxOuterShape,
        border = BorderStroke(1.dp, border),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .border(1.dp, border, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(primaryGlyph, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(secondaryGlyph, fontSize = 14.sp)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
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
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        TrivoxToggle(
            checked = checked,
            onCheckedChange = { onCheckedChange(it) }
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


private fun notificationActionChoices(
    activity: Activity
): List<Pair<NotificationActionChoice, String>> = listOf(
    NotificationActionChoice.SWITCH to activity.getString(R.string.v26_notify_switch),
    NotificationActionChoice.NEXT to activity.getString(R.string.v26_notify_next),
    NotificationActionChoice.PAUSE to activity.getString(R.string.v26_notify_pause),
    NotificationActionChoice.STOP to activity.getString(R.string.v26_notify_stop),
    NotificationActionChoice.NONE to activity.getString(R.string.v26_notify_none)
)

@Suppress("unused")
private fun validTelegramChannel(value: String): Boolean {
    val normalized = value.trim()
        .removePrefix("https://t.me/")
        .removePrefix("http://t.me/")
        .removePrefix("t.me/")
        .removePrefix("@")
        .substringBefore('/')
    return normalized.matches(Regex("[A-Za-z0-9_]{5,32}"))
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
