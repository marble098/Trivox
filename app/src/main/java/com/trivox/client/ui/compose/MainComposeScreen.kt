package com.trivox.client.ui.compose

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trivox.client.R
import com.trivox.client.core.ConnectionRuntime
import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.ConnectionState
import com.trivox.client.data.PingMethod
import com.trivox.client.data.SubscriptionSource
import com.trivox.client.data.TestStatus
import kotlinx.coroutines.delay
import java.util.Locale

interface MainComposeActions {
    fun composeProfiles(query: String, subscriptionId: String?, favoritesOnly: Boolean): List<ConfigProfile>
    fun composeSubscriptions(): List<SubscriptionSource>
    fun composeSelectedId(): String?
    fun composeRuntime(): ConnectionRuntime.Snapshot
    fun composeSettings(): AppSettings
    fun composeActiveSubscriptionId(): String?
    fun composeSelectSubscription(id: String?)
    fun composeSelectProfile(id: String)
    fun composeProfileActions(id: String)
    fun composePingProfile(id: String, method: PingMethod)
    fun composeAdd()
    fun composeRefreshSubscriptions()
    fun composeTestAll(method: PingMethod)
    fun composeToggleConnection()
    fun composePause()
    fun composeLivePing()
    fun composeRealDelay()
    fun composeRefreshExit()
    fun composeCopySummary()
    fun composeSelectFastest()
    fun composeCopyProxy()
    fun composeExportBackup()
    fun composeClearDead()
    fun composeOpenSettings()
    fun composeOpenRouting()
    fun composeOpenDiagnostics()
    fun composeOpenSubscriptions(sourceId: String? = null)
    fun composeToggleGrid()
    fun composeRequestQuickTile()
    fun composeSetMode(mode: ConnectionMode)
    fun composeLivePingLabel(): String
    fun composeRealDelayLabel(): String
}

private enum class MainTab(val glyph: String) {
    HOME("⌂"), CONFIGS("▦"), SUBSCRIPTIONS("⇅"), TOOLS("⌁"), SETTINGS("⚙")
}

