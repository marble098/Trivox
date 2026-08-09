package com.trivox.client.ui.compose

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.trivox.client.data.AppRoutingMode

data class RoutingAppUi(val label: String, val packageName: String, val system: Boolean)

interface RoutingComposeActions {
    fun routingItems(): List<RoutingAppUi>
    fun routingSelected(): Set<String>
    fun routingToggle(packageName: String)
    fun routingShowSystem(): Boolean
    fun routingSetShowSystem(value: Boolean)
    fun routingMode(): AppRoutingMode
    fun routingSetMode(mode: AppRoutingMode)
    fun routingSave()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingComposeScreen(activity: Activity, actions: RoutingComposeActions) {
    TrivoxTheme(activity) {
        var query by rememberSaveable { mutableStateOf("") }
        val showSystem = actions.routingShowSystem()
        val selected = actions.routingSelected()
        val allItems = actions.routingItems()
        val items = remember(allItems, showSystem, query) {
            allItems.filter {
                (showSystem || !it.system) &&
                    (query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true))
            }
        }

        val scrollBehavior = rememberTrivoxTopBarScrollBehavior()

        Scaffold(
            modifier = Modifier.trivoxNestedScroll(scrollBehavior),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TrivoxLargeTopBar(
                    title = activity.getString(R.string.app_routing),
                    previousTitle = activity.getString(R.string.app_name),
                    onBack = { activity.finish() },
                    scrollBehavior = scrollBehavior
                )
            },
            bottomBar = {
                TrivoxGlassBar {
                    Button(
                        onClick = actions::routingSave,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(activity.getString(R.string.save))
                    }
                }
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = TrivoxOuterShape
                    ) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                activity.getString(R.string.routing_mode_label),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            AppRoutingMode.entries.forEach { mode ->
                                val modeSelected = actions.routingMode() == mode
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { actions.routingSetMode(mode) }
                                        .padding(vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = modeSelected,
                                        onClick = { actions.routingSetMode(mode) }
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            routingModeTitle(activity, mode),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (modeSelected) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                        Text(
                                            routingModeSummary(activity, mode),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(activity.getString(R.string.compose_search_apps)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { actions.routingSetShowSystem(!showSystem) },
                        shape = TrivoxInnerShape
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = showSystem,
                                onCheckedChange = actions::routingSetShowSystem
                            )
                            Column(Modifier.weight(1f)) {
                                Text(activity.getString(R.string.show_system_apps))
                                Text(
                                    activity.getString(R.string.compose_routing_visible, items.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (allItems.isEmpty()) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                activity.getString(R.string.loading_apps),
                                modifier = Modifier.padding(18.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (items.isEmpty()) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                activity.getString(R.string.compose_no_apps),
                                modifier = Modifier.padding(18.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(items, key = { it.packageName }) { item ->
                        val checked = item.packageName in selected
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { actions.routingToggle(item.packageName) },
                            shape = TrivoxInnerShape
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { actions.routingToggle(item.packageName) }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                    Text(
                                        item.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(4.dp)) }
            }
        }
    }
}

private fun routingModeTitle(activity: Activity, mode: AppRoutingMode): String = when (mode) {
    AppRoutingMode.ALL -> activity.getString(R.string.routing_all)
    AppRoutingMode.ALLOW_SELECTED -> activity.getString(R.string.routing_allow)
    AppRoutingMode.BYPASS_SELECTED -> activity.getString(R.string.routing_bypass)
}

private fun routingModeSummary(activity: Activity, mode: AppRoutingMode): String = when (mode) {
    AppRoutingMode.ALL -> activity.getString(R.string.compose_routing_all_summary)
    AppRoutingMode.ALLOW_SELECTED -> activity.getString(R.string.compose_routing_allow_summary)
    AppRoutingMode.BYPASS_SELECTED -> activity.getString(R.string.compose_routing_bypass_summary)
}
