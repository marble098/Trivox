#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

CHANGED_PATHS = [
    "README.md",
    "app/src/main/java/com/trivox/client/config/XrayConfigBuilder.kt",
    "app/src/main/java/com/trivox/client/core/CoreManager.kt",
    "app/src/main/java/com/trivox/client/network/PingManager.kt",
    "app/src/main/java/com/trivox/client/network/TunnelHealthVerifier.kt",
    "app/src/main/java/com/trivox/client/ui/MainActivity.kt",
    "app/src/main/java/com/trivox/client/ui/ProfileAdapter.kt",
    "app/src/main/res/drawable/ic_expand_more_rounded.xml",
    "app/src/main/res/layout/activity_main.xml",
    "app/src/main/res/layout/activity_settings.xml",
    "app/src/main/res/layout/row_profile_grid.xml",
    "app/src/main/res/values/strings_v5.xml",
    "app/src/main/res/values-fa/strings_v5.xml",
    "app/src/test/java/com/trivox/client/config/XrayConfigBuilderWireGuardTest.kt",
    "tools/apply-v5-wireguard-dns-ui.py",
    "tools/install-trivox-v5-termux.sh",
]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    normalized = content.lstrip("\ufeff\r\n").rstrip() + "\n"
    if target.exists() and target.read_text(encoding="utf-8") == normalized:
        return
    target.write_text(normalized, encoding="utf-8")


def replace_once(path: str, old: str, new: str, marker: str | None = None) -> None:
    value = read(path)
    if marker and marker in value:
        return
    if old in value:
        value = value.replace(old, new, 1)
        write(path, value)
        return
    raise RuntimeError(f"Expected source block was not found in {path}")


def regex_once(path: str, pattern: str, replacement: str, marker: str) -> None:
    value = read(path)
    if marker in value:
        return
    updated, count = re.subn(pattern, replacement, value, count=1, flags=re.DOTALL)
    if count != 1:
        raise RuntimeError(f"Expected regex block was not found in {path}")
    write(path, updated)


