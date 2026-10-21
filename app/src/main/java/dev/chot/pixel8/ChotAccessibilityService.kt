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
import android.view.accessibility.AccessibilityWindowInfo
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ChotAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val lastInterceptEpochMs = AtomicLong(0L)
    private val isMicRecordingActive = AtomicBoolean(false)
    private val lastMicStartEpochMs = AtomicLong(0L)

    @Volatile
    private var logcatThread: Thread? = null

    @Volatile
    private var logcatProcess: Process? = null

    private val audioRecordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            val wasActive = isMicRecordingActive.getAndSet(configs.isNotEmpty())
            if (!wasActive && configs.isNotEmpty()) {
                lastMicStartEpochMs.set(System.currentTimeMillis())
                // If a TYPE_VOICE_INTERACTION window is already open when mic recording starts, intercept immediately
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

        if (!ChotPrefs.isEnabled(this)) return

        // 2. Check if Android's system-level TYPE_VOICE_INTERACTION overlay window just appeared
        if (hasActiveVoiceInteractionWindow()) {
            triggerIntercept("TYPE_VOICE_INTERACTION Overlay")
            return
        }

        // 3. Inspect Google App / Assistant / Gemini packages
        if (pkg in GOOGLE_ASSISTANT_PACKAGES) {
            val className = event.className?.toString() ?: ""

            // Never hijack normal Google Search or Launcher activities
            if (NORMAL_SEARCH_CLASSES.any { className.contains(it, ignoreCase = true) }) {
                return
            }

            // Immediate match on known Assistant / Gemini ("Robin") / VoicePlate activity classes
            if (ASSISTANT_OVERLAY_CLASSES.any { className.contains(it, ignoreCase = true) }) {
                triggerIntercept("AssistantActivity (${className.substringAfterLast('.')})")
                return
            }

            // Inspect the node hierarchy for Assistant / Gemini overlay resource IDs or voice prompts
            val root = try {
                rootInActiveWindow ?: event.source
            } catch (_: Exception) {
                null
            }

            if (root != null && isAssistantVoiceOverlayNode(root)) {
                triggerIntercept("AssistantOverlayView ($pkg)")
            }
        }
    }

    /**
     * Checks if any interactive window on screen is Google's VoiceInteractionSession / Assistant overlay.
     * On Pixel 8, "Hey Google" opens a system/assistant overlay window whose title or root hierarchy
     * belongs to Google Assistant / Gemini ("Robin"), whereas normal Google Search opens SearchActivity.
     */
    private fun hasActiveVoiceInteractionWindow(): Boolean {
        return try {
            windows?.any { win ->
                val title = win.title?.toString() ?: ""
                if (title.equals("Google Assistant", ignoreCase = true) ||
                    title.equals("Gemini", ignoreCase = true)
                ) {
                    return@any true
                }
                val root = win.root ?: return@any false
                val pkg = root.packageName?.toString() ?: ""
                if (pkg in GOOGLE_ASSISTANT_PACKAGES) {
                    win.type == AccessibilityWindowInfo.TYPE_SYSTEM || isAssistantVoiceOverlayNode(root)
                } else {
                    false
                }
            } == true
        } catch (_: Exception) {
            false
        }
    }

    private fun isAssistantVoiceOverlayNode(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 8) return false

        val viewId = node.viewIdResourceName ?: ""
        if (viewId.isNotEmpty()) {
            // Normal Google search bar / browser views should be ignored
            if (viewId.contains("search_box") || viewId.contains("url_bar") || viewId.contains("doodle")) {
                return false
            }
            if (ASSISTANT_VIEW_ID_TOKENS.any { viewId.contains(it, ignoreCase = true) }) {
                return true
            }
        }

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val recentMic = (System.currentTimeMillis() - lastMicStartEpochMs.get()) < 3000L || isMicRecordingActive.get()

        if (recentMic) {
            if (ASSISTANT_PROMPT_STRINGS.any {
                    text.equals(it, ignoreCase = true) || desc.equals(it, ignoreCase = true)
                }
            ) {
                return true
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue
            if (isAssistantVoiceOverlayNode(child, depth + 1)) {
                return true
            }
        }
        return false
    }

    /**
     * Fires the intercept pipeline:
     * 1. Enforces cooldown to prevent duplicate triggers.
     * 2. Immediately dismisses Google's VoiceInteractionSession via GLOBAL_ACTION_BACK.
     * 3. Starts ChotTrampolineActivity to steal exclusive transient audio focus, verify mic release,
     *    wake the lock screen if needed, and launch Claude into Voice Mode.
     */
    fun triggerIntercept(source: String, bypassCooldown: Boolean = false) {
        val now = System.currentTimeMillis()
        val cooldown = ChotPrefs.getCooldownMs(this)
        val prev = lastInterceptEpochMs.get()
        if (!bypassCooldown && (now - prev) < cooldown) {
            return
        }
        lastInterceptEpochMs.set(now)

        // Step 1: Dismiss Google Assistant / Gemini overlay immediately
        try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (_: Exception) {
        }

        // Follow-up check at 85ms: if TYPE_VOICE_INTERACTION overlay is still lingering, dismiss again
        handler.postDelayed({
            if (hasActiveVoiceInteractionWindow()) {
                try {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                } catch (_: Exception) {
                }
            }
        }, 85L)

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
     * tails filtered logcat tags (`VoiceInteractionManagerService`, `SoundTriggerMiddlewareLogging`,
     * `HotwordDetectionService`, `ActivityTaskManager`) so we intercept "Hey Google" at the hardware
     * SoundTrigger boundary (<15ms latency, before the Google UI even finishes inflating).
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
                    "VoiceInteractionManagerService:I",
                    "SoundTriggerMiddlewareLogging:I",
                    "HotwordDetectionService:I",
                    "ActivityTaskManager:I",
                    "*:S"
                )
                val proc = Runtime.getRuntime().exec(cmd)
                logcatProcess = proc
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line: String? = null
                while (!Thread.currentThread().isInterrupted && reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (!ChotPrefs.isEnabled(this@ChotAccessibilityService)) continue

                    val isDspHotword =
                        (l.contains("VoiceInteractionManagerService") &&
                            (l.contains("showSession") || l.contains("startVoiceActivity") || l.contains("Hotword"))) ||
                            (l.contains("SoundTriggerMiddlewareLogging") && l.contains("onRecognition")) ||
                            (l.contains("ActivityTaskManager") && l.contains("START u0") &&
                                (l.contains("VoicePlateActivity") ||
                                    l.contains("RobinVoicePlateActivity") ||
                                    l.contains("com.google.android.apps.search.assistant")))

                    if (isDspHotword) {
                        handler.post {
                            triggerIntercept("DSP-Logcat")
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

        private val ASSISTANT_OVERLAY_CLASSES = listOf(
            "VoicePlate",
            "RobinVoicePlate",
            "RobinActivity",
            "AssistantActivity",
            "OpaActivity",
            "ZeroState",
            "LockscreenEntry",
            "com.google.android.apps.search.assistant",
            "com.google.android.apps.bard"
        )

        private val NORMAL_SEARCH_CLASSES = listOf(
            "SearchActivity",
            "GoogleAppActivity",
            "PixelSearch",
            "CustomTabs"
        )

        private val ASSISTANT_VIEW_ID_TOKENS = listOf(
            ":id/assistant_",
            ":id/robin_",
            ":id/opa_",
            ":id/voice_plate",
            ":id/zero_state",
            ":id/ lockscreen_",
            ":id/glow_view",
            ":id/transcription_"
        )

        private val ASSISTANT_PROMPT_STRINGS = listOf(
            "Listening...",
            "Listening…",
            "Speak now",
            "Ask Gemini",
            "Talk to Gemini",
            "Hi, how can I help?"
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
