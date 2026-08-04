#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

PATCH_ID = "TRIVOX_V12_NETWORK_CORES"
EXPECTED_PREFIX = "e7e0d26e"
ROOT = Path.cwd()

class PatchError(RuntimeError):
    pass

def read(path: str) -> str:
    p = ROOT / path
    if not p.is_file():
        raise PatchError(f"Missing target file: {path}")
    return p.read_text(encoding="utf-8")

def write(path: str, text: str) -> None:
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8", newline="\n")

def replace_once(path: str, old: str, new: str, marker: str | None = None) -> None:
    text = read(path)
    if marker and marker in text:
        return
    count = text.count(old)
    if count != 1:
        raise PatchError(f"Anchor mismatch in {path}: expected 1 occurrence, found {count}")
    write(path, text.replace(old, new, 1))

def insert_before_closing_layout(path: str, view_id: str, block: str) -> None:
    text = read(path)
    if "realDelayProfileSpinner" in text:
        return
    idx = text.find(view_id)
    if idx < 0:
        raise PatchError(f"Could not find {view_id} in {path}")
    close = text.find("            </LinearLayout>", idx)
    if close < 0:
        raise PatchError(f"Could not find enclosing layout after {view_id}")
    write(path, text[:close] + block + "\n" + text[close:])

def git_head() -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "--short=12", "HEAD"],
            cwd=ROOT,
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
    except Exception:
        return "unknown"

def patch_models() -> None:
    path = "app/src/main/java/com/trivox/client/data/Models.kt"
    replace_once(
        path,
        "enum class AppRoutingMode { ALL, ALLOW_SELECTED, BYPASS_SELECTED }\n",
        "enum class AppRoutingMode { ALL, ALLOW_SELECTED, BYPASS_SELECTED }\n\n"
        "enum class RealDelayProfile {\n"
        "    TURBO, BALANCED, ACCURATE, CUSTOM;\n\n"
        "    companion object {\n"
        "        fun fromStored(value: String?): RealDelayProfile =\n"
        "            entries.firstOrNull { it.name.equals(value, true) } ?: BALANCED\n"
        "    }\n"
        "}\n",
        marker="enum class RealDelayProfile"
    )
    replace_once(
        path,
        "    var testAttempts: Int = 3,\n    var adaptiveHandshake: Boolean = true,",
        "    var testAttempts: Int = 3,\n"
        "    var realDelayProfile: RealDelayProfile = RealDelayProfile.BALANCED,\n"
        "    var realDelayGroupSize: Int = 8,\n"
        "    var realDelayWorkers: Int = 3,\n"
        "    var realDelayProbeTimeoutMs: Int = 3_600,\n"
        "    var realDelayStartGraceMs: Int = 80,\n"
        "    var realDelayTargetCount: Int = 2,\n"
        "    var realDelayRequiredProofs: Int = 2,\n"
        "    var adaptiveHandshake: Boolean = true,",
        marker="realDelayProfile: RealDelayProfile"
    )
    replace_once(
        path,
        "        testAttempts = testAttempts.coerceIn(2, 5)\n        livePingIntervalSeconds",
        "        testAttempts = testAttempts.coerceIn(2, 5)\n"
        "        realDelayGroupSize = realDelayGroupSize.coerceIn(2, 16)\n"
        "        realDelayWorkers = realDelayWorkers.coerceIn(1, 6)\n"
        "        realDelayProbeTimeoutMs = realDelayProbeTimeoutMs.coerceIn(1_500, 10_000)\n"
        "        realDelayStartGraceMs = realDelayStartGraceMs.coerceIn(0, 1_000)\n"
        "        realDelayTargetCount = realDelayTargetCount.coerceIn(1, 4)\n"
        "        realDelayRequiredProofs = realDelayRequiredProofs.coerceIn(1, realDelayTargetCount)\n"
        "        livePingIntervalSeconds",
        marker="realDelayGroupSize = realDelayGroupSize.coerceIn"
    )
    replace_once(
        path,
        "        .put(\"testAttempts\", testAttempts)\n        .put(\"adaptiveHandshake\"",
        "        .put(\"testAttempts\", testAttempts)\n"
        "        .put(\"realDelayProfile\", realDelayProfile.name)\n"
        "        .put(\"realDelayGroupSize\", realDelayGroupSize)\n"
        "        .put(\"realDelayWorkers\", realDelayWorkers)\n"
        "        .put(\"realDelayProbeTimeoutMs\", realDelayProbeTimeoutMs)\n"
        "        .put(\"realDelayStartGraceMs\", realDelayStartGraceMs)\n"
        "        .put(\"realDelayTargetCount\", realDelayTargetCount)\n"
        "        .put(\"realDelayRequiredProofs\", realDelayRequiredProofs)\n"
        "        .put(\"adaptiveHandshake\"",
        marker='.put("realDelayProfile"'
    )
    replace_once(
        path,
        "                testAttempts = json.optInt(\"testAttempts\", 3),\n                adaptiveHandshake",
        "                testAttempts = json.optInt(\"testAttempts\", 3),\n"
        "                realDelayProfile = RealDelayProfile.fromStored(\n"
        "                    json.optString(\"realDelayProfile\")\n"
        "                ),\n"
        "                realDelayGroupSize = json.optInt(\"realDelayGroupSize\", 8),\n"
        "                realDelayWorkers = json.optInt(\"realDelayWorkers\", 3),\n"
        "                realDelayProbeTimeoutMs = json.optInt(\"realDelayProbeTimeoutMs\", 3_600),\n"
        "                realDelayStartGraceMs = json.optInt(\"realDelayStartGraceMs\", 80),\n"
        "                realDelayTargetCount = json.optInt(\"realDelayTargetCount\", 2),\n"
        "                realDelayRequiredProofs = json.optInt(\"realDelayRequiredProofs\", 2),\n"
        "                adaptiveHandshake",
        marker="realDelayProfile = RealDelayProfile.fromStored"
    )

