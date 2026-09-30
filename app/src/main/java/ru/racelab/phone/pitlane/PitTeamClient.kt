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
    val teamName: String = "",
    val carName: String = "",
    val raceClass: String = "",
    val gpsHz: Double = 0.0,
    val satellites: Int = 0,
    val lastReceiveElapsedMs: Long = 0L,
    val relayReceivedAtMs: Long? = null,
    val publisherSession: String? = null,
    val seq: Long = 0L,
    val transport: String = "CONNECTING",
    val rttMs: Long? = null,
    val lastError: String? = null
)

class PitTeamClient(private val config: PitTeamConfig) {
    companion object {
        private const val WS_STALE_MS = 4_500L
        private const val PING_INTERVAL_MS = 5_000L
        private const val FALLBACK_INTERVAL_MS = 700L
        private const val MIN_RECONNECT_MS = 800L
        private const val MAX_RECONNECT_MS = 12_000L
    }

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
    @Volatile private var lastPingElapsed = 0L
    @Volatile private var lastFallbackElapsed = 0L
    @Volatile private var reconnectDelayMs = MIN_RECONNECT_MS
    private val fallbackInFlight = AtomicBoolean(false)

    fun start(scope: CoroutineScope): Job {
        connectWebSocket()
        val job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                if (!socketOpen && socket == null && now - lastWsAttemptElapsed >= reconnectDelayMs) {
                    connectWebSocket()
                }

                val wsFresh = socketOpen && now - lastWsMessageElapsed < WS_STALE_MS
                if (socketOpen && now - lastPingElapsed >= PING_INTERVAL_MS) {
                    lastPingElapsed = now
                    sendPing(now)
                }
                if (!wsFresh && now - lastFallbackElapsed >= FALLBACK_INTERVAL_MS) {
                    lastFallbackElapsed = now
                    fetchFallbackOnce()
                }

                delay(150L)
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
                lastWsMessageElapsed = SystemClock.elapsedRealtime()
                lastPingElapsed = 0L
                reconnectDelayMs = MIN_RECONNECT_MS
                _state.value = _state.value.copy(transport = "WEBSOCKET", lastError = null)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (json?.optString("type") == "pong") {
                    val sent = json.optLong("nonce", -1L)
                    if (sent >= 0L) {
                        val rtt = (SystemClock.elapsedRealtime() - sent).coerceAtLeast(0L)
                        _state.value = _state.value.copy(
                            transport = "WEBSOCKET",
                            rttMs = rtt,
                            lastError = null
                        )
                    }
                    lastWsMessageElapsed = SystemClock.elapsedRealtime()
                    return
                }
                applyPayload(text, "WEBSOCKET")
                lastWsMessageElapsed = SystemClock.elapsedRealtime()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socketOpen = false
                socket = null
                reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_MS)
                _state.value = _state.value.copy(transport = "HTTP FALLBACK")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socketOpen = false
                socket = null
                reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_MS)
                val error = if (response?.code == 429) "ЛИМИТ: 5 УСТРОЙСТВ КОМАНДЫ" else (t.message ?: "WEBSOCKET")
                _state.value = _state.value.copy(
                    transport = "HTTP FALLBACK",
                    lastError = error
                )
            }
        })
    }

    private fun sendPing(nowElapsed: Long) {
        val ws = socket ?: return
        if (!socketOpen) return
        if (ws.queueSize() > 8_192L) return
        ws.send(
            JSONObject()
                .put("type", "ping")
                .put("nonce", nowElapsed)
                .toString()
        )
    }

    private fun fetchFallbackOnce() {
        if (!fallbackInFlight.compareAndSet(false, true)) return
        try {
            val room = URLEncoder.encode(config.room, StandardCharsets.UTF_8.name())
            val key = URLEncoder.encode(config.key, StandardCharsets.UTF_8.name())
            val fallbackBase = if (config.relayUrl.trimEnd('/') == InternetPitRelaySettingsRepository.DEFAULT_BASE_URL) {
                InternetPitRelaySettingsRepository.LEGACY_HTTP_FALLBACK_URL
            } else {
                config.relayUrl.trimEnd('/')
            }
            val endpoint = fallbackBase +
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
        val incomingSession = data.optString("publisherSession", "").takeIf { it.isNotBlank() }
        val incomingSeq = data.optLong("seq", 0L)
        val current = _state.value
        val currentRelayMs = current.relayReceivedAtMs
        if (incomingSession != null && incomingSession == current.publisherSession && incomingSeq > 0L && incomingSeq <= current.seq) return
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
            teamName = data.optString("teamName", ""),
            carName = data.optString("carName", ""),
            raceClass = data.optString("raceClass", ""),
            gpsHz = data.optDouble("gpsHz", 0.0),
            satellites = data.optInt("satellites", 0),
            lastReceiveElapsedMs = nowElapsed,
            relayReceivedAtMs = incomingRelayMs,
            publisherSession = incomingSession ?: current.publisherSession,
            seq = if (incomingSeq > 0L) incomingSeq else current.seq,
            transport = transport,
            rttMs = current.rttMs,
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
