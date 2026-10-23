package dev.chot.pixel8

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * The one notification chot shows: its accessibility service was on and is now off, so "Hey Google" goes to
 * Gemini until it's turned back on in Settings. Android turns the service off on a force-stop (#61) and chot
 * can't turn it back on itself: it no longer holds `WRITE_SECURE_SETTINGS` (#64).
 */
object ChotNotifications {
    private const val CHANNEL_ID = "service_off"
    private const val SERVICE_OFF_ID = 1

    /** True when the service was on (and not turned off in Settings) but isn't in the enabled list now. */
    fun isServiceOffUnexpectedly(context: Context): Boolean =
        ChotPrefs.isServiceWanted(context) && !isAccessibilityServiceEnabled(context)

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        if (ChotAccessibilityService.instance != null) return true
        val expected = ComponentName(context, ChotAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    /** Whether chot may post notifications: a runtime permission on Android 13+, always granted before. */
    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * Posts the service-off notification if the service is wanted but off, else removes it. Returns true when a
     * new notification went up, so the caller can log it once rather than on every check.
     */
    fun syncServiceOff(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!isServiceOffUnexpectedly(context)) {
            manager.cancel(SERVICE_OFF_ID)
            return false
        }
        if (!canNotify(context)) return false
        if (manager.activeNotifications.any { it.id == SERVICE_OFF_ID }) return false

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Accessibility service off",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shown when chot's accessibility service was turned off by a force-stop"
            }
        )
        val openSettings = PendingIntent.getActivity(
            context,
            0,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("chot's accessibility service is off")
            .setContentText("\"Hey Google\" goes to Gemini until it's turned back on. Tap to open Accessibility settings.")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "A force-stop turned chot's accessibility service off, so \"Hey Google\" goes to Gemini. " +
                        "Tap to open Accessibility settings and turn chot back on."
                )
            )
            .setContentIntent(openSettings)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(SERVICE_OFF_ID, notification)
        return true
    }

    fun cancelServiceOff(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(SERVICE_OFF_ID)
    }
}
