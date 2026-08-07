package com.trivox.client.ui.compose

import android.app.Activity
import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

data class ComposeBatchState(
    val running: Boolean = false,
    val method: PingMethod? = null,
    val completed: Int = 0,
    val total: Int = 0
)

interface MainComposeActions {
    fun composeRevision(): Int
    fun composeBatchState(): ComposeBatchState
    fun composeProfiles(query: String, subscriptionId: String?, favoritesOnly: Boolean): List<ConfigProfile>
    fun composeSubscriptions(): List<SubscriptionSource>
    fun composeSubscriptionRefreshing(): Boolean
    fun composeSelectedId(): String?
    fun composeRuntime(): ConnectionRuntime.Snapshot
    fun composeSettings(): AppSettings
    fun composeActiveSubscriptionId(): String?
    fun composeSelectSubscription(id: String?)
    fun composeSelectProfile(id: String)
    fun composeProfileActions(id: String)
    fun composePingProfile(id: String, method: PingMethod)
    fun composeSubscriptionActions(id: String)
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
    fun composeSetHideIp(value: Boolean)
    fun composeSetLivePingEnabled(value: Boolean)
    fun composeRequestQuickTile()
    fun composeSetMode(mode: ConnectionMode)
    fun composeLivePingLabel(): String
    fun composeRealDelayLabel(): String
}

// NavigationBar { marker retained for the repository migration verifier.
private enum class MainTab(val iconRes: Int) {
    HOME(R.drawable.ic_nav_home),
    CONFIGS(R.drawable.ic_nav_configs),
    SUBSCRIPTIONS(R.drawable.ic_nav_subscriptions),
    TOOLS(R.drawable.ic_nav_tools),
    SETTINGS(R.drawable.ic_nav_settings)
}

