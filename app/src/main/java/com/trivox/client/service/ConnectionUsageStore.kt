package com.trivox.client.service

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import com.trivox.client.data.ConnectionMode
import com.trivox.client.util.Diagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Lightweight connection-usage accounting based on the app UID's network
 * counters. Xray owns the outbound sockets in this process, so sampling only
 * while a Trivox session is active gives a useful tunnel-traffic estimate
 * without packet-copying the TUN stream or adding a native dependency.
 */
object ConnectionUsageStore {
    data class UsageSnapshot(
        val active: Boolean = false,
        val sessionRxBytes: Long = 0L,
        val sessionTxBytes: Long = 0L,
        val todayRxBytes: Long = 0L,
        val todayTxBytes: Long = 0L,
        val yesterdayRxBytes: Long = 0L,
        val yesterdayTxBytes: Long = 0L,
        val monthRxBytes: Long = 0L,
        val monthTxBytes: Long = 0L
    )

    data class SessionRecord(
        val profileName: String,
        val mode: ConnectionMode,
        val startedAt: Long,
        val endedAt: Long,
        val rxBytes: Long,
        val txBytes: Long,
        val outcome: String
    ) {
        val durationMs: Long
            get() = (endedAt - startedAt).coerceAtLeast(0L)
    }

    private data class Active(
        val processToken: String,
        val sessionId: Long,
        val profileId: String,
        val profileName: String,
        val mode: ConnectionMode,
        val startedAt: Long,
        val lastRxCounter: Long,
        val lastTxCounter: Long,
        val rxBytes: Long,
        val txBytes: Long
    )

    private const val PREFS = "trivox_usage_history_v26"
    private const val KEY_ACTIVE = "active"
    private const val KEY_DAILY = "daily"
    private const val KEY_SESSIONS = "sessions"
    private const val MAX_SESSIONS = 200
    private const val MAX_DAILY_DAYS = 120
    private val LOCK = Any()
    private val PROCESS_TOKEN = UUID.randomUUID().toString()

    fun begin(
        context: Context,
        sessionId: Long,
        profileId: String,
        profileName: String,
        mode: ConnectionMode
    ) = synchronized(LOCK) {
        if (sessionId <= 0L) return@synchronized
        val prefs = prefs(context)
        val old = readActive(prefs)
        if (old != null) {
            if (old.processToken == PROCESS_TOKEN && old.sessionId == sessionId) {
                return@synchronized
            }
            val recovered = sampleActive(old)
            persistDailyDelta(prefs, recovered.second, recovered.third)
            appendSession(
                prefs,
                recovered.first,
                System.currentTimeMillis(),
                "recovered_after_process_restart"
            )
            prefs.edit().remove(KEY_ACTIVE).commit()
        }

        val counters = counters()
        writeActive(
            prefs,
            Active(
                processToken = PROCESS_TOKEN,
                sessionId = sessionId,
                profileId = profileId,
                profileName = profileName,
                mode = mode,
                startedAt = System.currentTimeMillis(),
                lastRxCounter = counters.first,
                lastTxCounter = counters.second,
                rxBytes = 0L,
                txBytes = 0L
            )
        )
        Diagnostics.debug("Usage tracker started; session=$sessionId, mode=$mode")
    }

    fun sample(context: Context, sessionId: Long) = synchronized(LOCK) {
        val prefs = prefs(context)
        val active = readActive(prefs) ?: return@synchronized
        if (active.sessionId != sessionId) return@synchronized
        val sampled = sampleActive(active)
        if (sampled.second > 0L || sampled.third > 0L) {
            persistDailyDelta(prefs, sampled.second, sampled.third)
        }
        writeActive(prefs, sampled.first)
    }

