package ru.racelab.phone.canbus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CanFrame(
    val timestampMs: Long,
    val id: Long,
    val extended: Boolean,
    val rtr: Boolean,
    val data: ByteArray
)

enum class CanByteOrder { LITTLE_ENDIAN, BIG_ENDIAN }

enum class CanChannel {
    NONE,
    RPM,
    VEHICLE_SPEED_KMH,
    THROTTLE_PCT,
    GEAR,
    STEERING_DEG,
    BRAKE_PRESSURE_BAR,
    WHEEL_FL_KMH,
    WHEEL_FR_KMH,
    WHEEL_RL_KMH,
    WHEEL_RR_KMH,
    COOLANT_C,
    OIL_TEMP_C,
    EV_SOC,
    EV_BATTERY_KW,
    EV_MOTOR_KW,
    EV_REGEN_KW,
    EV_BATTERY_TEMP_C,
    EV_INVERTER_TEMP_C,
    PIT_BUTTON
}

data class CanSignal(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val canId: Long,
    val extended: Boolean = false,
    val startBit: Int = 0,
    val bitLength: Int = 8,
    val byteOrder: CanByteOrder = CanByteOrder.LITTLE_ENDIAN,
    val signed: Boolean = false,
    val scale: Double = 1.0,
    val offset: Double = 0.0,
    val unit: String = "",
    val channel: CanChannel = CanChannel.NONE,
    val enabled: Boolean = true
)

data class CanSignalValue(
    val signal: CanSignal,
    val value: Double,
    val timestampMs: Long
)

enum class CanBitrate(val bitsPerSecond: Int, val slcanCommand: String) {
    KBPS_10(10_000, "S0"),
    KBPS_20(20_000, "S1"),
    KBPS_50(50_000, "S2"),
    KBPS_100(100_000, "S3"),
    KBPS_125(125_000, "S4"),
    KBPS_250(250_000, "S5"),
    KBPS_500(500_000, "S6"),
    KBPS_800(800_000, "S7"),
    KBPS_1000(1_000_000, "S8")
}

object SlcanParser {
    fun parse(lineInput: String, timestampMs: Long = System.currentTimeMillis()): CanFrame? {
        val line = lineInput.trim()
        if (line.length < 5) return null
        val type = line[0]
        val extended = type == 'T' || type == 'R'
        val rtr = type == 'r' || type == 'R'
        if (type !in charArrayOf('t', 'T', 'r', 'R')) return null
        val idChars = if (extended) 8 else 3
        if (line.length < 1 + idChars + 1) return null
        val id = line.substring(1, 1 + idChars).toLongOrNull(16) ?: return null
        val dlc = line.substring(1 + idChars, 2 + idChars).toIntOrNull(16)?.coerceIn(0, 8) ?: return null
        if (rtr) return CanFrame(timestampMs, id, extended, true, ByteArray(0))
        val start = 2 + idChars
        if (line.length < start + dlc * 2) return null
        val data = ByteArray(dlc)
        for (i in 0 until dlc) {
            data[i] = line.substring(start + i * 2, start + i * 2 + 2).toIntOrNull(16)?.toByte() ?: return null
        }
        return CanFrame(timestampMs, id, extended, false, data)
    }
}

object CanSignalDecoder {
    fun decode(frame: CanFrame, signal: CanSignal): Double? {
        if (!signal.enabled || signal.canId != frame.id || signal.extended != frame.extended || frame.rtr) return null
        val bits = signal.bitLength.coerceIn(1, 32)
        if (signal.startBit < 0 || signal.startBit + bits > frame.data.size * 8) return null

        val unsigned = when (signal.byteOrder) {
            CanByteOrder.LITTLE_ENDIAN -> {
                var all = 0UL
                frame.data.forEachIndexed { i, b ->
                    all = all or ((b.toInt() and 0xFF).toULong() shl (i * 8))
                }
                val mask = if (bits == 64) ULong.MAX_VALUE else (1UL shl bits) - 1UL
                (all shr signal.startBit) and mask
            }
            CanByteOrder.BIG_ENDIAN -> {
                var value = 0UL
                for (i in 0 until bits) {
                    val pos = signal.startBit + i
                    val byteIndex = pos / 8
                    val bitInByte = 7 - (pos % 8)
                    val bit = ((frame.data[byteIndex].toInt() ushr bitInByte) and 1).toULong()
                    value = (value shl 1) or bit
                }
                value
            }
        }

        var signedValue = unsigned.toLong()
        if (signal.signed) {
            val signBit = 1UL shl (bits - 1)
            if ((unsigned and signBit) != 0UL) {
                signedValue = (unsigned - (1UL shl bits)).toLong()
            }
        }
        return signedValue * signal.scale + signal.offset
    }
}

object CanSignalRepository {
    private const val PREFS = "racelab_can_signals"
    private const val KEY = "signals"

    fun list(context: Context): List<CanSignal> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) add(fromJson(arr.getJSONObject(i)))
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, signal: CanSignal) {
        val list = list(context).toMutableList()
        val index = list.indexOfFirst { it.id == signal.id }
        if (index >= 0) list[index] = signal else list += signal
        persist(context, list)
    }

    fun delete(context: Context, id: String) {
        persist(context, list(context).filterNot { it.id == id })
    }

    private fun persist(context: Context, signals: List<CanSignal>) {
        val arr = JSONArray()
        signals.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("canId", s.canId)
                    .put("extended", s.extended)
                    .put("startBit", s.startBit)
                    .put("bitLength", s.bitLength)
                    .put("byteOrder", s.byteOrder.name)
                    .put("signed", s.signed)
                    .put("scale", s.scale)
                    .put("offset", s.offset)
                    .put("unit", s.unit)
                    .put("channel", s.channel.name)
                    .put("enabled", s.enabled)
            )
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    private fun fromJson(o: JSONObject) = CanSignal(
        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
        name = o.optString("name", "CAN signal"),
        canId = o.optLong("canId", 0L),
        extended = o.optBoolean("extended", false),
        startBit = o.optInt("startBit", 0),
        bitLength = o.optInt("bitLength", 8).coerceIn(1, 32),
        byteOrder = runCatching { CanByteOrder.valueOf(o.optString("byteOrder", CanByteOrder.LITTLE_ENDIAN.name)) }
            .getOrDefault(CanByteOrder.LITTLE_ENDIAN),
        signed = o.optBoolean("signed", false),
        scale = o.optDouble("scale", 1.0),
        offset = o.optDouble("offset", 0.0),
        unit = o.optString("unit"),
        channel = runCatching { CanChannel.valueOf(o.optString("channel", CanChannel.NONE.name)) }
            .getOrDefault(CanChannel.NONE),
        enabled = o.optBoolean("enabled", true)
    )
}
