package com.trivox.client.ui.compose

// TRIVOX_V21_STABILITY_AUTO_LEAK_UI

// TRIVOX_V19_NATIVE_WIREGUARD_LEAK_GUARD

import android.app.Activity
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.ui.graphics.Color
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
import com.trivox.client.data.ExperienceMode
import com.trivox.client.data.PingMethod
import com.trivox.client.data.SubscriptionSource
import com.trivox.client.data.TestStatus
import com.trivox.client.network.CommunityConfigManager
import com.trivox.client.service.ConnectionUsageStore
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

data class ComposeBatchState(
    val running: Boolean = false,
    val method: PingMethod? = null,
    val completed: Int = 0,
    val total: Int = 0
)

data class ComposeProfileStats(
    val total: Int = 0,
    val alive: Int = 0,
    val failed: Int = 0
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
    fun composeSelectedProfile(): ConfigProfile?
    fun composeProfileStats(): ComposeProfileStats
    fun composeSaveSettings(value: AppSettings)
    fun composeLanguageTag(): String
    fun composeSetLanguage(tag: String)
    fun composeCheckForUpdates()
    fun composeUpdateStatus(): String
    fun composeLocalProxyStatus(): String
    fun composeActiveSubscriptionId(): String?
    fun composeSelectSubscription(id: String?)
    fun composeSelectProfile(id: String)
    fun composeProfileActions(id: String)
    fun composePingProfile(id: String, method: PingMethod)
    fun composeSubscriptionActions(id: String)
    fun composeMagicClipboard()
    fun composeMagicClipboardBusy(): Boolean
    fun composeAdd()
    fun composeRefreshSubscriptions()
    fun composeTestAll(method: PingMethod)
    fun composeToggleConnection()
    fun composePause()
    fun composeLivePing()
    fun composeRealDelay()
    fun composeLeakStatus(): String
    fun composeLeakCheckBusy(): Boolean
    fun composeCheckLeaksAndRepair()
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
    fun composeSetGrid(value: Boolean)
    fun composeSetHideIp(value: Boolean)
    fun composeSetLivePingEnabled(value: Boolean)
    fun composeRequestQuickTile()
    fun composeSetMode(mode: ConnectionMode)
    fun composeLivePingLabel(): String
    fun composeRealDelayLabel(): String
    fun composeSimpleToggleConnection()
    fun composeCommunitySync()
    fun composeCommunitySyncBusy(): Boolean
    fun composeCommunityStatus(): String
    fun composeOpenCommunityChannel()
    fun composeUsageSnapshot(): ConnectionUsageStore.UsageSnapshot
    fun composeUsageHistory(): List<ConnectionUsageStore.SessionRecord>
}

