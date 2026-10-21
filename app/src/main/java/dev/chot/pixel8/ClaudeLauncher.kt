package dev.chot.pixel8

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

object ClaudeLauncher {
    const val CLAUDE_PACKAGE = "com.anthropic.claude"
    const val ACTION_VOICE_ASSIST = "android.intent.action.VOICE_ASSIST"
    const val ACTION_VOICE_SEARCH_HANDS_FREE = "android.speech.action.VOICE_SEARCH_HANDS_FREE"

    data class DiscoveredTargets(
        val isInstalled: Boolean,
        val voiceAssistComponent: ComponentName?,
        val assistComponent: ComponentName?,
        val namedVoiceOrAssistActivity: ComponentName?,
        val mainLauncherComponent: ComponentName?
    )

    fun discoverTargets(context: Context): DiscoveredTargets {
        val pm = context.packageManager
        val installed = try {
            pm.getPackageInfo(CLAUDE_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
        if (!installed) {
            return DiscoveredTargets(
                isInstalled = false,
                voiceAssistComponent = null,
                assistComponent = null,
                namedVoiceOrAssistActivity = null,
                mainLauncherComponent = null
            )
        }

        val voiceAssist = resolveActivityForAction(pm, ACTION_VOICE_ASSIST)
            ?: resolveActivityForAction(pm, ACTION_VOICE_SEARCH_HANDS_FREE)
            ?: resolveActivityForAction(pm, Intent.ACTION_VOICE_COMMAND)
        val assist = resolveActivityForAction(pm, Intent.ACTION_ASSIST)

        var namedSpecial: ComponentName? = null
        try {
            val pkgInfo = pm.getPackageInfo(CLAUDE_PACKAGE, PackageManager.GET_ACTIVITIES)
            val activities = pkgInfo.activities ?: emptyArray()
            val match = activities.firstOrNull { act ->
                act.exported && (
                    act.name.contains("voice", ignoreCase = true) ||
                        act.name.contains("assist", ignoreCase = true)
                    )
            }
            if (match != null) {
                namedSpecial = ComponentName(CLAUDE_PACKAGE, match.name)
            }
        } catch (_: Exception) {
        }

        val mainIntent = pm.getLaunchIntentForPackage(CLAUDE_PACKAGE)
        val mainComp = mainIntent?.component

        return DiscoveredTargets(
            isInstalled = true,
            voiceAssistComponent = voiceAssist,
            assistComponent = assist,
            namedVoiceOrAssistActivity = namedSpecial,
            mainLauncherComponent = mainComp
        )
    }

    private fun resolveActivityForAction(pm: PackageManager, action: String): ComponentName? {
        val intent = Intent(action).setPackage(CLAUDE_PACKAGE)
        val matches = pm.queryIntentActivities(intent, 0)
        val first = matches.firstOrNull()?.activityInfo ?: return null
        return ComponentName(first.packageName, first.name)
    }

    /**
     * Launches the official Claude Android app into its Assistant / Voice Mode activity.
     * Returns a human-readable description of the launched intent for diagnostic logging.
     */
    fun launchClaude(context: Context, mode: LaunchMode): String {
        val targets = discoverTargets(context)
        if (!targets.isInstalled) {
            ChotPrefs.appendLog(context, "ERROR: com.anthropic.claude is not installed!")
            // Open Play Store listing as fallback
            try {
                val storeIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=$CLAUDE_PACKAGE")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(storeIntent)
            } catch (_: Exception) {
            }
            return "NotInstalled"
        }

        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED

        if (mode == LaunchMode.AUTO_ASSIST_AND_VOICE_TAP || mode == LaunchMode.ASSIST_INTENT_ONLY) {
            // 1. Try explicit VOICE_ASSIST component on com.anthropic.claude
            targets.voiceAssistComponent?.let { comp ->
                val intent = Intent(ACTION_VOICE_ASSIST).apply {
                    component = comp
                    addFlags(flags)
                    putExtra(Intent.EXTRA_ASSIST_INPUT_HINT_KEYBOARD, false)
                    putExtra("voice_mode", true)
                    putExtra("start_voice", true)
                }
                if (tryStart(context, intent)) {
                    return "VOICE_ASSIST (${comp.shortClassName})"
                }
            }

            // 2. Try explicit ASSIST component on com.anthropic.claude (the Digital Assistant role overlay!)
            targets.assistComponent?.let { comp ->
                val intent = Intent(Intent.ACTION_ASSIST).apply {
                    component = comp
                    addFlags(flags)
                    putExtra(Intent.EXTRA_ASSIST_INPUT_HINT_KEYBOARD, false)
                    putExtra("voice_mode", true)
                    putExtra("start_voice", true)
                }
                if (tryStart(context, intent)) {
                    return "ASSIST (${comp.shortClassName})"
                }
            }

            // 3. Try any exported activity in com.anthropic.claude matching *Voice* or *Assist*
            targets.namedVoiceOrAssistActivity?.let { comp ->
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    component = comp
                    addFlags(flags)
                    putExtra("voice_mode", true)
                }
                if (tryStart(context, intent)) {
                    return "NamedActivity (${comp.shortClassName})"
                }
            }
        }

        // 4. Fallback / Main App mode: launch Claude's main launcher activity
        val launchIntent = context.packageManager.getLaunchIntentForPackage(CLAUDE_PACKAGE)?.apply {
            addFlags(flags)
            putExtra("voice_mode", true)
        }
        if (launchIntent != null && tryStart(context, launchIntent)) {
            return "MainActivity (${launchIntent.component?.shortClassName ?: "default"})"
        }

        return "LaunchFailed"
    }

    private fun tryStart(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            ChotPrefs.appendLog(context, "Intent fallback (${e.javaClass.simpleName}): ${e.message}")
            false
        }
    }
}
