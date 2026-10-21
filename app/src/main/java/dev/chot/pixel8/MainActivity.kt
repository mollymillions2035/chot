package dev.chot.pixel8

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusContainer: LinearLayout
    private lateinit var logTextView: TextView
    private var pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0F1117")
        window.navigationBarColor = Color.parseColor("#0F1117")
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refreshStatusAndLogs()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F1117"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(18)
            setPadding(pad, dp(24), pad, dp(32))
        }

        // Header
        root.addView(TextView(this).apply {
            text = "chot // PIXEL 8"
            setTextColor(Color.parseColor("#D97757"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "Hardware DSP Hotword → Claude Voice"
            setTextColor(Color.parseColor("#F3F4F6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(6))
        })

        root.addView(TextView(this).apply {
            text = "Keeps the Pixel 8 Tensor G3 \"Hey Google\" hardware DSP active at zero battery cost, intercepts the Assistant overlay the millisecond it triggers, releases the mic, and launches Claude into Voice Mode."
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(0, 0, 0, dp(16))
        })

        // Master Enable Card
        val masterCard = createCard()
        val masterSwitch = Switch(this).apply {
            text = "Intercept \"Hey Google\" → Launch Claude"
            setTextColor(Color.parseColor("#F9FAFB"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Typeface.DEFAULT_BOLD
            isChecked = ChotPrefs.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                ChotPrefs.setEnabled(this@MainActivity, isChecked)
                ChotPrefs.appendLog(
                    this@MainActivity,
                    "Master interceptor ${if (isChecked) "ENABLED" else "PAUSED"}"
                )
                refreshStatusAndLogs()
            }
        }
        masterCard.addView(masterSwitch)
        root.addView(masterCard, cardLayoutParams())

        // Live System Readiness Card
        val readinessCard = createCard()
        readinessCard.addView(sectionTitle("PIXEL 8 SYSTEM CHECKLIST"))
        statusContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        readinessCard.addView(statusContainer)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }

        buttonRow.addView(
            styledButton("1. Enable Accessibility", "#D97757") {
                if (tryAutoEnableAccessibilityService()) {
                    Toast.makeText(
                        this,
                        "Auto-enabled chot Accessibility Service via WRITE_SECURE_SETTINGS!",
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshStatusAndLogs()
                } else {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(6)
            }
        )

        buttonRow.addView(
            styledButton("2. Overlay Permission", "#252A38") {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (_: Exception) {
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
            }
        )

        buttonRow.addView(
            styledButton("3. Assistant Settings", "#252A38") {
                try {
                    startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(4)
            }
        )

        readinessCard.addView(buttonRow)
        readinessCard.addView(
            styledButton("4. Claude Widget (Voice chat button)", "#252A38") { setUpClaudeWidget() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )
        root.addView(readinessCard, cardLayoutParams())

        // Launch Strategy & Voice Auto-Tap Card
        val configCard = createCard()
        configCard.addView(sectionTitle("CLAUDE VOICE LAUNCH STRATEGY"))

        val modes = LaunchMode.entries
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                modes.map { it.label }
            )
            setBackgroundColor(Color.parseColor("#252A38"))
            setSelection(modes.indexOf(ChotPrefs.getLaunchMode(this@MainActivity)).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    (view as? TextView)?.setTextColor(Color.parseColor("#F3F4F6"))
                    ChotPrefs.setLaunchMode(this@MainActivity, modes[pos])
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        configCard.addView(spinner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })

        val autoTapCheck = CheckBox(this).apply {
            text = "Auto-tap Claude Voice Mode waveform button if not already listening"
            setTextColor(Color.parseColor("#E5E7EB"))
            isChecked = ChotPrefs.isAutoTapVoiceEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                ChotPrefs.setAutoTapVoiceEnabled(this@MainActivity, checked)
            }
        }
        configCard.addView(autoTapCheck)

        val coordCheck = CheckBox(this).apply {
            text = "Enable Pixel 8 coordinate fallback tap (bottom-right waveform icon)"
            setTextColor(Color.parseColor("#E5E7EB"))
            isChecked = ChotPrefs.isCoordinateFallbackEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                ChotPrefs.setCoordinateFallbackEnabled(this@MainActivity, checked)
            }
        }
        configCard.addView(coordCheck)

        // Coordinate inputs
        val coordRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val xInput = EditText(this).apply {
            setText((ChotPrefs.getTapXPercent(this@MainActivity) * 100f).toInt().toString())
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "X %"
        }
        val yInput = EditText(this).apply {
            setText((ChotPrefs.getTapYPercent(this@MainActivity) * 100f).toInt().toString())
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Y %"
        }
        coordRow.addView(TextView(this).apply {
            text = "Waveform X%/Y%: "
            setTextColor(Color.parseColor("#9CA3AF"))
        })
        coordRow.addView(xInput, LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
        coordRow.addView(TextView(this).apply {
            text = " %   "
            setTextColor(Color.parseColor("#9CA3AF"))
        })
        coordRow.addView(yInput, LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
        coordRow.addView(TextView(this).apply {
            text = " %  "
            setTextColor(Color.parseColor("#9CA3AF"))
        })
        coordRow.addView(styledButton("Save", "#252A38") {
            val x = (xInput.text.toString().toFloatOrNull() ?: 88.5f) / 100f
            val y = (yInput.text.toString().toFloatOrNull() ?: 91.5f) / 100f
            ChotPrefs.setTapPercent(this, x, y)
            Toast.makeText(this, "Saved waveform tap coordinates (${(x * 100).toInt()}%, ${(y * 100).toInt()}%)", Toast.LENGTH_SHORT).show()
        })
        configCard.addView(coordRow)

        // Test Handover Button
        val testBtn = styledButton("⚡ Test Hotword Handover → Launch Claude Voice Now", "#D97757") {
            val svc = ChotAccessibilityService.instance
            if (svc != null) {
                svc.triggerIntercept("Manual-UI-Test", manual = true)
            } else {
                val intent = Intent(this, ChotTrampolineActivity::class.java).apply {
                    putExtra(ChotTrampolineActivity.EXTRA_TRIGGER_SOURCE, "Manual-Trampoline-Test")
                }
                startActivity(intent)
            }
        }
        configCard.addView(
            testBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        )

        root.addView(configCard, cardLayoutParams())

        // Live Diagnostic Log Card
        val logCard = createCard()
        val logHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        logHeader.addView(
            sectionTitle("LIVE INTERCEPT DIAGNOSTIC LOG"),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        logHeader.addView(styledButton("Refresh", "#252A38") { refreshStatusAndLogs() })
        logHeader.addView(
            styledButton("Clear", "#252A38") {
                ChotPrefs.clearLogs(this)
                refreshStatusAndLogs()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(6) }
        )
        logCard.addView(logHeader)

        logTextView = TextView(this).apply {
            setTextColor(Color.parseColor("#A7F3D0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, 0)
        }
        logCard.addView(logTextView)
        root.addView(logCard, cardLayoutParams())

        scroll.addView(root)
        return scroll
    }

    private fun refreshStatusAndLogs() {
        statusContainer.removeAllViews()

        // 1. Check Claude App & Discovered Intents
        val targets = ClaudeLauncher.discoverTargets(this)
        val claudeDetail = if (targets.isInstalled) {
            val found = listOfNotNull(
                targets.voiceAssistComponent?.let { "VOICE_ASSIST (${it.shortClassName})" },
                targets.assistComponent?.let { "ASSIST (${it.shortClassName})" },
                targets.namedVoiceOrAssistActivity?.let { "VoiceActivity (${it.shortClassName})" },
                targets.mainLauncherComponent?.let { "Main (${it.shortClassName})" }
            ).joinToString(", ")
            "Installed • Targets: $found"
        } else {
            "NOT INSTALLED — Install official Claude app (com.anthropic.claude) from Play Store"
        }
        statusContainer.addView(statusRow("Claude Android App", targets.isInstalled, claudeDetail))

        // 2. Check System Default Digital Assistant (Must be Google so Pixel 8 DSP stays active!)
        val voiceInteractionService = try {
            Settings.Secure.getString(contentResolver, "voice_interaction_service") ?: ""
        } catch (_: Exception) {
            ""
        }
        val isGoogleDefault = voiceInteractionService.contains("googlequicksearchbox", ignoreCase = true) ||
            voiceInteractionService.isEmpty()
        val assistantMsg = if (isGoogleDefault) {
            "Google is Default Assistant → Pixel 8 Tensor G3 \"Hey Google\" DSP is ACTIVE"
        } else {
            "WARNING: Default Assistant is '$voiceInteractionService'. Set it back to Google (Gemini) in Settings > Apps > Default apps > Digital assistant app so Pixel 8 hardware \"Hey Google\" DSP stays active!"
        }
        statusContainer.addView(statusRow("Pixel 8 Hardware DSP (\"Hey Google\")", isGoogleDefault, assistantMsg))

        // 3. Check chot Accessibility Service
        val isAccessEnabled = isAccessibilityServiceEnabled()
        val accessMsg = if (isAccessEnabled) {
            "Active & watching for \"Hey Google\" overlay + Claude Voice handover"
        } else {
            "DISABLED — Tap '1. Enable Accessibility' below and turn on chot"
        }
        statusContainer.addView(statusRow("chot Accessibility Interceptor", isAccessEnabled, accessMsg))

        // 4. Check Overlay Permission (Android 14/15/16 Background Activity Launch exemption)
        val hasOverlay = Settings.canDrawOverlays(this)
        val overlayMsg = if (hasOverlay) {
            "GRANTED — Android 14/15/16 Background Activity Launch exemption active"
        } else {
            "Tap '2. Overlay Permission' below (or run setup script) so Android 14+ allows instant background launch of Claude"
        }
        statusContainer.addView(statusRow("Display Over Other Apps (BAL)", hasOverlay, overlayMsg))

        // 5. Check chot's copy of Claude's widget, whose Voice chat button starts voice mode directly
        val widgetId = ClaudeWidget.boundWidgetId(this)
        val widgetMsg = when {
            widgetId != null ->
                "SET UP • \"Hey Google\" presses the widget's Voice chat button, which starts voice mode even with a draft or a Claude Code session open"
            ClaudeWidget.findProvider(this) == null ->
                "Claude's widget isn't available. Update the Claude app. Until then chot opens Claude and taps its Voice button."
            else ->
                "Not set up • Tap '4. Claude Widget' below. Without it chot opens Claude and taps its Voice button, which fails with a draft or a Claude Code session open."
        }
        statusContainer.addView(statusRow("Claude Widget (Voice chat button)", widgetId != null, widgetMsg))

        logTextView.text = ChotPrefs.getLogs(this)
    }

    /** Binds chot's own, never-shown copy of Claude's widget. The first time, the system asks the owner. */
    private fun setUpClaudeWidget() {
        val provider = ClaudeWidget.findProvider(this)
        if (provider == null) {
            Toast.makeText(this, "Claude's widget isn't available. Update the Claude app.", Toast.LENGTH_LONG).show()
            return
        }
        val id = ClaudeWidget.allocateWidgetId(this)
        if (ClaudeWidget.tryBind(this, id, provider)) {
            onClaudeWidgetBound(id)
            return
        }
        pendingWidgetId = id
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(ClaudeWidget.bindPermissionIntent(id, provider), REQUEST_BIND_CLAUDE_WIDGET)
        } catch (_: Exception) {
            ClaudeWidget.release(this, id)
            Toast.makeText(this, "This phone doesn't offer the widget permission prompt.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_BIND_CLAUDE_WIDGET) return
        val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId) ?: pendingWidgetId
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        if (resultCode == RESULT_OK) {
            onClaudeWidgetBound(id)
        } else {
            ClaudeWidget.release(this, id)
            Toast.makeText(this, "chot needs that permission to use Claude's widget.", Toast.LENGTH_LONG).show()
        }
    }

    private fun onClaudeWidgetBound(id: Int) {
        ClaudeWidget.onBound(this, id)
        Toast.makeText(this, "Claude's widget is set up.", Toast.LENGTH_SHORT).show()
        refreshStatusAndLogs()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (ChotAccessibilityService.instance != null) return true
        val expected = ComponentName(this, ChotAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun tryAutoEnableAccessibilityService(): Boolean {
        if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return try {
            val comp = ComponentName(this, ChotAccessibilityService::class.java).flattenToString()
            val current = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val updated = if (current.isBlank()) {
                comp
            } else if (!current.contains(comp)) {
                "$current:$comp"
            } else {
                current
            }
            Settings.Secure.putString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                updated
            )
            Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun statusRow(title: String, ok: Boolean, subtitle: String): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val badge = if (ok) "● READY" else "○ ACTION NEEDED"
        val badgeColor = if (ok) "#34D399" else "#FBBF24"

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        top.addView(
            TextView(this).apply {
                text = title
                setTextColor(Color.parseColor("#F3F4F6"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        top.addView(TextView(this).apply {
            text = badge
            setTextColor(Color.parseColor(badgeColor))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        })
        wrap.addView(top)

        wrap.addView(TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(2), 0, 0)
        })
        return wrap
    }

    private fun createCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#181B24"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#262B38"))
            }
        }
    }

    private fun cardLayoutParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(14)
        }

    private fun sectionTitle(label: String): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }

    private fun styledButton(label: String, bgHex: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    companion object {
        private const val REQUEST_BIND_CLAUDE_WIDGET = 1
    }
}