XRAY_CONFIG_BUILDER = r'''
package com.trivox.client.config

import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.DnsMode
import org.json.JSONArray
import org.json.JSONObject

object Validators {
    fun validPort(port: Int) = port in 1..65535

    fun validateDns(value: String): Boolean {
        val text = value.trim()
        if (text.isBlank() || text.any(Char::isWhitespace)) return false
        if (
            listOf(
                "https://",
                "https+local://",
                "tcp://",
                "tcp+local://",
                "quic://",
                "quic+local://"
            ).any { text.startsWith(it, ignoreCase = true) }
        ) {
            return true
        }
        if (text.startsWith('[')) return text.contains(']')
        return text.matches(Regex("[A-Za-z0-9._:-]+"))
    }
}

object XrayConfigBuilder {
    private const val WIREGUARD_RELIABLE_MTU = 1360
    private const val WIREGUARD_KEEPALIVE_SECONDS = 25
    private const val MAX_CUSTOM_DNS_SERVERS = 8

    private val privateNetworks = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
        "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24",
        "192.88.99.0/24", "192.168.0.0/16", "198.18.0.0/15",
        "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4",
        "::/128", "::1/128", "2001:db8::/32", "fc00::/7", "fe80::/10", "ff00::/8"
    )

    fun build(
        profile: ConfigProfile,
        settings: AppSettings,
        mode: ConnectionMode,
        tunFd: Int? = null,
        errorLogPath: String? = null
    ): String {
        settings.normalize()
        require(Validators.validPort(settings.socksPort)) { "Invalid mixed proxy port" }
        require(settings.mtu in 576..9000) { "MTU must be between 576 and 9000" }
        if (settings.dnsMode == DnsMode.CUSTOM) {
            require(
                settings.customDns.isNotEmpty() &&
                    settings.customDns.size <= MAX_CUSTOM_DNS_SERVERS &&
                    settings.customDns.all(Validators::validateDns)
            ) { "Invalid custom DNS endpoint" }
        }

        val log = JSONObject().put("loglevel", "warning")
        if (!errorLogPath.isNullOrBlank()) log.put("error", errorLogPath)

        val root = JSONObject().put("log", log)
        if (tunFd != null) {
            root.put("env", JSONObject().put("xray.tun.fd", tunFd.toString()))
        }
        root.put("inbounds", buildInbounds(profile, settings, mode))
        root.put("outbounds", buildOutbounds(profile, settings))
        root.put("dns", dns(profile, settings))
        root.put("routing", routing())
        return root.toString(2)
    }

    private fun buildInbounds(
        profile: ConfigProfile,
        settings: AppSettings,
        mode: ConnectionMode
    ): JSONArray {
        val result = JSONArray()
        if (mode == ConnectionMode.VPN) result.put(tunInbound(settings))

        // WireGuard is not considered connected until this localhost-only mixed
        // listener passes an end-to-end HTTP health probe.
        if (
            mode == ConnectionMode.PROXY ||
            settings.localProxyInVpn ||
            profile.protocol.equals("wireguard", ignoreCase = true)
        ) {
            result.put(mixedInbound(settings))
        }
        return result
    }

    private fun buildOutbounds(
        profile: ConfigProfile,
        settings: AppSettings
    ): JSONArray {
        val chain = ProxyChainCodec.decode(profile.outboundJson)
        val result = JSONArray()
        if (chain == null) {
            result.put(
                normalizeOutbound(JSONObject(profile.outboundJson), settings)
                    .put("tag", "proxy")
            )
        } else {
            val bridge = normalizeOutbound(JSONObject(chain.bridge.toString()), settings)
                .put("tag", "chain-bridge")
            val exit = normalizeOutbound(JSONObject(chain.exit.toString()), settings)
                .put("tag", "proxy")
                .put(
                    "proxySettings",
                    JSONObject().put("tag", "chain-bridge").put("transportLayer", true)
                )
            result.put(exit).put(bridge)
        }

        return result
            .put(dnsOutbound())
            .put(JSONObject().put("protocol", "freedom").put("tag", "direct"))
            .put(JSONObject().put("protocol", "blackhole").put("tag", "block"))
    }

    private fun normalizeOutbound(
        outbound: JSONObject,
        settings: AppSettings
    ): JSONObject {
        when (outbound.optString("protocol").lowercase()) {
            "shadowsocks" -> normalizeShadowsocks(outbound)
            "wireguard" -> normalizeWireGuard(outbound, settings)
        }

        val stream = outbound.optJSONObject("streamSettings") ?: return outbound
        val network = stream.optString("network").trim().lowercase()
        if (network.isBlank() || network == "none" || network == "null") {
            stream.put("network", "tcp")
        }
        return outbound
    }

    private fun normalizeShadowsocks(outbound: JSONObject) {
        val servers = outbound.optJSONObject("settings")
            ?.optJSONArray("servers") ?: JSONArray()
        for (index in 0 until servers.length()) {
            val server = servers.optJSONObject(index) ?: continue
            server.put(
                "password",
                Shadowsocks2022.normalizePassword(
                    server.optString("method"),
                    server.optString("password")
                )
            )
        }
    }

    private fun normalizeWireGuard(
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
        wireGuard.put("domainStrategy", "ForceIP")
        wireGuard.put("noKernelTun", true)

        val peers = wireGuard.optJSONArray("peers") ?: JSONArray()
        for (index in 0 until peers.length()) {
            val peer = peers.optJSONObject(index) ?: continue
            if (peer.optInt("keepAlive", 0) <= 0) {
                peer.put("keepAlive", WIREGUARD_KEEPALIVE_SECONDS)
            }
        }
    }

    private fun mixedInbound(settings: AppSettings) = JSONObject()
        .put("tag", "mixed-in")
        .put("listen", "127.0.0.1")
        .put("port", settings.socksPort)
        .put("protocol", "mixed")
        .put("settings", JSONObject().put("udp", true).put("auth", "noauth"))
        .put(
            "sniffing",
            JSONObject()
                .put("enabled", true)
                .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                .put("routeOnly", false)
        )

    private fun tunInbound(settings: AppSettings) = JSONObject()
        .put("tag", "tun-in")
        .put("port", 0)
        .put("protocol", "tun")
        .put("settings", JSONObject().put("name", "trivox0").put("mtu", settings.mtu))

    private fun dnsOutbound() = JSONObject()
        .put("protocol", "dns")
        .put("tag", "dns-out")
        .put(
            "settings",
            JSONObject().put(
                "rules",
                JSONArray().put(
                    JSONObject()
                        .put("action", "hijack")
                        .put("qType", "1,28")
                )
            )
        )

    private fun dns(
        profile: ConfigProfile,
        settings: AppSettings
    ): JSONObject = when (settings.dnsMode) {
        DnsMode.IMPORTED -> profile.originalDnsJson
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: smartDns(remoteSecureDns(profile), settings)

        DnsMode.CUSTOM -> smartDns(
            JSONArray(
                settings.customDns
                    .asSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                    .take(MAX_CUSTOM_DNS_SERVERS)
                    .toList()
            ),
            settings
        )

        DnsMode.SYSTEM -> JSONObject()
            .put("servers", JSONArray().put("localhost"))
            .put("queryStrategy", queryStrategy(settings))

        DnsMode.DIRECT -> smartDns(localSecureDns(), settings)
        DnsMode.THROUGH_PROXY,
        DnsMode.TRIVOX_DEFAULT -> smartDns(remoteSecureDns(profile), settings)
    }

    private fun smartDns(
        servers: JSONArray,
        settings: AppSettings
    ) = JSONObject()
        .put("servers", servers)
        .put("queryStrategy", queryStrategy(settings))
        .put("disableCache", false)
        .put("disableFallback", false)
        .put("disableFallbackIfMatch", false)
        .put("enableParallelQuery", true)
        .put("useSystemHosts", false)

    private fun queryStrategy(settings: AppSettings) =
        if (settings.ipv6) "UseIP" else "UseIPv4"

    private fun remoteSecureDns(profile: ConfigProfile): JSONArray =
        JSONArray().apply {
            if (
                profile.protocol.equals("wireguard", ignoreCase = true) &&
                !isIpLiteral(profile.server)
            ) {
                put(
                    JSONObject()
                        .put("address", "https+local://1.1.1.1/dns-query")
                        .put("domains", JSONArray().put("full:${profile.server}"))
                        .put("skipFallback", true)
                        .put("queryStrategy", "UseIPv4")
                )
            }
            put("https://1.1.1.1/dns-query")
            put("https://8.8.8.8/dns-query")
        }

    private fun localSecureDns() = JSONArray()
        .put("https+local://1.1.1.1/dns-query")
        .put("https+local://8.8.8.8/dns-query")

    private fun isIpLiteral(value: String): Boolean {
        val clean = value.trim().removePrefix("[").removeSuffix("]")
        return clean.matches(Regex("[0-9.]+")) ||
            (':' in clean && clean.matches(Regex("[0-9a-fA-F:]+")))
    }

    private fun routing(): JSONObject {
        val rules = JSONArray()
            .put(
                JSONObject()
                    .put("type", "field")
                    .put("inboundTag", JSONArray().put("tun-in"))
                    .put("network", "udp,tcp")
                    .put("port", "53")
                    .put("outboundTag", "dns-out")
            )
            .put(
                JSONObject()
                    .put("type", "field")
                    .put("ip", JSONArray(privateNetworks))
                    .put("outboundTag", "direct")
            )

        return JSONObject()
            .put("domainStrategy", "IPIfNonMatch")
            .put("rules", rules)
    }
}
'''

