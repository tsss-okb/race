package ru.racelab.phone.obd

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.racelab.phone.core.ObdParser
import java.util.UUID

enum class EvChannel {
    NONE, SOC, BATTERY_POWER_KW, MOTOR_POWER_KW, REGEN_KW, BATTERY_TEMP_C, INVERTER_TEMP_C
}

data class CustomPid(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val request: String,
    val responsePrefix: String,
    val byteCount: Int = 1,
    val scale: Double = 1.0,
    val offset: Double = 0.0,
    val signed: Boolean = false,
    val unit: String = "",
    val evChannel: EvChannel = EvChannel.NONE,
    val enabled: Boolean = true
)

object CustomPidRepository {
    private const val PREFS = "racelab_custom_pids"
    private const val KEY = "pids"

    fun list(context: Context): List<CustomPid> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) add(fromJson(arr.getJSONObject(i)))
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, pid: CustomPid) {
        val pids = list(context).toMutableList()
        val idx = pids.indexOfFirst { it.id == pid.id }
        if (idx >= 0) pids[idx] = pid else pids += pid
        persist(context, pids)
    }

    fun delete(context: Context, id: String) = persist(context, list(context).filterNot { it.id == id })

    fun parse(raw: String, pid: CustomPid): Double? {
        val bytes = ObdParser.cleanBytes(raw)
        val prefix = pid.responsePrefix.trim().replace(" ", "").chunked(2).mapNotNull { it.toIntOrNull(16) }
        if (prefix.isEmpty()) return null
        val start = findSequence(bytes, prefix)
        if (start < 0) return null
        val dataStart = start + prefix.size
        if (dataStart + pid.byteCount > bytes.size || pid.byteCount !in 1..4) return null
        var rawValue = 0L
        for (i in 0 until pid.byteCount) rawValue = (rawValue shl 8) or bytes[dataStart + i].toLong()
        if (pid.signed) {
            val bits = pid.byteCount * 8
            val signBit = 1L shl (bits - 1)
            if ((rawValue and signBit) != 0L) rawValue -= 1L shl bits
        }
        return rawValue * pid.scale + pid.offset
    }

    private fun findSequence(haystack: List<Int>, needle: List<Int>): Int {
        if (needle.size > haystack.size) return -1
        for (i in 0..haystack.size - needle.size) {
            var ok = true
            for (j in needle.indices) if (haystack[i + j] != needle[j]) { ok = false; break }
            if (ok) return i
        }
        return -1
    }

    private fun persist(context: Context, pids: List<CustomPid>) {
        val arr = JSONArray()
        pids.forEach { p ->
            arr.put(JSONObject()
                .put("id", p.id).put("name", p.name).put("request", p.request)
                .put("responsePrefix", p.responsePrefix).put("byteCount", p.byteCount)
                .put("scale", p.scale).put("offset", p.offset).put("signed", p.signed)
                .put("unit", p.unit).put("evChannel", p.evChannel.name).put("enabled", p.enabled))
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    private fun fromJson(o: JSONObject) = CustomPid(
        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
        name = o.optString("name", "Custom PID"),
        request = o.optString("request"),
        responsePrefix = o.optString("responsePrefix"),
        byteCount = o.optInt("byteCount", 1).coerceIn(1, 4),
        scale = o.optDouble("scale", 1.0),
        offset = o.optDouble("offset", 0.0),
        signed = o.optBoolean("signed", false),
        unit = o.optString("unit"),
        evChannel = runCatching { EvChannel.valueOf(o.optString("evChannel", "NONE")) }.getOrDefault(EvChannel.NONE),
        enabled = o.optBoolean("enabled", true)
    )
}
