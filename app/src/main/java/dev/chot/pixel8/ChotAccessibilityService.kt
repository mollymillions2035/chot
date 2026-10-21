package dev.chot.pixel8

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ChotAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val lastInterceptEpochMs = AtomicLong(0L)
    private val isMicRecordingActive = AtomicBoolean(false)

    private val audioRecordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            val wasActive = isMicRecordingActive.getAndSet(configs.isNotEmpty())
            if (!wasActive && configs.isNotEmpty()) {
                // If the Assistant / Gemini overlay is already open when mic recording starts, intercept immediately
                handler.post {
                    if (hasActiveVoiceInteractionWindow()) {
                        triggerIntercept("AssistantOverlayWindow+Mic")
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
    }

    override fun onDestroy() {
        instance = null
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

        // Cheap early-out while paused; triggerIntercept enforces the pause switch for every automatic path
        if (!ChotPrefs.isEnabled(this)) return

        // 2. Inspect Google App / Assistant / Gemini packages
        if (pkg in GOOGLE_ASSISTANT_PACKAGES) {
            val className = event.className?.toString() ?: ""

            // Never hijack normal Google Search or Launcher activities (including the search-bar mic's voice search).
            // Only window-state events carry the activity class; content events and the mic path stay safe because
            // hasActiveVoiceInteractionWindow() accepts nothing but the overlay itself (no text or generic view-id
            // heuristics, which is what used to match voice search).
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
    private fun hasActiveVoiceInteractionWindow(): Boolean = findVoiceInteractionWindow() != null

    private fun findVoiceInteractionWindow(): AccessibilityWindowInfo? {
        return try {
            windows?.firstOrNull { win ->
                // Only these two window types can be the overlay; skip the root IPC for status bar, IME etc.
                if (win.type != VOICE_INTERACTION_WINDOW_TYPE && win.type != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    return@firstOrNull false
                }
                val root = win.root ?: return@firstOrNull false
                val pkg = root.packageName?.toString() ?: ""
                if (pkg in GOOGLE_ASSISTANT_PACKAGES) {
                    win.type == VOICE_INTERACTION_WINDOW_TYPE ||
                        root.findAccessibilityNodeInfosByViewId(GEMINI_OVERLAY_ROOT_VIEW_ID).isNotEmpty()
                } else {
                    false
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * BACK goes through SystemUI and reaches whichever window has input focus when it arrives. If no window has
     * focus yet (an activity is still starting), it waits for the next one, which can be Claude. So every BACK is
     * logged with the window that had focus when it was sent. Only the window is looked up first; describing it
     * means a round trip to its app, so that waits until the BACK is on its way.
     */
    private fun sendBack(label: String) {
        val focused = findFocusedWindow()
        val ok = try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (_: Exception) {
            false
        }
        ChotPrefs.appendLog(this, "$label sent (focus: ${describeWindow(focused)}) -> $ok")
    }

    /**
     * Re-sends BACK only while the overlay still has input focus, so the BACK goes to the overlay and nowhere else,
     * and only before Claude is launched: a BACK still in flight when Claude's window takes focus closes Claude.
     */
    private fun sendFollowUpBackIfOverlayFocused(interceptEpochMs: Long) {
        val overlay = findVoiceInteractionWindow() ?: return
        val delay = "${FOLLOW_UP_BACK_DELAY_MS}ms"
        when {
            lastClaudeLaunchEpochMs.get() >= interceptEpochMs ->
                ChotPrefs.appendLog(this, "Overlay still on screen at $delay, Claude already launched: no follow-up BACK")
            overlay.isFocused -> sendBack("Follow-up BACK")
            else -> ChotPrefs.appendLog(
                this,
                "Overlay still on screen at $delay but not focused (focus: ${describeFocusedWindow()}): no follow-up BACK"
            )
        }
    }

    private fun findFocusedWindow(): AccessibilityWindowInfo? {
        return try {
            windows?.firstOrNull { it.isFocused }
        } catch (_: Exception) {
            null
        }
    }

    private fun describeWindow(win: AccessibilityWindowInfo?): String {
        if (win == null) return "none"
        return try {
            "${win.root?.packageName ?: "?"} '${win.title ?: ""}'"
        } catch (_: Exception) {
            "?"
        }
    }

    private fun describeFocusedWindow(): String = describeWindow(findFocusedWindow())

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
        // Every automatic path (window events, mic callback) ends up here, so the pause switch is checked here
        if (!manual && !ChotPrefs.isEnabled(this)) return

        val now = System.currentTimeMillis()
        val cooldown = ChotPrefs.getCooldownMs(this)
        val prev = lastInterceptEpochMs.get()
        if (!manual && (now - prev) < cooldown) {
            return
        }
        lastInterceptEpochMs.set(now)

        if (!manual) {
            // Step 1: Dismiss Google Assistant / Gemini overlay immediately
            sendBack("BACK")

            // Follow-up check: if the first BACK didn't dismiss the overlay, dismiss again, but only where it can't
            // reach anything else
            handler.postDelayed({ sendFollowUpBackIfOverlayFocused(now) }, FOLLOW_UP_BACK_DELAY_MS)
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
            val seen = if (claudeWindowSeen.get()) "was seen" else "was never seen"
            ChotPrefs.appendLog(this, "Auto-tap gave up after 6s, Claude's window $seen (focus: ${describeFocusedWindow()})")
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
        if (roots.isEmpty()) {
            // Claude's window was up and is gone before voice mode started, e.g. closed by a stray BACK
            if (claudeWindowSeen.get() && !claudeWindowLossLogged.getAndSet(true)) {
                ChotPrefs.appendLog(
                    this,
                    "Claude's window left the screen at ${elapsed}ms before voice mode (focus: ${describeFocusedWindow()})"
                )
            }
            return
        }
        claudeWindowSeen.set(true)

        for (root in roots) {
            // 1. Check if Claude is already in active Voice Mode UI
            if (isClaudeAlreadyInVoiceMode(root)) {
                claudeAutoTapArmedEpochMs.set(0L)
                ChotPrefs.appendLog(this, "Claude Voice Mode is active and listening (${elapsed}ms)")
                return
            }

            // 2. Search for the Voice Mode ("Voice Mode" / "Talk") button in Claude's accessibility tree
            val voiceButton = findClaudeVoiceModeButton(root) ?: continue
            if (tapVoiceButton(voiceButton)) return
        }

        // Dump visible Claude nodes once at ~1200ms if no Voice Mode button has been tapped yet
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

    /**
     * Clicks the Voice Mode button. Right after launch Claude's window can still be sliding in, so the button's
     * bounds can lie partly or wholly off screen. ACTION_CLICK works there anyway, but a tap would miss, so taps only
     * go to bounds fully on screen. Returns false, leaving auto-tap armed so the next poll retries with fresh bounds,
     * when ACTION_CLICK failed and the button isn't fully on screen yet.
     */
    private fun tapVoiceButton(voiceButton: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        voiceButton.getBoundsInScreen(rect)
        val label = voiceButton.contentDescription ?: voiceButton.text ?: voiceButton.className ?: "node"
        val actionClicked = voiceButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!actionClicked && !isFullyOnScreen(rect)) {
            if (!offScreenRetryLogged.getAndSet(true)) {
                ChotPrefs.appendLog(
                    this,
                    "Voice button '$label' at ${rect.toShortString()} is off screen and ACTION_CLICK failed: retrying"
                )
            }
            return false
        }

        claudeAutoTapArmedEpochMs.set(0L)
        var gestureTapped = false
        if (!actionClicked) {
            gestureTapped = dispatchTapGesture(rect.exactCenterX(), rect.exactCenterY())
        }
        ChotPrefs.appendLog(
            this,
            "Auto-tapped Voice button '$label' at ${rect.toShortString()} (action=$actionClicked, gesture=$gestureTapped)"
        )

        // Verify 380ms later: if ACTION_CLICK returned true but the Voice Mode button is still visible
        // (Compose sometimes requires a physical touch event), tap the button where it is now.
        if (actionClicked) {
            handler.postDelayed({ followUpVoiceButtonTap() }, 380L)
        }
        return true
    }

    private fun followUpVoiceButtonTap() {
        val currentRoots = getClaudeRootNodes()
        if (currentRoots.isEmpty() || currentRoots.any { isClaudeAlreadyInVoiceMode(it) }) return
        val button = currentRoots.firstNotNullOfOrNull { findClaudeVoiceModeButton(it) } ?: return
        val rect = Rect()
        button.getBoundsInScreen(rect)
        if (!isFullyOnScreen(rect)) {
            ChotPrefs.appendLog(this, "Voice button still showing at ${rect.toShortString()}, off screen: no follow-up tap")
            return
        }
        val tapOk = dispatchTapGesture(rect.exactCenterX(), rect.exactCenterY())
        ChotPrefs.appendLog(
            this,
            "Follow-up gesture tap on Voice button at (${rect.centerX()}, ${rect.centerY()}) -> $tapOk"
        )
    }

    private fun isFullyOnScreen(rect: Rect): Boolean {
        val screen = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return !rect.isEmpty && screen.contains(rect)
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
        private val lastClaudeLaunchEpochMs = AtomicLong(0L)
        private val coordinateFallbackScheduled = AtomicBoolean(false)
        private val diagnosticTreeDumped = AtomicBoolean(false)
        private val offScreenRetryLogged = AtomicBoolean(false)
        private val claudeWindowSeen = AtomicBoolean(false)
        private val claudeWindowLossLogged = AtomicBoolean(false)

        private const val FOLLOW_UP_BACK_DELAY_MS = 300L

        /** Called right after every Claude launch, so it also records the launch time for the follow-up BACK. */
        fun armClaudeVoiceAutoTap() {
            coordinateFallbackScheduled.set(false)
            diagnosticTreeDumped.set(false)
            offScreenRetryLogged.set(false)
            claudeWindowSeen.set(false)
            claudeWindowLossLogged.set(false)
            val now = System.currentTimeMillis()
            lastClaudeLaunchEpochMs.set(now)
            claudeAutoTapArmedEpochMs.set(now)
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
        private const val GEMINI_OVERLAY_ROOT_VIEW_ID =
            "com.google.android.googlequicksearchbox:id/assistant_robin_floaty_root_container"

        // AccessibilityWindowManager maps WindowManager's TYPE_VOICE_INTERACTION to no AccessibilityWindowInfo type
        private const val VOICE_INTERACTION_WINDOW_TYPE = -1

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
