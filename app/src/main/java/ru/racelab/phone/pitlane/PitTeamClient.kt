package ru.racelab.phone.pitlane

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    val transport: String = "CONNECTING",
    val lastError: String? = null
)

class PitTeamClient(private val config: PitTeamConfig) {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(PitTeamSnapshot())
    val state: kotlinx.coroutines.flow.StateFlow<PitTeamSnapshot> = _state

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var socketOpen = false
    @Volatile private var lastWsMessageElapsed = 0L
    @Volatile private var lastWsAttemptElapsed = 0L
    private val fallbackInFlight = AtomicBoolean(false)

    fun start(scope: CoroutineScope): Job {
        connectWebSocket()
        val job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                if (!socketOpen && now - lastWsAttemptElapsed > 800L) {
                    connectWebSocket()
                }

                val wsFresh = socketOpen && now - lastWsMessageElapsed < 1_500L
                if (!wsFresh) fetchFallbackOnce()

                delay(if (wsFresh) 500L else 220L)
            }
        }
        job.invokeOnCompletion {
            socketOpen = false
            socket?.close(1000, "team screen closed")
            socket = null
        }
        return job
    }

    @Synchronized
    private fun connectWebSocket() {
        if (socketOpen || socket != null) return
        lastWsAttemptElapsed = SystemClock.elapsedRealtime()

        val request = Request.Builder()
            .url(webSocketUrl())
            .build()

        socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                socketOpen = true
                _state.value = _state.value.copy(transport = "WEBSOCKET", lastError = null)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                applyPayload(text, "WEBSOCKET")
                lastWsMessageElapsed = SystemClock.elapsedRealtime()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socketOpen = false
                socket = null
                _state.value = _state.value.copy(transport = "HTTP FALLBACK")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socketOpen = false
                socket = null
                _state.value = _state.value.copy(
                    transport = "HTTP FALLBACK",
                    lastError = t.message ?: "WEBSOCKET"
                )
            }
        })
    }

    private fun fetchFallbackOnce() {
        if (!fallbackInFlight.compareAndSet(false, true)) return
        try {
            val room = URLEncoder.encode(config.room, StandardCharsets.UTF_8.name())
            val key = URLEncoder.encode(config.key, StandardCharsets.UTF_8.name())
            val endpoint = config.relayUrl.trimEnd('/') +
                "/api/room/" + room + "?key=" + key + "&t=" + System.currentTimeMillis()

            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 550
                readTimeout = 550
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
            applyPayload(json, "HTTP FALLBACK")
        } catch (t: Throwable) {
            _state.value = _state.value.copy(lastError = t.message ?: "NETWORK")
        } finally {
            fallbackInFlight.set(false)
        }
    }

    private fun applyPayload(json: String, transport: String) {
        val data = runCatching { JSONObject(json) }.getOrNull() ?: return
        if (data.optString("type") == "ack") return

        val incomingRelayMs = data.optNullableLong("relayReceivedAtMs")
        val currentRelayMs = _state.value.relayReceivedAtMs
        if (incomingRelayMs != null && currentRelayMs != null && incomingRelayMs < currentRelayMs) return

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
            relayReceivedAtMs = incomingRelayMs,
            transport = transport,
            lastError = null
        )
    }

    private fun webSocketUrl(): String {
        val base = config.relayUrl.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val room = URLEncoder.encode(config.room, StandardCharsets.UTF_8.name())
        val key = URLEncoder.encode(config.key, StandardCharsets.UTF_8.name())
        return base + "/ws/room/" + room + "?key=" + key + "&role=viewer"
    }
}

private fun JSONObject.optNullableLong(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    return runCatching { getLong(name) }.getOrNull()
}