def patch_config_parser() -> None:
    path = "app/src/main/java/com/trivox/client/config/ConfigParser.kt"
    replace_once(
        path,
        '        "wg", "wireguard"\n',
        '        "wg", "wireguard", "ssh", "openssh", "nordwhisper"\n',
        marker='"nordwhisper"'
    )
    replace_once(
        path,
        '            "wg", "wireguard" -> parseWireGuardUri(raw)\n            else ->',
        '            "wg", "wireguard" -> parseWireGuardUri(raw)\n'
        '            "ssh", "openssh" -> OpenSshProfileCodec.parse(raw)\n'
        '            "nordwhisper" -> NordWhisperCompatibility.reject(raw)\n'
        '            else ->',
        marker='OpenSshProfileCodec.parse(raw)'
    )

def patch_xray_builder() -> None:
    path = "app/src/main/java/com/trivox/client/config/XrayConfigBuilder.kt"
    replace_once(
        path,
        '        root.put("routing", routing())',
        '        root.put("routing", routing(settings, mode))',
        marker='routing(settings, mode)'
    )
    replace_once(
        path,
        '.put("routeOnly", false)',
        '.put("routeOnly", true)',
        marker='.put("routeOnly", true)'
    )
    replace_once(
        path,
        '    private fun routing(): JSONObject {\n        val rules = JSONArray()',
        '    private fun routing(\n'
        '        settings: AppSettings,\n'
        '        mode: ConnectionMode\n'
        '    ): JSONObject {\n'
        '        val dnsRouteTag = when {\n'
        '            settings.dnsMode == DnsMode.SYSTEM ||\n'
        '                settings.dnsMode == DnsMode.DIRECT -> "direct"\n'
        '            mode == ConnectionMode.PROXY &&\n'
        '                settings.dnsMode != DnsMode.THROUGH_PROXY &&\n'
        '                settings.dnsMode != DnsMode.IMPORTED -> "direct"\n'
        '            else -> "proxy"\n'
        '        }\n'
        '        val rules = JSONArray()',
        marker='val dnsRouteTag = when'
    )
    old = '''                    .put("inboundTag", JSONArray().put(DNS_ROUTING_TAG))
                    .put("outboundTag", "proxy")'''
    new = '''                    .put("inboundTag", JSONArray().put(DNS_ROUTING_TAG))
                    .put("outboundTag", dnsRouteTag)'''
    replace_once(path, old, new, marker='.put("outboundTag", dnsRouteTag)')

