package com.trivox.client.ui.compose

// TRIVOX_V37_EXPRESSIVE_2026_SETTINGS

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trivox.client.R
import com.trivox.client.config.Validators
import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.DnsMode
import com.trivox.client.data.ExperienceMode
import com.trivox.client.data.LauncherIconStyle
import com.trivox.client.data.NotificationActionChoice
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
        contentPadding = PaddingValues(
            horizontal = TrivoxUiTokens.pagePadding,
            vertical = 10.dp
        ),
        verticalArrangement = Arrangement.spacedBy(TrivoxUiTokens.groupSpacing)
    ) {
        if (settings.experienceMode == ExperienceMode.SIMPLE) {
            item {
                SettingsSection(activity.getString(R.string.v37_simple_essentials)) {
                    ChoiceSetting(
                        label = activity.getString(R.string.v15_language),
                        value = language,
                        choices = languageChoices(activity),
                        onSelected = actions::composeSetLanguage
                    )
                    ChoiceSetting(
                        label = activity.getString(R.string.v26_experience_mode),
                        value = settings.experienceMode,
                        choices = experienceChoices(activity)
                    ) { selected ->
                        actions.composeSaveSettings(settings.copy(experienceMode = selected))
                    }
                    ChoiceSetting(
                        label = activity.getString(R.string.v15_theme),
                        value = settings.themeMode,
                        choices = themeChoices(activity)
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
                        choices = visualThemeChoices(activity)
                    ) { selected ->
                        actions.composeSaveSettings(settings.copy(visualTheme = selected))
                    }
                }
            }

            item {
                SettingsSection(activity.getString(R.string.v37_simple_connection)) {
                    ChoiceSetting(
                        label = activity.getString(R.string.connection_mode),
                        value = settings.mode,
                        choices = connectionChoices(activity)
                    ) { selected ->
                        actions.composeSaveSettings(settings.copy(mode = selected))
                    }
                    BooleanSetting(
                        activity.getString(R.string.live_ping_enabled),
                        settings.livePingEnabled
                    ) { enabled ->
                        actions.composeSaveSettings(settings.copy(livePingEnabled = enabled))
                    }
                    BooleanSetting(
                        activity.getString(R.string.v19_leak_guard_toggle),
                        settings.autoLeakProtection
                    ) { enabled ->
                        actions.composeSaveSettings(settings.copy(autoLeakProtection = enabled))
                    }
                    BooleanSetting(
                        activity.getString(R.string.hide_ip_on_main),
                        settings.hideIpOnMain
                    ) { enabled ->
                        actions.composeSaveSettings(settings.copy(hideIpOnMain = enabled))
                    }
                }
            }

            item {
                SettingsSection(activity.getString(R.string.v15_settings_updates)) {
                    BooleanSetting(
                        activity.getString(R.string.v15_auto_update),
                        settings.autoUpdateCheck
                    ) { enabled ->
                        actions.composeSaveSettings(settings.copy(autoUpdateCheck = enabled))
                    }
                    OutlinedButton(
                        onClick = actions::composeCheckForUpdates,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(activity.getString(R.string.v15_check_update))
                    }
                    if (updateStatus.isNotBlank()) {
                        StatusText(updateStatus)
                    }
                }
            }
            return@LazyColumn
        }

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
                    choices = languageChoices(activity),
                    onSelected = actions::composeSetLanguage
                )
                ChoiceSetting(
                    label = activity.getString(R.string.v15_theme),
                    value = settings.themeMode,
                    choices = themeChoices(activity)
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
                    choices = visualThemeChoices(activity)
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
            }
        }

        item {
            SettingsSection(activity.getString(R.string.v37_home_personalization)) {
                BooleanSetting(
                    activity.getString(R.string.v37_home_usage),
                    settings.homeShowUsage
                ) { enabled ->
                    actions.composeSaveSettings(settings.copy(homeShowUsage = enabled))
                }
                BooleanSetting(
                    activity.getString(R.string.v37_home_map),
                    settings.homeShowMap
                ) { enabled ->
                    actions.composeSaveSettings(settings.copy(homeShowMap = enabled))
                }
                BooleanSetting(
                    activity.getString(R.string.v37_home_details),
                    settings.homeShowConnectionDetails
                ) { enabled ->
                    actions.composeSaveSettings(settings.copy(homeShowConnectionDetails = enabled))
                }
                BooleanSetting(
                    activity.getString(R.string.v37_home_leak_guard),
                    settings.homeShowLeakGuard
                ) { enabled ->
                    actions.composeSaveSettings(settings.copy(homeShowLeakGuard = enabled))
                }
                BooleanSetting(
                    activity.getString(R.string.hide_ip_on_main),
                    settings.hideIpOnMain
                ) { enabled ->
                    actions.composeSaveSettings(settings.copy(hideIpOnMain = enabled))
                }
            }
        }

        item {
            SettingsSection(activity.getString(R.string.v37_config_list)) {
                BooleanSetting(
                    activity.getString(R.string.compose_grid_layout),
                    settings.gridMode
                ) { enabled ->
                    actions.composeSaveSettings(settings.copy(gridMode = enabled))
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
                ) { selected ->
                    actions.composeSaveSettings(settings.copy(sortMode = selected))
                }
            }
        }

        item {
            SettingsSection(activity.getString(R.string.v15_settings_connection)) {
                ChoiceSetting(
                    label = activity.getString(R.string.connection_mode),
                    value = settings.mode,
                    choices = connectionChoices(activity)
                ) { selected ->
                    actions.composeSaveSettings(settings.copy(mode = selected))
                }
                NumberSetting(
                    label = activity.getString(R.string.v15_mixed_port),
                    value = settings.socksPort,
                    range = 1..65535
                ) { value ->
                    actions.composeSaveSettings(settings.copy(socksPort = value, httpPort = value))
                }
                StatusText(actions.composeLocalProxyStatus())
                BooleanSetting(
                    activity.getString(R.string.v15_local_proxy_in_vpn),
                    settings.localProxyInVpn
                ) { enabled ->
                    actions.composeSaveSettings(settings.copy(localProxyInVpn = enabled))
                }
                BooleanSetting(
                    activity.getString(R.string.v15_ipv6),
                    settings.ipv6
                ) { enabled ->
                    actions.composeSaveSettings(settings.copy(ipv6 = enabled))
                }
                BooleanSetting(
                    activity.getString(R.string.v15_blocking),
                    settings.blocking
                ) { enabled ->
                    actions.composeSaveSettings(settings.copy(blocking = enabled))
                }
                BooleanSetting(
                    activity.getString(R.string.v15_reconnect_network),
                    settings.reconnectOnNetworkChange
                ) { enabled ->
                    actions.composeSaveSettings(settings.copy(reconnectOnNetworkChange = enabled))
                }
                BooleanSetting(
                    activity.getString(R.string.v15_reconnect_boot),
                    settings.reconnectOnBoot
                ) { enabled ->
                    actions.composeSaveSettings(settings.copy(reconnectOnBoot = enabled))
                }
            }
        }

        item {
            SettingsSection(activity.getString(R.string.v15_settings_latency)) {
                BooleanSetting(
                    activity.getString(R.string.live_ping_enabled),
                    settings.livePingEnabled
                ) { enabled ->
                    actions.composeSaveSettings(settings.copy(livePingEnabled = enabled))
                }
                ChoiceSetting(
                    label = activity.getString(R.string.v15_real_profile),
                    value = settings.realDelayProfile,
                    choices = listOf(
                        RealDelayProfile.TURBO to activity.getString(R.string.real_delay_profile_turbo),
                        RealDelayProfile.BALANCED to activity.getString(R.string.real_delay_profile_balanced),
                        RealDelayProfile.ACCURATE to activity.getString(R.string.real_delay_profile_accurate)
                    )
                ) { selected ->
                    actions.composeSaveSettings(settings.copy(realDelayProfile = selected))
                }
            }
        }

        item {
            SettingsSection(activity.getString(R.string.v15_settings_dns)) {
                BooleanSetting(
                    activity.getString(R.string.v19_leak_guard_toggle),
                    settings.autoLeakProtection
                ) { enabled ->
                    actions.composeSaveSettings(settings.copy(autoLeakProtection = enabled))
                }
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
                ) { selected ->
                    actions.composeSaveSettings(settings.copy(dnsMode = selected))
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
                ) { value ->
                    actions.composeSaveSettings(settings.copy(wireGuardMtu = value))
                }
                NumberSetting(
                    activity.getString(R.string.v15_wireguard_keepalive),
                    settings.wireGuardKeepAliveSeconds,
                    0..120
                ) { value ->
                    actions.composeSaveSettings(settings.copy(wireGuardKeepAliveSeconds = value))
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
                ) { selected ->
                    actions.composeSaveSettings(settings.copy(notificationAction1 = selected))
                }
                ChoiceSetting(
                    label = activity.getString(R.string.v26_notification_action_2),
                    value = settings.notificationAction2,
                    choices = actionChoices
                ) { selected ->
                    actions.composeSaveSettings(settings.copy(notificationAction2 = selected))
                }
                ChoiceSetting(
                    label = activity.getString(R.string.v26_notification_action_3),
                    value = settings.notificationAction3,
                    choices = actionChoices
                ) { selected ->
                    actions.composeSaveSettings(settings.copy(notificationAction3 = selected))
                }
                NumberSetting(
                    label = activity.getString(R.string.v26_pause_minutes),
                    value = settings.notificationPauseMinutes,
                    range = 1..180
                ) { value ->
                    actions.composeSaveSettings(settings.copy(notificationPauseMinutes = value))
                }
            }
        }

        item {
            SettingsSection(activity.getString(R.string.v26_community_section)) {
                BooleanSetting(
                    activity.getString(R.string.v26_community_enabled),
                    settings.communitySourceEnabled
                ) { enabled ->
                    actions.composeSaveSettings(settings.copy(communitySourceEnabled = enabled))
                }
                BooleanSetting(
                    activity.getString(R.string.v26_community_auto_sync),
                    settings.communityAutoSync
                ) { enabled ->
                    actions.composeSaveSettings(settings.copy(communityAutoSync = enabled))
                }
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
                    StatusText(it)
                }
            }
        }

        item {
            SettingsSection(activity.getString(R.string.v15_settings_updates)) {
                BooleanSetting(
                    activity.getString(R.string.v15_auto_update),
                    settings.autoUpdateCheck
                ) { enabled ->
                    actions.composeSaveSettings(settings.copy(autoUpdateCheck = enabled))
                }
                OutlinedButton(
                    onClick = actions::composeCheckForUpdates,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(activity.getString(R.string.v15_check_update))
                }
                if (updateStatus.isNotBlank()) {
                    StatusText(updateStatus)
                }
            }
        }
    }
}