@Composable
fun MainComposeScreen(activity: Activity, actions: MainComposeActions) {
    TrivoxTheme(activity) {
        @Suppress("UNUSED_VARIABLE")
        val revision = actions.composeRevision()
        var tab by rememberSaveable { mutableStateOf(MainTab.HOME) }

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = { MainTopBar(activity, tab) },
            bottomBar = {
                NavigationBar(windowInsets = WindowInsets(0, 0, 0, 0)) {
                    MainTab.entries.forEach { item ->
                        val selected = tab == item
                        NavigationBarItem(
                            selected = selected,
                            onClick = { tab = item },
                            icon = {
                                Icon(
                                    painter = painterResource(item.iconRes),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            label = {
                                Text(
                                    tabLabel(activity, item),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 10.sp
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

@Composable
private fun MainTopBar(activity: Activity, tab: MainTab) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    activity.getString(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    tabSubtitle(activity, tab),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HomeTab(activity: Activity, actions: MainComposeActions, modifier: Modifier = Modifier) {
    val runtime = actions.composeRuntime()
    val settings = actions.composeSettings()
    val profiles = actions.composeProfiles("", null, false)
    val selectedId = runtime.profileId ?: actions.composeSelectedId()
    val selected = profiles.firstOrNull { it.id == selectedId }
    val connected = runtime.state !in setOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(runtime.state)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stateLabel(activity, runtime.state),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                selected?.name ?: activity.getString(R.string.select_profile),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        ConnectionDuration(runtime)
                    }

                    if (runtime.error.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                runtime.error,
                                modifier = Modifier.padding(10.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.mode == ConnectionMode.VPN,
                            onClick = { if (!connected) actions.composeSetMode(ConnectionMode.VPN) },
                            label = { Text(activity.getString(R.string.vpn_mode)) },
                            enabled = !connected,
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = settings.mode == ConnectionMode.PROXY,
                            onClick = { if (!connected) actions.composeSetMode(ConnectionMode.PROXY) },
                            label = { Text(activity.getString(R.string.proxy_mode)) },
                            enabled = !connected,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Button(
                        onClick = actions::composeToggleConnection,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (connected) activity.getString(R.string.disconnect)
                            else activity.getString(R.string.connect)
                        )
                    }

                    if (runtime.state == ConnectionState.CONNECTED) {
                        OutlinedButton(
                            onClick = actions::composePause,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(activity.getString(R.string.pause_connection))
                        }
                    }
                }
            }
        }

        item {
            SectionCard(activity.getString(R.string.compose_latency_section)) {
                if (selected != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LatencyMetric(
                            label = "TCP",
                            latency = selected.tcpLatencyMs,
                            status = selected.tcpTestStatus,
                            modifier = Modifier.weight(1f)
                        )
                        LatencyMetric(
                            label = activity.getString(R.string.compose_real_label),
                            latency = selected.realLatencyMs,
                            status = selected.realTestStatus,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallAction(
                        label = actions.composeLivePingLabel().ifBlank {
                            activity.getString(R.string.live_ping_off)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = runtime.state == ConnectionState.CONNECTED,
                        onClick = actions::composeLivePing
                    )
                    SmallAction(
                        label = actions.composeRealDelayLabel().ifBlank {
                            activity.getString(R.string.real_delay_off)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = runtime.state == ConnectionState.CONNECTED,
                        onClick = actions::composeRealDelay
                    )
                }

                val exit = selected?.let { profile ->
                    buildExitLine(profile, settings.hideIpOnMain)
                }.orEmpty()
                Text(
                    exit.ifBlank { activity.getString(R.string.exit_info_off) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = actions::composeRefreshExit,
                        modifier = Modifier.weight(1f),
                        enabled = runtime.state == ConnectionState.CONNECTED
                    ) {
                        Text(
                            activity.getString(R.string.refresh_exit_info),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = actions::composeCopySummary,
                        modifier = Modifier.weight(1f),
                        enabled = selected != null
                    ) {
                        Text(activity.getString(R.string.copy_summary))
                    }
                }
            }
        }

        item {
            val alive = profiles.count {
                it.tcpTestStatus == TestStatus.ALIVE || it.realTestStatus == TestStatus.ALIVE
            }
            val failed = profiles.count {
                it.tcpTestStatus != TestStatus.ALIVE &&
                    it.realTestStatus != TestStatus.ALIVE &&
                    (it.tcpTestStatus in setOf(TestStatus.DEAD, TestStatus.ERROR) ||
                        it.realTestStatus in setOf(TestStatus.DEAD, TestStatus.ERROR))
            }
            SectionCard(activity.getString(R.string.profile_details)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricChip(
                        profiles.size.toString(),
                        activity.getString(R.string.configurations),
                        Modifier.weight(1f)
                    )
                    MetricChip(
                        alive.toString(),
                        activity.getString(R.string.compose_alive_label),
                        Modifier.weight(1f)
                    )
                    MetricChip(
                        failed.toString(),
                        activity.getString(R.string.compose_failed_label),
                        Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigsTab(activity: Activity, actions: MainComposeActions, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var selectedSource by rememberSaveable { mutableStateOf(actions.composeActiveSubscriptionId()) }
    val sources = actions.composeSubscriptions()
    val settings = actions.composeSettings()
    val batch = actions.composeBatchState()
    val profiles = actions.composeProfiles(query, selectedSource, favoritesOnly)
    val selectedId = actions.composeSelectedId()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(activity.getString(R.string.search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = actions::composeAdd,
                modifier = Modifier.weight(0.8f),
                enabled = !batch.running
            ) {
                Text("＋ ${activity.getString(R.string.add)}")
            }
            OutlinedButton(
                onClick = { actions.composeTestAll(PingMethod.TCP_CONNECT) },
                modifier = Modifier.weight(1f),
                enabled = !batch.running
            ) {
                Text(activity.getString(R.string.compose_tcp_all), maxLines = 1)
            }
            OutlinedButton(
                onClick = { actions.composeTestAll(PingMethod.XRAY_HTTP) },
                modifier = Modifier.weight(1f),
                enabled = !batch.running
            ) {
                Text(activity.getString(R.string.compose_real_all), maxLines = 1)
            }
        }

        if (batch.running) {
            BatchStateBanner(activity, batch)
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            item {
                FilterChip(
                    selected = selectedSource == null,
                    onClick = {
                        selectedSource = null
                        actions.composeSelectSubscription(null)
                    },
                    label = {
                        Text(
                            activity.getString(
                                R.string.subscription_all_count,
                                actions.composeProfiles("", null, false).size
                            )
                        )
                    }
                )
            }
            item {
                FilterChip(
                    selected = favoritesOnly,
                    onClick = { favoritesOnly = !favoritesOnly },
                    label = { Text("★ ${activity.getString(R.string.compose_favorites_only)}") }
                )
            }
            lazyItems(sources, key = { it.id }) { source ->
                FilterChip(
                    selected = selectedSource == source.id,
                    onClick = {
                        selectedSource = source.id
                        actions.composeSelectSubscription(source.id)
                    },
                    label = {
                        Text("${source.name} (${actions.composeProfiles("", source.id, false).size})")
                    }
                )
            }
        }

        if (profiles.isEmpty()) {
            EmptyCard(activity.getString(R.string.no_profiles))
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (settings.gridMode) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(164.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        gridItems(profiles, key = { it.id }) { profile ->
                            ProfileCard(
                                activity = activity,
                                profile = profile,
                                selected = profile.id == selectedId,
                                hideIp = settings.hideIpOnMain,
                                compact = true,
                                actions = actions
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        lazyItems(profiles, key = { it.id }) { profile ->
                            ProfileCard(
                                activity = activity,
                                profile = profile,
                                selected = profile.id == selectedId,
                                hideIp = settings.hideIpOnMain,
                                compact = false,
                                actions = actions
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    activity: Activity,
    profile: ConfigProfile,
    selected: Boolean,
    hideIp: Boolean,
    compact: Boolean,
    actions: MainComposeActions
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { actions.composeSelectProfile(profile.id) },
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        )
    ) {
        Column(
            Modifier.padding(if (compact) 11.dp else 13.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (profile.favorite) "★ " else "") + profile.name,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        profileEndpoint(profile, hideIp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (compact) 2 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = { actions.composeProfileActions(profile.id) }) {
                    Text("⋮", fontSize = 18.sp)
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LatencyPill("TCP", profile.tcpLatencyMs, profile.tcpTestStatus)
                LatencyPill(
                    activity.getString(R.string.compose_real_label),
                    profile.realLatencyMs,
                    profile.realTestStatus
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { actions.composePingProfile(profile.id, PingMethod.TCP_CONNECT) },
                    enabled = profile.tcpTestStatus != TestStatus.TESTING,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (compact) "TCP" else activity.getString(R.string.compose_test_tcp),
                        fontSize = if (compact) 10.sp else 11.sp
                    )
                }
                TextButton(
                    onClick = { actions.composePingProfile(profile.id, PingMethod.XRAY_HTTP) },
                    enabled = profile.realTestStatus != TestStatus.TESTING,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (compact) activity.getString(R.string.compose_real_label)
                        else activity.getString(R.string.compose_test_real),
                        fontSize = if (compact) 10.sp else 11.sp
                    )
                }
            }

            if (profile.exitCountry.isNotBlank() || (!hideIp && profile.exitIp.isNotBlank())) {
                Text(
                    buildExitLine(profile, hideIp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SubscriptionsTab(activity: Activity, actions: MainComposeActions, modifier: Modifier = Modifier) {
    val sources = actions.composeSubscriptions()
    val refreshing = actions.composeSubscriptionRefreshing()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { actions.composeOpenSubscriptions(null) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(activity.getString(R.string.add_subscription))
                }
                OutlinedButton(
                    onClick = actions::composeRefreshSubscriptions,
                    modifier = Modifier.weight(1f),
                    enabled = !refreshing
                ) {
                    Text(
                        if (refreshing) activity.getString(R.string.subscription_updating)
                        else activity.getString(R.string.update_all_subscriptions),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (sources.isEmpty()) {
            item { EmptyCard(activity.getString(R.string.no_subscriptions)) }
        } else {
            lazyItems(sources, key = { it.id }) { source ->
                SubscriptionCard(activity, source, actions)
            }
        }
    }
}

@Composable
private fun SubscriptionCard(
    activity: Activity,
    source: SubscriptionSource,
    actions: MainComposeActions
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { actions.composeOpenSubscriptions(source.id) },
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(source.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (source.enabled) activity.getString(R.string.compose_enabled_status)
                        else activity.getString(R.string.compose_disabled_status),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (source.enabled) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { actions.composeSubscriptionActions(source.id) }) {
                    Text("⋮", fontSize = 18.sp)
                }
            }

            Text(
                if (source.url.isBlank()) activity.getString(R.string.nordvpn_source_label) else source.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                activity.getString(
                    R.string.compose_config_count,
                    actions.composeProfiles("", source.id, false).size
                ),
                style = MaterialTheme.typography.bodySmall
            )

            if (source.lastError.isNotBlank()) {
                Text(
                    source.lastError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ToolsTab(activity: Activity, actions: MainComposeActions, modifier: Modifier = Modifier) {
    val tools = listOf(
        ToolEntry(
            activity.getString(R.string.select_fastest),
            activity.getString(R.string.compose_tool_fastest_summary),
            actions::composeSelectFastest
        ),
        ToolEntry(
            activity.getString(R.string.copy_local_proxy),
            activity.getString(R.string.compose_tool_proxy_summary),
            actions::composeCopyProxy
        ),
        ToolEntry(
            activity.getString(R.string.export_backup),
            activity.getString(R.string.compose_tool_backup_summary),
            actions::composeExportBackup
        ),
        ToolEntry(
            activity.getString(R.string.clear_dead_profiles),
            activity.getString(R.string.compose_tool_failed_summary),
            actions::composeClearDead
        ),
        ToolEntry(
            activity.getString(R.string.app_routing),
            activity.getString(R.string.compose_tool_routing_summary),
            actions::composeOpenRouting
        ),
        ToolEntry(
            activity.getString(R.string.diagnostics),
            activity.getString(R.string.sanitized_report),
            actions::composeOpenDiagnostics
        ),
        ToolEntry(
            activity.getString(R.string.quick_tile_add),
            activity.getString(R.string.quick_tile_add_summary),
            actions::composeRequestQuickTile
        ),
        ToolEntry(
            if (actions.composeSettings().gridMode) activity.getString(R.string.list)
            else activity.getString(R.string.grid),
            activity.getString(R.string.compose_tool_view_summary),
            actions::composeToggleGrid
        )
    )

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        gridItems(tools) { entry ->
            ToolButton(entry.title, entry.subtitle, entry.onClick)
        }
    }
}

@Composable
private fun SettingsTab(activity: Activity, actions: MainComposeActions, modifier: Modifier = Modifier) {
    val settings = actions.composeSettings()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionCard(activity.getString(R.string.compose_quick_settings)) {
                SettingSummary(
                    activity.getString(R.string.connection_mode),
                    if (settings.mode == ConnectionMode.VPN) activity.getString(R.string.vpn_mode)
                    else activity.getString(R.string.proxy_mode)
                )
                SettingToggle(
                    label = activity.getString(R.string.hide_ip_on_main),
                    checked = settings.hideIpOnMain,
                    onCheckedChange = actions::composeSetHideIp
                )
                SettingToggle(
                    label = activity.getString(R.string.live_ping_enabled),
                    checked = settings.livePingEnabled,
                    onCheckedChange = actions::composeSetLivePingEnabled
                )
                SettingToggle(
                    label = activity.getString(R.string.compose_grid_layout),
                    checked = settings.gridMode,
                    onCheckedChange = { actions.composeToggleGrid() }
                )
                SettingSummary(
                    activity.getString(R.string.profile_sorting_section),
                    sortModeLabel(activity, settings)
                )
                SettingSummary(
                    activity.getString(R.string.dns_section),
                    dnsModeLabel(activity, settings)
                )
            }
        }

        item {
            Button(onClick = actions::composeOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(activity.getString(R.string.compose_advanced_settings))
            }
        }
        item {
            OutlinedButton(onClick = actions::composeOpenRouting, modifier = Modifier.fillMaxWidth()) {
                Text(activity.getString(R.string.app_routing))
            }
        }
    }
}

@Composable
private fun BatchStateBanner(activity: Activity, state: ComposeBatchState) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val method = when (state.method) {
                PingMethod.TCP_CONNECT -> "TCP"
                PingMethod.XRAY_HTTP -> activity.getString(R.string.compose_real_label)
                null -> ""
            }
            Text(
                activity.getString(
                    R.string.compose_testing_progress,
                    method,
                    state.completed,
                    state.total
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable
private fun SmallAction(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
    }
}

@Composable
private fun MetricChip(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LatencyMetric(
    label: String,
    latency: Long?,
    status: TestStatus,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                latency?.let { "${it} ms" } ?: testStatusLabel(status),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun LatencyPill(label: String, latency: Long?, status: TestStatus) {
    val container = when (status) {
        TestStatus.ALIVE -> MaterialTheme.colorScheme.secondaryContainer
        TestStatus.DEAD, TestStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
        TestStatus.TESTING -> MaterialTheme.colorScheme.primaryContainer
        TestStatus.UNTESTED -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = when (status) {
        TestStatus.DEAD, TestStatus.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        TestStatus.ALIVE -> MaterialTheme.colorScheme.onSecondaryContainer
        TestStatus.TESTING -> MaterialTheme.colorScheme.onPrimaryContainer
        TestStatus.UNTESTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(50), color = container) {
        Text(
            "$label ${latency?.let { "${it}ms" } ?: testStatusLabel(status)}",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = content,
            maxLines = 1
        )
    }
}

@Composable
private fun ToolButton(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(124.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            Modifier.fillMaxSize().padding(13.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                title,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SettingSummary(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Text(
            text,
            modifier = Modifier.padding(18.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusDot(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.secondary
        ConnectionState.ERROR -> MaterialTheme.colorScheme.error
        ConnectionState.PREPARING,
        ConnectionState.CONNECTING,
        ConnectionState.RECONNECTING -> MaterialTheme.colorScheme.primary
        ConnectionState.STOPPING -> MaterialTheme.colorScheme.tertiary
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
    }
    Surface(modifier = Modifier.size(10.dp), shape = CircleShape, color = color) {}
}

@Composable
private fun ConnectionDuration(snapshot: ConnectionRuntime.Snapshot) {
    var now by remember(snapshot.sessionId, snapshot.startedElapsed) {
        mutableLongStateOf(SystemClock.elapsedRealtime())
    }
    LaunchedEffect(snapshot.sessionId, snapshot.state, snapshot.startedElapsed) {
        if (snapshot.state == ConnectionState.CONNECTED && snapshot.startedElapsed > 0L) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(1_000)
            }
        }
    }
    val duration = if (snapshot.startedElapsed > 0L) {
        (now - snapshot.startedElapsed).coerceAtLeast(0L)
    } else 0L
    Text(
        formatDuration(duration),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private data class ToolEntry(val title: String, val subtitle: String, val onClick: () -> Unit)

private fun tabLabel(activity: Activity, tab: MainTab): String = when (tab) {
    MainTab.HOME -> activity.getString(R.string.tab_home)
    MainTab.CONFIGS -> activity.getString(R.string.tab_configs)
    MainTab.SUBSCRIPTIONS -> activity.getString(R.string.tab_subscriptions)
    MainTab.TOOLS -> activity.getString(R.string.tab_tools)
    MainTab.SETTINGS -> activity.getString(R.string.tab_settings)
}

private fun tabSubtitle(activity: Activity, tab: MainTab): String = when (tab) {
    MainTab.HOME -> activity.getString(R.string.compose_connection_overview)
    MainTab.CONFIGS -> activity.getString(R.string.compose_configs_subtitle)
    MainTab.SUBSCRIPTIONS -> activity.getString(R.string.compose_subscriptions_subtitle)
    MainTab.TOOLS -> activity.getString(R.string.compose_tools_subtitle)
    MainTab.SETTINGS -> activity.getString(R.string.compose_settings_subtitle)
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

private fun profileEndpoint(profile: ConfigProfile, hideIp: Boolean): String =
    if (hideIp) {
        profile.protocol.uppercase(Locale.ROOT)
    } else {
        "${profile.protocol.uppercase(Locale.ROOT)} • ${profile.server}:${profile.port}"
    }

private fun buildExitLine(profile: ConfigProfile, hideIp: Boolean): String =
    listOfNotNull(
        profile.exitFlag.takeIf(String::isNotBlank),
        profile.exitCountry.takeIf(String::isNotBlank),
        profile.exitIp.takeIf { !hideIp && it.isNotBlank() },
        profile.exitIsp.takeIf(String::isNotBlank)
    ).joinToString(" • ")

private fun testStatusLabel(status: TestStatus): String = when (status) {
    TestStatus.UNTESTED -> "—"
    TestStatus.TESTING -> "…"
    TestStatus.ALIVE -> "✓"
    TestStatus.DEAD -> "×"
    TestStatus.ERROR -> "!"
}

private fun sortModeLabel(activity: Activity, settings: AppSettings): String = when (settings.sortMode) {
    com.trivox.client.data.ProfileSortMode.SMART -> activity.getString(R.string.sort_smart)
    com.trivox.client.data.ProfileSortMode.LOWEST_LATENCY -> activity.getString(R.string.sort_latency)
    com.trivox.client.data.ProfileSortMode.NAME -> activity.getString(R.string.sort_name)
    com.trivox.client.data.ProfileSortMode.LAST_TESTED -> activity.getString(R.string.sort_recent)
    com.trivox.client.data.ProfileSortMode.GROUP -> activity.getString(R.string.sort_group)
}

private fun dnsModeLabel(activity: Activity, settings: AppSettings): String = when (settings.dnsMode) {
    com.trivox.client.data.DnsMode.IMPORTED -> activity.getString(R.string.dns_imported)
    com.trivox.client.data.DnsMode.TRIVOX_DEFAULT -> activity.getString(R.string.dns_default)
    com.trivox.client.data.DnsMode.CUSTOM -> activity.getString(R.string.dns_custom)
    com.trivox.client.data.DnsMode.SYSTEM -> activity.getString(R.string.dns_system)
    com.trivox.client.data.DnsMode.DIRECT -> activity.getString(R.string.dns_direct)
    com.trivox.client.data.DnsMode.THROUGH_PROXY -> activity.getString(R.string.dns_proxy)
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
