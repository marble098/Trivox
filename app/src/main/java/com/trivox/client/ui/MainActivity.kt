package com.trivox.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trivox.client.config.ConfigParser
import com.trivox.client.core.ConnectionRuntime
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConfigRepository
import com.trivox.client.data.ConnectionState
import com.trivox.client.service.ConnectionService
import com.trivox.client.ui.theme.TrivoxTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = when (intent?.action) {
            Intent.ACTION_SEND -> intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            else -> ""
        }
        setContent {
            TrivoxTheme {
                TrivoxAppScreen(
                    context = this,
                    initialInput = incoming
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrivoxAppScreen(context: Context, initialInput: String) {
    val repository = remember { ConfigRepository(context) }
    var profiles by remember { mutableStateOf(repository.all()) }
    var selectedId by remember { mutableStateOf(repository.selectedId()) }
    var input by remember { mutableStateOf(initialInput) }
    var runtime by remember { mutableStateOf(ConnectionRuntime.current()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        val listener: (ConnectionRuntime.Snapshot) -> Unit = { runtime = it }
        ConnectionRuntime.addListener(listener)
        onDispose { ConnectionRuntime.removeListener(listener) }
    }

    fun refresh() {
        profiles = repository.all()
        selectedId = repository.selectedId()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Trivox 1.0.0") })
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCard(runtime)
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp),
                label = { Text("Paste Xray config links or JSON") },
                minLines = 4
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    runCatching {
                        val parsed = ConfigParser.parseText(input)
                        repository.saveAll(parsed)
                        if (repository.selectedId().isNullOrBlank() && parsed.isNotEmpty()) {
                            repository.select(parsed.first().id)
                        }
                        input = ""
                        refresh()
                        scope.launch { snackbar.showSnackbar("Imported ${parsed.size} Xray profile(s)") }
                    }.onFailure {
                        scope.launch { snackbar.showSnackbar(it.message ?: "Import failed") }
                    }
                }) { Text("Import") }
                Button(onClick = {
                    val id = selectedId
                    if (id.isNullOrBlank()) {
                        scope.launch { snackbar.showSnackbar("Select a profile first") }
                    } else {
                        context.startService(
                            Intent(context, ConnectionService::class.java)
                                .setAction(ConnectionService.ACTION_START)
                                .putExtra(ConnectionService.EXTRA_PROFILE_ID, id)
                        )
                    }
                }) { Text("Connect") }
                TextButton(onClick = {
                    context.startService(
                        Intent(context, ConnectionService::class.java)
                            .setAction(ConnectionService.ACTION_STOP)
                    )
                }) { Text("Stop") }
            }
            Text(
                text = "Xray core 26.7.28 only",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileRow(
                        profile = profile,
                        selected = profile.id == selectedId,
                        onSelect = {
                            repository.select(profile.id)
                            refresh()
                        },
                        onCopy = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Trivox config", profile.raw))
                            scope.launch { snackbar.showSnackbar("Copied") }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(runtime: ConnectionRuntime.Snapshot) {
    val status = when (runtime.state) {
        ConnectionState.CONNECTED -> "Connected"
        ConnectionState.CONNECTING, ConnectionState.PREPARING -> "Connecting"
        ConnectionState.ERROR -> "Error: ${runtime.error}"
        else -> "Disconnected"
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(status, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(runtime.profileName.ifBlank { "Xray-only runtime" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ProfileRow(profile: ConfigProfile, selected: Boolean, onSelect: () -> Unit, onCopy: () -> Unit) {
    Card(onClick = onSelect, colors = CardDefaults.cardColors(
        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    )) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(profile.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${profile.protocol.uppercase()} · ${profile.server}:${profile.port}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCopy) { Text("Copy") }
            }
        }
    }
}
