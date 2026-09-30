package com.geniex.demo.model

import android.content.Context

object AppPreferences {
    private const val PREFS = "geniex_custom"
    private const val KEY_SELECTED_MODEL = "selected_model"
    private const val KEY_LAST_LOADED_MODEL = "last_loaded_model"
    private const val KEY_LAST_LOADED_COMPUTE = "last_loaded_compute"
    private const val KEY_SERVER_PORT = "server_port"
    private const val KEY_SERVER_LAN = "server_lan"
    private const val KEY_SERVER_KEY = "server_key"
    private const val KEY_PENDING_MODEL = "pending_model"
    private const val KEY_PENDING_COMPUTE = "pending_compute"
    private const val KEY_RESUME_SERVER = "resume_server"

    fun getSelectedModelId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SELECTED_MODEL, null)

    fun setSelectedModelId(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SELECTED_MODEL, id).apply()
    }


    data class LastLoadedModel(val modelId: String, val computeUnit: String)

    /**
     * Persist only a model that finished native creation successfully. This is
     * intentionally separate from the currently selected catalog row: browsing
     * the Models screen must not change what is restored on the next startup.
     */
    fun rememberSuccessfulModelLoad(context: Context, modelId: String, computeUnit: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SELECTED_MODEL, modelId)
            .putString(KEY_LAST_LOADED_MODEL, modelId)
            .putString(KEY_LAST_LOADED_COMPUTE, computeUnit)
            .commit()
    }

    fun getLastLoadedModel(context: Context): LastLoadedModel? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val modelId = prefs.getString(KEY_LAST_LOADED_MODEL, null)?.takeIf { it.isNotBlank() } ?: return null
        val compute = prefs.getString(KEY_LAST_LOADED_COMPUTE, null)?.takeIf { it.isNotBlank() } ?: return null
        return LastLoadedModel(modelId, compute)
    }

    fun clearLastLoadedModelIfMatches(context: Context, modelId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_LOADED_MODEL, null) != modelId) return
        prefs.edit()
            .remove(KEY_LAST_LOADED_MODEL)
            .remove(KEY_LAST_LOADED_COMPUTE)
            .commit()
    }

    data class PendingModelLoad(val modelId: String, val computeUnit: String)

    fun setPendingModelLoad(context: Context, modelId: String, computeUnit: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PENDING_MODEL, modelId)
            .putString(KEY_PENDING_COMPUTE, computeUnit)
            .commit()
    }

    fun getPendingModelLoad(context: Context): PendingModelLoad? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val modelId = prefs.getString(KEY_PENDING_MODEL, null) ?: return null
        val compute = prefs.getString(KEY_PENDING_COMPUTE, null) ?: return null
        return PendingModelLoad(modelId, compute)
    }

    fun clearPendingModelLoad(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PENDING_MODEL)
            .remove(KEY_PENDING_COMPUTE)
            .commit()
    }

    fun setResumeServerAfterRestart(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RESUME_SERVER, enabled)
            .commit()
    }

    fun consumeResumeServerAfterRestart(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_RESUME_SERVER, false)
        prefs.edit().remove(KEY_RESUME_SERVER).commit()
        return enabled
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