def patch_settings_xml() -> None:
    block = r'''
                <TextView style="@style/Widget.Trivox.FieldLabel" android:layout_marginTop="8dp" android:text="@string/real_delay_profile" />
                <Spinner android:id="@+id/realDelayProfileSpinner" android:layout_width="match_parent" android:layout_height="42dp" android:layout_marginTop="3dp" android:background="@drawable/input_background" android:paddingStart="10dp" android:paddingEnd="8dp" />
                <TextView style="@style/Widget.Trivox.FieldLabel" android:layout_marginTop="7dp" android:text="@string/real_delay_group_size" />
                <EditText android:id="@+id/realDelayGroupSizeInput" style="@style/Widget.Trivox.Input" android:layout_marginTop="3dp" android:inputType="number" android:textDirection="ltr" />
                <TextView style="@style/Widget.Trivox.FieldLabel" android:layout_marginTop="7dp" android:text="@string/real_delay_workers" />
                <EditText android:id="@+id/realDelayWorkersInput" style="@style/Widget.Trivox.Input" android:layout_marginTop="3dp" android:inputType="number" android:textDirection="ltr" />
                <TextView style="@style/Widget.Trivox.FieldLabel" android:layout_marginTop="7dp" android:text="@string/real_delay_timeout" />
                <EditText android:id="@+id/realDelayTimeoutInput" style="@style/Widget.Trivox.Input" android:layout_marginTop="3dp" android:inputType="number" android:textDirection="ltr" />
                <TextView style="@style/Widget.Trivox.FieldLabel" android:layout_marginTop="7dp" android:text="@string/real_delay_start_grace" />
                <EditText android:id="@+id/realDelayStartGraceInput" style="@style/Widget.Trivox.Input" android:layout_marginTop="3dp" android:inputType="number" android:textDirection="ltr" />
                <TextView style="@style/Widget.Trivox.FieldLabel" android:layout_marginTop="7dp" android:text="@string/real_delay_target_count" />
                <EditText android:id="@+id/realDelayTargetCountInput" style="@style/Widget.Trivox.Input" android:layout_marginTop="3dp" android:inputType="number" android:textDirection="ltr" />
                <TextView style="@style/Widget.Trivox.FieldLabel" android:layout_marginTop="7dp" android:text="@string/real_delay_required_proofs" />
                <EditText android:id="@+id/realDelayRequiredProofsInput" style="@style/Widget.Trivox.Input" android:layout_marginTop="3dp" android:inputType="number" android:textDirection="ltr" />'''
    insert_before_closing_layout(
        "app/src/main/res/layout/activity_settings.xml",
        'android:id="@+id/testUrlInput"',
        block
    )

