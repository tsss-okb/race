package ru.racelab.phone.remote

import android.content.Context

object RemoteControlSettingsRepository {
    private const val PREFS = "racelab_remote_control"
    private const val KEY_GM204 = "gm204_enabled"

    fun isGm204Enabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_GM204, true)

    fun setGm204Enabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GM204, enabled)
            .apply()
    }
}

enum class RemoteAction {
    NONE,
    PREVIOUS_TAB,
    NEXT_TAB,
    DASHBOARD,
    DATA
}
