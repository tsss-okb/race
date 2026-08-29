package ru.racelab.phone.pitlane

import android.content.Context
import java.security.SecureRandom

data class InternetPitRelaySettings(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val room: String = "",
    val key: String = ""
) {
    val configured: Boolean
        get() = baseUrl.startsWith("https://") && room.isNotBlank() && key.isNotBlank()
}

object InternetPitRelaySettingsRepository {
    const val DEFAULT_BASE_URL = "https://racelab-pit-relay.irradiated-tree.workers.dev"
    private const val PREFS = "racelab_pit_internet"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_ROOM = "room"
    private const val KEY_SECRET = "secret"

    fun load(context: Context): InternetPitRelaySettings {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val room = prefs.getString(KEY_ROOM, null)?.takeIf { it.isNotBlank() } ?: generateRoom()
        val key = prefs.getString(KEY_SECRET, null)?.takeIf { it.isNotBlank() } ?: generateKey()
        if (!prefs.contains(KEY_ROOM) || !prefs.contains(KEY_SECRET)) {
            prefs.edit().putString(KEY_ROOM, room).putString(KEY_SECRET, key).apply()
        }
        val storedBaseUrl = prefs.getString(KEY_BASE_URL, null)?.trim()?.trimEnd('/')
        val baseUrl = storedBaseUrl?.takeIf { it.startsWith("https://") } ?: DEFAULT_BASE_URL
        val enabled = if (prefs.contains(KEY_ENABLED)) prefs.getBoolean(KEY_ENABLED, true) else true
        if (!prefs.contains(KEY_BASE_URL) || storedBaseUrl.isNullOrBlank()) {
            prefs.edit().putString(KEY_BASE_URL, baseUrl).apply()
        }
        return InternetPitRelaySettings(
            enabled = enabled,
            baseUrl = baseUrl,
            room = room,
            key = key
        )
    }

    fun save(context: Context, settings: InternetPitRelaySettings) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_BASE_URL, settings.baseUrl.trim().trimEnd('/'))
            .putString(KEY_ROOM, settings.room.trim())
            .putString(KEY_SECRET, settings.key.trim())
            .apply()
    }

    fun regenerate(context: Context): InternetPitRelaySettings {
        val old = load(context)
        val next = old.copy(room = generateRoom(), key = generateKey())
        save(context, next)
        return next
    }

    private fun generateRoom(): String {
        val n = SecureRandom().nextInt(900_000) + 100_000
        return n.toString()
    }

    private fun generateKey(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val random = SecureRandom()
        return buildString {
            repeat(12) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }
}