def patch_settings_activity() -> None:
    path = "app/src/main/java/com/trivox/client/ui/SettingsActivity.kt"
    replace_once(
        path,
        'import com.trivox.client.data.ProfileSortMode\n',
        'import com.trivox.client.data.ProfileSortMode\nimport com.trivox.client.data.RealDelayProfile\n',
        marker='import com.trivox.client.data.RealDelayProfile'
    )
    replace_once(
        path,
        '        val testUrl = edit(R.id.testUrlInput)\n        val networkTuning',
        '        val testUrl = edit(R.id.testUrlInput)\n'
        '        val realDelayProfile = spinner(R.id.realDelayProfileSpinner)\n'
        '        val realDelayGroupSize = edit(R.id.realDelayGroupSizeInput)\n'
        '        val realDelayWorkers = edit(R.id.realDelayWorkersInput)\n'
        '        val realDelayTimeout = edit(R.id.realDelayTimeoutInput)\n'
        '        val realDelayStartGrace = edit(R.id.realDelayStartGraceInput)\n'
        '        val realDelayTargetCount = edit(R.id.realDelayTargetCountInput)\n'
        '        val realDelayRequiredProofs = edit(R.id.realDelayRequiredProofsInput)\n'
        '        val networkTuning',
        marker='val realDelayProfile = spinner'
    )
    replace_once(
        path,
        '        pingAttempts.setSelection(settings.testAttempts.coerceIn(2, 5) - 2)\n\n        val workerValues',
        '        pingAttempts.setSelection(settings.testAttempts.coerceIn(2, 5) - 2)\n\n'
        '        val realDelayProfiles = RealDelayProfile.entries\n'
        '        realDelayProfile.adapter = compactAdapter(\n'
        '            arrayOf(\n'
        '                getString(R.string.real_delay_profile_turbo),\n'
        '                getString(R.string.real_delay_profile_balanced),\n'
        '                getString(R.string.real_delay_profile_accurate),\n'
        '                getString(R.string.real_delay_profile_custom)\n'
        '            )\n'
        '        )\n'
        '        realDelayProfile.setSelection(\n'
        '            realDelayProfiles.indexOf(settings.realDelayProfile).coerceAtLeast(0)\n'
        '        )\n\n'
        '        val workerValues',
        marker='val realDelayProfiles = RealDelayProfile.entries'
    )
    replace_once(
        path,
        '        testUrl.setText(settings.testUrl)\n        networkTuning.isChecked',
        '        testUrl.setText(settings.testUrl)\n'
        '        realDelayGroupSize.setText(settings.realDelayGroupSize.toString())\n'
        '        realDelayWorkers.setText(settings.realDelayWorkers.toString())\n'
        '        realDelayTimeout.setText(settings.realDelayProbeTimeoutMs.toString())\n'
        '        realDelayStartGrace.setText(settings.realDelayStartGraceMs.toString())\n'
        '        realDelayTargetCount.setText(settings.realDelayTargetCount.toString())\n'
        '        realDelayRequiredProofs.setText(settings.realDelayRequiredProofs.toString())\n'
        '        networkTuning.isChecked',
        marker='realDelayGroupSize.setText'
    )
    replace_once(
        path,
        '            val testUrlValue = testUrl.text.toString().trim()\n            val dnsValues',
        '            val testUrlValue = testUrl.text.toString().trim()\n'
        '            val realGroupValue = realDelayGroupSize.intValue()\n'
        '            val realWorkersValue = realDelayWorkers.intValue()\n'
        '            val realTimeoutValue = realDelayTimeout.intValue()\n'
        '            val realGraceValue = realDelayStartGrace.intValue()\n'
        '            val realTargetsValue = realDelayTargetCount.intValue()\n'
        '            val realProofsValue = realDelayRequiredProofs.intValue()\n'
        '            val dnsValues',
        marker='val realGroupValue = realDelayGroupSize.intValue()'
    )
    replace_once(
        path,
        '            val tuningValid =\n                keepIdleValue != null',
        '            val realDelayValid =\n'
        '                realGroupValue != null && realGroupValue in 2..16 &&\n'
        '                    realWorkersValue != null && realWorkersValue in 1..6 &&\n'
        '                    realTimeoutValue != null && realTimeoutValue in 1_500..10_000 &&\n'
        '                    realGraceValue != null && realGraceValue in 0..1_000 &&\n'
        '                    realTargetsValue != null && realTargetsValue in 1..4 &&\n'
        '                    realProofsValue != null && realProofsValue in 1..realTargetsValue\n'
        '            if (!realDelayValid) {\n'
        '                toast(R.string.real_delay_settings_invalid)\n'
        '                return@setOnClickListener\n'
        '            }\n\n'
        '            val tuningValid =\n                keepIdleValue != null',
        marker='val realDelayValid ='
    )
    replace_once(
        path,
        '            settings.testUrl = testUrlValue\n            settings.networkTuningEnabled',
        '            settings.testUrl = testUrlValue\n'
        '            settings.realDelayProfile = realDelayProfiles[\n'
        '                realDelayProfile.selectedItemPosition\n'
        '            ]\n'
        '            settings.realDelayGroupSize = realGroupValue!!\n'
        '            settings.realDelayWorkers = realWorkersValue!!\n'
        '            settings.realDelayProbeTimeoutMs = realTimeoutValue!!\n'
        '            settings.realDelayStartGraceMs = realGraceValue!!\n'
        '            settings.realDelayTargetCount = realTargetsValue!!\n'
        '            settings.realDelayRequiredProofs = realProofsValue!!\n'
        '            settings.networkTuningEnabled',
        marker='settings.realDelayProfile = realDelayProfiles'
    )

