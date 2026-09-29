package com.geniex.demo.model

import android.content.Context

object AppPreferences {
    private const val PREFS = "geniex_custom"
    private const val KEY_SELECTED_MODEL = "selected_model"
    private const val KEY_SERVER_PORT = "server_port"
    private const val KEY_SERVER_LAN = "server_lan"
    private const val KEY_SERVER_KEY = "server_key"

    fun getSelectedModelId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SELECTED_MODEL, null)

    fun setSelectedModelId(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SELECTED_MODEL, id).apply()
    }

    fun getServerPort(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SERVER_PORT, 18181)

    fun setServerPort(context: Context, port: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SERVER_PORT, port).apply()
    }

    fun isLanEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SERVER_LAN, false)

    fun setLanEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SERVER_LAN, enabled).apply()
    }

    fun getApiKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SERVER_KEY, "") ?: ""

    fun setApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SERVER_KEY, key).apply()
    }
}
