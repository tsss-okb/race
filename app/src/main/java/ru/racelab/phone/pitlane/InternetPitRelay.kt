package ru.racelab.phone.pitlane

import android.content.Context
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import ru.racelab.phone.data.RaceRuntime
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object InternetPitRelay {
    private const val MAX_WS_QUEUE_BYTES = 16_384L

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "RaceLab-Pit-Heartbeat").apply { isDaemon = true }
    }
    private val fallbackExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RaceLab-Pit-HTTP-Fallback").apply { isDaemon = true }
    }
    private val httpInFlight = AtomicBoolean(false)

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile private var heartbeatTask: ScheduledFuture<*>? = null
    @Volatile private var reconnectTask: ScheduledFuture<*>? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var socketOpen = false
    @Volatile private var settings = InternetPitRelaySettings()
    @Volatile private var lastSuccessMs: Long? = null
    @Volatile private var generation = 0L

    fun start(context: Context) {
        applySettings(InternetPitRelaySettingsRepository.load(context))
    }

    @Synchronized
    fun applySettings(next: InternetPitRelaySettings) {
        generation += 1
        settings = next.copy(baseUrl = next.baseUrl.trim().trimEnd('/'))
        heartbeatTask?.cancel(false)
        reconnectTask?.cancel(false)
        reconnectTask = null
        socketOpen = false
        socket?.close(1000, "reconfigure")
        socket = null

        RaceRuntime.setInternetPitRelayStatus(
            enabled = settings.enabled,
            configured = settings.configured,
            status = when {
                !settings.enabled -> "OFF"
                !settings.configured -> "НУЖЕН RELAY URL"
                else -> "WS CONNECTING"
            },
            viewerUrl = viewerUrl(settings),
            lastSuccessMs = lastSuccessMs
        )

        if (settings.enabled && settings.configured) {
            connectSocket(generation)
            heartbeatTask = scheduler.scheduleWithFixedDelay(
                { heartbeat() },
                1_000,
                1_000,
                TimeUnit.MILLISECONDS
            )
        }
    }

    @Synchronized
    fun stop() {
        generation += 1
        heartbeatTask?.cancel(false)
        reconnectTask?.cancel(false)
        heartbeatTask = null
        reconnectTask = null
        socketOpen = false
        socket?.close(1000, "stop")
        socket = null
        RaceRuntime.setInternetPitRelayStatus(
            enabled = settings.enabled,
            configured = settings.configured,
            status = "STOPPED",
            viewerUrl = viewerUrl(settings),
            lastSuccessMs = lastSuccessMs
        )
    }

    fun publishPriority() {
        val cfg = settings
        if (!cfg.enabled || !cfg.configured) return
        if (!sendWebSocketLatest(priority = true)) {
            publishHttpFallback()
            scheduleReconnect()
        }
    }

    private fun heartbeat() {
        val cfg = settings
        if (!cfg.enabled || !cfg.configured) return

        if (!socketOpen) {
            scheduleReconnect()
            publishHttpFallback()
            return
        }

        sendWebSocketLatest(priority = false)
    }

    @Synchronized
    private fun connectSocket(expectedGeneration: Long) {
        if (expectedGeneration != generation || socketOpen || socket != null) return

        val cfg = settings
        if (!cfg.enabled || !cfg.configured) return

        val request = Request.Builder()
            .url(webSocketUrl(cfg, "publisher"))
            .build()

        socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (expectedGeneration != generation) {
                    webSocket.close(1000, "stale")
                    return
                }
                socket = webSocket
                socketOpen = true
                RaceRuntime.setInternetPitRelayStatus(
                    enabled = true,
                    configured = true,
                    status = "WS LIVE",
                    viewerUrl = viewerUrl(cfg),
                    lastSuccessMs = lastSuccessMs
                )
                sendWebSocketLatest(priority = true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val ack = runCatching { JSONObject(text) }.getOrNull()
                if (ack?.optString("type") == "ack") {
                    lastSuccessMs = System.currentTimeMillis()
                    RaceRuntime.setInternetPitRelayStatus(
                        enabled = true,
                        configured = true,
                        status = "WS LIVE",
                        viewerUrl = viewerUrl(cfg),
                        lastSuccessMs = lastSuccessMs
                    )
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (expectedGeneration != generation) return
                socketOpen = false
                socket = null
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (expectedGeneration != generation) return
                socketOpen = false
                socket = null
                RaceRuntime.setInternetPitRelayStatus(
                    enabled = true,
                    configured = true,
                    status = "HTTP FALLBACK",
                    viewerUrl = viewerUrl(cfg),
                    lastSuccessMs = lastSuccessMs
                )
                scheduleReconnect()
            }
        })
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (!settings.enabled || !settings.configured || socketOpen || socket != null) return
        if (reconnectTask?.isDone == false) return
        val expectedGeneration = generation
        reconnectTask = scheduler.schedule(
            { 
                reconnectTask = null
                connectSocket(expectedGeneration)
            },
            700,
            TimeUnit.MILLISECONDS
        )
    }

    private fun sendWebSocketLatest(priority: Boolean): Boolean {
        val ws = socket ?: return false
        if (!socketOpen) return false

        val queued = ws.queueSize()
        if (!priority && queued > MAX_WS_QUEUE_BYTES) return true
        if (priority && queued > MAX_WS_QUEUE_BYTES * 4) return false

        val sent = ws.send(buildPayload())
        if (!sent) {
            socketOpen = false
            return false
        }
        return true
    }

    private fun buildPayload(): String {
        val state = RaceRuntime.state.value
        val currentPit = if (state.pitTimerActive) {
            RaceRuntime.pitElapsedMs(SystemClock.elapsedRealtime())
        } else {
            state.pitLastMs ?: 0L
        }

        return JSONObject()
            .put("serverTimeMs", System.currentTimeMillis())
            .put("pitActive", state.pitTimerActive)
            .put("pitCurrentMs", currentPit)
            .put("pitLastMs", state.pitLastMs ?: JSONObject.NULL)
            .put("pitBestMs", state.pitBestMs ?: JSONObject.NULL)
            .put("pitCount", state.pitStopCount)
            .put("pitTrigger", state.pitLastTrigger)
            .put("sessionActive", state.sessionActive)
            .put("armed", state.armed)
            .put("lapCurrentMs", state.lapElapsedMs)
            .put("lapBestMs", state.bestLapMs ?: JSONObject.NULL)
            .put("deltaMs", state.deltaMs ?: JSONObject.NULL)
            .put("speedKmh", state.speedKmh)
            .put("track", state.currentTrackName ?: "RaceLab")
            .put("gpsHz", state.gpsHz)
            .put("satellites", state.satellites)
            .toString()
    }

    private fun publishHttpFallback() {
        if (!httpInFlight.compareAndSet(false, true)) return
        fallbackExecutor.execute {
            try {
                val cfg = settings
                if (!cfg.enabled || !cfg.configured) return@execute

                val bytes = buildPayload().toByteArray(StandardCharsets.UTF_8)
                val key = URLEncoder.encode(cfg.key, StandardCharsets.UTF_8.name())
                val room = URLEncoder.encode(cfg.room, StandardCharsets.UTF_8.name())
                val endpoint = cfg.baseUrl + "/api/room/" + room + "/update?key=" + key

                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 650
                    readTimeout = 650
                    doOutput = true
                    useCaches = false
                    setFixedLengthStreamingMode(bytes.size)
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Cache-Control", "no-store")
                }
                connection.outputStream.use { it.write(bytes) }
                val code = connection.responseCode
                connection.disconnect()

                if (code in 200..299) {
                    lastSuccessMs = System.currentTimeMillis()
                    RaceRuntime.setInternetPitRelayStatus(
                        enabled = true,
                        configured = true,
                        status = "HTTP FALLBACK",
                        viewerUrl = viewerUrl(cfg),
                        lastSuccessMs = lastSuccessMs
                    )
                }
            } catch (_: Throwable) {
                RaceRuntime.setInternetPitRelayStatus(
                    enabled = true,
                    configured = true,
                    status = "NO INTERNET",
                    viewerUrl = viewerUrl(settings),
                    lastSuccessMs = lastSuccessMs
                )
            } finally {
                httpInFlight.set(false)
            }
        }
    }

    private fun webSocketUrl(cfg: InternetPitRelaySettings, role: String): String {
        val base = cfg.baseUrl.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val key = URLEncoder.encode(cfg.key, StandardCharsets.UTF_8.name())
        val room = URLEncoder.encode(cfg.room, StandardCharsets.UTF_8.name())
        return base + "/ws/room/" + room + "?key=" + key + "&role=" + role
    }

    fun viewerUrl(cfg: InternetPitRelaySettings = settings): String {
        if (!cfg.configured) return ""
        val relay = URLEncoder.encode(cfg.baseUrl.trimEnd('/'), StandardCharsets.UTF_8.name())
        val key = URLEncoder.encode(cfg.key, StandardCharsets.UTF_8.name())
        val room = URLEncoder.encode(cfg.room, StandardCharsets.UTF_8.name())
        return "racelab://pit?relay=" + relay + "&room=" + room + "&key=" + key
    }
}