def patch_main_activity() -> None:
    path = "app/src/main/java/com/trivox/client/ui/MainActivity.kt"
    replace_once(
        path,
        'import com.trivox.client.config.ConfigParser\n',
        'import com.trivox.client.config.ConfigParser\nimport com.trivox.client.config.OpenSshProfileCodec\n',
        marker='import com.trivox.client.config.OpenSshProfileCodec'
    )
    replace_once(
        path,
        '                    val profiles = result.profiles.map {\n                        it.copy(',
        '                    val profiles = result.profiles.map { profile ->\n'
        '                        OpenSshProfileCodec.secureForStorage(this, profile).copy(',
        marker='OpenSshProfileCodec.secureForStorage(this, profile).copy'
    )
    replace_once(
        path,
        '                    val profiles = ConfigParser.parseText(text)\n                    ConfigRepository(this).importProfiles(',
        '                    val profiles = ConfigParser.parseText(text).map { profile ->\n'
        '                        OpenSshProfileCodec.secureForStorage(this, profile)\n'
        '                    }\n                    ConfigRepository(this).importProfiles(',
        marker='val profiles = ConfigParser.parseText(text).map { profile ->'
    )

def patch_connection_service() -> None:
    path = "app/src/main/java/com/trivox/client/service/ConnectionService.kt"
    replace_once(
        path,
        'import com.trivox.client.data.SettingsRepository\n',
        'import com.trivox.client.data.SettingsRepository\nimport com.trivox.client.config.OpenSshProfileCodec\nimport com.trivox.client.ssh.OpenSshTunnelBridge\n',
        marker='import com.trivox.client.ssh.OpenSshTunnelBridge'
    )
    replace_once(
        path,
        '    private val ownsCore =\n        AtomicBoolean(false)\n',
        '    private val ownsCore =\n        AtomicBoolean(false)\n    private var sshHandle: OpenSshTunnelBridge.Handle? = null\n',
        marker='private var sshHandle:'
    )
    replace_once(
        path,
        '        val request =\n            CoreStartRequest(\n                profile,\n                settings,\n                ConnectionMode.PROXY\n            )',
        '        val effectiveProfile =\n            if (OpenSshProfileCodec.isOpenSsh(profile)) {\n'
        '                runCatching {\n'
        '                    OpenSshTunnelBridge(this).start(profile)\n'
        '                }.getOrElse {\n'
        '                    fail("OpenSSH start failed: " + (it.message ?: "unknown"))\n'
        '                    return\n'
        '                }.also {\n'
                '                    sshHandle = it\n'
        '                }.proxyProfile\n'
        '            } else {\n'
        '                profile\n'
        '            }\n\n'
        '        val request =\n            CoreStartRequest(\n                effectiveProfile,\n                settings,\n                ConnectionMode.PROXY\n            )',
        marker='val effectiveProfile ='
    )
    replace_once(
        path,
        '                        if (\n                            !stopRequested.get() &&\n                            !core.isRunning()\n                        ) {',
        '                        val ssh = sshHandle\n'
        '                        if (!stopRequested.get() && ssh != null && !ssh.isAlive()) {\n'
        '                            fail("OpenSSH tunnel stopped unexpectedly: " + ssh.failureText())\n'
        '                        } else if (\n                            !stopRequested.get() &&\n                            !core.isRunning()\n                        ) {',
        marker='ssh != null && !ssh.isAlive()'
    )
    replace_once(
        path,
        '            ownsCore.set(false)\n            runCatching { core.stop() }',
        '            ownsCore.set(false)\n'
        '            sshHandle?.close()\n'
        '            sshHandle = null\n'
        '            runCatching { core.stop() }',
        marker='sshHandle?.close()'
    )

