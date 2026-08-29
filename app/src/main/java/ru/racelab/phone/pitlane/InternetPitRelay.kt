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

object InternetPitRelay {
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "RaceLab-InternetPitRelay").apply { isDaemon = true }
    }

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
            task = executor.scheduleWithFixedDelay(
                { publishLatest() },
                0,
                100,
                TimeUnit.MILLISECONDS
            )
        }
    }

    fun stop() {
        task?.cancel(false)
        task = null
        RaceRuntime.setInternetPitRelayStatus(
            enabled = settings.enabled,
            configured = settings.configured,
            status = "STOPPED",
            viewerUrl = viewerUrl(settings),
            lastSuccessMs = lastSuccessMs
        )
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
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 1_500
                readTimeout = 1_500
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Cache-Control", "no-store")
            }
            connection.outputStream.use { out ->
                out.write(body.toByteArray(StandardCharsets.UTF_8))
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