CORE_MANAGER = r'''
package com.trivox.client.core

import android.content.Context
import com.trivox.client.config.XrayConfigBuilder
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.ConnectionState
import com.trivox.client.network.TunnelHealthVerifier
import com.trivox.client.util.Diagnostics
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object ConnectionRuntime {
    data class Snapshot(
        val state: ConnectionState = ConnectionState.DISCONNECTED,
        val profileId: String? = null,
        val profileName: String = "",
        val startedElapsed: Long = 0,
        val error: String = "",
        val mode: ConnectionMode? = null,
        val sessionId: Long = 0
    )

    private val snapshot = AtomicReference(Snapshot())
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val sessionCounter = AtomicLong(0)

    fun current(): Snapshot = snapshot.get()
    fun nextSessionId(): Long = sessionCounter.incrementAndGet()

    fun update(value: Snapshot) {
        snapshot.set(value)
        notifyListeners(value)
    }

    fun updateSession(
        sessionId: Long,
        transform: (Snapshot) -> Snapshot
    ): Boolean {
        while (true) {
            val current = snapshot.get()
            if (current.sessionId != sessionId || sessionId == 0L) return false
            val next = transform(current)
            if (snapshot.compareAndSet(current, next)) {
                notifyListeners(next)
                return true
            }
        }
    }

    fun addListener(listener: (Snapshot) -> Unit) {
        listeners += listener
        runCatching { listener(snapshot.get()) }
            .onFailure {
                Diagnostics.warning("Connection listener failed: " + it.message)
            }
    }

    private fun notifyListeners(value: Snapshot) {
        listeners.forEach { listener ->
            runCatching { listener(value) }
                .onFailure {
                    Diagnostics.warning("Connection listener failed: " + it.message)
                }
        }
    }

    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners -= listener
    }
}

class CoreManager(context: Context) {
    private val appContext = context.applicationContext
    val adapter: CoreAdapter = XrayCoreAdapter(appContext)

    fun prepare(request: CoreStartRequest): Pair<String?, CoreResult> =
        runCatching {
            val json = buildJson(request)
            val validation = adapter.validate(json)
            if (!validation.success) null to validation
            else json to CoreResult(true)
        }.getOrElse {
            Diagnostics.recordThrowable("Core configuration preparation", it)
            null to CoreResult(
                false,
                "Configuration generation failed: " + it.message
            )
        }

    fun start(
        request: CoreStartRequest,
        protect: ((Int) -> Boolean)? = null
    ): CoreResult {
        val (json, prepared) = prepare(request)
        if (!prepared.success || json == null) return prepared
        return startPrepared(json, request, protect)
    }

    fun startValidated(
        request: CoreStartRequest,
        protect: ((Int) -> Boolean)? = null
    ): CoreResult {
        val json = runCatching { buildJson(request) }.getOrElse {
            Diagnostics.recordThrowable("Validated core start", it)
            return CoreResult(
                false,
                "Configuration generation failed: " + it.message
            )
        }
        return startPrepared(json, request, protect)
    }

    private fun startPrepared(
        json: String,
        request: CoreStartRequest,
        protect: ((Int) -> Boolean)?
    ): CoreResult {
        val started = adapter.start(json, protect)
        if (!started.success) {
            Diagnostics.error(started.error)
            return started
        }

        if (!request.profile.protocol.equals("wireguard", ignoreCase = true)) {
            return started
        }

        val health = TunnelHealthVerifier.measure(
            settings = request.settings,
            attempts = 1,
            budgetMs = WIREGUARD_START_VERIFY_BUDGET_MS
        )
        if (health.success) return started

        runCatching { adapter.stop() }
        val category = health.errorCategory
            ?.takeIf(String::isNotBlank)
            ?.let { " ($it)" }
            .orEmpty()
        val error =
            "WireGuard started locally, but no verified traffic crossed the tunnel$category. " +
                "The endpoint may be filtered, blocked, or incompatible with this network."
        Diagnostics.error(error)
        return CoreResult(false, error)
    }

    fun isRunning(): Boolean = adapter.state()
        .data
        ?.optJSONObject("data")
        ?.optBoolean("running") == true

    fun stop(): CoreResult = adapter.stop()

    private fun buildJson(request: CoreStartRequest): String =
        XrayConfigBuilder.build(
            profile = request.profile,
            settings = request.settings,
            mode = request.mode,
            tunFd = request.tunFd,
            errorLogPath = Diagnostics.xrayErrorLogPath()
        )

    companion object {
        private const val WIREGUARD_START_VERIFY_BUDGET_MS = 12_000
    }
}
'''

TUNNEL_HEALTH = r'''
package com.trivox.client.network

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
    private val targets = listOf(
        "https://cp.cloudflare.com/generate_204",
        "https://connectivitycheck.gstatic.com/generate_204"
    )

    fun measure(
        settings: AppSettings,
        attempts: Int = 2,
        budgetMs: Int = 12_000
    ): PingResult {
        val timestamp = System.currentTimeMillis()
        val count = attempts.coerceIn(1, 3)
        val budget = budgetMs.coerceIn(2_000, 20_000)
        val deadline = System.nanoTime() + budget * 1_000_000L
        val samples = mutableListOf<Long>()
        var lastFailure = "tunnel_timeout"

        repeat(count) { sampleIndex ->
            if (Thread.currentThread().isInterrupted) return@repeat
            var accepted = false

            for (proxyType in listOf(Proxy.Type.HTTP, Proxy.Type.SOCKS)) {
                if (accepted) break
                for (target in rotatedTargets(sampleIndex)) {
                    val remainingMs = (
                        (deadline - System.nanoTime()) / 1_000_000L
                    ).coerceAtMost(MAX_PROBE_TIMEOUT_MS.toLong()).toInt()
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

    private fun rotatedTargets(offset: Int): List<String> =
        if (offset % targets.size == 0) targets else targets.drop(1) + targets.first()

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
            connection.setRequestProperty("User-Agent", "Trivox-TunnelHealth/5")

            val started = System.nanoTime()
            val status = connection.responseCode
            val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
            if (status == 204) {
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

    private const val MIN_PROBE_TIMEOUT_MS = 500
    private const val MAX_PROBE_TIMEOUT_MS = 4_500
}
'''

