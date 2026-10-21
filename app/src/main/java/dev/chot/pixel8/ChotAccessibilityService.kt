package dev.chot.pixel8

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ChotAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val lastInterceptEpochMs = AtomicLong(0L)
    private val isMicRecordingActive = AtomicBoolean(false)

    // Until when a logcat hotword hint keeps polling for the Assistant overlay (0 = not polling)
    private val logcatHintDeadlineEpochMs = AtomicLong(0L)

    @Volatile
    private var logcatThread: Thread? = null

    @Volatile
    private var logcatProcess: Process? = null

    private val audioRecordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            val wasActive = isMicRecordingActive.getAndSet(configs.isNotEmpty())
            if (!wasActive && configs.isNotEmpty()) {
                // If the Assistant / Gemini overlay is already open when mic recording starts, intercept immediately
                handler.post {
                    if (hasActiveVoiceInteractionWindow()) {
                        triggerIntercept("VoiceInteractionWindow+Mic")
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ChotPrefs.appendLog(this, "ChotAccessibilityService connected & armed")

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioRecordingCallback(audioRecordingCallback, handler)

        startLogcatDspWatcherIfPermitted()
    }

    override fun onDestroy() {
        instance = null
        stopLogcatDspWatcher()
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.unregisterAudioRecordingCallback(audioRecordingCallback)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // 1. Handle Claude window events when Voice Mode Auto-Tap is armed
        if (pkg == ClaudeLauncher.CLAUDE_PACKAGE) {
            handleClaudeWindowEvent()
            return
        }

        // Cheap early-out while paused; triggerIntercept enforces the pause switch for every path
        if (!ChotPrefs.isEnabled(this)) return

        // 2. Inspect Google App / Assistant / Gemini packages
        if (pkg in GOOGLE_ASSISTANT_PACKAGES) {
            val className = event.className?.toString() ?: ""

            // Never hijack normal Google Search or Launcher activities (including the search-bar mic's voice search).
            // This runs before any overlay check.
            if (NORMAL_SEARCH_CLASSES.any { className.contains(it, ignoreCase = true) }) {
                return
            }

            // Immediate match on the Assistant / Gemini overlay activity classes
            if (ASSISTANT_OVERLAY_CLASSES.any { className.contains(it, ignoreCase = true) }) {
                triggerIntercept("AssistantActivity (${className.substringAfterLast('.')})")
                return
            }
        }

        // 3. Check if the Assistant / Gemini voice overlay window is on screen (also catches window-state events
        //    the framework coalesced away)
        if (hasActiveVoiceInteractionWindow()) {
            triggerIntercept("AssistantOverlayWindow")
        }
    }

    /**
     * Checks if Google's "Hey Google" voice overlay is on screen. Only the overlay itself counts: the full Gemini app
     * and Google voice search are ordinary activity windows of the same packages and must never match.
     * - Classic Google Assistant draws into the VoiceInteractionSession window (TYPE_VOICE_INTERACTION), which
     *   AccessibilityWindowInfo has no constant for and reports as type -1 (not TYPE_SYSTEM).
     * - Gemini draws into FloatyActivity, a transparent activity window, identified by its overlay root view.
     */
    private fun hasActiveVoiceInteractionWindow(): Boolean {
        return try {
            windows?.any { win ->
                val root = win.root ?: return@any false
                val pkg = root.packageName?.toString() ?: ""
                if (pkg in GOOGLE_ASSISTANT_PACKAGES) {
                    win.type == VOICE_INTERACTION_WINDOW_TYPE ||
                        root.findAccessibilityNodeInfosByViewId(GEMINI_OVERLAY_ROOT_VIEW_ID).isNotEmpty()
                } else {
                    false
                }
            } == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Fires the intercept pipeline:
     * 1. Honors the pause switch and enforces cooldown to prevent duplicate triggers.
     * 2. Immediately dismisses Google's Assistant / Gemini overlay via GLOBAL_ACTION_BACK. Automatic callers
     *    must only get here once the overlay is confirmed on screen, otherwise BACK lands in the user's app.
     * 3. Starts ChotTrampolineActivity to steal exclusive transient audio focus, verify mic release,
     *    wake the lock screen if needed, and launch Claude into Voice Mode.
     *
     * [manual] is set by MainActivity's Test button: it runs even while paused, skips the cooldown, and sends
     * no BACK because there is no overlay to dismiss.
     */
    fun triggerIntercept(source: String, manual: Boolean = false) {
        // Every automatic path (window events, mic callback, logcat) ends up here, so the pause switch is checked here
        if (!manual && !ChotPrefs.isEnabled(this)) return

        val now = System.currentTimeMillis()
        val cooldown = ChotPrefs.getCooldownMs(this)
        val prev = lastInterceptEpochMs.get()
        if (!manual && (now - prev) < cooldown) {
            return
        }
        lastInterceptEpochMs.set(now)
        logcatHintDeadlineEpochMs.set(0L)

        if (!manual) {
            // Step 1: Dismiss Google Assistant / Gemini overlay immediately
            try {
                performGlobalAction(GLOBAL_ACTION_BACK)
            } catch (_: Exception) {
            }

            // Follow-up check at 85ms: if the overlay is still lingering, dismiss again
            handler.postDelayed({
                if (hasActiveVoiceInteractionWindow()) {
                    try {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    } catch (_: Exception) {
                    }
                }
            }, 85L)
        }

        // Step 2: Launch transparent trampoline to force audio focus handover and launch Claude
        try {
            val trampoline = Intent(this, ChotTrampolineActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                putExtra(ChotTrampolineActivity.EXTRA_TRIGGER_SOURCE, source)
            }
            startActivity(trampoline)
        } catch (e: Exception) {
            ChotPrefs.appendLog(this, "Trampoline error: ${e.message}, launching Claude directly")
            ClaudeLauncher.launchClaude(this, ChotPrefs.getLaunchMode(this))
            armClaudeVoiceAutoTap()
        }
    }

    /**
     * Optional Hardware DSP Fast-Path:
     * If `adb shell pm grant dev.chot.pixel8 android.permission.READ_LOGS` has been granted,
     * tails filtered logcat tags (`AlwaysOnHotwordDetector`, `ActivityTaskManager`) so we know "Hey Google"
     * fired before the Google UI even finishes inflating. A hit is only a hint: see [onLogcatHotwordHint].
     */
    fun startLogcatDspWatcherIfPermitted() {
        if (checkSelfPermission(android.Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        if (logcatThread?.isAlive == true) return

        val thread = Thread({
            try {
                // Clear old buffer lines by only tailing from the last 1 line (-T 1)
                val cmd = arrayOf(
                    "logcat",
                    "-v", "brief",
                    "-T", "1",
                    "AlwaysOnHotwordDetector:I",
                    "ActivityTaskManager:I",
                    "*:S"
                )
                val proc = Runtime.getRuntime().exec(cmd)
                logcatProcess = proc
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line: String? = null
                while (!Thread.currentThread().isInterrupted && reader.readLine().also { line = it } != null) {
                    val l = line?.trimEnd() ?: continue
                    if (!ChotPrefs.isEnabled(this@ChotAccessibilityService)) continue

                    // - "onDetected" is logged by the default assistant's own hotword detector, so other on-device
                    //   sound models (Now Playing etc.) never produce it. SoundTriggerMiddlewareLogging can't tell
                    //   them apart, and VoiceInteractionManagerService logs nothing at INFO for a session.
                    // - The START line is Gemini's overlay activity being launched (not the full Gemini app).
                    val isDspHotword =
                        (l.startsWith("I/AlwaysOnHotwordDetector(") && l.endsWith("): onDetected")) ||
                            (l.startsWith("I/ActivityTaskManager(") && l.contains("START u0") &&
                                l.contains("cmp=$GEMINI_OVERLAY_COMPONENT"))

                    if (isDspHotword) {
                        handler.post {
                            onLogcatHotwordHint()
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }, "Chot-DSP-LogcatWatcher")
        thread.isDaemon = true
        thread.start()
        logcatThread = thread
    }

    /**
     * Logcat lines arrive before the Assistant overlay exists, so BACK right away would land in the user's app.
     * Poll for the overlay instead and intercept once it is confirmed; hints that never produce one (a rejected
     * hotword, or an intercept that already happened) expire on their own.
     */
    private fun onLogcatHotwordHint() {
        val now = System.currentTimeMillis()
        val alreadyPolling = now < logcatHintDeadlineEpochMs.get()
        logcatHintDeadlineEpochMs.set(now + LOGCAT_HINT_CONFIRM_WINDOW_MS)
        if (!alreadyPolling) {
            pollForOverlayAfterLogcatHint()
        }
    }

    private fun pollForOverlayAfterLogcatHint() {
        if (System.currentTimeMillis() >= logcatHintDeadlineEpochMs.get()) return
        if (hasActiveVoiceInteractionWindow()) {
            logcatHintDeadlineEpochMs.set(0L)
            triggerIntercept("DSP-Logcat")
            return
        }
        handler.postDelayed({ pollForOverlayAfterLogcatHint() }, LOGCAT_HINT_POLL_MS)
    }

    private fun stopLogcatDspWatcher() {
        try {
            logcatProcess?.destroy()
            logcatThread?.interrupt()
        } catch (_: Exception) {
        }
        logcatProcess = null
        logcatThread = null
    }

    /**
     * When Claude opens after a hotword intercept, ensures Claude enters active Voice Mode:
     * - Actively polls every 120ms (in addition to window events) so Compose UI updates are caught immediately.
     * - Searches Claude's full accessibility node tree (up to depth 40) for the Voice Mode ("Voice Mode" / "Talk") button.
     * - Performs ACTION_CLICK and verifies transition (falling back to an exact bounds tap gesture if needed).
     */
    private fun scheduleAutoTapPoll(delayMs: Long = 120L) {
        handler.postDelayed({
            if (claudeAutoTapArmedEpochMs.get() != 0L) {
                handleClaudeWindowEvent()
                if (claudeAutoTapArmedEpochMs.get() != 0L) {
                    scheduleAutoTapPoll(120L)
                }
            }
        }, delayMs)
    }

    private fun getClaudeRootNodes(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            val active = rootInActiveWindow
            if (active != null && active.packageName?.toString() == ClaudeLauncher.CLAUDE_PACKAGE) {
                roots.add(active)
            }
        } catch (_: Exception) {
        }
        try {
            for (win in windows) {
                val r = win.root ?: continue
                if (r.packageName?.toString() == ClaudeLauncher.CLAUDE_PACKAGE && roots.none { it == r }) {
                    roots.add(r)
                }
            }
        } catch (_: Exception) {
        }
        return roots
    }

    private fun handleClaudeWindowEvent() {
        val armedAt = claudeAutoTapArmedEpochMs.get()
        if (armedAt == 0L) return

        val elapsed = System.currentTimeMillis() - armedAt
        if (elapsed > 6000L) {
            claudeAutoTapArmedEpochMs.set(0L)
            return
        }

        if (!ChotPrefs.isAutoTapVoiceEnabled(this) ||
            ChotPrefs.getLaunchMode(this) == LaunchMode.ASSIST_INTENT_ONLY
        ) {
            claudeAutoTapArmedEpochMs.set(0L)
            return
        }

        // Wait at least 180ms after arming so AssistantOverlayActivity -> MainActivity transition starts
        if (elapsed < 180L) return

        val roots = getClaudeRootNodes()
        if (roots.isEmpty()) return

        for (root in roots) {
            // 1. Check if Claude is already in active Voice Mode UI
            if (isClaudeAlreadyInVoiceMode(root)) {
                claudeAutoTapArmedEpochMs.set(0L)
                ChotPrefs.appendLog(this, "Claude Voice Mode is active and listening (${elapsed}ms)")
                return
            }

            // 2. Search for the Voice Mode ("Voice Mode" / "Talk") button in Claude's accessibility tree
            val voiceButton = findClaudeVoiceModeButton(root)
            if (voiceButton != null) {
                claudeAutoTapArmedEpochMs.set(0L)
                val rect = Rect()
                voiceButton.getBoundsInScreen(rect)
                val label = voiceButton.contentDescription ?: voiceButton.text ?: voiceButton.className ?: "node"
                val actionClicked = voiceButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                var gestureTapped = false
                if (!actionClicked && !rect.isEmpty) {
                    gestureTapped = dispatchTapGesture(rect.exactCenterX(), rect.exactCenterY())
                }
                ChotPrefs.appendLog(
                    this,
                    "Auto-tapped Voice button '$label' at ${rect.toShortString()} (action=$actionClicked, gesture=$gestureTapped)"
                )

                // Verify 380ms later: if ACTION_CLICK returned true but the Voice Mode button is still visible
                // (Compose sometimes requires a physical touch event), tap its exact screen coordinates.
                if (actionClicked && !rect.isEmpty) {
                    val cx = rect.exactCenterX()
                    val cy = rect.exactCenterY()
                    handler.postDelayed({
                        val currentRoots = getClaudeRootNodes()
                        val stillNotInVoice = currentRoots.isNotEmpty() &&
                            currentRoots.none { isClaudeAlreadyInVoiceMode(it) } &&
                            currentRoots.any { findClaudeVoiceModeButton(it) != null }
                        if (stillNotInVoice) {
                            val tapOk = dispatchTapGesture(cx, cy)
                            ChotPrefs.appendLog(
                                this,
                                "Follow-up gesture tap on Voice button at (${cx.toInt()}, ${cy.toInt()}) -> $tapOk"
                            )
                        }
                    }, 380L)
                }
                return
            }
        }

        // Dump visible Claude nodes once at ~1200ms if no Voice Mode button matched yet
        if (elapsed >= 1200L && !diagnosticTreeDumped.getAndSet(true)) {
            val summary = mutableListOf<String>()
            for (root in roots) {
                collectNodeSummary(root, 0, summary)
            }
            ChotPrefs.appendLog(
                this,
                "Claude UI nodes (${summary.size}): ${summary.take(18).joinToString(" | ")}"
            )
        }

        // 3. If Claude's UI has settled (>= 1600ms) and no labeled semantic node was exposed,
        //    use the Pixel 8 coordinate fallback tap if enabled
        if (elapsed >= 1600L && ChotPrefs.isCoordinateFallbackEnabled(this) &&
            !coordinateFallbackScheduled.getAndSet(true)
        ) {
            claudeAutoTapArmedEpochMs.set(0L)
            dispatchCoordinateTap()
        }
    }

    private fun collectNodeSummary(node: AccessibilityNodeInfo, depth: Int, out: MutableList<String>) {
        if (depth > 40 || out.size >= 25) return
        val t = node.text?.toString()?.trim().orEmpty()
        val d = node.contentDescription?.toString()?.trim().orEmpty()
        val id = node.viewIdResourceName?.substringAfterLast('/')?.trim().orEmpty()
        if (t.isNotEmpty() || d.isNotEmpty() || id.isNotEmpty() || node.isClickable) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (t.isNotEmpty() || d.isNotEmpty() || id.isNotEmpty()) {
                out.add("d=$depth[t='$t',cd='$d',id='$id',clk=${node.isClickable},b=${r.centerX()},${r.centerY()}]")
            }
        }
        for (i in 0 until node.childCount) {
            val c = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue
            collectNodeSummary(c, depth + 1, out)
        }
    }

    private fun isClaudeAlreadyInVoiceMode(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 40) return false
        val text = (node.text?.toString() ?: "").lowercase()
        val desc = (node.contentDescription?.toString() ?: "").lowercase()

        if (CLAUDE_ACTIVE_VOICE_INDICATORS.any { text.contains(it) || desc.contains(it) }) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue
            if (isClaudeAlreadyInVoiceMode(child, depth + 1)) return true
        }
        return false
    }

    private fun findClaudeVoiceModeButton(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 40) return null

        val desc = (node.contentDescription?.toString() ?: "").trim().lowercase()
        val text = (node.text?.toString() ?: "").trim().lowercase()
        val viewId = (node.viewIdResourceName ?: "").trim().lowercase()

        val isVoiceMatch = (
            CLAUDE_VOICE_BUTTON_DESC_SUBSTRINGS.any { token -> desc.contains(token) } ||
                CLAUDE_VOICE_BUTTON_EXACT_TEXTS.any { token -> text == token || desc == token } ||
                viewId.contains("voice_mode") ||
                viewId.contains("bell_mode")
            ) && !desc.contains("voice input") && !desc.contains("speech input")

        if (isVoiceMatch) {
            // Walk up to the nearest clickable node if this leaf node isn't directly clickable
            var target: AccessibilityNodeInfo? = node
            var hops = 0
            while (target != null && !target.isClickable && hops < 6) {
                target = target.parent
                hops++
            }
            return target ?: node
        }

        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue
            val found = findClaudeVoiceModeButton(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun performClickOrTapNode(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            return dispatchTapGesture(rect.exactCenterX(), rect.exactCenterY())
        }
        return false
    }

    private fun dispatchCoordinateTap() {
        val dm = resources.displayMetrics
        val x = dm.widthPixels * ChotPrefs.getTapXPercent(this)
        val y = dm.heightPixels * ChotPrefs.getTapYPercent(this)
        val ok = dispatchTapGesture(x, y)
        ChotPrefs.appendLog(
            this,
            "Coordinate fallback tap at (${x.toInt()}, ${y.toInt()}) -> $ok"
        )
    }

    private fun dispatchTapGesture(x: Float, y: Float): Boolean {
        return try {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 45L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        @Volatile
        var instance: ChotAccessibilityService? = null
            private set

        private val claudeAutoTapArmedEpochMs = AtomicLong(0L)
        private val coordinateFallbackScheduled = AtomicBoolean(false)
        private val diagnosticTreeDumped = AtomicBoolean(false)

        fun armClaudeVoiceAutoTap() {
            coordinateFallbackScheduled.set(false)
            diagnosticTreeDumped.set(false)
            claudeAutoTapArmedEpochMs.set(System.currentTimeMillis())
            instance?.scheduleAutoTapPoll(100L)
        }

        private val GOOGLE_ASSISTANT_PACKAGES = setOf(
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.bard"
        )

        // Gemini's "Hey Google" overlay. The full Gemini app is a different activity in the same package
        // (…voice.robin.main.MainActivity), and the com.google.android.apps.bard app is only a launcher shell for it.
        private const val GEMINI_OVERLAY_ACTIVITY =
            "com.google.android.apps.search.assistant.surfaces.voice.robin.ui.floaty.activity.FloatyActivity"
        private const val GEMINI_OVERLAY_COMPONENT = "com.google.android.googlequicksearchbox/$GEMINI_OVERLAY_ACTIVITY"
        private const val GEMINI_OVERLAY_ROOT_VIEW_ID =
            "com.google.android.googlequicksearchbox:id/assistant_robin_floaty_root_container"

        // AccessibilityWindowManager maps WindowManager's TYPE_VOICE_INTERACTION to no AccessibilityWindowInfo type
        private const val VOICE_INTERACTION_WINDOW_TYPE = -1

        private const val LOGCAT_HINT_CONFIRM_WINDOW_MS = 2000L
        private const val LOGCAT_HINT_POLL_MS = 40L

        private val ASSISTANT_OVERLAY_CLASSES = listOf(
            GEMINI_OVERLAY_ACTIVITY,
            "VoicePlate",
            "RobinVoicePlate",
            "RobinActivity",
            "AssistantActivity",
            "OpaActivity",
            "ZeroState",
            "LockscreenEntry"
        )

        private val NORMAL_SEARCH_CLASSES = listOf(
            "SearchActivity",
            "GoogleAppActivity",
            "PixelSearch",
            "CustomTabs"
        )

        private val CLAUDE_VOICE_BUTTON_DESC_SUBSTRINGS = listOf(
            "voice mode",
            "start voice",
            "voice chat",
            "start talking",
            "waveform",
            "audio mode",
            "speak to claude",
            "talk to claude"
        )

        private val CLAUDE_VOICE_BUTTON_EXACT_TEXTS = listOf(
            "talk",
            "voice mode",
            "start voice",
            "voice chat",
            "start talking"
        )

        private val CLAUDE_ACTIVE_VOICE_INDICATORS = listOf(
            "hold tight, connecting",
            "start chatting anytime",
            "mute microphone",
            "unmute microphone",
            "mute claude",
            "unmute claude",
            "starting voice mode",
            "end voice",
            "stop voice",
            "tap to interrupt"
        )
    }
}
