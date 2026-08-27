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

data class ObdReading(
    val rpm: Double? = null,
    val speedKmh: Double? = null,
    val throttlePct: Double? = null,
    val coolantC: Double? = null
)

object ObdParser {
    fun parse(raw: String): ObdReading? {
        val clean = raw.uppercase()
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(">", " ")
            .replace(Regex("[^0-9A-F ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.isBlank()) return null
        val bytes = clean.split(' ').mapNotNull { it.toIntOrNull(16) }
        for (i in 0 until bytes.size - 2) {
            if (bytes[i] != 0x41) continue
            when (bytes[i + 1]) {
                0x0C -> if (i + 3 < bytes.size) {
                    val rpm = ((bytes[i + 2] * 256 + bytes[i + 3]) / 4.0)
                    return ObdReading(rpm = rpm)
                }
                0x0D -> return ObdReading(speedKmh = bytes[i + 2].toDouble())
                0x11 -> return ObdReading(throttlePct = bytes[i + 2] * 100.0 / 255.0)
                0x05 -> return ObdReading(coolantC = (bytes[i + 2] - 40).toDouble())
            }
        }
        return null
    }
}
