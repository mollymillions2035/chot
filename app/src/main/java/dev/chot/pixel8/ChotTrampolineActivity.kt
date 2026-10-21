package dev.chot.pixel8

import android.app.Activity
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.WindowManager

class ChotTrampolineActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure screen wakes and displays over lock screen when triggered hands-free
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        val triggerSource = intent?.getStringExtra(EXTRA_TRIGGER_SOURCE) ?: "Hotword"
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager?.isKeyguardLocked != true) {
            executeHandoverToClaude(triggerSource)
            return
        }

        // Claude can't show over the lock screen, so launching it now would leave it behind the keyguard, where it
        // never shows up after the unlock. Ask to unlock and hand over only once that succeeds.
        ChotPrefs.appendLog(this, "[$triggerSource] Phone is locked: waiting for the unlock")
        requestUnlockOnceAwake(keyguardManager, triggerSource, System.currentTimeMillis())
    }

    /**
     * Asks to unlock once the screen has been on for [UNLOCK_PROMPT_SETTLE_MS]. Asked while the phone is still waking
     * from screen-off, the Pixel 8 offered only the PIN pad, without the fingerprint. If the owner unlocks from the
     * lock screen meanwhile, the handover starts right away.
     */
    private fun requestUnlockOnceAwake(keyguardManager: KeyguardManager, triggerSource: String, lockedAt: Long) {
        val power = getSystemService(PowerManager::class.java)
        val waitStart = SystemClock.elapsedRealtime()
        // Screen already on: count from when it came on (0, so no wait, if chot hasn't seen that). Screen off: count
        // from when this check first sees it on, which can't be early even if the broadcast arrives late
        var screenOnAt = if (power.isInteractive) ChotAccessibilityService.lastScreenOnElapsedMs else -1L

        fun check() {
            if (isFinishing || isDestroyed) return
            if (!keyguardManager.isKeyguardLocked) {
                onUnlocked(triggerSource, lockedAt)
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (screenOnAt < 0 && power.isInteractive) {
                screenOnAt = now
            }
            val screenOnMs = if (screenOnAt < 0) -1L else now - screenOnAt
            if (screenOnMs < UNLOCK_PROMPT_SETTLE_MS && now - waitStart < UNLOCK_PROMPT_MAX_WAIT_MS) {
                handler.postDelayed({ check() }, 50L)
                return
            }

            val screenOn = if (screenOnMs > 60_000L) "for a while" else "for ${screenOnMs}ms"
            ChotPrefs.appendLog(this, "[$triggerSource] Asking to unlock (screen on $screenOn)")
            keyguardManager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    onUnlocked(triggerSource, lockedAt)
                }

                override fun onDismissCancelled() {
                    ChotPrefs.appendLog(
                        this@ChotTrampolineActivity,
                        "[$triggerSource] Unlock cancelled: not starting Claude"
                    )
                    finish()
                }

                override fun onDismissError() {
                    ChotPrefs.appendLog(this@ChotTrampolineActivity, "[$triggerSource] Unlock failed: not starting Claude")
                    finish()
                }
            })
        }
        check()
    }

    private fun onUnlocked(triggerSource: String, lockedAt: Long) {
        ChotPrefs.appendLog(this, "[$triggerSource] Unlocked after ${System.currentTimeMillis() - lockedAt}ms")
        executeHandoverToClaude(triggerSource)
    }

    private fun executeHandoverToClaude(triggerSource: String) {
        val startTime = System.currentTimeMillis()
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // 1. Steal exclusive transient audio focus to force Google Assistant / Gemini SpeechRecognizer
        //    to immediately drop its AudioRecord microphone session.
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { }
            .build()

        try {
            audioManager.requestAudioFocus(focusRequest)
        } catch (_: Exception) {
        }

        // 2. Ask ActivityManager to clean up any backgrounded Google Assistant / Gemini processes
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses("com.google.android.googlequicksearchbox")
            am.killBackgroundProcesses("com.google.android.apps.bard")
        } catch (_: Exception) {
        }

        // 3. Poll activeRecordingConfigurations (every 20ms, up to 340ms max) until Google releases the mic
        val maxWaitMs = 340L
        var voiceChatButton: View? = null
        var widgetLayoutMs = -1L
        fun checkMicAndLaunch() {
            val elapsed = System.currentTimeMillis() - startTime
            val activeConfigs = try {
                audioManager.activeRecordingConfigurations
            } catch (_: Exception) {
                emptyList()
            }

            if (activeConfigs.isNotEmpty() && elapsed < maxWaitMs) {
                handler.postDelayed({ checkMicAndLaunch() }, 20L)
                return
            }

            // Release our transient audio focus right before launching Claude so the mic is 100% free
            try {
                audioManager.abandonAudioFocusRequest(focusRequest)
            } catch (_: Exception) {
            }

            // Claude's widget starts voice mode by itself; without it, launch Claude and let the auto-tapper find
            // the Voice button
            val button = voiceChatButton
            val launchedTarget = if (button != null && button.performClick()) {
                ChotAccessibilityService.armAfterWidgetPress(this@ChotTrampolineActivity)
                "Claude widget's Voice chat button (widget laid out in ${widgetLayoutMs}ms)"
            } else {
                val mode = ChotPrefs.getLaunchMode(this@ChotTrampolineActivity)
                val target = ClaudeLauncher.launchClaude(this@ChotTrampolineActivity, mode)
                ChotAccessibilityService.armClaudeVoiceAutoTap(this@ChotTrampolineActivity)
                target
            }
            val totalMs = System.currentTimeMillis() - startTime

            ChotPrefs.appendLog(
                this@ChotTrampolineActivity,
                "[$triggerSource] Mic freed in ${totalMs}ms -> Launched $launchedTarget"
            )

            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }

        // Wait 45ms initially so GLOBAL_ACTION_BACK + AudioFocus loss propagate to Google's audio thread
        handler.postDelayed({ checkMicAndLaunch() }, 45L)

        // Lay out Claude's widget during that wait, so pressing its button later costs nothing
        if (ChotPrefs.getLaunchMode(this) == LaunchMode.AUTO_ASSIST_AND_VOICE_TAP) {
            val layoutStart = System.currentTimeMillis()
            voiceChatButton = ClaudeWidget.findVoiceChatButton(this)
            widgetLayoutMs = System.currentTimeMillis() - layoutStart
        }
    }

    companion object {
        const val EXTRA_TRIGGER_SOURCE = "extra_trigger_source"

        // How long the screen has to have been on before chot asks to unlock, so the prompt offers the fingerprint,
        // and the longest chot waits for that
        private const val UNLOCK_PROMPT_SETTLE_MS = 800L
        private const val UNLOCK_PROMPT_MAX_WAIT_MS = 3000L
    }
}