PROFILE_ADAPTER = r'''
package com.trivox.client.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.trivox.client.R
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.PingMethod
import com.trivox.client.data.TestStatus

class ProfileAdapter(
    private val onClick: (ConfigProfile) -> Unit,
    private val onLongClick: (ConfigProfile) -> Unit,
    private val onAction: (ConfigProfile) -> Unit,
    private val onPing: (ConfigProfile) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.Holder>() {
    private data class Row(
        val profile: ConfigProfile,
        val selected: Boolean,
        val connected: Boolean,
        val hideIp: Boolean,
        val gridMode: Boolean
    )

    private val differ = AsyncListDiffer(
        this,
        object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean =
                oldItem.profile.id == newItem.profile.id

            override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean =
                oldItem == newItem
        }
    )

    init {
        setHasStableIds(true)
    }

    fun submit(
        values: List<ConfigProfile>,
        selected: String?,
        connected: String?,
        hideIp: Boolean,
        gridMode: Boolean
    ) {
        differ.submitList(
            values.map { profile ->
                Row(
                    profile = profile,
                    selected = profile.id == selected,
                    connected = profile.id == connected,
                    hideIp = hideIp,
                    gridMode = gridMode
                )
            }
        )
    }

    override fun getItemId(position: Int): Long =
        differ.currentList[position].profile.id.hashCode().toLong()

    override fun getItemViewType(position: Int): Int =
        if (differ.currentList[position].gridMode) VIEW_GRID else VIEW_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(
            if (viewType == VIEW_GRID) R.layout.row_profile_grid
            else R.layout.row_profile,
            parent,
            false
        )
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = differ.currentList[position]
        holder.bind(
            profile = row.profile,
            selected = row.selected,
            connected = row.connected,
            hideIp = row.hideIp
        )
    }

    override fun getItemCount(): Int = differ.currentList.size

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val favorite = view.findViewById<TextView>(R.id.favoriteText)
        private val name = view.findViewById<TextView>(R.id.nameText)
        private val detail = view.findViewById<TextView>(R.id.detailText)
        private val location = view.findViewById<TextView>(R.id.locationText)
        private val status = view.findViewById<TextView>(R.id.statusText)
        private val latency = view.findViewById<TextView>(R.id.latencyText)
        private val ping = view.findViewById<TextView>(R.id.pingActionText)
        private val action = view.findViewById<TextView>(R.id.actionText)

        fun bind(
            profile: ConfigProfile,
            selected: Boolean,
            connected: Boolean,
            hideIp: Boolean
        ) {
            itemView.isActivated = selected
            itemView.alpha = if (profile.enabled) 1f else 0.52f
            favorite.text = if (profile.favorite) "★" else "☆"
            name.text = profile.name
            detail.text = if (hideIp) {
                itemView.context.getString(
                    R.string.profile_detail_private,
                    profile.protocol.uppercase(),
                    profile.group
                )
            } else {
                itemView.context.getString(
                    R.string.profile_detail,
                    profile.protocol.uppercase(),
                    profile.server,
                    profile.port,
                    profile.group
                )
            }

            val locationValue = buildString {
                profile.exitFlag.takeIf(String::isNotBlank)?.let {
                    append(it)
                    append(' ')
                }
                profile.exitCountry.takeIf(String::isNotBlank)?.let(::append)
                if (!hideIp && profile.exitIp.isNotBlank()) {
                    if (isNotBlank()) append(" • ")
                    append(profile.exitIp)
                }
            }
            location.text = locationValue
            location.visibility = if (locationValue.isBlank()) View.GONE else View.VISIBLE

            val statusValue = when {
                connected -> itemView.context.getString(R.string.state_connected)
                profile.testStatus == TestStatus.TESTING ->
                    itemView.context.getString(R.string.status_testing)
                else -> ""
            }
            status.text = statusValue
            status.visibility = if (statusValue.isBlank()) View.GONE else View.VISIBLE

            latency.text = profile.latencyMs?.let { value ->
                itemView.context.getString(
                    R.string.latency_method_format,
                    pingMethodLabel(profile.latencyMethod),
                    value
                )
            } ?: "—"
            ping.text = if (profile.testStatus == TestStatus.TESTING) "…" else "⚡"
            action.text = "⋮"
            action.contentDescription = itemView.context.getString(R.string.config_actions)
            ping.contentDescription = itemView.context.getString(R.string.ping_now)

            itemView.setOnClickListener { onClick(profile) }
            itemView.setOnLongClickListener {
                onLongClick(profile)
                true
            }
            latency.setOnClickListener { onPing(profile) }
            ping.setOnClickListener { onPing(profile) }
            action.setOnClickListener { onAction(profile) }
        }

        private fun pingMethodLabel(stored: String): String = when (
            PingMethod.fromStored(stored, PingMethod.TCP_CONNECT)
        ) {
            PingMethod.TCP_CONNECT ->
                itemView.context.getString(R.string.ping_method_tcp_short)
            PingMethod.XRAY_HTTP ->
                itemView.context.getString(R.string.ping_method_xray_short)
        }
    }

    companion object {
        private const val VIEW_LIST = 0
        private const val VIEW_GRID = 1
    }
}
'''

