package ru.racelab.phone.core

object NmeaParser {
    private fun coord(raw: String, hemi: String): Double? {
        val v = raw.toDoubleOrNull() ?: return null
        val deg = (v / 100.0).toInt()
        val min = v - deg * 100.0
        var out = deg + min / 60.0
        if (hemi == "S" || hemi == "W") out = -out
        return out
    }

    fun parse(line: String, ts: Long = System.currentTimeMillis()): GeoPoint? {
        val p = line.trim().substringBefore('*').split(',')
        if (p.isEmpty()) return null
        return when {
            p[0].endsWith("RMC") && p.size >= 9 -> {
                if (p[2] != "A") return null
                val lat = coord(p[3], p[4]) ?: return null
                val lon = coord(p[5], p[6]) ?: return null
                val speedMps = p[7].toDoubleOrNull()?.times(0.514444)
                val heading = p[8].toDoubleOrNull()
                GeoPoint(lat, lon, ts, speedMps, heading, source = "ble_nmea")
            }
            p[0].endsWith("GGA") && p.size >= 10 -> {
                val quality = p[6].toIntOrNull() ?: 0
                if (quality <= 0) return null
                val lat = coord(p[2], p[3]) ?: return null
                val lon = coord(p[4], p[5]) ?: return null
                val alt = p[9].toDoubleOrNull()
                GeoPoint(lat, lon, ts, altitudeM = alt, source = "ble_nmea")
            }
            else -> null
        }
    }
}

data class NmeaQuality(
    val satellites: Int? = null,
    val pdop: Double? = null,
    val hdop: Double? = null,
    val vdop: Double? = null
)

object NmeaQualityParser {
    fun parse(line: String): NmeaQuality? {
        val p = line.trim().substringBefore('*').split(',')
        if (p.isEmpty()) return null
        return when {
            p[0].endsWith("GGA") && p.size >= 10 -> {
                NmeaQuality(
                    satellites = p[7].toIntOrNull(),
                    hdop = p[8].toDoubleOrNull()
                )
            }
            p[0].endsWith("GSA") && p.size >= 18 -> {
                NmeaQuality(
                    pdop = p.getOrNull(p.size - 3)?.toDoubleOrNull(),
                    hdop = p.getOrNull(p.size - 2)?.toDoubleOrNull(),
                    vdop = p.getOrNull(p.size - 1)?.toDoubleOrNull()
                )
            }
            else -> null
        }
    }
}

data class ObdReading(
    val rpm: Double? = null,
    val speedKmh: Double? = null,
    val throttlePct: Double? = null,
    val coolantC: Double? = null,
    val engineLoadPct: Double? = null,
    val intakeC: Double? = null,
    val mapKpa: Double? = null,
    val timingDeg: Double? = null,
    val mafGps: Double? = null,
    val voltageV: Double? = null,
    val oilTempC: Double? = null,
    val fuelPressureKpa: Double? = null,
    val shortTrimPct: Double? = null,
    val longTrimPct: Double? = null
)

object ObdParser {
    fun cleanBytes(raw: String): List<Int> {
        val clean = raw.uppercase()
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(">", " ")
            .replace(Regex("[^0-9A-F ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.isBlank()) return emptyList()
        return clean.split(' ').mapNotNull { it.toIntOrNull(16) }
    }

    fun parse(raw: String): ObdReading? {
        val bytes = cleanBytes(raw)
        for (i in 0 until bytes.size - 2) {
            if (bytes[i] != 0x41) continue
            val pid = bytes[i + 1]
            val a = bytes.getOrNull(i + 2) ?: continue
            val b = bytes.getOrNull(i + 3)
            return when (pid) {
                0x04 -> ObdReading(engineLoadPct = a * 100.0 / 255.0)
                0x05 -> ObdReading(coolantC = (a - 40).toDouble())
                0x06 -> ObdReading(shortTrimPct = a * 100.0 / 128.0 - 100.0)
                0x07 -> ObdReading(longTrimPct = a * 100.0 / 128.0 - 100.0)
                0x0A -> ObdReading(fuelPressureKpa = a * 3.0)
                0x0B -> ObdReading(mapKpa = a.toDouble())
                0x0C -> if (b != null) ObdReading(rpm = (a * 256 + b) / 4.0) else null
                0x0D -> ObdReading(speedKmh = a.toDouble())
                0x0E -> ObdReading(timingDeg = a / 2.0 - 64.0)
                0x0F -> ObdReading(intakeC = (a - 40).toDouble())
                0x10 -> if (b != null) ObdReading(mafGps = (a * 256 + b) / 100.0) else null
                0x11 -> ObdReading(throttlePct = a * 100.0 / 255.0)
                0x42 -> if (b != null) ObdReading(voltageV = (a * 256 + b) / 1000.0) else null
                0x5C -> ObdReading(oilTempC = (a - 40).toDouble())
                else -> null
            }
        }
        return null
    }

    fun parseSupportedPids(raw: String, requestBase: Int): Set<String> {
        val bytes = cleanBytes(raw)
        val responsePid = requestBase and 0xFF
        for (i in 0 until bytes.size - 5) {
            if (bytes[i] == 0x41 && bytes[i + 1] == responsePid) {
                val bits = ((bytes[i + 2].toLong() shl 24) or
                    (bytes[i + 3].toLong() shl 16) or
                    (bytes[i + 4].toLong() shl 8) or bytes[i + 5].toLong())
                val out = mutableSetOf<String>()
                for (bit in 0 until 32) {
                    if ((bits and (1L shl (31 - bit))) != 0L) {
                        out += "01" + "%02X".format(requestBase + bit + 1)
                    }
                }
                return out
            }
        }
        return emptySet()
    }
}
