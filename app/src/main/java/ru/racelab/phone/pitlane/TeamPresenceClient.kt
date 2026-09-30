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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

data class TeamDevicePresence(
    val slot: Int,
    val role: String,
    val online: Boolean,
    val ageMs: Long? = null
)

class TeamPresenceClient(private val config: PitTeamConfig) {
    companion object {
        private const val ONLINE_TTL_MS = 7_000L
        private const val LOOP_DELAY_MS = 1_200L
    }

    private val deviceId = UUID.randomUUID().toString()
    private val _devices = MutableStateFlow(
        (1..5).map { slot ->
            TeamDevicePresence(
                slot = slot,
                role = defaultRole(slot),
                online = false,
                ageMs = null
            )
        }
    )
    val devices: StateFlow<List<TeamDevicePresence>> = _devices

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            publishSelf()
            val next = (1..5).map { slot -> fetchSlot(slot) }
            _devices.value = next
            delay(LOOP_DELAY_MS)
        }
    }

    private fun publishSelf() {
        runCatching {
            val endpoint = apiUrl(presenceRoom(config.deviceSlot), update = true)
            val payload = JSONObject()
                .put("type", "teamPresence")
                .put("slot", config.deviceSlot.coerceIn(1, 5))
                .put("role", config.deviceRole.ifBlank { defaultRole(config.deviceSlot) }.take(24))
                .put("deviceId", deviceId)
                .put("sentAtMs", System.currentTimeMillis())
                .toString()
                .toByteArray(StandardCharsets.UTF_8)

            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 650
                readTimeout = 650
                doOutput = true
                useCaches = false
                setFixedLengthStreamingMode(payload.size)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Cache-Control", "no-store")
            }
            connection.outputStream.use { it.write(payload) }
            connection.responseCode
            connection.disconnect()
        }
    }

    private fun fetchSlot(slot: Int): TeamDevicePresence {
        val fallback = TeamDevicePresence(slot, defaultRole(slot), false, null)
        return runCatching {
            val connection = (URL(apiUrl(presenceRoom(slot), update = false)).openConnection() as HttpURLConnection).apply {
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
                return@runCatching fallback
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val json = JSONObject(body)
            val receivedAt = json.optLong("relayReceivedAtMs", json.optLong("sentAtMs", 0L))
            val age = if (receivedAt > 0L) (System.currentTimeMillis() - receivedAt).coerceAtLeast(0L) else Long.MAX_VALUE
            TeamDevicePresence(
                slot = slot,
                role = json.optString("role", defaultRole(slot)).ifBlank { defaultRole(slot) },
                online = age < ONLINE_TTL_MS,
                ageMs = age.takeIf { it != Long.MAX_VALUE }
            )
        }.getOrDefault(fallback)
    }

    private fun presenceRoom(slot: Int): String = config.room + "-device-" + slot.coerceIn(1, 5)

    private fun apiUrl(roomValue: String, update: Boolean): String {
        val base = config.relayUrl.trimEnd('/')
        val room = URLEncoder.encode(roomValue, StandardCharsets.UTF_8.name())
        val key = URLEncoder.encode(config.key, StandardCharsets.UTF_8.name())
        return if (update) {
            "$base/api/room/$room/update?key=$key"
        } else {
            "$base/api/room/$room?key=$key&t=${SystemClock.elapsedRealtime()}"
        }
    }
}

fun defaultRole(slot: Int): String = when (slot.coerceIn(1, 5)) {
    1 -> "Тимлид"
    2 -> "Инженер"
    3 -> "Механик"
    4 -> "Телеметрия"
    else -> "Команда"
}