    fun finish(context: Context, sessionId: Long, outcome: String) = synchronized(LOCK) {
        val prefs = prefs(context)
        val active = readActive(prefs) ?: return@synchronized
        if (active.sessionId != sessionId) return@synchronized
        val sampled = sampleActive(active)
        if (sampled.second > 0L || sampled.third > 0L) {
            persistDailyDelta(prefs, sampled.second, sampled.third)
        }
        appendSession(
            prefs,
            sampled.first,
            System.currentTimeMillis(),
            outcome.take(80)
        )
        prefs.edit().remove(KEY_ACTIVE).commit()
        Diagnostics.debug(
            "Usage tracker finished; session=$sessionId, " +
                "rx=${sampled.first.rxBytes}, tx=${sampled.first.txBytes}"
        )
    }

    fun snapshot(context: Context): UsageSnapshot = synchronized(LOCK) {
        val prefs = prefs(context)
        val active = readActive(prefs)
        val sampled = active?.let(::sampleActive)
        val transientRx = sampled?.second ?: 0L
        val transientTx = sampled?.third ?: 0L
        val current = sampled?.first ?: active
        val daily = readDaily(prefs)
        val nowMillis = System.currentTimeMillis()
        val today = dayKey(nowMillis)
        val yesterday = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            add(Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis.let(::dayKey)
        val month = monthKey(nowMillis)

        fun totals(key: String): Pair<Long, Long> {
            val value = daily.optJSONObject(key) ?: return 0L to 0L
            return value.optLong("rx").coerceAtLeast(0L) to
                value.optLong("tx").coerceAtLeast(0L)
        }

        val todayStored = totals(today)
        val yesterdayStored = totals(yesterday)
        var monthRx = 0L
        var monthTx = 0L
        val keys = daily.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.startsWith(month)) {
                val value = totals(key)
                monthRx += value.first
                monthTx += value.second
            }
        }

