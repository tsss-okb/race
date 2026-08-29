package ru.racelab.phone

import org.junit.Assert.*
import org.junit.Test
import ru.racelab.phone.canbus.*
import ru.racelab.phone.core.*
import kotlin.math.abs

class CoreLogicTest {
    @Test
    fun forwardCrossingInterpolatesAndReverseRejected() {
        val previousHeading = GeoPoint(0.0, -0.00010, 0)
        val linePoint = GeoPoint(0.0, 0.0, 1000)
        val line = RaceGeometry.lineAt(linePoint, previousHeading, 35.0)

        val hit = RaceGeometry.crossing(
            GeoPoint(0.0, -0.00005, 1000),
            GeoPoint(0.0, 0.00005, 3000),
            line
        )
        assertEquals(2000L, hit)

        val reverse = RaceGeometry.crossing(
            GeoPoint(0.0, 0.00005, 1000),
            GeoPoint(0.0, -0.00005, 3000),
            line
        )
        assertNull(reverse)
    }

    @Test
    fun nmeaRmcAndQualityParse() {
        val point = NmeaParser.parse(
            "\$GPRMC,123519,A,4807.038,N,01131.000,E,20.0,84.4,230394,003.1,W*6A",
            1234L
        )
        requireNotNull(point)
        assertTrue(abs(point.lat - 48.1173) < 0.00001)
        assertTrue(abs(point.lon - 11.5166667) < 0.00001)
        assertTrue(abs((point.speedMps ?: 0.0) - 10.28888) < 0.0001)

        val gga = NmeaQualityParser.parse(
            "\$GPGGA,123520,4807.038,N,01131.000,E,1,12,0.8,545.4,M,46.9,M,,*47"
        )
        requireNotNull(gga)
        assertEquals(12, gga.satellites)
        assertEquals(0.8, gga.hdop!!, 0.0001)

        val gsa = NmeaQualityParser.parse(
            "\$GPGSA,A,3,04,05,09,12,24,25,29,31,,,,,1.5,0.9,1.2*00"
        )
        requireNotNull(gsa)
        assertEquals(1.5, gsa.pdop!!, 0.0001)
        assertEquals(0.9, gsa.hdop!!, 0.0001)
        assertEquals(1.2, gsa.vdop!!, 0.0001)
    }

    @Test
    fun obdExtendedPidsParse() {
        assertEquals(1726.0, ObdParser.parse("41 0C 1A F8")!!.rpm!!, 0.01)
        assertEquals(100.0, ObdParser.parse("41 0D 64")!!.speedKmh!!, 0.01)
        assertEquals(50.196, ObdParser.parse("41 04 80")!!.engineLoadPct!!, 0.01)
        assertEquals(13.5, ObdParser.parse("41 42 34 BC")!!.voltageV!!, 0.01)
        assertEquals(80.0, ObdParser.parse("41 5C 78")!!.oilTempC!!, 0.01)
    }

    @Test
    fun slcanParsesStandardAndExtendedFrames() {
        val standard = SlcanParser.parse("t12381122334455667788", 100L)
        requireNotNull(standard)
        assertEquals(0x123L, standard.id)
        assertFalse(standard.extended)
        assertEquals(8, standard.data.size)
        assertEquals(0x11, standard.data[0].toInt() and 0xFF)

        val extended = SlcanParser.parse("T18DAF1103AABBCC", 200L)
        requireNotNull(extended)
        assertEquals(0x18DAF110L, extended.id)
        assertTrue(extended.extended)
        assertEquals(3, extended.data.size)
    }

    @Test
    fun canSignalDecoderLittleEndianSignedAndScale() {
        val frame = CanFrame(
            timestampMs = 1L,
            id = 0x321,
            extended = false,
            rtr = false,
            data = byteArrayOf(0x10, 0x27, 0xF6.toByte(), 0xFF.toByte())
        )

        val unsigned = CanSignal(
            name = "speed",
            canId = 0x321,
            startBit = 0,
            bitLength = 16,
            byteOrder = CanByteOrder.LITTLE_ENDIAN,
            scale = 0.01,
            unit = "km/h"
        )
        assertEquals(100.0, CanSignalDecoder.decode(frame, unsigned)!!, 0.0001)

        val signed = CanSignal(
            name = "steer",
            canId = 0x321,
            startBit = 16,
            bitLength = 16,
            byteOrder = CanByteOrder.LITTLE_ENDIAN,
            signed = true,
            scale = 0.1
        )
        assertEquals(-1.0, CanSignalDecoder.decode(frame, signed)!!, 0.0001)
    }
}