private enum class MainTab(
    val outlineIconRes: Int,
    val filledIconRes: Int
) {
    HOME(R.drawable.ic_nav_home, R.drawable.ic_sf_home_fill),
    CONFIGS(R.drawable.ic_nav_configs, R.drawable.ic_sf_configs_fill),
    SUBSCRIPTIONS(R.drawable.ic_nav_subscriptions, R.drawable.ic_sf_subscriptions_fill),
    TOOLS(R.drawable.ic_nav_tools, R.drawable.ic_sf_tools_fill),
    SETTINGS(R.drawable.ic_nav_settings, R.drawable.ic_sf_settings_fill)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainComposeScreen(activity: Activity, actions: MainComposeActions) {
    val revision = actions.composeRevision()
    val settings = remember(revision) { actions.composeSettings() }

    TrivoxTheme(
        activity = activity,
        settingsOverride = settings
    ) {
        var simpleSettings by rememberSaveable { mutableStateOf(false) }

        TrivoxGradientBackground(
            activity = activity,
            settingsOverride = settings
        ) {
            if (settings.experienceMode == ExperienceMode.SIMPLE) {
                if (simpleSettings) {
                    Column(Modifier.fillMaxSize()) {
                        TrivoxGlassBar {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { simpleSettings = false }) {
                                    Text(activity.getString(R.string.v26_back))
                                }
                                Text(
                                    activity.getString(R.string.settings),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        UnifiedSettingsScreen(
                            activity = activity,
                            actions = actions,
                            uiRevision = revision,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    SimpleHomeScreen(
                        activity = activity,
                        actions = actions,
                        uiRevision = revision,
                        onSettings = { simpleSettings = true }
                    )
                }
                return@TrivoxGradientBackground
            }

            var tab by rememberSaveable { mutableStateOf(MainTab.HOME) }
            val scrollBehavior = rememberTrivoxTopBarScrollBehavior()
            LaunchedEffect(tab) {
                scrollBehavior.state.heightOffset = 0f
                scrollBehavior.state.contentOffset = 0f
            }
            Scaffold(
                modifier = Modifier.trivoxNestedScroll(scrollBehavior),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    TrivoxLargeTopBar(
                        title = tabLabel(activity, tab),
                        scrollBehavior = scrollBehavior
                    )
                },
                bottomBar = {
                    TrivoxGlassBar {
                        NavigationBar(
                            windowInsets = WindowInsets(0, 0, 0, 0),
                            containerColor = Color.Transparent,
                            tonalElevation = 0.dp
                        ) {
                            MainTab.entries.forEach { item ->
                                val selected = tab == item
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { tab = item },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = Color.Transparent,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f),
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f)
                                    ),
                                    icon = {
                                        Icon(
                                            painter = painterResource(
                                                item.outlineIconRes
                                            ),
                                            contentDescription = null,
                                            modifier = Modifier.size(21.dp)
                                        )
                                    },
                                    label = {
                                        Text(
                                            tabLabel(activity, item),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            ) { padding ->
                when (tab) {
                    MainTab.HOME -> HomeTab(
                        activity = activity,
                        actions = actions,
                        uiRevision = revision,
                        modifier = Modifier.padding(padding)
                    )
                    MainTab.CONFIGS -> ConfigsTab(
                        activity = activity,
                        actions = actions,
                        uiRevision = revision,
                        modifier = Modifier.padding(padding)
                    )
                    MainTab.SUBSCRIPTIONS -> SubscriptionsTab(
                        activity = activity,
                        actions = actions,
                        uiRevision = revision,
                        modifier = Modifier.padding(padding)
                    )
                    MainTab.TOOLS -> ToolsTab(
                        activity = activity,
                        actions = actions,
                        uiRevision = revision,
                        modifier = Modifier.padding(padding)
                    )
                    MainTab.SETTINGS -> SettingsTab(
                        activity = activity,
                        actions = actions,
                        uiRevision = revision,
                        modifier = Modifier.padding(padding)
                    )
                }
            }
        }
    }
}


@Composable
private fun SimpleHomeScreen(
    activity: Activity,
    actions: MainComposeActions,
    uiRevision: Int,
    onSettings: () -> Unit
) {
    val runtime = remember(uiRevision) { actions.composeRuntime() }
    val settings = remember(uiRevision) { actions.composeSettings() }
    val selected = remember(uiRevision) { actions.composeSelectedProfile() }
    val busy = actions.composeCommunitySyncBusy()
    val status = actions.composeCommunityStatus()
    val active = runtime.state !in setOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.84f)
                        ),
                        TrivoxOuterShape
                    ),
                shape = TrivoxOuterShape,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    StatusDot(runtime.state)
                    Text(
                        activity.getString(R.string.v26_simple_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (active) {
                            selected?.name ?: stateLabel(activity, runtime.state)
                        } else {
                            activity.getString(R.string.v26_simple_subtitle)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Button(
                        onClick = actions::composeSimpleToggleConnection,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(68.dp)
                            .trivoxSpringPress(
                                runtime.state != ConnectionState.STOPPING
                            ),
                        shape = TrivoxInnerShape,
                        enabled = runtime.state != ConnectionState.STOPPING
                    ) {
                        if (runtime.state in setOf(
                                ConnectionState.PREPARING,
                                ConnectionState.CONNECTING,
                                ConnectionState.RECONNECTING,
                                ConnectionState.STOPPING
                            )
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(9.dp))
                        }
                        Text(
                            if (active) {
                                activity.getString(R.string.v26_simple_disconnect)
                            } else {
                                activity.getString(R.string.v26_simple_connect)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (runtime.error.isNotBlank()) {
                        Text(
                            runtime.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        item {
            SectionCard(activity.getString(R.string.v26_community_section)) {
                Text(
                    activity.getString(
                        R.string.v26_source_attribution,
                        settings.communityChannelUsername
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    activity.getString(R.string.v26_community_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = actions::composeCommunitySync,
                        enabled = settings.communitySourceEnabled && !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(activity.getString(R.string.v26_community_sync))
                    }
                    OutlinedButton(
                        onClick = actions::composeOpenCommunityChannel,
                        modifier = Modifier.weight(1f).trivoxSpringPress()
                    ) {
                        Text(activity.getString(R.string.v26_community_open))
                    }
                }
                if (status.isNotBlank()) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            UsageHistoryCard(activity, actions, runtime)
        }

        item {
            OutlinedButton(
                onClick = onSettings,
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text(activity.getString(R.string.v26_simple_settings))
            }
        }
    }
}

@Composable
private fun UsageHistoryCard(
    activity: Activity,
    actions: MainComposeActions,
    runtime: ConnectionRuntime.Snapshot
) {
    var usage by remember(runtime.sessionId) {
        mutableStateOf(actions.composeUsageSnapshot())
    }
    var history by remember(runtime.sessionId) {
        mutableStateOf(actions.composeUsageHistory())
    }
    LaunchedEffect(runtime.sessionId, runtime.state) {
        while (true) {
            usage = actions.composeUsageSnapshot()
            delay(
                if (runtime.state == ConnectionState.CONNECTED) 1_000L else 5_000L
            )
        }
    }
    LaunchedEffect(runtime.sessionId) {
        while (true) {
            history = actions.composeUsageHistory()
            delay(15_000L)
        }
    }

    SectionCard(activity.getString(R.string.v26_usage_title)) {
        UsageRow(
            activity.getString(R.string.v26_usage_session),
            usage.sessionRxBytes,
            usage.sessionTxBytes
        )
        UsageRow(
            activity.getString(R.string.v26_usage_today),
            usage.todayRxBytes,
            usage.todayTxBytes
        )
        UsageRow(
            activity.getString(R.string.v26_usage_yesterday),
            usage.yesterdayRxBytes,
            usage.yesterdayTxBytes
        )
        UsageRow(
            activity.getString(R.string.v26_usage_month),
            usage.monthRxBytes,
            usage.monthTxBytes
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Text(
            activity.getString(R.string.v26_history_title),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (history.isEmpty()) {
            Text(
                activity.getString(R.string.v26_history_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            history.take(5).forEach { record ->
                Text(
                    buildString {
                        append(record.profileName)
                        append(" • ")
                        append(
                            DateFormat.getDateTimeInstance(
                                DateFormat.SHORT,
                                DateFormat.SHORT
                            ).format(Date(record.endedAt))
                        )
                        append("\n↓ ")
                        append(formatDataBytes(record.rxBytes))
                        append("   ↑ ")
                        append(formatDataBytes(record.txBytes))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            activity.getString(R.string.v26_usage_estimate_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
        )
    }
}

@Composable
private fun UsageRow(label: String, rx: Long, tx: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            "↓ ${formatDataBytes(rx)}   ↑ ${formatDataBytes(tx)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatDataBytes(value: Long): String {
    val bytes = value.coerceAtLeast(0L).toDouble()
    return when {
        bytes >= 1024.0 * 1024.0 * 1024.0 ->
            String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024.0 * 1024.0 ->
            String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024.0 ->
            String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "${value.coerceAtLeast(0L)} B"
    }
}

@Composable
private fun MainTopBar(activity: Activity, tab: MainTab) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    tabLabel(activity, tab),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${activity.getString(R.string.app_name)} • ${tabSubtitle(activity, tab)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            )
        }
    }
}

@Composable
private fun HomeTab(
    activity: Activity,
    actions: MainComposeActions,
    uiRevision: Int,
    modifier: Modifier = Modifier
) {
    val runtime = remember(uiRevision) { actions.composeRuntime() }
    val settings = remember(uiRevision) { actions.composeSettings() }
    val selected = remember(uiRevision) { actions.composeSelectedProfile() }
    val leakStatus = remember(uiRevision) { actions.composeLeakStatus() }
    val leakBusy = remember(uiRevision) { actions.composeLeakCheckBusy() }
    val connected = runtime.state !in setOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.86f)
                        ),
                        TrivoxOuterShape
                    ),
                shape = TrivoxOuterShape,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(runtime.state)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            val statusColor = when (runtime.state) {
                                ConnectionState.CONNECTED ->
                                    MaterialTheme.colorScheme.secondary
                                ConnectionState.ERROR ->
                                    MaterialTheme.colorScheme.error
                                ConnectionState.PREPARING,
                                ConnectionState.CONNECTING,
                                ConnectionState.RECONNECTING ->
                                    MaterialTheme.colorScheme.primary
                                ConnectionState.STOPPING ->
                                    MaterialTheme.colorScheme.tertiary
                                ConnectionState.DISCONNECTED ->
                                    MaterialTheme.colorScheme.onSurface
                            }
                            Text(
                                stateLabel(activity, runtime.state),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = statusColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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
                            shape = TrivoxCompactShape
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

                    val connectionColor by animateColorAsState(
                        targetValue = if (connected) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        animationSpec = tween(durationMillis = 180),
                        label = "connection-button-color"
                    )
                    val connectionContentColor = if (connected) {
                        MaterialTheme.colorScheme.onError
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    }
                    val transitioning = runtime.state in setOf(
                        ConnectionState.PREPARING,
                        ConnectionState.CONNECTING,
                        ConnectionState.RECONNECTING,
                        ConnectionState.STOPPING
                    )
                    Button(
                        onClick = actions::composeToggleConnection,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .trivoxSpringPress(
                                runtime.state != ConnectionState.STOPPING
                            )
                            .animateContentSize(),
                        enabled = runtime.state != ConnectionState.STOPPING,
                        shape = TrivoxInnerShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = connectionColor,
                            contentColor = connectionContentColor
                        )
                    ) {
                        if (transitioning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(17.dp),
                                strokeWidth = 2.dp,
                                color = connectionContentColor
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        AnimatedContent(
                            targetState = connected,
                            label = "connection-action"
                        ) { isConnected ->
                            Text(
                                if (isConnected) {
                                    activity.getString(R.string.disconnect)
                                } else {
                                    activity.getString(R.string.connect)
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (runtime.state == ConnectionState.CONNECTED) {
                        OutlinedButton(
                            onClick = actions::composePause,
                            modifier = Modifier
                                .fillMaxWidth()
                                .trivoxSpringPress()
                        ) {
                            Text(activity.getString(R.string.pause_connection))
                        }
                    }
                }
            }
        }

        
        item {
            UsageHistoryCard(activity, actions, runtime)
        }

        item {
            WorldMapCard(
                connected =
                    runtime.state ==
                        ConnectionState.CONNECTED,
                sessionId =
                    runtime.sessionId,
                settings = settings,
                exitIp =
                    selected
                        ?.exitIp
                        .orEmpty(),
                countryCode =
                    selected
                        ?.exitCountryCode
                        .orEmpty(),
                countryName =
                    selected
                        ?.exitCountry
                        .orEmpty(),
                flagText =
                    buildString {
                        append(
                            selected
                                ?.exitFlag
                                .orEmpty()
                        )
                        append(' ')
                        append(
                            selected
                                ?.name
                                .orEmpty()
                        )
                    },
                hideIp =
                    settings.hideIpOnMain
            )
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
            SectionCard(activity.getString(R.string.v19_leak_guard_title)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            activity.getString(R.string.v19_leak_guard_toggle),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            activity.getString(R.string.v21_leak_auto_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TrivoxToggle(
                        checked = settings.autoLeakProtection,
                        onCheckedChange = {
                            actions.composeSaveSettings(
                                settings.copy(autoLeakProtection = it)
                            )
                        }
                    )
                }

                OutlinedButton(
                    onClick = actions::composeCheckLeaksAndRepair,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = runtime.state == ConnectionState.CONNECTED &&
                        runtime.mode == ConnectionMode.VPN &&
                        !leakBusy
                ) {
                    if (leakBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (leakBusy) {
                            activity.getString(R.string.v19_leak_checking)
                        } else {
                            activity.getString(R.string.v19_leak_check)
                        }
                    )
                }

                if (leakStatus.isNotBlank()) {
                    Surface(
                        shape = TrivoxCompactShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            leakStatus,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

            }
}

@Composable
private fun ConfigsTab(
    activity: Activity,
    actions: MainComposeActions,
    uiRevision: Int,
    modifier: Modifier = Modifier
) {
    var query by rememberSaveable { mutableStateOf("") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var selectedSource by rememberSaveable { mutableStateOf(actions.composeActiveSubscriptionId()) }
    val sources = remember(uiRevision) { actions.composeSubscriptions() }
    val settings = remember(uiRevision) { actions.composeSettings() }
    val batch = remember(uiRevision) { actions.composeBatchState() }
    val magicBusy = remember(uiRevision) { actions.composeMagicClipboardBusy() }
    val profiles = remember(query, selectedSource, favoritesOnly, uiRevision) {
        actions.composeProfiles(query, selectedSource, favoritesOnly)
    }
    val selectedId = remember(uiRevision) { actions.composeSelectedId() }

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
                modifier = Modifier.weight(1f),
                enabled = !batch.running && !magicBusy
            ) {
                Text("＋ ${activity.getString(R.string.add)}")
            }
            Button(
                onClick = actions::composeMagicClipboard,
                modifier = Modifier.weight(1f),
                enabled = !batch.running && !magicBusy
            ) {
                if (magicBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(7.dp))
                }
                Text(
                    if (magicBusy) {
                        activity.getString(R.string.v15_magic_clipboard_working)
                    } else {
                        activity.getString(R.string.v15_magic_clipboard)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { actions.composeTestAll(PingMethod.TCP_CONNECT) },
                modifier = Modifier.weight(1f),
                enabled = !batch.running && !magicBusy
            ) {
                Text(
                    if (batch.running && batch.method == PingMethod.TCP_CONNECT) {
                        "TCP ${batch.completed}/${batch.total.coerceAtLeast(1)}"
                    } else {
                        activity.getString(R.string.compose_tcp_all)
                    },
                    maxLines = 1
                )
            }
            OutlinedButton(
                onClick = { actions.composeTestAll(PingMethod.XRAY_HTTP) },
                modifier = Modifier.weight(1f),
                enabled = !batch.running && !magicBusy
            ) {
                Text(
                    if (batch.running && batch.method == PingMethod.XRAY_HTTP) {
                        "${activity.getString(R.string.compose_real_label)} " +
                            "${batch.completed}/${batch.total.coerceAtLeast(1)}"
                    } else {
                        activity.getString(R.string.compose_real_all)
                    },
                    maxLines = 1
                )
            }
        }

        if (batch.running) {
            BatchStateBanner(activity, batch)
        }

        val countSnapshot = remember(uiRevision) {
            actions.composeProfiles("", null, false)
        }
        val allProfileCount = countSnapshot.size
        val favoriteProfileCount = remember(countSnapshot) {
            countSnapshot.count { it.favorite }
        }
        val sourceCounts = remember(countSnapshot) {
            countSnapshot
                .groupingBy { it.subscriptionId }
                .eachCount()
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 1.dp)
        ) {
            item {
                ConfigScopeChip(
                    label = activity.getString(R.string.v21_scope_all),
                    count = allProfileCount,
                    selected = selectedSource == null,
                    onClick = {
                        selectedSource = null
                        actions.composeSelectSubscription(null)
                    }
                )
            }
            item {
                ConfigScopeChip(
                    label = activity.getString(R.string.v21_scope_favorites),
                    count = favoriteProfileCount,
                    selected = favoritesOnly,
                    onClick = { favoritesOnly = !favoritesOnly }
                )
            }
            if ((sourceCounts[CommunityConfigManager.COMMUNITY_SUBSCRIPTION_ID] ?: 0) > 0) {
                item {
                    ConfigScopeChip(
                        label = "@" + settings.communityChannelUsername,
                        count = sourceCounts[CommunityConfigManager.COMMUNITY_SUBSCRIPTION_ID] ?: 0,
                        selected = selectedSource == CommunityConfigManager.COMMUNITY_SUBSCRIPTION_ID,
                        onClick = {
                            selectedSource = CommunityConfigManager.COMMUNITY_SUBSCRIPTION_ID
                            actions.composeSelectSubscription(CommunityConfigManager.COMMUNITY_SUBSCRIPTION_ID)
                        }
                    )
                }
            }
            lazyItems(sources, key = { it.id }) { source ->
                ConfigScopeChip(
                    label = source.name,
                    count = sourceCounts[source.id] ?: 0,
                    selected = selectedSource == source.id,
                    onClick = {
                        selectedSource = source.id
                        actions.composeSelectSubscription(source.id)
                    },
                    onLongClick = {
                        actions.composeSubscriptionActions(source.id)
                    }
                )
            }
        }

        if (sources.isNotEmpty()) {
            Text(
                activity.getString(R.string.v15_subscription_long_press_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (profiles.isEmpty()) {
            EmptyCard(activity.getString(R.string.no_profiles))
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (settings.gridMode) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(172.dp),
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

@OptIn(ExperimentalFoundationApi::class)
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
            .animateContentSize()
            .trivoxSpringPress()
            .combinedClickable(
                onClick = { actions.composeSelectProfile(profile.id) },
                onLongClick = { actions.composeProfileActions(profile.id) }
            ),
        shape = TrivoxOuterShape,
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
            else MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        if (compact) CompactProfileCard(activity, profile, hideIp, actions)
        else ListProfileCard(activity, profile, hideIp, actions)
    }
}

@Composable
private fun CompactProfileCard(
    activity: Activity,
    profile: ConfigProfile,
    hideIp: Boolean,
    actions: MainComposeActions
) {
    Column(
        Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    (if (profile.favorite) "★ " else "") + profile.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    profileEndpoint(profile, hideIp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                Modifier.size(32.dp).clickable {
                    actions.composeProfileActions(profile.id)
                },
                contentAlignment = Alignment.Center
            ) {
                Text("⋮", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LatencyPill("TCP", profile.tcpLatencyMs, profile.tcpTestStatus)
            LatencyPill(
                activity.getString(R.string.compose_real_label),
                profile.realLatencyMs, profile.realTestStatus
            )
        }
        if (
            profile.tcpTestStatus == TestStatus.TESTING ||
            profile.realTestStatus == TestStatus.TESTING
        ) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(
                onClick = { actions.composePingProfile(profile.id, PingMethod.TCP_CONNECT) },
                enabled = profile.tcpTestStatus != TestStatus.TESTING,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    if (profile.tcpTestStatus == TestStatus.TESTING) "TCP…"
                    else activity.getString(R.string.compose_test_tcp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium
                )
            }
            TextButton(
                onClick = { actions.composePingProfile(profile.id, PingMethod.XRAY_HTTP) },
                enabled = profile.realTestStatus != TestStatus.TESTING,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    if (profile.realTestStatus == TestStatus.TESTING)
                        "${activity.getString(R.string.compose_real_label)}…"
                    else activity.getString(R.string.compose_test_real),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun ListProfileCard(
    activity: Activity,
    profile: ConfigProfile,
    hideIp: Boolean,
    actions: MainComposeActions
) {
    Column(
        Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    (if (profile.favorite) "★ " else "") + profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    profileEndpoint(profile, hideIp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                Modifier.size(36.dp).clickable {
                    actions.composeProfileActions(profile.id)
                },
                contentAlignment = Alignment.Center
            ) {
                Text("⋮", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            LatencyPill("TCP", profile.tcpLatencyMs, profile.tcpTestStatus)
            LatencyPill(
                activity.getString(R.string.compose_real_label),
                profile.realLatencyMs, profile.realTestStatus
            )
        }
        if (
            profile.tcpTestStatus == TestStatus.TESTING ||
            profile.realTestStatus == TestStatus.TESTING
        ) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { actions.composePingProfile(profile.id, PingMethod.TCP_CONNECT) },
                enabled = profile.tcpTestStatus != TestStatus.TESTING,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    if (profile.tcpTestStatus == TestStatus.TESTING) "TCP…"
                    else activity.getString(R.string.compose_test_tcp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(
                onClick = { actions.composePingProfile(profile.id, PingMethod.XRAY_HTTP) },
                enabled = profile.realTestStatus != TestStatus.TESTING,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    if (profile.realTestStatus == TestStatus.TESTING)
                        "${activity.getString(R.string.compose_real_label)}…"
                    else activity.getString(R.string.compose_test_real),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (
            profile.exitCountry.isNotBlank() ||
            (!hideIp && profile.exitIp.isNotBlank())
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                buildExitLine(profile, hideIp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SubscriptionsTab(
    activity: Activity,
    actions: MainComposeActions,
    uiRevision: Int,
    modifier: Modifier = Modifier
) {
    val sources = remember(uiRevision) { actions.composeSubscriptions() }
    val refreshing = remember(uiRevision) { actions.composeSubscriptionRefreshing() }
    val sourceCounts = remember(uiRevision) {
        actions.composeProfiles("", null, false)
            .groupingBy { it.subscriptionId }
            .eachCount()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { actions.composeOpenSubscriptions(null) },
                    modifier = Modifier.weight(1f).trivoxSpringPress()
                ) {
                    Text(activity.getString(R.string.add_subscription))
                }
                OutlinedButton(
                    onClick = actions::composeRefreshSubscriptions,
                    modifier = Modifier.weight(1f).trivoxSpringPress(!refreshing),
                    enabled = !refreshing
                ) {
                    Text(
                        if (refreshing) {
                            activity.getString(R.string.subscription_updating)
                        } else {
                            activity.getString(R.string.update_all_subscriptions)
                        },
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
                SubscriptionCard(
                    activity = activity,
                    source = source,
                    configCount = sourceCounts[source.id] ?: 0,
                    actions = actions
                )
            }
        }
    }
}

@Composable
private fun SubscriptionCard(
    activity: Activity,
    source: SubscriptionSource,
    configCount: Int,
    actions: MainComposeActions
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .trivoxSpringPress()
            .clickable { actions.composeOpenSubscriptions(source.id) },
        shape = TrivoxOuterShape,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.90f)
        ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        source.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (source.enabled) {
                            activity.getString(R.string.compose_enabled_status)
                        } else {
                            activity.getString(R.string.compose_disabled_status)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (source.enabled) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        configCount.toString(),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = {
                    actions.composeSubscriptionActions(source.id)
                }) {
                    Text("⋮", fontSize = 18.sp)
                }
            }

            Text(
                if (source.url.isBlank()) {
                    activity.getString(R.string.nordvpn_source_label)
                } else {
                    source.url
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
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
private fun ToolsTab(
    activity: Activity,
    actions: MainComposeActions,
    uiRevision: Int,
    modifier: Modifier = Modifier
) {
    val settings = remember(uiRevision) { actions.composeSettings() }
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
            if (settings.gridMode) activity.getString(R.string.list)
            else activity.getString(R.string.grid),
            activity.getString(R.string.compose_tool_view_summary),
            actions::composeToggleGrid
        )
    )

    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
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
private fun SettingsTab(
    activity: Activity,
    actions: MainComposeActions,
    uiRevision: Int,
    modifier: Modifier = Modifier
) {
    UnifiedSettingsScreen(
        activity = activity,
        actions = actions,
        uiRevision = uiRevision,
        modifier = modifier
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConfigScopeChip(
    label: String,
    count: Int? = null,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier
            .animateContentSize()
            .trivoxSpringPress()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = TrivoxInnerShape,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        ),
        tonalElevation = if (selected) 1.dp else 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                label,
                modifier = Modifier.widthIn(min = 0.dp, max = 172.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (count != null) {
                Surface(
                    shape = CircleShape,
                    color = if (selected) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onSecondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                ) {
                    Text(
                        count.toString(),
                        Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
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
            val progress = if (state.total > 0) {
                (state.completed.toFloat() / state.total.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            if (state.total > 0) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    TrivoxInsetGroup(title = title, content = content)
}

@Composable
private fun SmallAction(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.trivoxSpringPress(enabled),
        enabled = enabled
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun MetricChip(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = TrivoxInnerShape,
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
        shape = TrivoxInnerShape,
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
            .height(112.dp)
            .trivoxSpringPress()
            .clickable(onClick = onClick),
        shape = TrivoxOuterShape,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.90f)
        ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 13.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
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
        TrivoxToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = TrivoxOuterShape,
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
    val targetColor = when (state) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.secondary
        ConnectionState.ERROR -> MaterialTheme.colorScheme.error
        ConnectionState.PREPARING,
        ConnectionState.CONNECTING,
        ConnectionState.RECONNECTING -> MaterialTheme.colorScheme.primary
        ConnectionState.STOPPING -> MaterialTheme.colorScheme.tertiary
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
    }
    val color by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 180),
        label = "connection-status-dot"
    )
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
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Text(
            formatDuration(duration),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
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