def patch_vpn_service() -> None:
    path = "app/src/main/java/com/trivox/client/service/TrivoxVpnService.kt"
    replace_once(
        path,
        'import com.trivox.client.data.SettingsRepository\n',
        'import com.trivox.client.data.SettingsRepository\nimport com.trivox.client.config.OpenSshProfileCodec\nimport com.trivox.client.ssh.OpenSshTunnelBridge\n',
        marker='import com.trivox.client.ssh.OpenSshTunnelBridge'
    )
    replace_once(
        path,
        '    private val reconnectQueued =\n        AtomicBoolean(false)\n',
        '    private val reconnectQueued =\n        AtomicBoolean(false)\n'
        '    private var sshHandle: OpenSshTunnelBridge.Handle? = null\n'
        '    private var sshSourceProfile: com.trivox.client.data.ConfigProfile? = null\n',
        marker='private var sshHandle:'
    )
    replace_once(
        path,
        '        applyAppRouting(\n            builder,\n            settings.appRoutingMode,\n            settings.routedPackages\n        )\n',
        '        applyAppRouting(\n            builder,\n            settings.appRoutingMode,\n            settings.routedPackages\n        )\n\n'
        '        if (OpenSshProfileCodec.isOpenSsh(profile)) {\n'
        '            if (settings.appRoutingMode == AppRoutingMode.ALLOW_SELECTED) {\n'
        '                if (packageName in settings.routedPackages) {\n'
        '                    fail("OpenSSH cannot tunnel the Trivox app itself; remove Trivox from selected VPN apps")\n'
        '                    return\n'
        '                }\n'
        '            } else {\n'
        '                runCatching { builder.addDisallowedApplication(packageName) }\n'
        '                    .onFailure {\n'
        '                        fail("Unable to exclude OpenSSH transport from its own VPN: " + it.message)\n'
        '                        return\n'
        '                    }\n'
        '            }\n'
        '        }\n',
        marker='exclude OpenSSH transport from its own VPN'
    )
    replace_once(
        path,
        '        val request =\n            CoreStartRequest(\n                profile,\n                settings,\n                ConnectionMode.VPN,\n                tun!!.fd\n            )',
        '        val effectiveProfile =\n            if (OpenSshProfileCodec.isOpenSsh(profile)) {\n'
        '                runCatching {\n'
        '                    OpenSshTunnelBridge(this).start(profile)\n'
        '                }.getOrElse {\n'
        '                    fail("OpenSSH start failed: " + (it.message ?: "unknown"))\n'
        '                    return\n'
        '                }.also {\n'
        '                    sshSourceProfile = profile\n'
        '                    sshHandle = it\n'
        '                }.proxyProfile\n'
        '            } else {\n'
        '                profile\n'
        '            }\n\n'
        '        val request =\n            CoreStartRequest(\n                effectiveProfile,\n                settings,\n                ConnectionMode.VPN,\n                tun!!.fd\n            )',
        marker='val effectiveProfile ='
    )
    replace_once(
        path,
        '                            if (\n                                !stopRequested\n                                    .get() &&\n                                !core.isRunning()\n                            ) {',
        '                            val ssh = sshHandle\n'
        '                            if (!stopRequested.get() && ssh != null && !ssh.isAlive()) {\n'
        '                                fail("OpenSSH tunnel stopped unexpectedly: " + ssh.failureText())\n'
        '                            } else if (\n                                !stopRequested\n                                    .get() &&\n                                !core.isRunning()\n                            ) {',
        marker='OpenSSH tunnel stopped unexpectedly:'
    )
    replace_once(
        path,
        '        if (stopRequested.get()) {\n'
        '            return\n'
        '        }\n\n'
        '        val result =\n'
        '            core.startValidated(\n'
        '                request = request,',
        '        if (stopRequested.get()) {\n'
        '            return\n'
        '        }\n\n'
        '        var reconnectRequest = request\n'
        '        sshSourceProfile?.let { source ->\n'
        '            sshHandle?.close()\n'
        '            val restarted = runCatching {\n'
        '                OpenSshTunnelBridge(this).start(source)\n'
        '            }.getOrElse {\n'
        '                fail("OpenSSH reconnect failed: " + (it.message ?: "unknown"))\n'
        '                return\n'
        '            }\n'
        '            sshHandle = restarted\n'
        '            reconnectRequest = request.copy(profile = restarted.proxyProfile)\n'
        '        }\n\n'
        '        val result =\n'
        '            core.startValidated(\n'
        '                request = reconnectRequest,',
        marker='var reconnectRequest = request'
    )
    replace_once(
        path,
        '            ownsCore.set(false)\n            runCatching { core.stop() }',
        '            ownsCore.set(false)\n'
        '            sshHandle?.close()\n'
        '            sshHandle = null\n'
        '            sshSourceProfile = null\n'
        '            runCatching { core.stop() }',
        marker='sshHandle?.close()'
    )