@Composable
fun MainComposeScreen(activity: Activity, actions: MainComposeActions) {
    TrivoxTheme(activity) {
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                tick++
            }
        }
        @Suppress("UNUSED_EXPRESSION")
        tick
        var tab by rememberSaveable { mutableStateOf(MainTab.HOME) }
        Scaffold(
            topBar = { MainTopBar(activity, tab) },
            bottomBar = {
                NavigationBar {
                    MainTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Text(item.glyph) },
                            label = {
                                Text(
                                    when (item) {
                                        MainTab.HOME -> activity.getString(R.string.tab_home)
                                        MainTab.CONFIGS -> activity.getString(R.string.tab_configs)
                                        MainTab.SUBSCRIPTIONS -> activity.getString(R.string.tab_subscriptions)
                                        MainTab.TOOLS -> activity.getString(R.string.tab_tools)
                                        MainTab.SETTINGS -> activity.getString(R.string.tab_settings)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }
        ) { padding ->
            when (tab) {
                MainTab.HOME -> HomeTab(activity, actions, Modifier.padding(padding))
                MainTab.CONFIGS -> ConfigsTab(activity, actions, Modifier.padding(padding))
                MainTab.SUBSCRIPTIONS -> SubscriptionsTab(activity, actions, Modifier.padding(padding))
                MainTab.TOOLS -> ToolsTab(activity, actions, Modifier.padding(padding))
                MainTab.SETTINGS -> SettingsTab(activity, actions, Modifier.padding(padding))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(activity: Activity, tab: MainTab) {
    TopAppBar(
        title = {
            Column {
                Text(activity.getString(R.string.app_name), fontWeight = FontWeight.Bold)
                Text(
                    when (tab) {
                        MainTab.HOME -> activity.getString(R.string.compose_connection_overview)
                        MainTab.CONFIGS -> activity.getString(R.string.tab_configs)
                        MainTab.SUBSCRIPTIONS -> activity.getString(R.string.tab_subscriptions)
                        MainTab.TOOLS -> activity.getString(R.string.tab_tools)
                        MainTab.SETTINGS -> activity.getString(R.string.tab_settings)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Composable
private fun HomeTab(activity: Activity, actions: MainComposeActions, modifier: Modifier = Modifier) {
    val runtime = actions.composeRuntime()
    val settings = actions.composeSettings()
    val profiles = actions.composeProfiles("", null, false)
    val selected = profiles.firstOrNull { it.id == (runtime.profileId ?: actions.composeSelectedId()) }
    val connected = runtime.state !in setOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)
    val durationMs = if (runtime.startedElapsed > 0) {
        (android.os.SystemClock.elapsedRealtime() - runtime.startedElapsed).coerceAtLeast(0L)
    } else 0L
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stateLabel(activity, runtime.state), style = MaterialTheme.typography.titleLarge)
                            Text(
                                selected?.name ?: activity.getString(R.string.select_profile),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(formatDuration(durationMs), style = MaterialTheme.typography.titleMedium)
                    }
                    if (runtime.error.isNotBlank()) {
                        Text(runtime.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.mode == ConnectionMode.VPN,
                            onClick = { if (!connected) actions.composeSetMode(ConnectionMode.VPN) },
                            label = { Text(activity.getString(R.string.vpn_mode)) },
                            enabled = !connected
                        )
                        FilterChip(
                            selected = settings.mode == ConnectionMode.PROXY,
                            onClick = { if (!connected) actions.composeSetMode(ConnectionMode.PROXY) },
                            label = { Text(activity.getString(R.string.proxy_mode)) },
                            enabled = !connected
                        )
                    }
                    Button(
                        onClick = { actions.composeToggleConnection() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (connected) activity.getString(R.string.disconnect) else activity.getString(R.string.connect))
                    }
                    if (runtime.state == ConnectionState.CONNECTED) {
                        OutlinedButton(onClick = { actions.composePause() }, modifier = Modifier.fillMaxWidth()) {
                            Text(activity.getString(R.string.pause_connection))
                        }
                    }
                }
            }
        }
        item {
            SectionCard(title = activity.getString(R.string.live_ping_method)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallAction(actions.composeLivePingLabel().ifBlank { activity.getString(R.string.live_ping_off) }, Modifier.weight(1f)) { actions.composeLivePing() }
                    SmallAction(actions.composeRealDelayLabel().ifBlank { activity.getString(R.string.real_delay_off) }, Modifier.weight(1f)) { actions.composeRealDelay() }
                }
                val exit = selected?.let { profile ->
                    listOf(profile.exitFlag, profile.exitCountry, profile.exitIp, profile.exitIsp)
                        .filter { it.isNotBlank() }.joinToString(" • ")
                }.orEmpty()
                Text(
                    exit.ifBlank { activity.getString(R.string.exit_info_off) },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { actions.composeRefreshExit() }, modifier = Modifier.weight(1f), enabled = runtime.state == ConnectionState.CONNECTED) {
                        Text(activity.getString(R.string.refresh_exit_info))
                    }
                    OutlinedButton(onClick = { actions.composeCopySummary() }, modifier = Modifier.weight(1f), enabled = runtime.state == ConnectionState.CONNECTED) {
                        Text(activity.getString(R.string.copy_summary))
                    }
                }
            }
        }
        item {
            val alive = profiles.count { it.tcpTestStatus == TestStatus.ALIVE || it.realTestStatus == TestStatus.ALIVE }
            val dead = profiles.count { it.tcpTestStatus == TestStatus.DEAD || it.realTestStatus == TestStatus.DEAD }
            SectionCard(title = activity.getString(R.string.profile_details)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricChip("${profiles.size}", activity.getString(R.string.configurations), Modifier.weight(1f))
                    MetricChip("$alive", activity.getString(R.string.status_alive), Modifier.weight(1f))
                    MetricChip("$dead", activity.getString(R.string.status_dead), Modifier.weight(1f))
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun ConfigsTab(activity: Activity, actions: MainComposeActions, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var selectedSource by rememberSaveable { mutableStateOf(actions.composeActiveSubscriptionId()) }
    val sources = actions.composeSubscriptions()
    val profiles = actions.composeProfiles(query, selectedSource, favoritesOnly)
    val selectedId = actions.composeSelectedId()
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(activity.getString(R.string.search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { actions.composeAdd() }, label = { Text("＋ ${activity.getString(R.string.add)}") })
                AssistChip(onClick = { actions.composeTestAll(PingMethod.TCP_CONNECT) }, label = { Text(activity.getString(R.string.tcp_test)) })
                AssistChip(onClick = { actions.composeTestAll(PingMethod.XRAY_HTTP) }, label = { Text(activity.getString(R.string.real_delay_all_icon)) })
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                FilterChip(
                    selected = selectedSource == null,
                    onClick = {
                        selectedSource = null
                        actions.composeSelectSubscription(null)
                    },
                    label = { Text(activity.getString(R.string.subscription_all_count, actions.composeProfiles("", null, false).size)) }
                )
                FilterChip(
                    selected = favoritesOnly,
                    onClick = { favoritesOnly = !favoritesOnly },
                    label = { Text("★ ${activity.getString(R.string.compose_favorites_only)}") }
                )
            }
        }
        if (sources.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    sources.forEach { source ->
                        FilterChip(
                            selected = selectedSource == source.id,
                            onClick = {
                                selectedSource = source.id
                                actions.composeSelectSubscription(source.id)
                            },
                            label = { Text("${source.name} (${actions.composeProfiles("", source.id, false).size})") }
                        )
                    }
                }
            }
        }
        if (profiles.isEmpty()) {
            item { EmptyCard(activity.getString(R.string.no_profiles)) }
        } else {
            items(profiles, key = { it.id }) { profile ->
                ProfileCard(activity, profile, profile.id == selectedId, actions)
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun ProfileCard(activity: Activity, profile: ConfigProfile, selected: Boolean, actions: MainComposeActions) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { actions.composeSelectProfile(profile.id) },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text((if (profile.favorite) "★ " else "") + profile.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${profile.protocol.uppercase(Locale.ROOT)} • ${profile.server}:${profile.port}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { actions.composeProfileActions(profile.id) }) { Text("⋮") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LatencyPill("TCP", profile.tcpLatencyMs, profile.tcpTestStatus)
                LatencyPill("REAL", profile.realLatencyMs, profile.realTestStatus)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { actions.composePingProfile(profile.id, PingMethod.TCP_CONNECT) }) { Text("TCP") }
                TextButton(onClick = { actions.composePingProfile(profile.id, PingMethod.XRAY_HTTP) }) { Text("Real") }
            }
            if (profile.exitCountry.isNotBlank() || profile.exitIp.isNotBlank()) {
                Text(listOf(profile.exitFlag, profile.exitCountry, profile.exitIp).filter { it.isNotBlank() }.joinToString(" "), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SubscriptionsTab(activity: Activity, actions: MainComposeActions, modifier: Modifier = Modifier) {
    val sources = actions.composeSubscriptions()
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.composeOpenSubscriptions(null) }, modifier = Modifier.weight(1f)) { Text(activity.getString(R.string.add_subscription)) }
                OutlinedButton(onClick = { actions.composeRefreshSubscriptions() }, modifier = Modifier.weight(1f)) { Text(activity.getString(R.string.update_all_subscriptions)) }
            }
        }
        if (sources.isEmpty()) {
            item { EmptyCard(activity.getString(R.string.no_subscriptions)) }
        } else {
            items(sources, key = { it.id }) { source ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { actions.composeOpenSubscriptions(source.id) },
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(source.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(if (source.enabled) activity.getString(R.string.enabled) else activity.getString(R.string.disabled), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            if (source.url.isBlank()) activity.getString(R.string.nordvpn_source_label) else source.url,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text("${actions.composeProfiles("", source.id, false).size} ${activity.getString(R.string.configurations)}", style = MaterialTheme.typography.bodySmall)
                        if (source.lastError.isNotBlank()) Text(source.lastError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun ToolsTab(activity: Activity, actions: MainComposeActions, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        item { ToolButton(activity.getString(R.string.select_fastest), activity.getString(R.string.compose_tool_fastest_summary)) { actions.composeSelectFastest() } }
        item { ToolButton(activity.getString(R.string.copy_local_proxy), activity.getString(R.string.compose_tool_proxy_summary)) { actions.composeCopyProxy() } }
        item { ToolButton(activity.getString(R.string.export_backup), activity.getString(R.string.compose_tool_backup_summary)) { actions.composeExportBackup() } }
        item { ToolButton(activity.getString(R.string.clear_dead_profiles), activity.getString(R.string.status_dead)) { actions.composeClearDead() } }
        item { ToolButton(activity.getString(R.string.app_routing), activity.getString(R.string.compose_tool_routing_summary)) { actions.composeOpenRouting() } }
        item { ToolButton(activity.getString(R.string.diagnostics), activity.getString(R.string.sanitized_report)) { actions.composeOpenDiagnostics() } }
        item { ToolButton(activity.getString(R.string.quick_tile_add), activity.getString(R.string.quick_tile_add_summary)) { actions.composeRequestQuickTile() } }
        item { ToolButton(activity.getString(R.string.grid), activity.getString(R.string.compose_tool_view_summary)) { actions.composeToggleGrid() } }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun SettingsTab(activity: Activity, actions: MainComposeActions, modifier: Modifier = Modifier) {
    val settings = actions.composeSettings()
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            SectionCard(title = activity.getString(R.string.settings)) {
                SettingSummary(activity.getString(R.string.connection_mode), if (settings.mode == ConnectionMode.VPN) activity.getString(R.string.vpn_mode) else activity.getString(R.string.proxy_mode))
                SettingSummary(activity.getString(R.string.dns_section), settings.dnsMode.name)
                SettingSummary(activity.getString(R.string.live_ping_method), settings.livePingMethod.name)
                SettingSummary(activity.getString(R.string.profile_sorting_section), settings.sortMode.name)
                SettingSummary(activity.getString(R.string.mixed_port), settings.socksPort.toString())
            }
        }
        item {
            Button(onClick = { actions.composeOpenSettings() }, modifier = Modifier.fillMaxWidth()) {
                Text(activity.getString(R.string.settings))
            }
        }
        item {
            OutlinedButton(onClick = { actions.composeOpenRouting() }, modifier = Modifier.fillMaxWidth()) {
                Text(activity.getString(R.string.app_routing))
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun SmallAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun MetricChip(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LatencyPill(label: String, latency: Long?, status: TestStatus) {
    Surface(shape = RoundedCornerShape(50), tonalElevation = 1.dp) {
        Text(
            "$label ${latency?.let { "${it}ms" } ?: status.name.lowercase(Locale.ROOT)}",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ToolButton(title: String, subtitle: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingSummary(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Text(text, modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun stateLabel(activity: Activity, state: ConnectionState): String = activity.getString(
    when (state) {
        ConnectionState.DISCONNECTED -> R.string.state_disconnected
        ConnectionState.PREPARING -> R.string.state_preparing
        ConnectionState.CONNECTING -> R.string.state_connecting
        ConnectionState.CONNECTED -> R.string.state_connected
        ConnectionState.RECONNECTING -> R.string.state_reconnecting
        ConnectionState.STOPPING -> R.string.state_stopping
        ConnectionState.ERROR -> R.string.state_error
    }
)

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}
