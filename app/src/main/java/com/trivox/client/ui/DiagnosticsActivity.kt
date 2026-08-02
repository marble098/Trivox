package com.trivox.client.ui

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.trivox.client.R
import com.trivox.client.core.ConnectionRuntime
import com.trivox.client.core.CoreManager
import com.trivox.client.core.CoreManifest
import com.trivox.client.data.SettingsRepository
import com.trivox.client.util.Diagnostics

class DiagnosticsActivity : AppCompatActivity() {
    private var report = ""
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { runCatching { contentResolver.openOutputStream(it)?.bufferedWriter()?.use { writer -> writer.write(report) } }
            .onFailure { error -> Toast.makeText(this, error.message, Toast.LENGTH_LONG).show() } }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_diagnostics)
        findViewById<Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        val core = CoreManager(this); val manifest = runCatching { CoreManifest.load(this) }.getOrNull()
        val runtime = ConnectionRuntime.current(); val settings = SettingsRepository(this).load()
        report = Diagnostics.report(core.adapter.version(), settings.mode.name, runtime.state.name, runtime.profileName, runtime.error) + buildString {
            appendLine(); appendLine("--- Core manifest ---")
            appendLine("Adapter: ${manifest?.adapter ?: "unknown"}")
            appendLine("Pinned version: ${manifest?.version ?: "unknown"}")
            appendLine("Prepared: ${manifest?.prepared ?: false}")
            appendLine("Manifest SHA-256: ${manifest?.sha256?.takeIf(String::isNotBlank) ?: "missing"}")
            appendLine("ABIs: ${manifest?.abis?.joinToString().orEmpty()}")
            appendLine("Local ports: SOCKS ${settings.socksPort}, HTTP ${settings.httpPort}")
        }
        findViewById<TextView>(R.id.reportText).text = report
        findViewById<android.view.View>(R.id.exportButton).setOnClickListener { exporter.launch("trivox-diagnostics.txt") }
    }
}
