package ru.racelab.phone.pitlane

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class PitTeamConfig(
    val relayUrl: String,
    val room: String,
    val key: String
) {
    val valid: Boolean
        get() = relayUrl.startsWith("https://") && room.isNotBlank() && key.isNotBlank()
}

data class PitTeamSnapshot(
    val pitActive: Boolean = false,
    val pitBaseMs: Long = 0L,
    val pitBaseReceivedElapsedMs: Long = 0L,
    val pitLastMs: Long? = null,
    val pitBestMs: Long? = null,
    val pitCount: Int = 0,
    val pitTrigger: String = "—",
    val lapCurrentMs: Long = 0L,
    val lapBestMs: Long? = null,
    val deltaMs: Long? = null,
    val speedKmh: Double = 0.0,
    val track: String = "RaceLab",
    val gpsHz: Double = 0.0,
    val satellites: Int = 0,
    val lastReceiveElapsedMs: Long = 0L,
    val relayReceivedAtMs: Long? = null,
    val lastError: String? = null
)

class PitTeamClient(private val config: PitTeamConfig) {
    private val _state = MutableStateFlow(PitTeamSnapshot())
    val state: StateFlow<PitTeamSnapshot> = _state.asStateFlow()

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            val cycleStart = SystemClock.elapsedRealtime()
            fetchOnce()
            val spent = SystemClock.elapsedRealtime() - cycleStart
            delay((100L - spent).coerceAtLeast(20L))
        }
    }

    private fun fetchOnce() {
        val room = URLEncoder.encode(config.room, StandardCharsets.UTF_8.name())
        val key = URLEncoder.encode(config.key, StandardCharsets.UTF_8.name())
        val endpoint = config.relayUrl.trimEnd('/') + "/api/room/" + room + "?key=" + key + "&t=" + System.currentTimeMillis()

        try {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 900
                readTimeout = 900
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-store")
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                connection.disconnect()
                throw IllegalStateException("HTTP $code")
            }

            val json = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val data = JSONObject(json)
            val nowElapsed = SystemClock.elapsedRealtime()

            _state.value = PitTeamSnapshot(
                pitActive = data.optBoolean("pitActive", false),
                pitBaseMs = data.optLong("pitCurrentMs", 0L),
                pitBaseReceivedElapsedMs = nowElapsed,
                pitLastMs = data.optNullableLong("pitLastMs"),
                pitBestMs = data.optNullableLong("pitBestMs"),
                pitCount = data.optInt("pitCount", 0),
                pitTrigger = data.optString("pitTrigger", "—"),
                lapCurrentMs = data.optLong("lapCurrentMs", 0L),
                lapBestMs = data.optNullableLong("lapBestMs"),
                deltaMs = data.optNullableLong("deltaMs"),
                speedKmh = data.optDouble("speedKmh", 0.0),
                track = data.optString("track", "RaceLab"),
                gpsHz = data.optDouble("gpsHz", 0.0),
                satellites = data.optInt("satellites", 0),
                lastReceiveElapsedMs = nowElapsed,
                relayReceivedAtMs = data.optNullableLong("relayReceivedAtMs"),
                lastError = null
            )
        } catch (t: Throwable) {
            _state.value = _state.value.copy(lastError = t.message ?: "NETWORK")
        }
    }
}

private fun JSONObject.optNullableLong(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    return runCatching { getLong(name) }.getOrNull()
}