ROW_PROFILE_GRID = r'''
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layoutDirection="locale"
    android:layout_margin="4dp"
    android:background="@drawable/row_background"
    android:minHeight="132dp"
    android:orientation="vertical"
    android:padding="9dp"
    android:textDirection="locale">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="32dp"
        android:gravity="center_vertical"
        android:orientation="horizontal">

        <TextView
            android:id="@+id/favoriteText"
            android:layout_width="28dp"
            android:layout_height="match_parent"
            android:gravity="center"
            android:textColor="@color/blue"
            android:textSize="16sp" />

        <TextView
            android:id="@+id/nameText"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:ellipsize="end"
            android:maxLines="1"
            android:textColor="@color/text_primary"
            android:textSize="11sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/actionText"
            android:layout_width="28dp"
            android:layout_height="match_parent"
            android:gravity="center"
            android:text="⋮"
            android:textColor="@color/text_secondary"
            android:textSize="18sp"
            android:textStyle="bold" />
    </LinearLayout>

    <TextView
        android:id="@+id/detailText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="3dp"
        android:ellipsize="middle"
        android:maxLines="2"
        android:minHeight="27dp"
        android:textColor="@color/text_secondary"
        android:textDirection="ltr"
        android:textSize="8.5sp" />

    <TextView
        android:id="@+id/locationText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="2dp"
        android:ellipsize="end"
        android:maxLines="1"
        android:textColor="@color/blue"
        android:textSize="8.5sp"
        android:visibility="gone" />

    <TextView
        android:id="@+id/statusText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="2dp"
        android:ellipsize="end"
        android:maxLines="1"
        android:textColor="@color/blue"
        android:textSize="8.5sp"
        android:textStyle="bold"
        android:visibility="gone" />

    <View
        android:layout_width="match_parent"
        android:layout_height="1dp"
        android:layout_marginTop="6dp"
        android:background="@color/divider" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="38dp"
        android:gravity="center_vertical"
        android:orientation="horizontal">

        <TextView
            android:id="@+id/latencyText"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:gravity="center_vertical|start"
            android:ellipsize="end"
            android:maxLines="1"
            android:textColor="@color/text_primary"
            android:textDirection="ltr"
            android:textSize="9sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/pingActionText"
            android:layout_width="38dp"
            android:layout_height="match_parent"
            android:gravity="center"
            android:text="@string/ping_short"
            android:textColor="@color/blue"
            android:textSize="13sp"
            android:textStyle="bold" />
    </LinearLayout>
</LinearLayout>
'''

EXPAND_ICON = r'''
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@color/text_primary"
        android:pathData="M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6z" />
</vector>
'''

STRINGS_EN = r'''
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="sort_position_summary_v5">Connected configurations keep their normal sorted position; connection state is shown inside the card.</string>
    <string name="dns_smart_summary_v5">Smart DNS intercepts VPN port 53 inside Xray, uses encrypted IP-based DoH with fallback and cache, and avoids the system resolver. Direct and System modes are explicit bypass choices; custom non-local endpoints follow the tunnel.</string>
</resources>
'''

STRINGS_FA = r'''
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="sort_position_summary_v5">کانفیگ متصل جایگاه طبیعی خود را در مرتب‌سازی حفظ می‌کند و وضعیت اتصال داخل کارت نمایش داده می‌شود.</string>
    <string name="dns_smart_summary_v5">DNS هوشمند، ترافیک پورت ۵۳ VPN را داخل Xray می‌گیرد، از DoH رمزنگاری‌شده مبتنی بر IP با کش و مسیر جایگزین استفاده می‌کند و به DNS سیستم وابسته نیست. حالت‌های مستقیم و سیستم عمداً خارج از تونل‌اند؛ DNS سفارشیِ غیرمحلی از تونل عبور می‌کند.</string>
</resources>
'''

XRAY_TEST = r'''
package com.trivox.client.config

import com.trivox.client.data.AppSettings
import com.trivox.client.data.ConfigProfile
import com.trivox.client.data.ConnectionMode
import com.trivox.client.data.DnsMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayConfigBuilderWireGuardTest {
    @Test
    fun wireGuardUsesVerifiedMixedInboundReliableMtuAndSmartDns() {
        val outbound = JSONObject()
            .put("protocol", "wireguard")
            .put(
                "settings",
                JSONObject()
                    .put("secretKey", "private")
                    .put("address", JSONArray().put("10.5.0.2/32"))
                    .put("mtu", 1420)
                    .put(
                        "peers",
                        JSONArray().put(
                            JSONObject()
                                .put("publicKey", "public")
                                .put("endpoint", "198.51.100.10:51820")
                        )
                    )
            )
        val profile = ConfigProfile(
            name = "WireGuard test",
            protocol = "wireguard",
            server = "198.51.100.10",
            port = 51820,
            raw = outbound.toString(),
            outboundJson = outbound.toString()
        )
        val settings = AppSettings(
            mode = ConnectionMode.VPN,
            mtu = 1500,
            ipv6 = true,
            dnsMode = DnsMode.TRIVOX_DEFAULT,
            localProxyInVpn = false
        )

        val root = JSONObject(
            XrayConfigBuilder.build(profile, settings, ConnectionMode.VPN)
        )
        val inbounds = root.getJSONArray("inbounds")
        assertTrue((0 until inbounds.length()).any {
            inbounds.getJSONObject(it).optString("protocol") == "mixed"
        })

        val wireGuard = root.getJSONArray("outbounds")
            .getJSONObject(0)
            .getJSONObject("settings")
        assertEquals(1360, wireGuard.getInt("mtu"))
        assertEquals(25, wireGuard.getJSONArray("peers").getJSONObject(0).getInt("keepAlive"))
        assertTrue(wireGuard.getBoolean("noKernelTun"))

        val dns = root.getJSONObject("dns")
        assertTrue(dns.getJSONArray("servers").getString(0).startsWith("https://"))
        assertTrue(dns.getBoolean("enableParallelQuery"))

        val firstRule = root.getJSONObject("routing")
            .getJSONArray("rules")
            .getJSONObject(0)
        assertEquals("53", firstRule.getString("port"))
        assertEquals("dns-out", firstRule.getString("outboundTag"))
    }
}
'''

