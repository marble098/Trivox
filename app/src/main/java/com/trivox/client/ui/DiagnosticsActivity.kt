package com.trivox.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import java.io.BufferedWriter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.trivox.client.R
import com.trivox.client.ui.compose.LegacyLayoutBridge
import com.trivox.client.ui.compose.showLegacyMirror
import com.trivox.client.core.ConnectionRuntime
import com.trivox.client.core.CoreManifest
import com.trivox.client.data.SettingsRepository
import com.trivox.client.util.Diagnostics

class DiagnosticsActivity : ThemedActivity() {
    private lateinit var reportText:
        TextView

    private var report = ""

    private val exporter =
        registerForActivityResult(
            ActivityResultContracts
                .CreateDocument(
                    "text/plain"
                )
        ) { uri: Uri? ->
            uri?.let {
                runCatching {
                    contentResolver
                        .openOutputStream(it)
                        ?.bufferedWriter()
                        ?.use {
                                writer: BufferedWriter ->
                            writer.write(report)
                        }
                }.onFailure {
                        error: Throwable ->
                    toast(
                        error.message
                            ?: getString(
                                R.string
                                    .unknown_error
                            )
                    )
                }
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(
            savedInstanceState
        )
        val composeHost = LegacyLayoutBridge.install(this, "activity_diagnostics")
        composeHost.compose.showLegacyMirror(this, composeHost.legacy, getString(R.string.diagnostics))

        findViewById<Toolbar>(
            R.id.toolbar
        ).setNavigationOnClickListener {
            finish()
        }

        reportText =
            findViewById(
                R.id.reportText
            )

        findViewById<
            android.view.View
            >(
            R.id.refreshDiagnosticsButton
        ).setOnClickListener {
            renderReport()
        }

        findViewById<
            android.view.View
            >(
            R.id.copyDiagnosticsButton
        ).setOnClickListener {
            val clipboard =
                getSystemService(
                    ClipboardManager::class.java
                )

            clipboard.setPrimaryClip(
                ClipData.newPlainText(
                    getString(
                        R.string.diagnostics
                    ),
                    report
                )
            )
            toast(
                getString(
                    R.string.copied
                )
            )
        }

        findViewById<
            android.view.View
            >(
            R.id.clearDiagnosticsButton
        ).setOnClickListener {
            AlertDialog
                .Builder(this)
                .setTitle(
                    R.string
                        .clear_diagnostics_title
                )
                .setMessage(
                    R.string
                        .clear_diagnostics_message
                )
                .setNegativeButton(
                    R.string.cancel,
                    null
                )
                .setPositiveButton(
                    R.string.delete
                ) {
                        _: DialogInterface,
                        _: Int ->
                    Diagnostics.clear()
                    Diagnostics.info(
                        "Diagnostics were " +
                            "cleared by user"
                    )
                    renderReport()
                    toast(
                        getString(
                            R.string
                                .diagnostics_cleared
                        )
                    )
                }
                .show()
        }

        findViewById<
            android.view.View
            >(
            R.id.exportButton
        ).setOnClickListener {
            exporter.launch(
                "trivox-diagnostics.txt"
            )
        }

        renderReport()
    }

    override fun onResume() {
        super.onResume()
        renderReport()
    }

    private fun renderReport() {
        val manifest =
            runCatching {
                CoreManifest.load(this)
            }.getOrNull()

        val runtime =
            ConnectionRuntime.current()
        val settings =
            SettingsRepository(this)
                .load()

        report =
            Diagnostics.report(
                coreVersion =
                    manifest?.version
                        ?: "unknown",
                mode =
                    settings.mode.name,
                state =
                    runtime.state.name,
                profile =
                    runtime.profileName,
                lastError =
                    runtime.error
            ) +
                buildString {
                    appendLine()
                    appendLine()
                    appendLine(
                        "--- Core manifest ---"
                    )
                    appendLine(
                        "Adapter: " +
                            (
                                manifest
                                    ?.adapter
                                    ?: "unknown"
                                )
                    )
                    appendLine(
                        "Pinned version: " +
                            (
                                manifest
                                    ?.version
                                    ?: "unknown"
                                )
                    )
                    appendLine(
                        "Prepared: " +
                            (
                                manifest
                                    ?.prepared
                                    ?: false
                                )
                    )
                    appendLine(
                        "Manifest SHA-256: " +
                            (
                                manifest
                                    ?.sha256
                                    ?.takeIf(
                                        String::isNotBlank
                                    )
                                    ?: "missing"
                                )
                    )
                    appendLine(
                        "ABIs: " +
                            manifest
                                ?.abis
                                ?.joinToString()
                                .orEmpty()
                    )
                    appendLine(
                        "Local mixed proxy: " +
                            "127.0.0.1:" +
                            settings.socksPort
                    )
                }

        reportText.text = report
    }

    private fun toast(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }
}
