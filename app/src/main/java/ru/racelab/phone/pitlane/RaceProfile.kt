package ru.racelab.phone.pitlane

import android.content.Context

data class RaceProfile(
    val teamName: String = "",
    val driverName: String = "",
    val carNumber: String = "",
    val carName: String = "",
    val raceClass: String = "",
    val raceTargetLaps: Int = 0,
    val resourceReservePct: Double = 5.0
) {
    val displayTeam: String get() = teamName.ifBlank { "Команда" }
    val displayDriver: String get() = driverName.ifBlank { "Пилот не указан" }
    val displayNumber: String get() = carNumber.ifBlank { "—" }
    val displayCar: String get() = carName.ifBlank { "Машина не указана" }
    val displayClass: String get() = raceClass.ifBlank { "Класс не указан" }
}

object RaceProfileRepository {
    private const val PREFS = "racelab_race_profile"
    private const val TEAM = "team_name"
    private const val DRIVER = "driver_name"
    private const val NUMBER = "car_number"
    private const val CAR = "car_name"
    private const val CLASS = "race_class"
    private const val TARGET_LAPS = "race_target_laps"
    private const val RESOURCE_RESERVE = "resource_reserve_pct"

    fun load(context: Context): RaceProfile {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return RaceProfile(
            teamName = prefs.getString(TEAM, "")?.trim().orEmpty(),
            driverName = prefs.getString(DRIVER, "")?.trim().orEmpty(),
            carNumber = prefs.getString(NUMBER, "")?.trim().orEmpty(),
            carName = prefs.getString(CAR, "")?.trim().orEmpty(),
            raceClass = prefs.getString(CLASS, "")?.trim().orEmpty(),
            raceTargetLaps = prefs.getInt(TARGET_LAPS, 0).coerceIn(0, 9999),
            resourceReservePct = prefs.getFloat(RESOURCE_RESERVE, 5.0f).toDouble().coerceIn(0.0, 25.0)
        )
    }

    fun save(context: Context, profile: RaceProfile) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(TEAM, profile.teamName.trim().take(48))
            .putString(DRIVER, profile.driverName.trim().take(48))
            .putString(NUMBER, profile.carNumber.trim().take(8))
            .putString(CAR, profile.carName.trim().take(64))
            .putString(CLASS, profile.raceClass.trim().take(32))
            .putInt(TARGET_LAPS, profile.raceTargetLaps.coerceIn(0, 9999))
            .putFloat(RESOURCE_RESERVE, profile.resourceReservePct.coerceIn(0.0, 25.0).toFloat())
            .apply()
    }
}