TERMUX_INSTALLER = r'''
#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PAYLOAD=""
for candidate in \
    "$SCRIPT_DIR/apply-v5-wireguard-dns-ui.py" \
    "$SCRIPT_DIR/tools/apply-v5-wireguard-dns-ui.py"
do
    [[ -f "$candidate" ]] && PAYLOAD="$candidate" && break
done
[[ -n "$PAYLOAD" ]] || {
    printf 'Patch payload not found next to installer.\n' >&2
    exit 1
}

if [[ -n "${1:-}" && "${1:-}" != "--no-push" ]]; then
    REPO_DIR="$1"
elif [[ -d "$SCRIPT_DIR/../.git" ]]; then
    REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
else
    REPO_DIR="$HOME/Trivox"
fi
PUSH=1
[[ "${1:-}" == "--no-push" || "${2:-}" == "--no-push" ]] && PUSH=0

say() { printf '\n[Trivox v5.1] %s\n' "$*"; }
die() { printf '\n[Trivox v5.1] ERROR: %s\n' "$*" >&2; exit 1; }

PATCH_PATHS=(
    README.md
    app/src/main/java/com/trivox/client/config/XrayConfigBuilder.kt
    app/src/main/java/com/trivox/client/core/CoreManager.kt
    app/src/main/java/com/trivox/client/network/PingManager.kt
    app/src/main/java/com/trivox/client/network/TunnelHealthVerifier.kt
    app/src/main/java/com/trivox/client/ui/MainActivity.kt
    app/src/main/java/com/trivox/client/ui/ProfileAdapter.kt
    app/src/main/res/drawable/ic_expand_more_rounded.xml
    app/src/main/res/layout/activity_main.xml
    app/src/main/res/layout/activity_settings.xml
    app/src/main/res/layout/row_profile_grid.xml
    app/src/main/res/values/strings_v5.xml
    app/src/main/res/values-fa/strings_v5.xml
    app/src/test/java/com/trivox/client/config/XrayConfigBuilderWireGuardTest.kt
    tools/apply-v5-wireguard-dns-ui.py
    tools/install-trivox-v5-termux.sh
)

is_patch_path() {
    local wanted="$1" item
    for item in "${PATCH_PATHS[@]}"; do
        [[ "$wanted" == "$item" ]] && return 0
    done
    return 1
}

if ! command -v git >/dev/null 2>&1; then
    command -v pkg >/dev/null 2>&1 || die "git is missing and this is not Termux"
    pkg install -y git
fi
if ! command -v python3 >/dev/null 2>&1; then
    command -v pkg >/dev/null 2>&1 || die "python3 is missing"
    pkg install -y python
fi

if [[ ! -d "$REPO_DIR/.git" ]]; then
    say "Cloning Trivox into $REPO_DIR"
    git clone https://github.com/marble098/Trivox.git "$REPO_DIR"
fi
cd "$REPO_DIR"

branch="$(git symbolic-ref --quiet --short HEAD || true)"
[[ -n "$branch" ]] || die "Repository is in detached HEAD state"
remote="$(git remote get-url origin 2>/dev/null || true)"
[[ "$remote" == *"marble098/Trivox"* ]] || say "Warning: origin is $remote"

# Preserve the backup made by the interrupted v5.0 installer, but keep backups
# outside the repository so they can never be staged by git add.
if [[ -d "$REPO_DIR/.trivox-backups" ]]; then
    legacy_target="$HOME/.trivox-backups/Trivox-legacy-$(date +%Y%m%d-%H%M%S)"
    mkdir -p "$(dirname "$legacy_target")"
    mv -- "$REPO_DIR/.trivox-backups" "$legacy_target"
    say "Moved previous in-repository backup to $legacy_target"
fi

# Keep generated status files out of commits without changing the repository's
# tracked .gitignore.
mkdir -p .git/info
for local_exclude in /trivox-v5-status.txt /.trivox-backups/; do
    grep -Fqx "$local_exclude" .git/info/exclude 2>/dev/null || \
        printf '%s\n' "$local_exclude" >> .git/info/exclude
done

declare -A dirty_seen=()
while IFS= read -r -d '' path; do
    dirty_seen["$path"]=1
done < <(
    git diff --name-only -z
    git diff --cached --name-only -z
    git ls-files --others --exclude-standard -z
)

dirty_paths=("${!dirty_seen[@]}")
interrupted_patch=0
if (( ${#dirty_paths[@]} > 0 )) && {
    [[ -f app/src/main/res/values/strings_v5.xml ]] ||
    [[ -f app/src/main/java/com/trivox/client/network/TunnelHealthVerifier.kt ]];
}; then
    interrupted_patch=1
    for path in "${dirty_paths[@]}"; do
        if ! is_patch_path "$path"; then
            interrupted_patch=0
            break
        fi
    done
fi

stash_created=0
if (( ${#dirty_paths[@]} > 0 )); then
    if (( interrupted_patch )); then
        say "Detected the interrupted v5.0 patch; resuming it in place without stashing partial files"
    else
        say "Saving unrelated local work in an automatic stash"
        git stash push -u -m "trivox-v5.1-auto-stash-$(date +%Y%m%d-%H%M%S)"
        stash_created=1
    fi
fi

if (( interrupted_patch )); then
    say "Keeping current HEAD because the previous patch already started from it"
else
    say "Updating branch $branch"
    git pull --ff-only origin "$branch"
fi

backup_dir="${TRIVOX_BACKUP_DIR:-$HOME/.trivox-backups/Trivox}"
mkdir -p "$backup_dir"
backup="$backup_dir/trivox-v5.1-$(date +%Y%m%d-%H%M%S).tar.gz"
git ls-files -z | tar --null -czf "$backup" --files-from=-
say "Backup created: $backup"

mkdir -p tools
cp -f -- "$PAYLOAD" tools/apply-v5-wireguard-dns-ui.py
chmod +x tools/apply-v5-wireguard-dns-ui.py

say "Applying reviewed WireGuard, DNS and UI changes"
python3 tools/apply-v5-wireguard-dns-ui.py
chmod +x tools/install-trivox-v5-termux.sh 2>/dev/null || true

git diff --check
python3 tools/validate-android-resources.py
python3 tools/validate-api-guards.py
python3 tools/validate-string-formats.py
python3 tools/audit-trivox.py --ci

if [[ "${TRIVOX_RUN_GRADLE:-auto}" == "1" ]] || {
    [[ "${TRIVOX_RUN_GRADLE:-auto}" == "auto" ]] &&
    [[ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]] &&
    [[ -d "$HOME/.gradle/caches" ]];
}; then
    say "Running cached Gradle tests and lint"
    ./gradlew --no-daemon test lint
else
    say "Gradle download/build skipped; GitHub Actions will perform the full build"
fi

if [[ -n "$(git status --porcelain -- "${PATCH_PATHS[@]}")" ]]; then
    git add -- "${PATCH_PATHS[@]}"
    git diff --cached --check
    git commit -m "Improve WireGuard verification DNS and grid UI"
else
    say "Repository already matches this patch"
fi

if (( PUSH )); then
    say "Pushing $branch"
    git push origin "$branch"
fi

status_file="$REPO_DIR/trivox-v5-status.txt"
{
    echo "repository=$REPO_DIR"
    echo "branch=$branch"
    echo "commit=$(git rev-parse HEAD)"
    echo "remote=$remote"
    echo "push=$PUSH"
    echo "backup=$backup"
    echo
    git status --short --branch
} | tee "$status_file"

if (( stash_created )); then
    say "Restoring the unrelated work that was stashed before installation"
    git stash pop || say "Stash restore needs manual conflict resolution; the stash remains available"
fi

say "Done. Status: $status_file"
'''


