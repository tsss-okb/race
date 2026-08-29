package ru.racelab.phone.pitlane

import android.content.Context
import android.os.SystemClock
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
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "RaceLab-InternetPit-Heartbeat").apply { isDaemon = true }
    }
    private val publisher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RaceLab-InternetPit-Publisher").apply { isDaemon = true }
    }
    private val publishInFlight = AtomicBoolean(false)
    private val publishPending = AtomicBoolean(false)

    @Volatile private var task: ScheduledFuture<*>? = null
    @Volatile private var context: Context? = null
    @Volatile private var settings = InternetPitRelaySettings()
    @Volatile private var lastSuccessMs: Long? = null

    fun start(context: Context) {
        this.context = context.applicationContext
        applySettings(InternetPitRelaySettingsRepository.load(context))
    }

    @Synchronized
    fun applySettings(next: InternetPitRelaySettings) {
        settings = next.copy(baseUrl = next.baseUrl.trim().trimEnd('/'))
        task?.cancel(false)
        task = null

        val viewer = viewerUrl(settings)
        RaceRuntime.setInternetPitRelayStatus(
            enabled = settings.enabled,
            configured = settings.configured,
            status = when {
                !settings.enabled -> "OFF"
                !settings.configured -> "НУЖЕН RELAY URL"
                else -> "CONNECTING"
            },
            viewerUrl = viewer,
            lastSuccessMs = lastSuccessMs
        )

        if (settings.enabled && settings.configured) {
            requestPublish()
            task = scheduler.scheduleWithFixedDelay(
                { requestPublish() },
                1_000,
                1_000,
                TimeUnit.MILLISECONDS
            )
        }
    }

    fun stop() {
        task?.cancel(false)
        task = null
        publishPending.set(false)
        RaceRuntime.setInternetPitRelayStatus(
            enabled = settings.enabled,
            configured = settings.configured,
            status = "STOPPED",
            viewerUrl = viewerUrl(settings),
            lastSuccessMs = lastSuccessMs
        )
    }

    fun publishPriority() {
        if (settings.enabled && settings.configured) requestPublish()
    }

    private fun requestPublish() {
        publishPending.set(true)
        if (!publishInFlight.compareAndSet(false, true)) return

        publisher.execute {
            try {
                while (publishPending.getAndSet(false)) {
                    publishLatest()
                }
            } finally {
                publishInFlight.set(false)
                if (publishPending.get()) requestPublish()
            }
        }
    }

    private fun publishLatest() {
        val cfg = settings
        if (!cfg.enabled || !cfg.configured) return

        val state = RaceRuntime.state.value
        val currentPit = if (state.pitTimerActive) {
            RaceRuntime.pitElapsedMs(SystemClock.elapsedRealtime())
        } else {
            state.pitLastMs ?: 0L
        }

        val body = JSONObject()
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

        val key = URLEncoder.encode(cfg.key, StandardCharsets.UTF_8.name())
        val room = URLEncoder.encode(cfg.room, StandardCharsets.UTF_8.name())
        val endpoint = cfg.baseUrl + "/api/room/" + room + "/update?key=" + key

        try {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 650
                readTimeout = 650
                doOutput = true
                useCaches = false
                setFixedLengthStreamingMode(bytes.size)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Cache-Control", "no-store")
                setRequestProperty("Connection", "keep-alive")
            }
            connection.outputStream.use { out ->
                out.write(bytes)
            }
            val code = connection.responseCode
            connection.disconnect()

            if (code in 200..299) {
                lastSuccessMs = System.currentTimeMillis()
                RaceRuntime.setInternetPitRelayStatus(
                    enabled = true,
                    configured = true,
                    status = "LIVE",
                    viewerUrl = viewerUrl(cfg),
                    lastSuccessMs = lastSuccessMs
                )
            } else {
                RaceRuntime.setInternetPitRelayStatus(
                    enabled = true,
                    configured = true,
                    status = "HTTP " + code,
                    viewerUrl = viewerUrl(cfg),
                    lastSuccessMs = lastSuccessMs
                )
            }
        } catch (_: Throwable) {
            RaceRuntime.setInternetPitRelayStatus(
                enabled = true,
                configured = true,
                status = "NO INTERNET",
                viewerUrl = viewerUrl(cfg),
                lastSuccessMs = lastSuccessMs
            )
        }
    }

    fun viewerUrl(cfg: InternetPitRelaySettings = settings): String {
        if (!cfg.configured) return ""
        val relay = URLEncoder.encode(cfg.baseUrl.trimEnd('/'), StandardCharsets.UTF_8.name())
        val key = URLEncoder.encode(cfg.key, StandardCharsets.UTF_8.name())
        val room = URLEncoder.encode(cfg.room, StandardCharsets.UTF_8.name())
        return "racelab://pit?relay=" + relay + "&room=" + room + "&key=" + key
    }
}