@Composable
private fun StatusText(value: String) {
    if (value.isBlank()) return
    Text(
        text = value,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    TrivoxInsetGroup(title = title, content = content)
}

@Composable
private fun ExperienceModeShowcaseSection(
    activity: Activity,
    settings: AppSettings,
    onSelect: (ExperienceMode) -> Unit
) {
    SettingsSection(activity.getString(R.string.v37_experience_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ExperienceModeShowcaseCard(
                selected = settings.experienceMode == ExperienceMode.SIMPLE,
                title = activity.getString(R.string.v34_mode_simple_title),
                description = activity.getString(R.string.v37_mode_simple_short),
                primaryGlyph = "◯",
                secondaryGlyph = "▭",
                onClick = { onSelect(ExperienceMode.SIMPLE) }
            )
            ExperienceModeShowcaseCard(
                selected = settings.experienceMode == ExperienceMode.ADVANCED,
                title = activity.getString(R.string.v34_mode_advanced_title),
                description = activity.getString(R.string.v37_mode_advanced_short),
                primaryGlyph = "▦",
                secondaryGlyph = "◫",
                onClick = { onSelect(ExperienceMode.ADVANCED) }
            )
        }
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
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
    }
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.52f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Card(
        onClick = onClick,
        shape = TrivoxInnerShape,
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
                    .size(52.dp)
                    .border(1.dp, border, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(primaryGlyph, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(secondaryGlyph, fontSize = 13.sp)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
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
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        TrivoxToggle(
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
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
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
                    label = { Text(display, maxLines = 1) }
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
            if (!valid) Text("${range.first} … ${range.last}")
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

private fun languageChoices(activity: Activity) = listOf(
    "" to activity.getString(R.string.language_system),
    "fa" to activity.getString(R.string.language_persian),
    "en" to activity.getString(R.string.language_english)
)

private fun experienceChoices(activity: Activity) = listOf(
    ExperienceMode.SIMPLE to activity.getString(R.string.v26_mode_simple),
    ExperienceMode.ADVANCED to activity.getString(R.string.v26_mode_advanced)
)

private fun themeChoices(activity: Activity) = listOf(
    ThemeMode.SYSTEM to activity.getString(R.string.v20_theme_system),
    ThemeMode.LIGHT to activity.getString(R.string.v15_theme_light),
    ThemeMode.DARK to activity.getString(R.string.v15_theme_dark)
)

private fun visualThemeChoices(activity: Activity) = listOf(
    VisualTheme.CLASSIC to activity.getString(R.string.v26_theme_classic),
    VisualTheme.OCEAN to activity.getString(R.string.v26_theme_ocean),
    VisualTheme.AURORA to activity.getString(R.string.v26_theme_aurora),
    VisualTheme.SUNSET to activity.getString(R.string.v26_theme_sunset),
    VisualTheme.FOREST to activity.getString(R.string.v26_theme_forest),
    VisualTheme.GRAPHITE to activity.getString(R.string.v26_theme_graphite)
)

private fun connectionChoices(activity: Activity) = listOf(
    ConnectionMode.VPN to activity.getString(R.string.vpn_mode),
    ConnectionMode.PROXY to activity.getString(R.string.proxy_mode)
)

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