def apply() -> None:
    write(
        "app/src/main/java/com/trivox/client/config/XrayConfigBuilder.kt",
        XRAY_CONFIG_BUILDER,
    )
    write("app/src/main/java/com/trivox/client/core/CoreManager.kt", CORE_MANAGER)
    write(
        "app/src/main/java/com/trivox/client/network/TunnelHealthVerifier.kt",
        TUNNEL_HEALTH,
    )
    write("app/src/main/java/com/trivox/client/ui/ProfileAdapter.kt", PROFILE_ADAPTER)
    write("app/src/main/res/layout/row_profile_grid.xml", ROW_PROFILE_GRID)
    write("app/src/main/res/drawable/ic_expand_more_rounded.xml", EXPAND_ICON)
    write("app/src/main/res/values/strings_v5.xml", STRINGS_EN)
    write("app/src/main/res/values-fa/strings_v5.xml", STRINGS_FA)
    write(
        "app/src/test/java/com/trivox/client/config/XrayConfigBuilderWireGuardTest.kt",
        XRAY_TEST,
    )
    write("tools/install-trivox-v5-termux.sh", TERMUX_INSTALLER)

    replace_once(
        "app/src/main/java/com/trivox/client/network/PingManager.kt",
        '''    fun measure(\n        profile: ConfigProfile,\n        settings: AppSettings,\n        workDir: File\n    ): PingResult =\n        when (settings.pingMethod) {\n            PingMethod.TCP_CONNECT ->\n                tcp(\n                    profile = profile,\n                    attempts = settings.testAttempts,\n                    timeoutMs = DEFAULT_TCP_TIMEOUT_MS\n                )\n\n            PingMethod.XRAY_HTTP ->\n                realXray(\n                    profile = profile,\n                    settings = settings,\n                    workDir = workDir,\n                    attempts = settings.testAttempts\n                )\n        }\n''',
        '''    fun measure(\n        profile: ConfigProfile,\n        settings: AppSettings,\n        workDir: File\n    ): PingResult {\n        // TCP reachability of a nearby hostname does not prove a WireGuard\n        // handshake or usable tunnel. WireGuard profiles always use the full\n        // Xray route so an "Alive" result is end-to-end.\n        if (profile.protocol.equals("wireguard", ignoreCase = true)) {\n            return realXray(\n                profile = profile,\n                settings = settings,\n                workDir = workDir,\n                attempts = settings.testAttempts\n            )\n        }\n\n        return when (settings.pingMethod) {\n            PingMethod.TCP_CONNECT ->\n                tcp(\n                    profile = profile,\n                    attempts = settings.testAttempts,\n                    timeoutMs = DEFAULT_TCP_TIMEOUT_MS\n                )\n\n            PingMethod.XRAY_HTTP ->\n                realXray(\n                    profile = profile,\n                    settings = settings,\n                    workDir = workDir,\n                    attempts = settings.testAttempts\n                )\n        }\n    }\n''',
        marker='TCP reachability of a nearby hostname does not prove a WireGuard',
    )

    replace_once(
        "app/src/main/java/com/trivox/client/ui/MainActivity.kt",
        "import androidx.appcompat.widget.Toolbar\n",
        "import androidx.appcompat.widget.AppCompatImageButton\nimport androidx.appcompat.widget.Toolbar\n",
        marker="import androidx.appcompat.widget.AppCompatImageButton",
    )
    replace_once(
        "app/src/main/java/com/trivox/client/ui/MainActivity.kt",
        "import com.trivox.client.network.SubscriptionRefreshCoordinator\n",
        "import com.trivox.client.network.SubscriptionRefreshCoordinator\nimport com.trivox.client.network.TunnelHealthVerifier\n",
        marker="import com.trivox.client.network.TunnelHealthVerifier",
    )
    replace_once(
        "app/src/main/java/com/trivox/client/ui/MainActivity.kt",
        "    private lateinit var quickToolsToggle: Button\n",
        "    private lateinit var quickToolsToggle: AppCompatImageButton\n",
        marker="private lateinit var quickToolsToggle: AppCompatImageButton",
    )
    replace_once(
        "app/src/main/java/com/trivox/client/ui/MainActivity.kt",
        '''        quickToolsToggle.text =\n            if (quickToolsExpanded) {\n                "⌃"\n            } else {\n                "⌄"\n            }\n''',
        '''        quickToolsToggle.setImageResource(\n            R.drawable.ic_expand_more_rounded\n        )\n        quickToolsToggle.rotation =\n            if (quickToolsExpanded) 180f else 0f\n        quickToolsToggle.contentDescription =\n            getString(R.string.toggle_connection_tools)\n''',
        marker="quickToolsToggle.setImageResource",
    )

    # The connected profile remains visible, but the configured comparator owns
    # its position instead of a hard-coded first-row override.
    replace_once(
        "app/src/main/java/com/trivox/client/ui/MainActivity.kt",
        '''        return Comparator {\n                left,\n                right ->\n            when {\n                left.id ==\n                    connectedId &&\n                    right.id !=\n                    connectedId ->\n                    -1\n\n                right.id ==\n                    connectedId &&\n                    left.id !=\n                    connectedId ->\n                    1\n\n                else ->\n                    base.compare(\n                        left,\n                        right\n                    )\n            }\n        }\n''',
        '''        // connectedId is intentionally not used as a ranking key.\n        // The row keeps its normal SMART/name/latency/group position.\n        @Suppress("UNUSED_VARIABLE")\n        val connectedProfileId = connectedId\n        return base\n''',
        marker="connectedId is intentionally not used as a ranking key",
    )

    replace_once(
        "app/src/main/java/com/trivox/client/ui/MainActivity.kt",
        '''            current.profileId,\n            settings.hideIpOnMain\n        )\n''',
        '''            current.profileId,\n            settings.hideIpOnMain,\n            settings.gridMode\n        )\n''',
        marker="settings.hideIpOnMain,\n            settings.gridMode",
    )

    regex_once(
        "app/src/main/java/com/trivox/client/ui/MainActivity.kt",
        r'''(    private fun requestRealDelayNow\(\) \{.*?            val result =\n)                runCatching \{.*?                \}\.getOrElse \{''',
        r'''\1                runCatching {\n                    TunnelHealthVerifier.measure(\n                        settings = settings,\n                        attempts = settings.testAttempts.coerceAtMost(2),\n                        budgetMs = REAL_DELAY_BUDGET_MS\n                    )\n                }.getOrElse {''',
        marker="budgetMs = REAL_DELAY_BUDGET_MS",
    )

    regex_once(
        "app/src/main/java/com/trivox/client/ui/MainActivity.kt",
        r'''                                PingMethod\n                                    \.XRAY_HTTP ->\n                                    pingManager\n                                        \.httpViaLocalProxy\(.*?                                        \)''',
        '''                                PingMethod\n                                    .XRAY_HTTP ->\n                                    TunnelHealthVerifier\n                                        .measure(\n                                            settings = settings,\n                                            attempts = settings.testAttempts\n                                                .coerceAtMost(2),\n                                            budgetMs =\n                                                LIVE_PING_BUDGET_MS\n                                        )''',
        marker="LIVE_PING_BUDGET_MS",
    )

    replace_once(
        "app/src/main/java/com/trivox/client/ui/MainActivity.kt",
        '''        private const val LIVE_PING_INTERVAL_MS =\n            8_000L\n''',
        '''        private const val LIVE_PING_INTERVAL_MS =\n            8_000L\n        private const val LIVE_PING_BUDGET_MS =\n            10_000\n        private const val REAL_DELAY_BUDGET_MS =\n            16_000\n''',
        marker="private const val LIVE_PING_BUDGET_MS",
    )

    replace_once(
        "app/src/main/res/layout/activity_main.xml",
        '''            <Button\n                android:id="@+id/quickToolsToggle"\n                style="@style/Widget.Trivox.Button.Secondary"\n                android:layout_width="38dp"\n                android:layout_height="36dp"\n                android:layout_marginStart="5dp"\n                android:contentDescription="@string/toggle_connection_tools"\n                android:minWidth="38dp"\n                android:padding="0dp"\n                android:text="⌄"\n                android:textSize="18sp" />\n''',
        '''            <androidx.appcompat.widget.AppCompatImageButton\n                android:id="@+id/quickToolsToggle"\n                android:layout_width="38dp"\n                android:layout_height="36dp"\n                android:layout_marginStart="5dp"\n                android:background="@drawable/input_background"\n                android:contentDescription="@string/toggle_connection_tools"\n                android:minWidth="38dp"\n                android:padding="7dp"\n                android:scaleType="centerInside"\n                android:src="@drawable/ic_expand_more_rounded"\n                android:tint="@color/text_primary" />\n''',
        marker="<androidx.appcompat.widget.AppCompatImageButton",
    )

    replace_once(
        "app/src/main/res/layout/activity_settings.xml",
        'android:text="@string/connected_profile_pinned_summary"',
        'android:text="@string/sort_position_summary_v5"',
        marker='android:text="@string/sort_position_summary_v5"',
    )
    replace_once(
        "app/src/main/res/layout/activity_settings.xml",
        '''                <Spinner\n                    android:id="@+id/dnsMode"\n                    android:layout_width="match_parent"\n                    android:layout_height="42dp"\n                    android:background="@drawable/input_background"\n                    android:paddingStart="10dp"\n                    android:paddingEnd="8dp" />\n\n                <EditText\n''',
        '''                <Spinner\n                    android:id="@+id/dnsMode"\n                    android:layout_width="match_parent"\n                    android:layout_height="42dp"\n                    android:background="@drawable/input_background"\n                    android:paddingStart="10dp"\n                    android:paddingEnd="8dp" />\n\n                <TextView\n                    android:layout_width="match_parent"\n                    android:layout_height="wrap_content"\n                    android:layout_marginTop="6dp"\n                    android:lineSpacingExtra="1dp"\n                    android:text="@string/dns_smart_summary_v5"\n                    android:textColor="@color/muted"\n                    android:textSize="10sp" />\n\n                <EditText\n''',
        marker='android:text="@string/dns_smart_summary_v5"',
    )

    readme = read("README.md")
    marker = "## WireGuard, DNS and grid reliability v5"
    if marker not in readme:
        readme += r'''

## WireGuard, DNS and grid reliability v5

The v5 reliability pass no longer pins the active profile to the first row. It
adds a dedicated compact grid card, a vector expand control, and bounded live
health probes so the UI cannot remain in a misleading measuring state.

WireGuard native-process startup is no longer reported as a successful
connection by itself. Trivox keeps a localhost-only mixed listener for
end-to-end verification, applies a conservative MTU and persistent keepalive,
and stops the core when real HTTP traffic cannot cross the tunnel. TCP endpoint
reachability is never used as an Alive result for WireGuard profiles.

DNS traffic arriving from Android's VPN interface is hijacked into Xray's DNS
outbound. Smart/default DNS uses encrypted IP-based DoH through the selected
route with cache, parallel fallback, and no dependency on Android's system
resolver. Direct and System remain explicit opt-in bypass modes.
'''
        write("README.md", readme)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--list", action="store_true")
    args = parser.parse_args()
    if args.list:
        print("\n".join(CHANGED_PATHS))
    else:
        apply()
        print("Applied Trivox WireGuard, DNS and UI reliability v5 changes.")
