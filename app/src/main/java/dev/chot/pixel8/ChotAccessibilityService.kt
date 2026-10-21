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
import java.util.concurrent.atomic.AtomicInteger
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
        // Only clear it if it's still this instance, so a late onDestroy can't clear a newer one
        if (instance === this) {
            instance = null
        }
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
            checkClaudeWindow()
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
    private fun hasActiveVoiceInteractionWindow(): Boolean {
        return try {
            windows?.any { win ->
                // Only these two window types can be the overlay; skip the root IPC for status bar, IME etc.
                if (win.type != VOICE_INTERACTION_WINDOW_TYPE && win.type != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    return@any false
                }
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
     * BACK goes through SystemUI and reaches whichever window has input focus when it arrives. If no window has
     * focus yet (an activity is still starting), it waits for the next one, which can be Claude. So every BACK is
     * logged with the window that had focus when it was sent. Only the window is looked up first; describing it
     * means a round trip to its app, so that waits until the BACK is on its way.
     */
    private fun sendBack() {
        val focused = findFocusedWindow()
        val ok = try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (_: Exception) {
            false
        }
        ChotPrefs.appendLog(this, "BACK sent (focus: ${describeWindow(focused)}) -> $ok")
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
     *    wait for the owner to unlock if the phone is locked, and launch Claude into Voice Mode.
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
            // Step 1: Dismiss Google Assistant / Gemini overlay immediately. A second BACK used to follow if the
            // overlay lingered; it never went out in 23 phone intercepts, and a late one can close Claude.
            sendBack()
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
            armClaudeVoiceAutoTap(this)
        }
    }

    /**
     * When Claude opens after a hotword intercept, ensures Claude enters active Voice Mode. Runs on Claude's window
     * events and on the 120ms poll (`schedulePoll`):
     * - Searches Claude's full accessibility node tree (up to depth 40) for the Voice Mode ("Voice Mode" / "Talk") button.
     * - Performs ACTION_CLICK and verifies transition (falling back to an exact bounds tap gesture if needed).
     */
    private fun checkClaudeWindow() {
        try {
            handleClaudeWindowEvent()
        } catch (e: Exception) {
            // Log instead of crashing: a crash takes "Hey Google" down until Android restarts the service
            if (!autoTapErrorLogged.getAndSet(true)) {
                ChotPrefs.appendLog(this, "Auto-tap error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
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
            val seen = if (claudeWindowSeenAtMs.get() > 0L) "was seen" else "was never seen"
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

        val viaWidget = launchedViaWidget.get()
        val roots = getClaudeRootNodes()
        if (roots.isEmpty()) {
            if (viaWidget && claudeWindowSeenAtMs.get() == 0L && elapsed >= WIDGET_OPEN_TIMEOUT_MS) {
                launchClaudeAfterWidgetPress(elapsed)
                return
            }
            // Claude's window was up and is gone before voice mode started, e.g. closed by a stray BACK
            if (claudeWindowSeenAtMs.get() > 0L && !claudeWindowLossLogged.getAndSet(true)) {
                ChotPrefs.appendLog(
                    this,
                    "Claude's window left the screen at ${elapsed}ms before voice mode (focus: ${describeFocusedWindow()})"
                )
            }
            return
        }
        claudeWindowSeenAtMs.compareAndSet(0L, elapsed)

        // After a press on Claude's widget, voice mode starts by itself: give it time, counted from when Claude's
        // window appeared, before tapping anything
        val tapAllowedAfterMs = if (viaWidget) claudeWindowSeenAtMs.get() + WIDGET_TAP_GRACE_MS else 180L

        for (root in roots) {
            // 1. Check if Claude is already in active Voice Mode UI
            if (isClaudeAlreadyInVoiceMode(root)) {
                claudeAutoTapArmedEpochMs.set(0L)
                // After a widget press, also log when Claude's window appeared, to tune WIDGET_OPEN_TIMEOUT_MS
                val window = if (viaWidget) ", window at ${claudeWindowSeenAtMs.get()}ms" else ""
                ChotPrefs.appendLog(this, "Claude Voice Mode is active and listening (${elapsed}ms$window)")
                return
            }

            if (elapsed < tapAllowedAfterMs) continue

            // 2. Search for the Voice Mode ("Voice Mode" / "Talk") button in Claude's accessibility tree
            val voiceButton = findClaudeVoiceModeButton(root) ?: continue
            if (tapVoiceButton(voiceButton)) return
        }
        if (elapsed < tapAllowedAfterMs) return

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
            // A skipped tap leaves the auto-tap armed: the Voice button can still show up before the 6s limit
            if (dispatchCoordinateTap(roots)) {
                claudeAutoTapArmedEpochMs.set(0L)
            }
        }
    }

    /**
     * The widget press didn't bring Claude up, for example because the system blocked the launch: start Claude the
     * usual way and let the auto-tap find the Voice button.
     */
    private fun launchClaudeAfterWidgetPress(elapsed: Long) {
        ChotPrefs.appendLog(this, "Claude didn't open ${elapsed}ms after the widget press: launching it directly")
        val target = ClaudeLauncher.launchClaude(this, ChotPrefs.getLaunchMode(this))
        ChotPrefs.appendLog(this, "Launched $target")
        armClaudeVoiceAutoTap(this)
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
        val rawText = node.text?.toString().orEmpty()
        // A text box's text is the owner's draft: log only that there is one, not what it says. Untrimmed, so a
        // draft of only spaces or newlines shows up too.
        val t = if (node.isEditable && rawText.isNotEmpty() && !node.isShowingHintText) {
            "<draft, ${rawText.length} chars>"
        } else {
            rawText.trim()
        }
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

    /**
     * Taps the configured spot, but only where it can't hit anything other than an unlabeled Voice button: with a
     * draft in Claude's text box, Send sits exactly there, and in a Claude Code session it can be Stop.
     * Returns false if it skipped the tap.
     */
    private fun dispatchCoordinateTap(claudeRoots: List<AccessibilityNodeInfo>): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels * ChotPrefs.getTapXPercent(this)
        val y = dm.heightPixels * ChotPrefs.getTapYPercent(this)
        val point = "(${x.toInt()}, ${y.toInt()})"

        if (isKeyboardAt(x.toInt(), y.toInt())) {
            ChotPrefs.appendLog(this, "Coordinate fallback skipped: the keyboard covers $point")
            return false
        }
        val target = claudeRoots.firstNotNullOfOrNull { findClickableAt(it, x.toInt(), y.toInt()) }
        val label = target?.let { describeLabel(it) }.orEmpty()
        if (target != null && label.isNotEmpty() && findClaudeVoiceModeButton(target) == null) {
            ChotPrefs.appendLog(this, "Coordinate fallback skipped: $point is on '$label'")
            return false
        }

        val ok = dispatchTapGesture(x, y)
        ChotPrefs.appendLog(this, "Coordinate fallback tap at $point -> $ok")
        return true
    }

    private fun isKeyboardAt(x: Int, y: Int): Boolean {
        return try {
            windows?.any { win ->
                if (win.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) return@any false
                val r = Rect()
                win.getBoundsInScreen(r)
                r.contains(x, y)
            } == true
        } catch (_: Exception) {
            false
        }
    }

    /** The smallest clickable node under (x, y). */
    private fun findClickableAt(node: AccessibilityNodeInfo, x: Int, y: Int, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 40) return null
        val r = Rect()
        node.getBoundsInScreen(r)
        if (!r.contains(x, y)) return null
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue
            findClickableAt(child, x, y, depth + 1)?.let { return it }
        }
        return if (node.isClickable) node else null
    }

    /**
     * A node's own label, or the first one inside it (Claude puts the Voice button's label on a child). A text box
     * counts as labeled but is never quoted, so the owner's draft stays out of the log.
     */
    private fun describeLabel(node: AccessibilityNodeInfo, depth: Int = 0): String? {
        if (depth > 6) return null
        if (node.isEditable) return "text box"
        val own = (node.contentDescription ?: node.text)?.toString()?.trim()
        if (!own.isNullOrEmpty()) return own
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue
            describeLabel(child, depth + 1)?.let { return it }
        }
        return null
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
        private val offScreenRetryLogged = AtomicBoolean(false)
        // Milliseconds after arming when Claude's window first showed up, 0 until it does
        private val claudeWindowSeenAtMs = AtomicLong(0L)
        private val claudeWindowLossLogged = AtomicBoolean(false)
        private val launchedViaWidget = AtomicBoolean(false)
        private val autoTapErrorLogged = AtomicBoolean(false)
        private val noServiceLogged = AtomicBoolean(false)
        private val pollGeneration = AtomicInteger(0)
        private val pollHandler = Handler(Looper.getMainLooper())

        // After a press on Claude's widget: how long Claude gets to open before chot launches it directly, and how
        // long its window gets to enter voice mode before chot looks for the Voice button. On the Pixel 8 chot saw
        // Claude's window 180-224ms after the press when warm and 269ms when cold, and voice mode at the same moment
        // when warm and at 505ms when cold. These keep even the fallback under 3s from the hotword.
        private const val WIDGET_OPEN_TIMEOUT_MS = 1000L
        private const val WIDGET_TAP_GRACE_MS = 700L

        /** Called right after every Claude launch. */
        fun armClaudeVoiceAutoTap(context: Context) = arm(context, viaWidget = false)

        /** Called right after pressing the Voice chat button on chot's copy of Claude's widget. */
        fun armAfterWidgetPress(context: Context) = arm(context, viaWidget = true)

        private fun arm(context: Context, viaWidget: Boolean) {
            coordinateFallbackScheduled.set(false)
            diagnosticTreeDumped.set(false)
            offScreenRetryLogged.set(false)
            claudeWindowSeenAtMs.set(0L)
            claudeWindowLossLogged.set(false)
            autoTapErrorLogged.set(false)
            noServiceLogged.set(false)
            launchedViaWidget.set(viaWidget)
            claudeAutoTapArmedEpochMs.set(System.currentTimeMillis())
            schedulePoll(context.applicationContext, pollGeneration.incrementAndGet(), 100L)
        }

        /**
         * Checks Claude's window every 120ms while armed, in addition to window events, so Compose updates are caught
         * quickly. It runs through whichever service instance is connected when it fires, rather than the one
         * connected at arming, and stops when a newer arming starts its own poll.
         */
        private fun schedulePoll(context: Context, generation: Int, delayMs: Long) {
            pollHandler.postDelayed({
                if (generation != pollGeneration.get()) return@postDelayed
                val armedAt = claudeAutoTapArmedEpochMs.get()
                if (armedAt == 0L) return@postDelayed

                val service = instance
                if (service != null) {
                    service.checkClaudeWindow()
                } else {
                    if (!noServiceLogged.getAndSet(true)) {
                        ChotPrefs.appendLog(context, "Auto-tap: chot's accessibility service isn't connected")
                    }
                    if (System.currentTimeMillis() - armedAt > 6000L) {
                        claudeAutoTapArmedEpochMs.set(0L)
                    }
                }
                if (claudeAutoTapArmedEpochMs.get() != 0L) {
                    schedulePoll(context, generation, 120L)
                }
            }, delayMs)
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
