import ru.racelab.phone.core.*
import kotlin.math.abs

fun checkNear(actual: Double, expected: Double, eps: Double, name: String) {
    require(abs(actual - expected) <= eps) { "$name: $actual != $expected" }
}

fun main() {
    val previousHeading = GeoPoint(0.0, -0.00010, 0)
    val linePoint = GeoPoint(0.0, 0.0, 1000)
    val line = RaceGeometry.lineAt(linePoint, previousHeading, 35.0)
    val hit = RaceGeometry.crossing(
        GeoPoint(0.0, -0.00005, 1000),
        GeoPoint(0.0, 0.00005, 3000),
        line
    ) ?: error("No forward crossing")
    require(hit == 2000L) { "Interpolation failed: $hit" }
    val reverse = RaceGeometry.crossing(
        GeoPoint(0.0, 0.00005, 1000),
        GeoPoint(0.0, -0.00005, 3000),
        line
    )
    require(reverse == null) { "Reverse crossing accepted" }

    val engine = RaceEngine()
    engine.setStart(line)
    engine.onPoint(GeoPoint(0.0, -0.00005, 0), GeoPoint(0.0, 0.00005, 2000))
    val update = engine.onPoint(GeoPoint(0.0, -0.00005, 20_000), GeoPoint(0.0, 0.00005, 22_000))
    require(update.lapCompleted?.timeMs == 20_000L) { "Lap timing failed: ${update.lapCompleted}" }

    val nmea = NmeaParser.parse("\$GPRMC,123519,A,4807.038,N,01131.000,E,20.0,84.4,230394,003.1,W*6A", 1234)
        ?: error("NMEA RMC failed")
    checkNear(nmea.lat, 48.1173, 0.00001, "lat")
    checkNear(nmea.lon, 11.5166667, 0.00001, "lon")
    checkNear(nmea.speedMps ?: 0.0, 10.28888, 0.0001, "speed")

    val rpm = ObdParser.parse("41 0C 1A F8")?.rpm ?: error("RPM parse")
    checkNear(rpm, 1726.0, 0.01, "rpm")
    val speed = ObdParser.parse("41 0D 64")?.speedKmh ?: error("speed parse")
    checkNear(speed, 100.0, 0.01, "obd speed")
    val throttle = ObdParser.parse("41 11 80")?.throttlePct ?: error("throttle parse")
    checkNear(throttle, 50.196, 0.01, "throttle")
    val coolant = ObdParser.parse("41 05 7B")?.coolantC ?: error("coolant parse")
    checkNear(coolant, 83.0, 0.01, "coolant")

    println("RaceLab core self-test: PASS")
    println("crossing=$hit ms, lap=${update.lapCompleted!!.timeMs} ms, rpm=$rpm, speed=$speed, throttle=${"%.2f".format(throttle)}, coolant=$coolant")
}
