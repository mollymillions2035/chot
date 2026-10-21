package dev.chot.pixel8

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.view.ViewGroup

/**
 * chot's own copy of Claude's home-screen widget, never shown. The widget's "Voice chat" button fires Claude's own
 * PendingIntent, which starts voice mode by itself whatever screen Claude is on, including with a draft in the
 * text box or a Claude Code session open (issues 14 and 15). Pressing it doesn't depend on reading Claude's screen.
 * Binding the copy takes a one-time system prompt.
 */
object ClaudeWidget {
    // Any id unique within chot: it names chot's widget host to the system
    private const val HOST_ID = 0xC407

    // The size, in dp, chot asks Claude to lay the widget out for: about a 4x2 home-screen widget, whose top row
    // has the Voice chat button
    private val WIDGET_SIZE = SizeF(350f, 220f)

    private const val VOICE_CHAT_LABEL = "voice chat"

    fun findProvider(context: Context): AppWidgetProviderInfo? =
        AppWidgetManager.getInstance(context)
            .getInstalledProvidersForPackage(ClaudeLauncher.CLAUDE_PACKAGE, null)
            .firstOrNull()

    /** The widget id chot has bound, or null if it has none or the system dropped it (Claude uninstalled, say). */
    fun boundWidgetId(context: Context): Int? {
        val id = ChotPrefs.getClaudeWidgetId(context)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return null
        val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(id)
        return if (info?.provider?.packageName == ClaudeLauncher.CLAUDE_PACKAGE) id else null
    }

    fun allocateWidgetId(context: Context): Int = AppWidgetHost(context, HOST_ID).allocateAppWidgetId()

    /** Binds [id] right away if the owner has already allowed chot to create widgets. */
    fun tryBind(context: Context, id: Int, provider: AppWidgetProviderInfo): Boolean =
        AppWidgetManager.getInstance(context)
            .bindAppWidgetIdIfAllowed(id, provider.profile, provider.provider, sizeOptions())

    /** The system prompt that lets chot create widgets, for when [tryBind] wasn't allowed. */
    fun bindPermissionIntent(id: Int, provider: AppWidgetProviderInfo): Intent =
        Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, provider.profile)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, sizeOptions())
        }

    /**
     * Keeps [id] as chot's widget and releases any earlier one. Claude's widget marks its configuration as
     * optional, so chot skips it and gets the default buttons, whose fixed top row has Voice chat.
     */
    fun onBound(context: Context, id: Int) {
        val previous = ChotPrefs.getClaudeWidgetId(context)
        if (previous != AppWidgetManager.INVALID_APPWIDGET_ID && previous != id) {
            release(context, previous)
        }
        ChotPrefs.setClaudeWidgetId(context, id)
        ChotPrefs.appendLog(context, "Bound chot's copy of Claude's widget (id $id)")
    }

    fun release(context: Context, id: Int) {
        try {
            AppWidgetHost(context, HOST_ID).deleteAppWidgetId(id)
        } catch (_: Exception) {
        }
    }

    /**
     * Lays out chot's copy of the widget and returns its Voice chat button, ready to click, or null if the widget
     * isn't bound or has no such button (logged). The button sends Claude's PendingIntent through [activity], which
     * has to be in the foreground when it's clicked.
     */
    fun findVoiceChatButton(activity: Activity): View? {
        // Any failure here must leave the old launch path working, so nothing may escape
        return try {
            layOutAndFindVoiceChatButton(activity)
        } catch (e: Exception) {
            ChotPrefs.appendLog(activity, "Claude's widget didn't load: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun layOutAndFindVoiceChatButton(activity: Activity): View? {
        val id = boundWidgetId(activity) ?: return null
        val info = AppWidgetManager.getInstance(activity).getAppWidgetInfo(id) ?: return null
        val hostView = AppWidgetHost(activity.applicationContext, HOST_ID).createView(activity, id, info)

        // Without a layout, a widget with layouts for several sizes shows its smallest one
        val density = activity.resources.displayMetrics.density
        val width = (WIDGET_SIZE.width * density).toInt()
        val height = (WIDGET_SIZE.height * density).toInt()
        hostView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        hostView.layout(0, 0, width, height)

        val labels = mutableListOf<String>()
        val labeled = findLabeled(hostView, labels)
        // The label sits on the button's icon; the click goes to the nearest parent that handles it
        val button = generateSequence(labeled) { it.parent as? View }.firstOrNull { it.hasOnClickListeners() }
        if (button == null) {
            ChotPrefs.appendLog(
                activity,
                "Claude's widget has no Voice chat button (labels: ${labels.joinToString().ifEmpty { "none" }})"
            )
        }
        return button
    }

    private fun findLabeled(view: View, labels: MutableList<String>): View? {
        val label = view.contentDescription?.toString()?.trim().orEmpty()
        if (label.isNotEmpty()) {
            labels.add(label)
            if (label.lowercase().contains(VOICE_CHAT_LABEL)) return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findLabeled(view.getChildAt(i), labels)?.let { return it }
            }
        }
        return null
    }

    private fun sizeOptions(): Bundle = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, WIDGET_SIZE.width.toInt())
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, WIDGET_SIZE.width.toInt())
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, WIDGET_SIZE.height.toInt())
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, WIDGET_SIZE.height.toInt())
        putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(WIDGET_SIZE))
    }
}
