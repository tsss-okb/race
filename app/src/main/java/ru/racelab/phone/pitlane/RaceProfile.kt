package ru.racelab.phone.pitlane

import android.content.Context

data class RaceProfile(
    val teamName: String = "",
    val carName: String = "",
    val raceClass: String = ""
) {
    val displayTeam: String get() = teamName.ifBlank { "Команда" }
    val displayCar: String get() = carName.ifBlank { "Машина не указана" }
    val displayClass: String get() = raceClass.ifBlank { "Класс не указан" }
}

object RaceProfileRepository {
    private const val PREFS = "racelab_race_profile"
    private const val TEAM = "team_name"
    private const val CAR = "car_name"
    private const val CLASS = "race_class"

    fun load(context: Context): RaceProfile {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return RaceProfile(
            teamName = prefs.getString(TEAM, "")?.trim().orEmpty(),
            carName = prefs.getString(CAR, "")?.trim().orEmpty(),
            raceClass = prefs.getString(CLASS, "")?.trim().orEmpty()
        )
    }

    fun save(context: Context, profile: RaceProfile) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(TEAM, profile.teamName.trim().take(48))
            .putString(CAR, profile.carName.trim().take(64))
            .putString(CLASS, profile.raceClass.trim().take(32))
            .apply()
    }
}
