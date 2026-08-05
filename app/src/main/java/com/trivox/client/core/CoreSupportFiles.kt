package com.trivox.client.core

import android.content.Context
import com.trivox.client.util.Diagnostics
import java.io.File

object CoreSupportFiles {
    private val names = listOf(
        "Country.mmdb",
        "geoip.dat",
        "geosite.dat",
        "geoip.db",
        "geosite.db",
        "geoip.metadb"
    )

    fun install(context: Context, workDir: File, homeDir: File) {
        val dataDir = File(workDir, "data").apply { mkdirs() }
        val dirs = listOf(
            dataDir,
            homeDir,
            File(homeDir, ".config/mihomo"),
            File(homeDir, ".config/sing-box")
        )
        dirs.forEach { it.mkdirs() }

        names.forEach { name ->
            val source = "core-data/$name"
            val targets = dirs.map { File(it, name) }
            val alreadyReady = targets.any { it.isFile && it.length() > 0L }
            if (alreadyReady) return@forEach

            runCatching {
                context.assets.open(source).use { input ->
                    val bytes = input.readBytes()
                    if (bytes.isEmpty()) return@use
                    targets.forEach { target ->
                        target.parentFile?.mkdirs()
                        target.outputStream().use { it.write(bytes) }
                    }
                    Diagnostics.info("Installed core support file: $name")
                }
            }.onFailure {
                Diagnostics.warning("Core support file missing or skipped: $name")
            }
        }
    }

    fun applyEnvironment(
        environment: MutableMap<String, String>,
        workDir: File,
        homeDir: File
    ) {
        val dataDir = File(workDir, "data").apply { mkdirs() }
        environment["TRIVOX_CORE_DATA"] = dataDir.absolutePath
        environment["V2RAY_LOCATION_ASSET"] = dataDir.absolutePath
        environment["XRAY_LOCATION_ASSET"] = dataDir.absolutePath
        environment["SING_BOX_DATA_DIR"] = dataDir.absolutePath
        environment["GEOIP_DB_PATH"] = File(dataDir, "geoip.db").absolutePath
        environment["GEOSITE_DB_PATH"] = File(dataDir, "geosite.db").absolutePath
        environment["GEOIP_DAT_PATH"] = File(dataDir, "geoip.dat").absolutePath
        environment["GEOSITE_DAT_PATH"] = File(dataDir, "geosite.dat").absolutePath
        environment["CLASH_HOME"] = homeDir.absolutePath
        environment["MIHOMO_HOME"] = homeDir.absolutePath
    }
}