        UsageSnapshot(
            active = current != null,
            sessionRxBytes = current?.rxBytes ?: 0L,
            sessionTxBytes = current?.txBytes ?: 0L,
            todayRxBytes = todayStored.first + transientRx,
            todayTxBytes = todayStored.second + transientTx,
            yesterdayRxBytes = yesterdayStored.first,
            yesterdayTxBytes = yesterdayStored.second,
            monthRxBytes = monthRx + transientRx,
            monthTxBytes = monthTx + transientTx
        )
    }

    fun recentSessions(context: Context, limit: Int = 8): List<SessionRecord> =
        synchronized(LOCK) {
            val array = readSessions(prefs(context))
            (array.length() - 1 downTo 0)
                .asSequence()
                .mapNotNull { index ->
                    val json = array.optJSONObject(index) ?: return@mapNotNull null
                    val mode = runCatching {
                        ConnectionMode.valueOf(json.optString("mode"))
                    }.getOrDefault(ConnectionMode.VPN)
                    SessionRecord(
                        profileName = json.optString("profileName", "Trivox"),
                        mode = mode,
                        startedAt = json.optLong("startedAt"),
                        endedAt = json.optLong("endedAt"),
                        rxBytes = json.optLong("rx").coerceAtLeast(0L),
                        txBytes = json.optLong("tx").coerceAtLeast(0L),
                        outcome = json.optString("outcome")
                    )
                }
                .take(limit.coerceIn(1, 30))
                .toList()
        }

    private fun sampleActive(active: Active): Triple<Active, Long, Long> {
        val now = counters()
        val rxDelta = counterDelta(active.lastRxCounter, now.first)
        val txDelta = counterDelta(active.lastTxCounter, now.second)
        return Triple(
            active.copy(
                lastRxCounter = now.first,
                lastTxCounter = now.second,
                rxBytes = active.rxBytes + rxDelta,
                txBytes = active.txBytes + txDelta
            ),
            rxDelta,
            txDelta
        )
    }

    private fun counterDelta(previous: Long, current: Long): Long =
        if (previous < 0L || current < 0L || current < previous) 0L
        else current - previous

    private fun counters(): Pair<Long, Long> {
        val uid = Process.myUid()
        return TrafficStats.getUidRxBytes(uid) to TrafficStats.getUidTxBytes(uid)
    }

    private fun appendSession(
        prefs: android.content.SharedPreferences,
        active: Active,
        endedAt: Long,
        outcome: String
    ) {
        val source = readSessions(prefs)
        val kept = JSONArray()
        val first = (source.length() - (MAX_SESSIONS - 1)).coerceAtLeast(0)
        for (index in first until source.length()) {
            kept.put(source.opt(index))
        }
        kept.put(
            JSONObject()
                .put("profileId", active.profileId)
                .put("profileName", active.profileName)
                .put("mode", active.mode.name)
                .put("startedAt", active.startedAt)
                .put("endedAt", endedAt)
                .put("rx", active.rxBytes)
                .put("tx", active.txBytes)
                .put("outcome", outcome)
        )
        prefs.edit().putString(KEY_SESSIONS, kept.toString()).commit()
    }

    private fun persistDailyDelta(
        prefs: android.content.SharedPreferences,
        rx: Long,
        tx: Long
    ) {
        if (rx <= 0L && tx <= 0L) return
        val daily = readDaily(prefs)
        val key = dayKey(System.currentTimeMillis())
        val current = daily.optJSONObject(key) ?: JSONObject()
        current.put("rx", current.optLong("rx") + rx.coerceAtLeast(0L))
        current.put("tx", current.optLong("tx") + tx.coerceAtLeast(0L))
        daily.put(key, current)
        pruneDaily(daily)
        prefs.edit().putString(KEY_DAILY, daily.toString()).apply()
    }

    private fun pruneDaily(daily: JSONObject) {
        val keys = mutableListOf<String>()
        val iterator = daily.keys()
        while (iterator.hasNext()) keys += iterator.next()
        keys.sorted().dropLast(MAX_DAILY_DAYS).forEach(daily::remove)
    }

    private fun readActive(prefs: android.content.SharedPreferences): Active? {
        val raw = prefs.getString(KEY_ACTIVE, null)?.takeIf(String::isNotBlank) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val session = json.optLong("sessionId")
        if (session <= 0L) return null
        return Active(
            processToken = json.optString("processToken"),
            sessionId = session,
            profileId = json.optString("profileId"),
            profileName = json.optString("profileName", "Trivox"),
            mode = runCatching {
                ConnectionMode.valueOf(json.optString("mode"))
            }.getOrDefault(ConnectionMode.VPN),
            startedAt = json.optLong("startedAt", System.currentTimeMillis()),
            lastRxCounter = json.optLong("lastRxCounter", -1L),
            lastTxCounter = json.optLong("lastTxCounter", -1L),
            rxBytes = json.optLong("rxBytes").coerceAtLeast(0L),
            txBytes = json.optLong("txBytes").coerceAtLeast(0L)
        )
    }

    private fun writeActive(
        prefs: android.content.SharedPreferences,
        active: Active
    ) {
        prefs.edit().putString(
            KEY_ACTIVE,
            JSONObject()
                .put("processToken", active.processToken)
                .put("sessionId", active.sessionId)
                .put("profileId", active.profileId)
                .put("profileName", active.profileName)
                .put("mode", active.mode.name)
                .put("startedAt", active.startedAt)
                .put("lastRxCounter", active.lastRxCounter)
                .put("lastTxCounter", active.lastTxCounter)
                .put("rxBytes", active.rxBytes)
                .put("txBytes", active.txBytes)
                .toString()
        ).apply()
    }

    private fun readDaily(prefs: android.content.SharedPreferences): JSONObject =
        runCatching { JSONObject(prefs.getString(KEY_DAILY, "{}") ?: "{}") }
            .getOrDefault(JSONObject())

    private fun readSessions(prefs: android.content.SharedPreferences): JSONArray =
        runCatching { JSONArray(prefs.getString(KEY_SESSIONS, "[]") ?: "[]") }
            .getOrDefault(JSONArray())

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dayKey(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(time))

    private fun monthKey(time: Long): String =
        SimpleDateFormat("yyyy-MM", Locale.US).format(Date(time))
}
