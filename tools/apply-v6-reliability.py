#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def fail(message: str) -> None:
    raise RuntimeError(message)


def read(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        fail(f"Missing required file: {rel}")
    return path.read_text(encoding="utf-8")


def write(rel: str, text: str) -> None:
    path = ROOT / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    if not text.endswith("\n"):
        text += "\n"
    old = path.read_text(encoding="utf-8") if path.exists() else None
    if old != text:
        path.write_text(text, encoding="utf-8")
        print(f"updated {rel}")


def replace_once(rel: str, old: str, new: str, marker: str | None = None) -> None:
    text = read(rel)
    if marker and marker in text:
        return
    count = text.count(old)
    if count != 1:
        fail(f"Expected exactly one match in {rel}, found {count}: {old[:100]!r}")
    write(rel, text.replace(old, new, 1))


def regex_once(
    rel: str,
    pattern: str,
    replacement: str,
    marker: str | None = None,
    flags: int = re.DOTALL,
) -> None:
    text = read(rel)
    if marker and marker in text:
        return
    updated, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        fail(f"Expected exactly one regex match in {rel}, found {count}: {pattern[:100]!r}")
    write(rel, updated)


def append_once(rel: str, block: str, marker: str) -> None:
    text = read(rel)
    if marker in text:
        return
    write(rel, text.rstrip() + "\n\n" + block.strip() + "\n")


def patch_models() -> None:
    rel = "app/src/main/java/com/trivox/client/data/Models.kt"
    replace_once(
        rel,
        "    var livePingMethod: PingMethod = PingMethod.XRAY_HTTP,\n"
        "    var hideIpOnMain: Boolean = false,",
        "    var livePingMethod: PingMethod = PingMethod.XRAY_HTTP,\n"
        "    var livePingEnabled: Boolean = true,\n"
        "    var livePingIntervalSeconds: Int = 8,\n"
        "    var hideIpOnMain: Boolean = false,",
        marker="var livePingIntervalSeconds",
    )
    replace_once(
        rel,
        "        testAttempts = testAttempts.coerceIn(2, 5)\n",
        "        testAttempts = testAttempts.coerceIn(2, 5)\n"
        "        livePingIntervalSeconds = livePingIntervalSeconds.coerceIn(3, 300)\n",
        marker="livePingIntervalSeconds = livePingIntervalSeconds.coerceIn",
    )
    replace_once(
        rel,
        "        .put(\"pingMethod\", pingMethod.name).put(\"livePingMethod\", livePingMethod.name)\n"
        "        .put(\"hideIpOnMain\", hideIpOnMain)",
        "        .put(\"pingMethod\", pingMethod.name).put(\"livePingMethod\", livePingMethod.name)\n"
        "        .put(\"livePingEnabled\", livePingEnabled)\n"
        "        .put(\"livePingIntervalSeconds\", livePingIntervalSeconds)\n"
        "        .put(\"hideIpOnMain\", hideIpOnMain)",
        marker='.put("livePingIntervalSeconds"',
    )
    replace_once(
        rel,
        "                livePingMethod = PingMethod.fromStored(\n"
        "                    json.optString(\"livePingMethod\"),\n"
        "                    legacyPingMethod\n"
        "                ),\n"
        "                hideIpOnMain = json.optBoolean(\"hideIpOnMain\", false),",
        "                livePingMethod = PingMethod.fromStored(\n"
        "                    json.optString(\"livePingMethod\"),\n"
        "                    legacyPingMethod\n"
        "                ),\n"
        "                livePingEnabled = json.optBoolean(\"livePingEnabled\", true),\n"
        "                livePingIntervalSeconds = json\n"
        "                    .optInt(\"livePingIntervalSeconds\", 8)\n"
        "                    .coerceIn(3, 300),\n"
        "                hideIpOnMain = json.optBoolean(\"hideIpOnMain\", false),",
        marker='optInt("livePingIntervalSeconds"',
    )


def patch_core_manager() -> None:
    rel = "app/src/main/java/com/trivox/client/core/CoreManager.kt"
    replace_once(
        rel,
        "    fun start(\n"
        "        request: CoreStartRequest,\n"
        "        protect: ((Int) -> Boolean)? = null\n"
        "    ): CoreResult {",
        "    fun start(\n"
        "        request: CoreStartRequest,\n"
        "        protect: ((Int) -> Boolean)? = null,\n"
        "        isCancelled: () -> Boolean = { false }\n"
        "    ): CoreResult {",
        marker="isCancelled: () -> Boolean",
    )
    replace_once(
        rel,
        "        return startPrepared(json, request, protect)\n"
        "    }\n\n"
        "    fun startValidated(\n"
        "        request: CoreStartRequest,\n"
        "        protect: ((Int) -> Boolean)? = null\n"
        "    ): CoreResult {",
        "        return startPrepared(json, request, protect, isCancelled)\n"
        "    }\n\n"
        "    fun startValidated(\n"
        "        request: CoreStartRequest,\n"
        "        protect: ((Int) -> Boolean)? = null,\n"
        "        isCancelled: () -> Boolean = { false }\n"
        "    ): CoreResult {",
        marker="startPrepared(json, request, protect, isCancelled)",
    )
    replace_once(
        rel,
        "        return startPrepared(json, request, protect)\n"
        "    }\n\n"
        "    private fun startPrepared(\n"
        "        json: String,\n"
        "        request: CoreStartRequest,\n"
        "        protect: ((Int) -> Boolean)?\n"
        "    ): CoreResult {\n"
        "        val started = adapter.start(json, protect)",
        "        return startPrepared(json, request, protect, isCancelled)\n"
        "    }\n\n"
        "    private fun startPrepared(\n"
        "        json: String,\n"
        "        request: CoreStartRequest,\n"
        "        protect: ((Int) -> Boolean)?,\n"
        "        isCancelled: () -> Boolean\n"
        "    ): CoreResult {\n"
        "        if (isCancelled()) return cancelledStart()\n"
        "        val started = adapter.start(json, protect)",
        marker="private fun cancelledStart",
    )
    replace_once(
        rel,
        "        if (!started.success) {\n"
        "            Diagnostics.error(started.error)\n"
        "            return started\n"
        "        }\n\n"
        "        if (!request.profile.protocol.equals(\"wireguard\", ignoreCase = true)) {",
        "        if (!started.success) {\n"
        "            Diagnostics.error(started.error)\n"
        "            return started\n"
        "        }\n\n"
        "        if (isCancelled()) {\n"
        "            runCatching { adapter.stop() }\n"
        "            return cancelledStart()\n"
        "        }\n\n"
        "        if (!request.profile.protocol.equals(\"wireguard\", ignoreCase = true)) {",
        marker="if (isCancelled()) {\n            runCatching { adapter.stop() }",
    )
    replace_once(
        rel,
        "        val health = TunnelHealthVerifier.measure(\n"
        "            settings = request.settings,\n"
        "            attempts = 1,\n"
        "            budgetMs = WIREGUARD_START_VERIFY_BUDGET_MS\n"
        "        )\n"
        "        if (health.success) return started\n\n"
        "        runCatching { adapter.stop() }",
        "        val health = TunnelHealthVerifier.measure(\n"
        "            settings = request.settings,\n"
        "            attempts = 1,\n"
        "            budgetMs = WIREGUARD_START_VERIFY_BUDGET_MS,\n"
        "            perProbeTimeoutMs = WIREGUARD_PROBE_TIMEOUT_MS,\n"
        "            isCancelled = isCancelled\n"
        "        )\n"
        "        if (isCancelled() || health.errorCategory == \"cancelled\") {\n"
        "            runCatching { adapter.stop() }\n"
        "            return cancelledStart()\n"
        "        }\n"
        "        if (health.success) return started\n\n"
        "        runCatching { adapter.stop() }",
        marker="WIREGUARD_PROBE_TIMEOUT_MS",
    )
    replace_once(
        rel,
        "    fun isRunning(): Boolean = adapter.state()",
        "    private fun cancelledStart(): CoreResult =\n"
        "        CoreResult(false, \"Connection start cancelled\")\n\n"
        "    fun isRunning(): Boolean = adapter.state()",
        marker="private fun cancelledStart",
    )
    replace_once(
        rel,
        "        private const val WIREGUARD_START_VERIFY_BUDGET_MS = 12_000\n",
        "        private const val WIREGUARD_START_VERIFY_BUDGET_MS = 7_000\n"
        "        private const val WIREGUARD_PROBE_TIMEOUT_MS = 900\n",
        marker="WIREGUARD_PROBE_TIMEOUT_MS = 900",
    )


def patch_tunnel_verifier() -> None:
    rel = "app/src/main/java/com/trivox/client/network/TunnelHealthVerifier.kt"
    new_text = r'''package com.trivox.client.network

import com.trivox.client.data.AppSettings
import com.trivox.client.data.PingMethod
import com.trivox.client.data.PingResult
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.Locale
import kotlin.math.abs

/**
 * A bounded end-to-end probe through Trivox's localhost mixed listener.
 * Native process liveness alone is intentionally not treated as connectivity.
 */
object TunnelHealthVerifier {
    private val fallbackTargets = listOf(
        "https://cp.cloudflare.com/generate_204",
        "https://connectivitycheck.gstatic.com/generate_204",
        "https://www.gstatic.com/generate_204"
    )

    fun measure(
        settings: AppSettings,
        attempts: Int = 2,
        budgetMs: Int = 12_000,
        perProbeTimeoutMs: Int = MAX_PROBE_TIMEOUT_MS,
        isCancelled: () -> Boolean = { false }
    ): PingResult {
        val timestamp = System.currentTimeMillis()
        val count = attempts.coerceIn(1, 3)
        val budget = budgetMs.coerceIn(1_500, 20_000)
        val probeTimeout = perProbeTimeoutMs.coerceIn(
            MIN_PROBE_TIMEOUT_MS,
            MAX_PROBE_TIMEOUT_MS
        )
        val deadline = System.nanoTime() + budget * 1_000_000L
        val samples = mutableListOf<Long>()
        var lastFailure = "tunnel_timeout"

        for (sampleIndex in 0 until count) {
            if (cancelled(isCancelled)) {
                return cancelledResult(timestamp, samples.size, count)
            }
            var accepted = false

            for (proxyType in listOf(Proxy.Type.HTTP, Proxy.Type.SOCKS)) {
                if (accepted || cancelled(isCancelled)) break
                for (target in rotatedTargets(settings, sampleIndex)) {
                    if (cancelled(isCancelled)) {
                        return cancelledResult(timestamp, samples.size, count)
                    }
                    val remainingMs = (
                        (deadline - System.nanoTime()) / 1_000_000L
                    ).coerceAtMost(probeTimeout.toLong()).toInt()
                    if (remainingMs < MIN_PROBE_TIMEOUT_MS) break

                    val result = probe(
                        settings = settings,
                        proxyType = proxyType,
                        url = target,
                        timeoutMs = remainingMs,
                        nonce = timestamp + sampleIndex + samples.size
                    )
                    if (result.latencyMs != null) {
                        samples += result.latencyMs
                        accepted = true
                        break
                    }
                    lastFailure = result.errorCategory ?: lastFailure
                }
            }
        }

        val sorted = samples.sorted()
        val latency = sorted.takeIf(List<Long>::isNotEmpty)
            ?.get(sorted.size / 2)
        val jitter = if (sorted.size > 1) {
            val deviations = sorted.map { abs(it - (latency ?: it)) }.sorted()
            deviations[deviations.size / 2]
        } else {
            null
        }

        return PingResult(
            method = PingMethod.XRAY_HTTP.name,
            success = latency != null,
            latencyMs = latency,
            jitterMs = jitter,
            successRatio = samples.size.toDouble() / count.toDouble(),
            resolvedIp = "127.0.0.1",
            timestamp = timestamp,
            errorCategory = if (latency == null) lastFailure else null
        )
    }

    private fun rotatedTargets(settings: AppSettings, offset: Int): List<String> {
        val targets = buildList {
            settings.testUrl.trim().takeIf(String::isNotBlank)?.let(::add)
            addAll(fallbackTargets)
        }.distinct()
        if (targets.isEmpty() || offset % targets.size == 0) return targets
        val shift = offset % targets.size
        return targets.drop(shift) + targets.take(shift)
    }

    private fun cancelled(isCancelled: () -> Boolean): Boolean =
        Thread.currentThread().isInterrupted || isCancelled()

    private fun cancelledResult(
        timestamp: Long,
        completed: Int,
        requested: Int
    ) = PingResult(
        method = PingMethod.XRAY_HTTP.name,
        success = false,
        latencyMs = null,
        jitterMs = null,
        successRatio = completed.toDouble() / requested.coerceAtLeast(1).toDouble(),
        resolvedIp = "127.0.0.1",
        timestamp = timestamp,
        errorCategory = "cancelled"
    )

    private fun probe(
        settings: AppSettings,
        proxyType: Proxy.Type,
        url: String,
        timeoutMs: Int,
        nonce: Long
    ): ProbeResult {
        var connection: HttpURLConnection? = null
        return try {
            val proxy = Proxy(
                proxyType,
                InetSocketAddress("127.0.0.1", settings.socksPort)
            )
            val separator = if ('?' in url) '&' else '?'
            val uri = URI("$url${separator}trivox_health=$nonce")
            connection = uri.toURL().openConnection(proxy) as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            connection.setRequestProperty("User-Agent", "Trivox-TunnelHealth/6")

            val started = System.nanoTime()
            val status = connection.responseCode
            val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
            if (status in 200..399) {
                ProbeResult(latencyMs = elapsed)
            } else {
                ProbeResult(errorCategory = "http_$status")
            }
        } catch (throwable: Throwable) {
            ProbeResult(
                errorCategory = throwable.javaClass.simpleName
                    .ifBlank { "tunnel_probe_failed" }
                    .lowercase(Locale.ROOT)
            )
        } finally {
            runCatching { connection?.inputStream?.close() }
            runCatching { connection?.errorStream?.close() }
            connection?.disconnect()
        }
    }

    private data class ProbeResult(
        val latencyMs: Long? = null,
        val errorCategory: String? = null
    )

    private const val MIN_PROBE_TIMEOUT_MS = 350
    private const val MAX_PROBE_TIMEOUT_MS = 4_500
}
'''
    write(rel, new_text)


def patch_services() -> None:
    vpn = "app/src/main/java/com/trivox/client/service/TrivoxVpnService.kt"
    proxy = "app/src/main/java/com/trivox/client/service/ConnectionService.kt"

    for rel in (vpn, proxy):
        replace_once(
            rel,
            "    private val executor =\n        Executors.newSingleThreadExecutor()\n",
            "    private val executor =\n        Executors.newSingleThreadExecutor()\n"
            "    private val cleanupExecutor =\n"
            "        Executors.newSingleThreadExecutor()\n",
            marker="private val cleanupExecutor",
        )

    replace_once(
        vpn,
        "        reconnectQueued.set(false)\n"
        "        ConnectionSessionStore\n"
        "            .clear(this)\n\n"
        "        executeSafely {",
        "        reconnectQueued.set(false)\n"
        "        ConnectionSessionStore\n"
        "            .clear(this)\n\n"
        "        cleanupExecutor.execute {",
        marker="cleanupExecutor.execute {",
    )
    replace_once(
        proxy,
        "        ConnectionSessionStore\n"
        "            .clear(this)\n\n"
        "        executeSafely {",
        "        ConnectionSessionStore\n"
        "            .clear(this)\n\n"
        "        cleanupExecutor.execute {",
        marker="cleanupExecutor.execute {",
    )

    # Both initial start and network reconnect become cooperatively cancellable.
    vpn_text = read(vpn)
    old_vpn_call = (
        "        val result =\n"
        "            core.startValidated(\n"
        "                request\n"
        "            ) { fd ->\n"
        "                protect(fd)\n"
        "            }"
    )
    new_vpn_call = (
        "        val result =\n"
        "            core.startValidated(\n"
        "                request = request,\n"
        "                protect = { fd -> protect(fd) },\n"
        "                isCancelled = stopRequested::get\n"
        "            )"
    )
    if old_vpn_call in vpn_text:
        count = vpn_text.count(old_vpn_call)
        if count not in (1, 2):
            fail(f"Unexpected WireGuard start-call count in {vpn}: {count}")
        write(vpn, vpn_text.replace(old_vpn_call, new_vpn_call))
    elif vpn_text.count("isCancelled = stopRequested::get") < 2:
        fail(f"Could not find cancellable VPN start calls in {vpn}")

    replace_once(
        proxy,
        "        val result =\n            core.start(request)\n",
        "        val result =\n"
        "            core.start(\n"
        "                request = request,\n"
        "                isCancelled = stopRequested::get\n"
        "            )\n",
        marker="isCancelled = stopRequested::get",
    )

    for rel in (vpn, proxy):
        replace_once(
            rel,
            "            if (\n"
            "                ownsCore.compareAndSet(\n"
            "                    true,\n"
            "                    false\n"
            "                )\n"
            "            ) {\n"
            "                core.stop()\n"
            "            }",
            "            ownsCore.set(false)\n"
            "            runCatching { core.stop() }\n"
            "                .onFailure {\n"
            "                    Diagnostics.warning(\n"
            "                        \"Immediate core stop failed: \" + it.message\n"
            "                    )\n"
            "                }",
            marker="Immediate core stop failed",
        )
        replace_once(
            rel,
            "        executor.shutdown",
            "        cleanupExecutor.shutdownNow()\n        executor.shutdown",
            marker="cleanupExecutor.shutdownNow()",
        )


def patch_main_activity() -> None:
    rel = "app/src/main/java/com/trivox/client/ui/MainActivity.kt"
    replace_once(
        rel,
        "import com.trivox.client.data.ConfigProfile\n",
        "import com.trivox.client.data.AppSettings\nimport com.trivox.client.data.ConfigProfile\n",
        marker="import com.trivox.client.data.AppSettings",
    )
    replace_once(rel, "import kotlin.math.abs\n", "", marker=None)
    replace_once(
        rel,
        "    private val livePingBusy =\n        AtomicBoolean(false)\n",
        "    private val livePingBusy =\n        AtomicBoolean(false)\n"
        "    private val manualLivePingRequested =\n"
        "        AtomicBoolean(false)\n",
        marker="manualLivePingRequested",
    )
    regex_once(
        rel,
        r"    private var lastLivePingPersistAt = 0L\n"
        r"    private var lastLivePingPersistMs: Long\? = null\n"
        r"    private var lastLivePingPersistProfileId:\n"
        r"        String\? = null\n",
        "",
        marker=None,
    )
    replace_once(
        rel,
        "        livePingResult = null\n"
        "        livePingText.setText(\n"
        "            R.string\n"
        "                .live_ping_measuring\n"
        "        )",
        "        manualLivePingRequested.set(true)\n"
        "        livePingResult = null\n"
        "        livePingText.setText(\n"
        "            R.string\n"
        "                .live_ping_measuring\n"
        "        )",
        marker="manualLivePingRequested.set(true)",
    )
    replace_once(
        rel,
        "            val profiles =\n"
        "                repository.all()\n"
        "                    .filter { it.enabled }",
        "            val subscriptionId = activeSubscriptionId\n"
        "            val profiles =\n"
        "                repository.all()\n"
        "                    .filter { profile ->\n"
        "                        profile.enabled &&\n"
        "                            (\n"
        "                                subscriptionId == null ||\n"
        "                                    profile.subscriptionId == subscriptionId\n"
        "                                )\n"
        "                    }",
        marker="val subscriptionId = activeSubscriptionId",
    )
    replace_once(
        rel,
        "                    profile.id ==\n"
        "                        current.profileId ||\n"
        "                        (\n"
        "                            sourceMatches &&\n"
        "                                searchMatches\n"
        "                            )",
        "                    sourceMatches &&\n"
        "                        (\n"
        "                            profile.id == current.profileId ||\n"
        "                                searchMatches\n"
        "                            )",
        marker="profile.id == current.profileId ||",
    )
    replace_once(
        rel,
        "        renderConnectedInfo(\n"
        "            current,\n"
        "            infoProfile,\n"
        "            settings.hideIpOnMain\n"
        "        )",
        "        renderConnectedInfo(\n"
        "            current,\n"
        "            infoProfile,\n"
        "            settings\n"
        "        )",
        marker="infoProfile,\n            settings\n",
    )
    replace_once(
        rel,
        "    private fun renderConnectedInfo(\n"
        "        snapshot: ConnectionRuntime.Snapshot,\n"
        "        profile: ConfigProfile?,\n"
        "        hideIp: Boolean\n"
        "    ) {",
        "    private fun renderConnectedInfo(\n"
        "        snapshot: ConnectionRuntime.Snapshot,\n"
        "        profile: ConfigProfile?,\n"
        "        settings: AppSettings\n"
        "    ) {\n"
        "        val hideIp = settings.hideIpOnMain",
        marker="val hideIp = settings.hideIpOnMain",
    )
    replace_once(
        rel,
        "            } else {\n"
        "                getString(\n"
        "                    R.string.live_ping_waiting\n"
        "                )\n"
        "            }\n\n"
        "        livePingText.isEnabled =",
        "            } else if (!settings.livePingEnabled) {\n"
        "                getString(\n"
        "                    R.string.live_ping_auto_disabled\n"
        "                )\n"
        "            } else {\n"
        "                getString(\n"
        "                    R.string.live_ping_waiting\n"
        "                )\n"
        "            }\n\n"
        "        livePingText.isEnabled =",
        marker="R.string.live_ping_auto_disabled",
    )
    replace_once(
        rel,
        "    private fun toggleConnection() {\n"
        "        if (!connectionActionBusy.compareAndSet(false, true)) return",
        "    private fun toggleConnection() {\n"
        "        if (connectionStartPending.get()) {\n"
        "            cancelPendingConnection()\n"
        "            return\n"
        "        }\n"
        "        if (!connectionActionBusy.compareAndSet(false, true)) return",
        marker="cancelPendingConnection()",
    )
    replace_once(
        rel,
        "            stopActiveConnection(runtime)\n"
        "            return\n"
        "        }",
        "            renderState(\n"
        "                runtime.copy(state = ConnectionState.STOPPING)\n"
        "            )\n"
        "            stopActiveConnection(runtime)\n"
        "            return\n"
        "        }",
        marker="runtime.copy(state = ConnectionState.STOPPING)",
    )
    replace_once(
        rel,
        "    private fun showConnectionStartPending(\n",
        "    private fun cancelPendingConnection() {\n"
        "        pendingVpnProfile = null\n"
        "        connectionStartPending.set(false)\n"
        "        handler.removeCallbacks(connectionStartTimeout)\n"
        "        val mode = settingsRepository.load().mode\n"
        "        val snapshot = ConnectionRuntime.current()\n"
        "        renderState(\n"
        "            snapshot.copy(\n"
        "                state = ConnectionState.STOPPING,\n"
        "                mode = snapshot.mode ?: mode\n"
        "            )\n"
        "        )\n"
        "        if (mode == ConnectionMode.PROXY) {\n"
        "            startService(\n"
        "                Intent(this, ConnectionService::class.java)\n"
        "                    .setAction(ConnectionService.ACTION_STOP)\n"
        "            )\n"
        "        } else {\n"
        "            startService(\n"
        "                Intent(this, TrivoxVpnService::class.java)\n"
        "                    .setAction(TrivoxVpnService.ACTION_STOP)\n"
        "            )\n"
        "        }\n"
        "    }\n\n"
        "    private fun showConnectionStartPending(\n",
        marker="private fun cancelPendingConnection",
    )
    replace_once(
        rel,
        "        connectButton.setText(\n"
        "            R.string.connecting_now\n"
        "        )\n"
        "        connectButton.isEnabled = false",
        "        connectButton.setText(\n"
        "            R.string.disconnect\n"
        "        )\n"
        "        connectButton.isEnabled = true",
        marker="R.string.disconnect\n        )\n        connectButton.isEnabled = true",
    )
    replace_once(
        rel,
        "        connectButton.isEnabled =\n"
        "            snapshot.state !in setOf(\n"
        "                ConnectionState.PREPARING,\n"
        "                ConnectionState.CONNECTING,\n"
        "                ConnectionState.STOPPING\n"
        "            )",
        "        connectButton.isEnabled =\n"
        "            snapshot.state != ConnectionState.STOPPING",
        marker="snapshot.state != ConnectionState.STOPPING",
    )
    replace_once(
        rel,
        "            handler.postDelayed(\n"
        "                durationTick,\n"
        "                1_000\n"
        "            )\n"
        "            handler.post(\n"
        "                livePingTick\n"
        "            )",
        "            handler.postDelayed(\n"
        "                durationTick,\n"
        "                1_000\n"
        "            )\n"
        "            if (settingsRepository.load().livePingEnabled) {\n"
        "                handler.post(\n"
        "                    livePingTick\n"
        "                )\n"
        "            }",
        marker="settingsRepository.load().livePingEnabled",
    )
    # Replace the complete periodic live-ping runner and remove persistence sorting side-effects.
    pattern = (
        r"    private val livePingTick =\n"
        r"        object : Runnable \{.*?\n"
        r"    private fun applyPingResultToProfile\("
    )
    replacement = r'''    private val livePingTick =
        object : Runnable {
            override fun run() {
                val snapshot = ConnectionRuntime.current()
                if (snapshot.state != ConnectionState.CONNECTED) return

                val settings = settingsRepository.load()
                val manual = manualLivePingRequested.getAndSet(false)
                if (!settings.livePingEnabled && !manual) return

                val profile = repository.find(snapshot.profileId) ?: return
                val sessionId = snapshot.sessionId
                if (!livePingBusy.compareAndSet(false, true)) {
                    if (settings.livePingEnabled) {
                        handler.postDelayed(this, livePingIntervalMs(settings))
                    }
                    return
                }

                latencyWorker.execute {
                    val result = runCatching {
                        when (settings.livePingMethod) {
                            PingMethod.TCP_CONNECT -> pingManager.tcp(
                                profile = profile,
                                attempts = settings.testAttempts,
                                timeoutMs = 5_000
                            )

                            PingMethod.XRAY_HTTP -> TunnelHealthVerifier.measure(
                                settings = settings,
                                attempts = settings.testAttempts.coerceAtMost(2),
                                budgetMs = LIVE_PING_BUDGET_MS,
                                isCancelled = {
                                    val latest = ConnectionRuntime.current()
                                    latest.state != ConnectionState.CONNECTED ||
                                        latest.sessionId != sessionId
                                }
                            )
                        }
                    }.getOrElse {
                        Diagnostics.recordThrowable("Live ping", it)
                        failedPingResult(settings.livePingMethod, it)
                    }

                    val latest = ConnectionRuntime.current()
                    if (
                        latest.state == ConnectionState.CONNECTED &&
                        latest.sessionId == sessionId &&
                        latest.profileId == profile.id
                    ) {
                        // Live ping is session telemetry only. It intentionally does
                        // not overwrite the stored batch result, so sorting remains stable.
                        livePingResult = result
                    }

                    livePingBusy.set(false)
                    runOnUiThread {
                        refresh()
                        val current = ConnectionRuntime.current()
                        val latestSettings = settingsRepository.load()
                        if (
                            current.state == ConnectionState.CONNECTED &&
                            current.sessionId == sessionId &&
                            latestSettings.livePingEnabled
                        ) {
                            handler.postDelayed(
                                this,
                                livePingIntervalMs(latestSettings)
                            )
                        }
                    }
                }
            }
        }

    private fun livePingIntervalMs(settings: AppSettings): Long =
        settings.livePingIntervalSeconds.coerceIn(3, 300) * 1_000L

    private fun applyPingResultToProfile('''
    regex_once(rel, pattern, replacement, marker="Live ping is session telemetry only")
    replace_once(
        rel,
        "        private const val LIVE_PING_INTERVAL_MS =\n"
        "            8_000L\n",
        "",
        marker=None,
    )
    regex_once(
        rel,
        r"        private const val LIVE_PING_PERSIST_INTERVAL_MS =\n"
        r"            60_000L\n"
        r"        private const val LIVE_PING_SIGNIFICANT_CHANGE_MS =\n"
        r"            50L\n",
        "",
        marker=None,
    )
    replace_once(
        rel,
        "            CONNECTION_ACTION_COOLDOWN_MS =\n                1_200L\n",
        "            CONNECTION_ACTION_COOLDOWN_MS =\n                250L\n",
        marker="250L",
    )


def patch_settings() -> None:
    rel = "app/src/main/java/com/trivox/client/ui/SettingsActivity.kt"
    replace_once(
        rel,
        "        val pingAttempts =\n"
        "            findViewById<Spinner>(\n"
        "                R.id.pingAttemptsSpinner\n"
        "            )",
        "        val livePingEnabled =\n"
        "            findViewById<SwitchCompat>(\n"
        "                R.id.livePingEnabledSwitch\n"
        "            )\n"
        "        val livePingInterval =\n"
        "            findViewById<EditText>(\n"
        "                R.id.livePingIntervalInput\n"
        "            )\n"
        "        val pingAttempts =\n"
        "            findViewById<Spinner>(\n"
        "                R.id.pingAttemptsSpinner\n"
        "            )",
        marker="R.id.livePingIntervalInput",
    )
    replace_once(
        rel,
        "            recreate()\n",
        "            recreateWithMotion()\n",
        marker="recreateWithMotion()",
    )
    replace_once(
        rel,
        "        val attempts =\n",
        "        livePingEnabled.isChecked = settings.livePingEnabled\n"
        "        livePingInterval.setText(\n"
        "            settings.livePingIntervalSeconds.toString()\n"
        "        )\n\n"
        "        val attempts =\n",
        marker="settings.livePingIntervalSeconds.toString()",
    )
    replace_once(
        rel,
        "            val testUrlValue =\n"
        "                testUrl.text\n"
        "                    .toString()\n"
        "                    .trim()\n",
        "            val testUrlValue =\n"
        "                testUrl.text\n"
        "                    .toString()\n"
        "                    .trim()\n"
        "            val livePingIntervalValue =\n"
        "                livePingInterval.text\n"
        "                    .toString()\n"
        "                    .toIntOrNull()\n",
        marker="livePingIntervalValue",
    )
    replace_once(
        rel,
        "            if (\n"
        "                !validTestUrl(\n"
        "                    testUrlValue\n"
        "                )\n"
        "            ) {",
        "            if (\n"
        "                livePingIntervalValue !in 3..300\n"
        "            ) {\n"
        "                Toast.makeText(\n"
        "                    this,\n"
        "                    R.string.invalid_live_ping_interval,\n"
        "                    Toast.LENGTH_LONG\n"
        "                ).show()\n"
        "                return@setOnClickListener\n"
        "            }\n\n"
        "            if (\n"
        "                !validTestUrl(\n"
        "                    testUrlValue\n"
        "                )\n"
        "            ) {",
        marker="R.string.invalid_live_ping_interval",
    )
    replace_once(
        rel,
        "            settings.livePingMethod =\n"
        "                pingMethods[\n"
        "                    pingMethod\n"
        "                        .selectedItemPosition\n"
        "                ]\n",
        "            settings.livePingMethod =\n"
        "                pingMethods[\n"
        "                    pingMethod\n"
        "                        .selectedItemPosition\n"
        "                ]\n"
        "            settings.livePingEnabled =\n"
        "                livePingEnabled.isChecked\n"
        "            settings.livePingIntervalSeconds =\n"
        "                livePingIntervalValue!!\n",
        marker="settings.livePingEnabled =",
    )

    layout = "app/src/main/res/layout/activity_settings.xml"
    replace_once(
        layout,
        "                <TextView\n"
        "                    android:id=\"@+id/pingMethodSummary\"\n"
        "                    android:layout_width=\"match_parent\"\n"
        "                    android:layout_height=\"wrap_content\"\n"
        "                    android:layout_marginTop=\"5dp\"\n"
        "                    android:textColor=\"@color/text_secondary\"\n"
        "                    android:textSize=\"10sp\" />\n\n"
        "                <TextView\n"
        "                    style=\"@style/Widget.Trivox.FieldLabel\"\n"
        "                    android:layout_marginTop=\"10dp\"\n"
        "                    android:text=\"@string/ping_attempts\" />",
        "                <TextView\n"
        "                    android:id=\"@+id/pingMethodSummary\"\n"
        "                    android:layout_width=\"match_parent\"\n"
        "                    android:layout_height=\"wrap_content\"\n"
        "                    android:layout_marginTop=\"5dp\"\n"
        "                    android:textColor=\"@color/text_secondary\"\n"
        "                    android:textSize=\"10sp\" />\n\n"
        "                <androidx.appcompat.widget.SwitchCompat\n"
        "                    android:id=\"@+id/livePingEnabledSwitch\"\n"
        "                    android:layout_width=\"match_parent\"\n"
        "                    android:layout_height=\"46dp\"\n"
        "                    android:layout_marginTop=\"8dp\"\n"
        "                    android:gravity=\"center_vertical\"\n"
        "                    android:text=\"@string/live_ping_enabled\"\n"
        "                    android:textColor=\"@color/text_primary\"\n"
        "                    android:textSize=\"11sp\"\n"
        "                    app:showText=\"false\" />\n\n"
        "                <TextView\n"
        "                    android:layout_width=\"match_parent\"\n"
        "                    android:layout_height=\"wrap_content\"\n"
        "                    android:layout_marginTop=\"-3dp\"\n"
        "                    android:text=\"@string/live_ping_enabled_summary\"\n"
        "                    android:textColor=\"@color/muted\"\n"
        "                    android:textSize=\"10sp\" />\n\n"
        "                <TextView\n"
        "                    style=\"@style/Widget.Trivox.FieldLabel\"\n"
        "                    android:layout_marginTop=\"10dp\"\n"
        "                    android:text=\"@string/live_ping_interval\" />\n\n"
        "                <EditText\n"
        "                    android:id=\"@+id/livePingIntervalInput\"\n"
        "                    style=\"@style/Widget.Trivox.Input\"\n"
        "                    android:layout_marginTop=\"3dp\"\n"
        "                    android:hint=\"@string/live_ping_interval_hint\"\n"
        "                    android:imeOptions=\"actionDone\"\n"
        "                    android:inputType=\"number\"\n"
        "                    android:maxLength=\"3\"\n"
        "                    android:textDirection=\"ltr\" />\n\n"
        "                <TextView\n"
        "                    style=\"@style/Widget.Trivox.FieldLabel\"\n"
        "                    android:layout_marginTop=\"10dp\"\n"
        "                    android:text=\"@string/ping_attempts\" />",
        marker="@+id/livePingIntervalInput",
    )


def patch_animations() -> None:
    rel = "app/src/main/java/com/trivox/client/ui/ThemedActivity.kt"
    write(
        rel,
        r'''package com.trivox.client.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity

open class ThemedActivity : AppCompatActivity() {
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (!motionEnabled()) return
        val root = window.decorView
        installPressMotion(root)
        root.alpha = 0.97f
        root.animate()
            .alpha(1f)
            .setDuration(120L)
            .start()
    }

    protected fun recreateWithMotion() {
        if (!motionEnabled()) {
            recreate()
            return
        }
        val root = window.decorView
        root.animate()
            .alpha(0.92f)
            .setDuration(90L)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    root.animate().setListener(null)
                    recreate()
                }
            })
            .start()
    }

    private fun installPressMotion(view: View) {
        if (
            view.isClickable &&
            view.stateListAnimator == null &&
            view !is ViewGroup
        ) {
            view.stateListAnimator = StateListAnimator().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 0.975f),
                            ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 0.975f)
                        )
                        duration = 65L
                    }
                )
                addState(
                    intArrayOf(),
                    AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(view, View.SCALE_X, 1f),
                            ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f)
                        )
                        duration = 95L
                    }
                )
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                installPressMotion(view.getChildAt(index))
            }
        }
    }

    private fun motionEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            ValueAnimator.areAnimatorsEnabled()
}
''',
    )


def patch_subscription_management() -> None:
    rel = "app/src/main/java/com/trivox/client/ui/SubscriptionManagementActivity.kt"
    replace_once(
        rel,
        "            url.text =\n"
        "                if (\n"
        "                    source.kind ==\n"
        "                    SubscriptionKind\n"
        "                        .NORDVPN\n"
        "                ) {\n"
        "                    getString(\n"
        "                        R.string\n"
        "                            .nordvpn_source_label\n"
        "                    )\n"
        "                } else {\n"
        "                    source.url\n"
        "                }",
        "            url.text =\n"
        "                when {\n"
        "                    source.kind == SubscriptionKind.NORDVPN ->\n"
        "                        getString(R.string.nordvpn_source_label)\n"
        "                    source.url.isBlank() ->\n"
        "                        getString(R.string.local_subscription_label)\n"
        "                    else -> source.url\n"
        "                }",
        marker="R.string.local_subscription_label",
    )
    replace_once(
        rel,
        "            update.isEnabled =\n                !operationBusy.get()",
        "            update.isEnabled =\n"
        "                !operationBusy.get() &&\n"
        "                    (\n"
        "                        source.kind == SubscriptionKind.NORDVPN ||\n"
        "                            source.url.isNotBlank()\n"
        "                        )",
        marker="source.url.isNotBlank()",
    )
    replace_once(
        rel,
        "                if (\n"
        "                    name.isBlank() ||\n"
        "                    !validSubscriptionUrl(\n"
        "                        url\n"
        "                    )\n"
        "                ) {",
        "                if (\n"
        "                    name.isBlank() ||\n"
        "                    (\n"
        "                        url.isNotBlank() &&\n"
        "                            !validSubscriptionUrl(url)\n"
        "                        )\n"
        "                ) {",
        marker="url.isNotBlank() &&",
    )
    replace_once(
        rel,
        "                if (source == null) {\n"
        "                    updateSources(\n"
        "                        listOf(saved)\n"
        "                    )\n"
        "                }",
        "                if (source == null && saved.url.isNotBlank()) {\n"
        "                    updateSources(\n"
        "                        listOf(saved)\n"
        "                    )\n"
        "                }",
        marker="source == null && saved.url.isNotBlank()",
    )
    replace_once(
        rel,
        "                .filter {\n"
        "                    it.enabled\n"
        "                }",
        "                .filter { source ->\n"
        "                    source.enabled &&\n"
        "                        (\n"
        "                            source.kind == SubscriptionKind.NORDVPN ||\n"
        "                                source.url.isNotBlank()\n"
        "                            )\n"
        "                }",
        marker=None,
    )

    coord = "app/src/main/java/com/trivox/client/network/SubscriptionRefreshCoordinator.kt"
    replace_once(
        coord,
        "                        .filter {\n"
        "                            it.enabled\n"
        "                        }",
        "                        .filter { source ->\n"
        "                            source.enabled &&\n"
        "                                (\n"
        "                                    source.kind != SubscriptionKind.URL ||\n"
        "                                        source.url.isNotBlank()\n"
        "                                    )\n"
        "                        }",
        marker="source.kind != SubscriptionKind.URL",
    )


def patch_grid() -> None:
    rel = "app/src/main/java/com/trivox/client/ui/ProfileAdapter.kt"
    replace_once(
        rel,
        "            location.visibility = if (locationValue.isBlank()) View.GONE else View.VISIBLE",
        "            location.visibility = when {\n"
        "                locationValue.isNotBlank() -> View.VISIBLE\n"
        "                rowIsGrid() -> View.INVISIBLE\n"
        "                else -> View.GONE\n"
        "            }",
        marker="rowIsGrid() -> View.INVISIBLE",
    )
    replace_once(
        rel,
        "            status.visibility = if (statusValue.isBlank()) View.GONE else View.VISIBLE",
        "            status.visibility = when {\n"
        "                statusValue.isNotBlank() -> View.VISIBLE\n"
        "                rowIsGrid() -> View.INVISIBLE\n"
        "                else -> View.GONE\n"
        "            }",
        marker="statusValue.isNotBlank() -> View.VISIBLE",
    )
    replace_once(
        rel,
        "        private fun pingMethodLabel(stored: String): String = when (",
        "        private fun rowIsGrid(): Boolean =\n"
        "            itemViewType == VIEW_GRID\n\n"
        "        private fun pingMethodLabel(stored: String): String = when (",
        marker="private fun rowIsGrid",
    )
    layout = "app/src/main/res/layout/row_profile_grid.xml"
    replace_once(
        layout,
        "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
        "    android:layout_width=\"match_parent\"\n"
        "    android:layout_height=\"wrap_content\"\n",
        "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
        "    android:layout_width=\"match_parent\"\n"
        "    android:layout_height=\"146dp\"\n",
        marker='android:layout_height="146dp"',
    )
    replace_once(
        layout,
        "    android:minHeight=\"132dp\"\n",
        "",
        marker=None,
    )


def patch_wireguard() -> None:
    rel = "app/src/main/java/com/trivox/client/config/XrayConfigBuilder.kt"
    pattern = (
        r"    private fun normalizeWireGuard\(\n"
        r"        outbound: JSONObject,\n"
        r"        settings: AppSettings\n"
        r"    \) \{.*?\n"
        r"    \}\n\n"
        r"    private fun mixedInbound"
    )
    replacement = r'''    private fun normalizeWireGuard(
        outbound: JSONObject,
        settings: AppSettings
    ) {
        val wireGuard = outbound.optJSONObject("settings") ?: JSONObject().also {
            outbound.put("settings", it)
        }
        val currentMtu = wireGuard.optInt("mtu", 1420).takeIf { it > 0 } ?: 1420
        val minimum = minOf(settings.mtu, if (settings.ipv6) 1280 else 1200)
        val reliableMtu = minOf(currentMtu, settings.mtu, WIREGUARD_RELIABLE_MTU)
            .coerceAtLeast(minimum)

        wireGuard.put("mtu", reliableMtu)
        wireGuard.put("noKernelTun", true)
        if (wireGuard.optInt("workers", 0) <= 0) {
            wireGuard.put("workers", 2)
        }

        val normalizedAddresses = JSONArray()
        val addresses = wireGuard.optJSONArray("address") ?: JSONArray()
        var hasIpv6Address = false
        for (index in 0 until addresses.length()) {
            val raw = addresses.optString(index).trim()
            if (raw.isBlank()) continue
            hasIpv6Address = hasIpv6Address || ':' in raw
            normalizedAddresses.put(
                when {
                    '/' in raw -> raw
                    ':' in raw -> "$raw/128"
                    else -> "$raw/32"
                }
            )
        }
        if (normalizedAddresses.length() > 0) {
            wireGuard.put("address", normalizedAddresses)
        }

        val validStrategies = setOf(
            "AsIs", "ForceIP", "ForceIPv4", "ForceIPv6", "ForceIPv4v6", "ForceIPv6v4"
        )
        val existingStrategy = wireGuard.optString("domainStrategy")
        if (existingStrategy !in validStrategies) {
            wireGuard.put(
                "domainStrategy",
                if (settings.ipv6 && hasIpv6Address) "ForceIP" else "ForceIPv4"
            )
        }

        val peers = wireGuard.optJSONArray("peers") ?: JSONArray()
        for (index in 0 until peers.length()) {
            val peer = peers.optJSONObject(index) ?: continue
            val endpoint = peer.optString("endpoint").trim()
                .removePrefix("udp://")
                .removePrefix("UDP://")
            if (endpoint.isNotBlank()) peer.put("endpoint", endpoint)
            if (peer.optInt("keepAlive", 0) <= 0) {
                peer.put("keepAlive", WIREGUARD_KEEPALIVE_SECONDS)
            }
        }
    }

    private fun mixedInbound'''
    regex_once(rel, pattern, replacement, marker='wireGuard.put("workers", 2)')


def patch_strings() -> None:
    write(
        "app/src/main/res/values/strings_v6.xml",
        '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="live_ping_enabled">Automatic live ping</string>
    <string name="live_ping_enabled_summary">Runs only while connected. Manual live ping remains available when automatic mode is off.</string>
    <string name="live_ping_interval">Live ping interval (seconds)</string>
    <string name="live_ping_interval_hint">3 to 300 seconds</string>
    <string name="invalid_live_ping_interval">Live ping interval must be between 3 and 300 seconds.</string>
    <string name="live_ping_auto_disabled">Live ping: automatic mode off</string>
    <string name="local_subscription_label">Local subscription • no URL</string>
</resources>
''',
    )
    write(
        "app/src/main/res/values-fa/strings_v6.xml",
        '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="live_ping_enabled">پینگ زنده خودکار</string>
    <string name="live_ping_enabled_summary">فقط هنگام اتصال اجرا می‌شود؛ در حالت خاموش نیز پینگ زنده دستی قابل استفاده است.</string>
    <string name="live_ping_interval">فاصله پینگ زنده (ثانیه)</string>
    <string name="live_ping_interval_hint">بین ۳ تا ۳۰۰ ثانیه</string>
    <string name="invalid_live_ping_interval">فاصله پینگ زنده باید بین ۳ تا ۳۰۰ ثانیه باشد.</string>
    <string name="live_ping_auto_disabled">پینگ زنده: حالت خودکار خاموش</string>
    <string name="local_subscription_label">ساب محلی • بدون لینک</string>
</resources>
''',
    )


def patch_tests() -> None:
    write(
        "app/src/test/java/com/trivox/client/data/AppSettingsLivePingTest.kt",
        r'''package com.trivox.client.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsLivePingTest {
    @Test
    fun livePingSettingsRoundTrip() {
        val source = AppSettings(
            livePingEnabled = false,
            livePingIntervalSeconds = 17
        )
        val restored = AppSettings.fromJson(source.toJson())
        assertFalse(restored.livePingEnabled)
        assertEquals(17, restored.livePingIntervalSeconds)
    }

    @Test
    fun livePingIntervalIsBounded() {
        assertEquals(3, AppSettings(livePingIntervalSeconds = 0).normalize().livePingIntervalSeconds)
        assertEquals(300, AppSettings(livePingIntervalSeconds = 999).normalize().livePingIntervalSeconds)
        assertTrue(AppSettings().livePingEnabled)
    }
}
''',
    )
    write(
        "app/src/test/java/com/trivox/client/config/XrayConfigBuilderWireGuardV6Test.kt",
        r'''package com.trivox.client.config

import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConnectionMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class XrayConfigBuilderWireGuardV6Test {
    @Test
    fun normalizesPortableWireGuardFieldsWithoutOverwritingValidStrategy() {
        val outbound = JSONObject()
            .put("protocol", "wireguard")
            .put(
                "settings",
                JSONObject()
                    .put("secretKey", "private")
                    .put("address", JSONArray().put("10.5.0.2"))
                    .put("domainStrategy", "AsIs")
                    .put(
                        "peers",
                        JSONArray().put(
                            JSONObject()
                                .put("publicKey", "public")
                                .put("endpoint", "udp://example.com:51820")
                        )
                    )
            )
        val profile = ConfigProfile(
            name = "wg",
            protocol = "wireguard",
            server = "example.com",
            port = 51820,
            raw = outbound.toString(),
            outboundJson = outbound.toString()
        )

        val built = JSONObject(
            XrayConfigBuilder.build(profile, AppSettings(), ConnectionMode.PROXY)
        )
        val settings = built.getJSONArray("outbounds")
            .getJSONObject(0)
            .getJSONObject("settings")

        assertEquals("10.5.0.2/32", settings.getJSONArray("address").getString(0))
        assertEquals("AsIs", settings.getString("domainStrategy"))
        assertEquals(2, settings.getInt("workers"))
        assertEquals(
            "example.com:51820",
            settings.getJSONArray("peers").getJSONObject(0).getString("endpoint")
        )
        assertEquals(25, settings.getJSONArray("peers").getJSONObject(0).getInt("keepAlive"))
    }
}
''',
    )


def patch_readme() -> None:
    append_once(
        "README.md",
        '''## Connection, subscription and motion reliability v6

The v6 pass makes WireGuard startup cancellation cooperative and moves service
cleanup to an independent executor, so disconnect no longer waits behind the
startup health probe. The health verifier accepts verified HTTP responses from
the configured test URL and multiple fallbacks while keeping strict time budgets.

Batch TCP ping and Real Delay now operate only on the selected subscription tab.
Automatic live ping can be disabled or scheduled from 3 to 300 seconds, and its
session-only measurements no longer overwrite stored batch latency or reshuffle
the connected card. Empty local subscriptions are supported, grid cards keep a
fixed footprint, and lightweight platform animations respect the system animator
setting.
''',
        marker="Connection, subscription and motion reliability v6",
    )


def checks() -> list[tuple[str, str]]:
    return [
        ("app/src/main/java/com/trivox/client/data/Models.kt", "livePingIntervalSeconds"),
        ("app/src/main/java/com/trivox/client/core/CoreManager.kt", "WIREGUARD_PROBE_TIMEOUT_MS"),
        ("app/src/main/java/com/trivox/client/network/TunnelHealthVerifier.kt", "isCancelled: () -> Boolean"),
        ("app/src/main/java/com/trivox/client/service/TrivoxVpnService.kt", "cleanupExecutor.execute"),
        ("app/src/main/java/com/trivox/client/service/ConnectionService.kt", "cleanupExecutor.execute"),
        ("app/src/main/java/com/trivox/client/ui/MainActivity.kt", "Live ping is session telemetry only"),
        ("app/src/main/java/com/trivox/client/ui/MainActivity.kt", "val subscriptionId = activeSubscriptionId"),
        ("app/src/main/java/com/trivox/client/ui/SettingsActivity.kt", "settings.livePingEnabled"),
        ("app/src/main/java/com/trivox/client/ui/ThemedActivity.kt", "recreateWithMotion"),
        ("app/src/main/java/com/trivox/client/ui/SubscriptionManagementActivity.kt", "saved.url.isNotBlank"),
        ("app/src/main/java/com/trivox/client/network/SubscriptionRefreshCoordinator.kt", "source.url.isNotBlank"),
        ("app/src/main/java/com/trivox/client/config/XrayConfigBuilder.kt", 'wireGuard.put("workers", 2)'),
        ("app/src/main/res/layout/activity_settings.xml", "livePingIntervalInput"),
        ("app/src/main/res/layout/row_profile_grid.xml", 'android:layout_height="146dp"'),
        ("app/src/main/res/values/strings_v6.xml", "live_ping_enabled"),
        ("app/src/main/res/values-fa/strings_v6.xml", "live_ping_enabled"),
        ("README.md", "Connection, subscription and motion reliability v6"),
    ]


def verify() -> None:
    missing: list[str] = []
    for rel, marker in checks():
        path = ROOT / rel
        if not path.is_file() or marker not in path.read_text(encoding="utf-8"):
            missing.append(f"{rel}: {marker}")
    if missing:
        fail("v6 verification failed:\n- " + "\n- ".join(missing))
    print(f"v6 verification passed ({len(checks())} guards)")


def apply() -> None:
    patch_models()
    patch_core_manager()
    patch_tunnel_verifier()
    patch_services()
    patch_main_activity()
    patch_settings()
    patch_animations()
    patch_subscription_management()
    patch_grid()
    patch_wireguard()
    patch_strings()
    patch_tests()
    patch_readme()
    verify()


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Apply Trivox v6 connection, subscription and UI reliability changes"
    )
    parser.add_argument("--check", action="store_true", help="verify without modifying files")
    args = parser.parse_args()
    try:
        if args.check:
            verify()
        else:
            apply()
        return 0
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
