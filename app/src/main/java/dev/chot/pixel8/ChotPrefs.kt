package dev.chot.pixel8

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LaunchMode(val id: String, val label: String) {
    AUTO_ASSIST_AND_VOICE_TAP(
        "auto",
        "Auto (Claude ASSIST Intent + Voice Mode Auto-Tap)"
    ),
    ASSIST_INTENT_ONLY(
        "assist_only",
        "Claude Digital Assistant Overlay Intent Only (ACTION_VOICE_ASSIST / ACTION_ASSIST)"
    ),
    MAIN_APP_AND_VOICE_TAP(
        "main_tap",
        "Launch Claude Main App + Auto-Tap Voice Mode Waveform"
    );

    companion object {
        fun fromId(id: String?): LaunchMode =
            entries.firstOrNull { it.id == id } ?: AUTO_ASSIST_AND_VOICE_TAP
    }
}

object ChotPrefs {
    private const val PREFS_NAME = "chot_pixel8_prefs"
    private const val KEY_ENABLED = "interceptor_enabled"
    private const val KEY_LAUNCH_MODE = "launch_mode"
    private const val KEY_AUTO_TAP_VOICE = "auto_tap_voice"
    private const val KEY_COORDINATE_FALLBACK = "coordinate_fallback"
    private const val KEY_TAP_X_PCT = "tap_x_pct"
    private const val KEY_TAP_Y_PCT = "tap_y_pct"
    private const val KEY_LOGS = "event_logs"
    private const val KEY_CLAUDE_WIDGET_ID = "claude_widget_id"
    private const val KEY_SERVICE_WANTED = "accessibility_service_wanted"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getLaunchMode(context: Context): LaunchMode =
        LaunchMode.fromId(prefs(context).getString(KEY_LAUNCH_MODE, LaunchMode.AUTO_ASSIST_AND_VOICE_TAP.id))

    fun setLaunchMode(context: Context, mode: LaunchMode) {
        prefs(context).edit().putString(KEY_LAUNCH_MODE, mode.id).apply()
    }

    fun isAutoTapVoiceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_TAP_VOICE, true)

    fun setAutoTapVoiceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_TAP_VOICE, enabled).apply()
    }

    fun isCoordinateFallbackEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COORDINATE_FALLBACK, true)

    fun setCoordinateFallbackEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_COORDINATE_FALLBACK, enabled).apply()
    }

    /**
     * Pixel 8 (1080x2400) default waveform button location inside Claude's bottom input bar
     * (measured at [943,2222][967,2247] -> center (955, 2234) = 88.4% X, 93.1% Y when keyboard is closed).
     */
    fun getTapXPercent(context: Context): Float =
        prefs(context).getFloat(KEY_TAP_X_PCT, 0.884f)

    fun getTapYPercent(context: Context): Float =
        prefs(context).getFloat(KEY_TAP_Y_PCT, 0.931f)

    fun setTapPercent(context: Context, xPct: Float, yPct: Float) {
        prefs(context).edit()
            .putFloat(KEY_TAP_X_PCT, xPct.coerceIn(0.05f, 0.98f))
            .putFloat(KEY_TAP_Y_PCT, yPct.coerceIn(0.05f, 0.98f))
            .apply()
    }

    fun getClaudeWidgetId(context: Context): Int =
        prefs(context).getInt(KEY_CLAUDE_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

    fun setClaudeWidgetId(context: Context, id: Int) {
        prefs(context).edit().putInt(KEY_CLAUDE_WIDGET_ID, id).apply()
    }

    /**
     * Whether chot's accessibility service should be on: set when it connects, cleared when Android unbinds it
     * (turned off in Settings). A force-stop kills chot without either, so it stays set (#61).
     */
    fun isServiceWanted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SERVICE_WANTED, false)

    fun setServiceWanted(context: Context, wanted: Boolean) {
        prefs(context).edit().putBoolean(KEY_SERVICE_WANTED, wanted).apply()
    }

    @Synchronized
    fun appendLog(context: Context, message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = "[$stamp] $message"
        val existing = prefs(context).getString(KEY_LOGS, "") ?: ""
        val lines = if (existing.isBlank()) emptyList() else existing.lines()
        val updated = (listOf(entry) + lines).take(100).joinToString("\n")
        prefs(context).edit().putString(KEY_LOGS, updated).apply()
    }

    fun getLogs(context: Context): String =
        prefs(context).getString(KEY_LOGS, "No intercepts recorded yet. Say \"Hey Google\" or tap Test below.") ?: ""

    fun clearLogs(context: Context) {
        prefs(context).edit().remove(KEY_LOGS).apply()
    }
}
