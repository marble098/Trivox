package com.trivox.client.ui.compose

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
        val items = actions.routingItems().filter {
            (showSystem || !it.system) &&
                (query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true))
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(activity.getString(R.string.app_routing)) },
                    navigationIcon = { TextButton(onClick = { activity.finish() }) { Text("‹") } }
                )
            },
            bottomBar = {
                Button(
                    onClick = { actions.routingSave() },
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                ) { Text(activity.getString(R.string.save)) }
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Text(activity.getString(R.string.routing_mode_label), style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AppRoutingMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = actions.routingMode() == mode,
                                    onClick = { actions.routingSetMode(mode) },
                                    label = {
                                        Text(
                                            when (mode) {
                                                AppRoutingMode.ALL -> activity.getString(R.string.routing_all)
                                                AppRoutingMode.ALLOW_SELECTED -> activity.getString(R.string.routing_allow)
                                                AppRoutingMode.BYPASS_SELECTED -> activity.getString(R.string.routing_bypass)
                                            }
                                        )
                                    }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { actions.routingSetShowSystem(!showSystem) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = showSystem, onCheckedChange = actions::routingSetShowSystem)
                            Text(activity.getString(R.string.show_system_apps))
                        }
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(activity.getString(R.string.search)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("${items.size} ${activity.getString(R.string.applications)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(items, key = { it.packageName }) { item ->
                    val checked = item.packageName in selected
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { actions.routingToggle(item.packageName) },
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { actions.routingToggle(item.packageName) })
                            Column(Modifier.weight(1f)) {
                                Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.packageName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}
