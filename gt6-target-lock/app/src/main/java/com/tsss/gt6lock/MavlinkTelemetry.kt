package com.tsss.gt6lock

import android.os.Process
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Tiny allocation-light MAVLink v1/v2 UDP telemetry receiver.
 * Listen-only: it never sends flight commands and never touches the tracking thread.
 */
class MavlinkTelemetry(private val port: Int = 14550) {
    @Volatile var connected = false
        private set
    @Volatile var lastRxMs = 0L
        private set
    @Volatile var rxHz = 0f
        private set

    @Volatile var sysId = 0
    @Volatile var compId = 0
    @Volatile var vehicleType = 0
    @Volatile var customMode = 0L
    @Volatile var armed = false

    @Volatile var rollDeg = 0f
    @Volatile var pitchDeg = 0f
    @Volatile var yawDeg = 0f
    @Volatile var rollRateDeg = 0f
    @Volatile var pitchRateDeg = 0f
    @Volatile var yawRateDeg = 0f

    @Volatile var airspeed = 0f
    @Volatile var groundSpeed = 0f
    @Volatile var headingDeg = 0f
    @Volatile var throttlePct = 0
    @Volatile var altitudeM = 0f
    @Volatile var relativeAltitudeM = 0f
    @Volatile var climbMs = 0f

    @Volatile var gpsFix = 0
    @Volatile var satellites = 0
    @Volatile var batteryV = 0f
    @Volatile var batteryA = 0f
    @Volatile var batteryPct = -1