def patch_destroy_cleanup() -> None:
    connection = "app/src/main/java/com/trivox/client/service/ConnectionService.kt"
    text = read(connection)
    if "Proxy destroy OpenSSH cleanup" not in text:
        old = "        cleanupExecutor.shutdownNow()\n        executor.shutdownNow()"
        new = (
            "        // Proxy destroy OpenSSH cleanup\n"
            "        sshHandle?.close()\n"
            "        sshHandle = null\n"
            "        cleanupExecutor.shutdownNow()\n"
            "        executor.shutdownNow()"
        )
        replace_once(connection, old, new)

    vpn = "app/src/main/java/com/trivox/client/service/TrivoxVpnService.kt"
    text = read(vpn)
    if "VPN destroy OpenSSH cleanup" not in text:
        old = "        tun = null\n        cleanupExecutor.shutdownNow()"
        new = (
            "        tun = null\n"
            "        // VPN destroy OpenSSH cleanup\n"
            "        sshHandle?.close()\n"
            "        sshHandle = null\n"
            "        sshSourceProfile = null\n"
            "        cleanupExecutor.shutdownNow()"
        )
        replace_once(vpn, old, new)

def verify_new_files() -> None:
    required = [
        "app/src/main/java/com/trivox/client/network/RealDelayPolicy.kt",
        "app/src/main/java/com/trivox/client/network/BatchRealDelayRunner.kt",
        "app/src/main/java/com/trivox/client/network/TunnelHealthVerifier.kt",
        "app/src/main/java/com/trivox/client/config/OpenSshProfileCodec.kt",
        "app/src/main/java/com/trivox/client/config/NordWhisperCompatibility.kt",
        "app/src/main/java/com/trivox/client/ssh/OpenSshBinaryManager.kt",
        "app/src/main/java/com/trivox/client/ssh/OpenSshCommand.kt",
        "app/src/main/java/com/trivox/client/ssh/OpenSshTunnelBridge.kt",
    ]
    missing = [p for p in required if not (ROOT / p).is_file()]
    if missing:
        raise PatchError("Extract the ZIP into the repository root first. Missing: " + ", ".join(missing))

def main() -> int:
    verify_new_files()
    head = git_head()
    if head != "unknown" and not head.startswith(EXPECTED_PREFIX):
        print(f"[!] Repository HEAD is {head}; patch was authored for {EXPECTED_PREFIX}.")
        print("[!] Continuing only because all structural anchors are validated.")

    operations = [
        patch_models,
        patch_config_parser,
        patch_xray_builder,
        patch_settings_xml,
        patch_settings_activity,
        patch_main_activity,
        patch_connection_service,
        patch_vpn_service,
        patch_destroy_cleanup,
    ]
    for operation in operations:
        operation()
        print(f"[ok] {operation.__name__}")

    marker = ROOT / ".trivox-v12-network-cores-applied"
    marker.write_text(PATCH_ID + "\n", encoding="utf-8")
    print("[done] Trivox v12 network/core patch applied.")
    print("Run: ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug")
    return 0

if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except PatchError as exc:
        print(f"[error] {exc}", file=sys.stderr)
        raise SystemExit(2)
