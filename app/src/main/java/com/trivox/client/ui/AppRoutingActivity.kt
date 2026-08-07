package com.trivox.client.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.trivox.client.data.AppRoutingMode
import com.trivox.client.data.SettingsRepository
import com.trivox.client.ui.compose.RoutingAppUi
import com.trivox.client.ui.compose.RoutingComposeActions
import com.trivox.client.ui.compose.RoutingComposeScreen
import java.util.concurrent.Executors

class AppRoutingActivity : ThemedActivity(), RoutingComposeActions {
    private data class Item(
        val label: String,
        val packageName: String,
        val system: Boolean
    )

    private var allItems by mutableStateOf<List<Item>>(emptyList())
    private var selectedPackages by mutableStateOf<Set<String>>(emptySet())
    private var showSystemApps by mutableStateOf(false)
    private var selectedMode by mutableStateOf(AppRoutingMode.ALL)
    private lateinit var repository: SettingsRepository
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SettingsRepository(this)
        repository.load().also { settings ->
            selectedPackages = settings.routedPackages.toSet()
            showSystemApps = settings.showSystemApps
            selectedMode = settings.appRoutingMode
        }

        setContent {
            RoutingComposeScreen(this@AppRoutingActivity, this@AppRoutingActivity)
        }

        worker.execute {
            val loaded = loadApplications()
            if (!isFinishing && !isDestroyed) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) allItems = loaded
                }
            }
        }
    }

    private fun loadApplications(): List<Item> {
        val apps =
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0)
            }

        return apps.asSequence()
            .filter { it.packageName != packageName }
            .map {
                Item(
                    label = packageManager.getApplicationLabel(it).toString(),
                    packageName = it.packageName,
                    system = it.flags and ApplicationInfo.FLAG_SYSTEM != 0
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    override fun routingItems(): List<RoutingAppUi> =
        allItems.map { RoutingAppUi(it.label, it.packageName, it.system) }

    override fun routingSelected(): Set<String> = selectedPackages

    override fun routingToggle(packageName: String) {
        selectedPackages = selectedPackages.toMutableSet().apply {
            if (!add(packageName)) remove(packageName)
        }.toSet()
    }

    override fun routingShowSystem(): Boolean = showSystemApps

    override fun routingSetShowSystem(value: Boolean) {
        showSystemApps = value
    }

    override fun routingMode(): AppRoutingMode = selectedMode

    override fun routingSetMode(mode: AppRoutingMode) {
        selectedMode = mode
    }

    override fun routingSave() {
        val latest = repository.load()
        latest.appRoutingMode = selectedMode
        latest.routedPackages = selectedPackages
        latest.showSystemApps = showSystemApps
        repository.save(latest)
        finish()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