    @Volatile var latitude = 0.0
    @Volatile var longitude = 0.0

    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "GT6-MAVLink-UDP", isDaemon = true) {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            receiveLoop()
        }
    }

    fun stop() {
        running = false
        socket?.close()
        socket = null
        worker = null
        connected = false
    }

    fun ageMs(now: Long = SystemClock.elapsedRealtime()): Long =
        if (lastRxMs == 0L) Long.MAX_VALUE else max(0L, now - lastRxMs)

    fun modeName(): String {
        val m = customMode.toInt()
        return when (vehicleType) {
            1 -> when (m) { // ArduPlane
                0 -> "MANUAL"; 1 -> "CIRCLE"; 2 -> "STABILIZE"; 3 -> "TRAINING"
                4 -> "ACRO"; 5 -> "FBWA"; 6 -> "FBWB"; 7 -> "CRUISE"
                8 -> "AUTOTUNE"; 10 -> "AUTO"; 11 -> "RTL"; 12 -> "LOITER"
                13 -> "TAKEOFF"; 14 -> "AVOID"; 15 -> "GUIDED"; 16 -> "INIT"
                17 -> "QSTAB"; 18 -> "QHOVER"; 19 -> "QLOITER"; 20 -> "QLAND"
                21 -> "QRTL"; 22 -> "QAUTOTUNE"; 23 -> "QACRO"; 24 -> "THERMAL"
                25 -> "LOITERQLAND"; 26 -> "AUTOLAND"; else -> "M$m"
            }
            2, 3, 4, 13, 14, 15 -> when (m) { // ArduCopter-like
                0 -> "STABILIZE"; 1 -> "ACRO"; 2 -> "ALTHOLD"; 3 -> "AUTO"
                4 -> "GUIDED"; 5 -> "LOITER"; 6 -> "RTL"; 7 -> "CIRCLE"
                9 -> "LAND"; 11 -> "DRIFT"; 13 -> "SPORT"; 15 -> "AUTOTUNE"
                16 -> "POSHOLD"; 17 -> "BRAKE"; 18 -> "THROW"; 20 -> "GUIDED_NOGPS"
                21 -> "SMART_RTL"; 22 -> "FLOWHOLD"; 23 -> "FOLLOW"
                else -> "M$m"
            }
            10 -> when (m) { // Rover
                0 -> "MANUAL"; 1 -> "ACRO"; 3 -> "STEERING"; 4 -> "HOLD"
                5 -> "LOITER"; 6 -> "FOLLOW"; 7 -> "SIMPLE"; 10 -> "AUTO"
                11 -> "RTL"; 12 -> "SMART_RTL"; 15 -> "GUIDED"; 16 -> "INIT"
                else -> "M$m"
            }
            else -> "M$m"
        }
    }

    private fun receiveLoop() {
        val buffer = ByteArray(4096)
        var count = 0
        var windowStart = SystemClock.elapsedRealtime()
        try {
            DatagramSocket(port).use { s ->
                socket = s
                s.receiveBufferSize = 256 * 1024
                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    s.receive(packet)
                    val now = SystemClock.elapsedRealtime()
                    parseDatagram(buffer, packet.offset, packet.length)
                    lastRxMs = now
                    connected = true
                    count++
                    val dt = now - windowStart
                    if (dt >= 1000L) {
                        rxHz = count * 1000f / dt.coerceAtLeast(1L)
                        count = 0
                        windowStart = now
                    }
                }
            }
        } catch (_: SocketException) {
            // Expected during stop()/socket close.
        } catch (_: Throwable) {
            connected = false
        } finally {
            socket = null
        }
    }

    private fun parseDatagram(b: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len
        while (i < end) {
            val magic = u8(b, i)
            if (magic == 0xFE) {
                if (i + 6 > end) break
                val plen = u8(b, i + 1)
                val frameLen = 6 + plen + 2
                if (i + frameLen > end) break
                val sid = u8(b, i + 3)
                val cid = u8(b, i + 4)
                val msgId = u8(b, i + 5)
                decode(msgId, b, i + 6, plen, sid, cid)
                i += frameLen
            } else if (magic == 0xFD) {
                if (i + 10 > end) break
                val plen = u8(b, i + 1)
                val incompat = u8(b, i + 2)
                val signed = (incompat and 0x01) != 0
                val frameLen = 10 + plen + 2 + if (signed) 13 else 0
                if (i + frameLen > end) break
                val sid = u8(b, i + 5)
                val cid = u8(b, i + 6)
                val msgId = u8(b, i + 7) or
                    (u8(b, i + 8) shl 8) or
                    (u8(b, i + 9) shl 16)
                decode(msgId, b, i + 10, plen, sid, cid)
                i += frameLen
            } else {
                i++
            }
        }
    }

    private fun decode(id: Int, b: ByteArray, p: Int, n: Int, sid: Int, cid: Int) {
        sysId = sid
        compId = cid
        when (id) {
            0 -> if (n >= 9) { // HEARTBEAT
                customMode = u32(b, p)
                vehicleType = u8(b, p + 4)
                armed = (u8(b, p + 6) and 0x80) != 0
            }
            1 -> if (n >= 19) { // SYS_STATUS
                val mv = u16(b, p + 14)
                val ca = i16(b, p + 16)
                val pct = i8(b, p + 18)
                if (mv != 0xFFFF) batteryV = mv / 1000f
                if (ca != -1) batteryA = ca / 100f
                batteryPct = pct
            }
            24 -> if (n >= 30) { // GPS_RAW_INT
                latitude = i32(b, p + 8) / 1e7
                longitude = i32(b, p + 12) / 1e7
                gpsFix = u8(b, p + 28)
                satellites = u8(b, p + 29)
            }
            30 -> if (n >= 28) { // ATTITUDE
                rollDeg = rad2deg(f32(b, p + 4))
                pitchDeg = rad2deg(f32(b, p + 8))
                yawDeg = wrap360(rad2deg(f32(b, p + 12)))
                rollRateDeg = rad2deg(f32(b, p + 16))
                pitchRateDeg = rad2deg(f32(b, p + 20))
                yawRateDeg = rad2deg(f32(b, p + 24))
            }
            33 -> if (n >= 28) { // GLOBAL_POSITION_INT
                latitude = i32(b, p + 4) / 1e7
                longitude = i32(b, p + 8) / 1e7
                altitudeM = i32(b, p + 12) / 1000f
                relativeAltitudeM = i32(b, p + 16) / 1000f
                val hdg = u16(b, p + 26)
                if (hdg != 0xFFFF) headingDeg = hdg / 100f
            }
            74 -> if (n >= 20) { // VFR_HUD
                airspeed = f32(b, p)
                groundSpeed = f32(b, p + 4)
                headingDeg = wrap360(i16(b, p + 8).toFloat())
                throttlePct = u16(b, p + 10).coerceIn(0, 100)
                altitudeM = f32(b, p + 12)
                climbMs = f32(b, p + 16)
            }
        }
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun i8(b: ByteArray, i: Int) = b[i].toInt()
    private fun u16(b: ByteArray, i: Int): Int =
        u8(b, i) or (u8(b, i + 1) shl 8)
    private fun i16(b: ByteArray, i: Int): Int =
        u16(b, i).toShort().toInt()
    private fun i32(b: ByteArray, i: Int): Int =
        u8(b, i) or (u8(b, i + 1) shl 8) or
            (u8(b, i + 2) shl 16) or (u8(b, i + 3) shl 24)
    private fun u32(b: ByteArray, i: Int): Long = i32(b, i).toLong() and 0xFFFFFFFFL
    private fun f32(b: ByteArray, i: Int): Float = Float.fromBits(i32(b, i))
    private fun rad2deg(v: Float) = Math.toDegrees(v.toDouble()).toFloat()
    private fun wrap360(v0: Float): Float {
        var v = v0 % 360f
        if (v < 0f) v += 360f
        return v
    }
}
