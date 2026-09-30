package ru.racelab.phone.pitlane

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class TeamCarEntry(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val relayUrl: String,
    val room: String,
    val key: String
) {
    val valid: Boolean
        get() = relayUrl.startsWith("https://") && room.isNotBlank() && key.isNotBlank()

    fun toPitConfig(deviceSlot: Int, deviceRole: String): PitTeamConfig = PitTeamConfig(
        relayUrl = relayUrl,
        room = room,
        key = key,
        deviceSlot = deviceSlot,
        deviceRole = deviceRole
    )
}

object TeamGarageRepository {
    private const val PREFS = "racelab_team_garage"
    private const val CARS = "cars_json"
    const val MAX_CARS = 8

    fun load(context: Context): List<TeamCarEntry> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CARS, null)
            ?: return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val entry = TeamCarEntry(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        label = o.optString("label"),
                        relayUrl = o.optString("relayUrl"),
                        room = o.optString("room"),
                        key = o.optString("key")
                    )
                    if (entry.valid) add(entry)
                }
            }.take(MAX_CARS)
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, cars: List<TeamCarEntry>) {
        val array = JSONArray()
        cars.filter { it.valid }.take(MAX_CARS).forEach { car ->
            array.put(
                JSONObject()
                    .put("id", car.id)
                    .put("label", car.label.trim().take(32))
                    .put("relayUrl", car.relayUrl.trim().trimEnd('/'))
                    .put("room", car.room.trim())
                    .put("key", car.key.trim())
            )
        }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(CARS, array.toString())
            .apply()
    }

    fun ensurePrimary(context: Context, config: PitTeamConfig): List<TeamCarEntry> {
        if (!config.valid) return load(context)
        val current = load(context).toMutableList()
        val index = current.indexOfFirst { it.room == config.room && it.relayUrl.trimEnd('/') == config.relayUrl.trimEnd('/') }
        val primary = TeamCarEntry(
            id = if (index >= 0) current[index].id else "primary",
            label = if (index >= 0) current[index].label.ifBlank { "Основная машина" } else "Основная машина",
            relayUrl = config.relayUrl,
            room = config.room,
            key = config.key
        )
        if (index >= 0) current[index] = primary else current.add(0, primary)
        val result = current.distinctBy { it.relayUrl.trimEnd('/') + "|" + it.room }.take(MAX_CARS)
        save(context, result)
        return result
    }
}
