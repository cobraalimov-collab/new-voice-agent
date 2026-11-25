package com.aiagent.phone

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var etKey: EditText
    private lateinit var cbShow: CheckBox
    private lateinit var tvMic: TextView
    private lateinit var tvNotif: TextView
    private lateinit var tvAcc: TextView
    private lateinit var btnMic: Button
    private lateinit var btnNotif: Button
    private lateinit var btnAcc: Button
    private lateinit var btnAppInfo: Button
    private lateinit var rgLang: RadioGroup
    private lateinit var btnTest: Button
    private lateinit var btnListen: Button
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView

    private val logListener: (String) -> Unit = { line -> main.post { appendLog(line) } }

    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        TtsManager.init(this)

        etKey = findViewById(R.id.etKey)
        cbShow = findViewById(R.id.cbShow)
        tvMic = findViewById(R.id.tvMic)
        tvNotif = findViewById(R.id.tvNotif)
        tvAcc = findViewById(R.id.tvAcc)
        btnMic = findViewById(R.id.btnMic)
        btnNotif = findViewById(R.id.btnNotif)
        btnAcc = findViewById(R.id.btnAcc)
        btnAppInfo = findViewById(R.id.btnAppInfo)
        rgLang = findViewById(R.id.rgLang)
        btnTest = findViewById(R.id.btnTest)
        btnListen = findViewById(R.id.btnListen)
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)

        // API key (auto-saved on every change)
        etKey.setText(Prefs.getApiKey(this))
        etKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                Prefs.setApiKey(this@MainActivity, ClaudeClient.sanitizeKey(s?.toString() ?: ""))
            }
        })
        cbShow.setOnCheckedChangeListener { _, checked ->
            val sel = etKey.selectionEnd
            etKey.inputType = InputType.TYPE_CLASS_TEXT or
                    (if (checked) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
            etKey.setSelection(if (sel >= 0) sel.coerceAtMost(etKey.text.length) else etKey.text.length)
        }

        // Language
        when (Prefs.getLang(this)) {
            AppLang.UZ -> (findViewById<RadioButton>(R.id.rbUz)).isChecked = true
            AppLang.RU -> (findViewById<RadioButton>(R.id.rbRu)).isChecked = true
            AppLang.EN -> (findViewById<RadioButton>(R.id.rbEn)).isChecked = true
        }
        rgLang.setOnCheckedChangeListener { _, id ->
            val lang = when (id) {
                R.id.rbRu -> AppLang.RU
                R.id.rbEn -> AppLang.EN
                else -> AppLang.UZ
            }
            Prefs.setLang(this, lang)
            AgentLog.add("SYS", "Language: ${lang.label}")
        }

        // Permissions
        btnMic.setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
        btnNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            } else {
                openAppNotificationSettings()
            }
        }
        btnAcc.setOnClickListener {
            AgentLog.add("SYS", "Enable 'AI Phone Agent' in Accessibility. If the switch is greyed out: tap 'App info', then menu (3 dots) > 'Allow restricted settings'.")
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                AgentLog.add("ERROR", "Cannot open accessibility settings: ${e.message}")
            }
        }
        btnAppInfo.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                AgentLog.add("ERROR", "Cannot open app info: ${e.message}")
            }
        }

        btnTest.setOnClickListener { testVoiceAndApi() }
        btnListen.setOnClickListener { toggleListening() }

        AgentLog.add("SYS", "Ready. Enter the API key, grant permissions, press 'Test Voice & API'.")
    }

    override fun onStart() {
        super.onStart()
        tvLog.text = AgentLog.snapshot().joinToString("\n") + "\n"
        scrollLogToEnd()
        AgentLog.addListener(logListener)
    }

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    override fun onPause() {
        main.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onStop() {
        AgentLog.removeListener(logListener)
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        AgentLog.add("SYS", "Permission ${permissions.firstOrNull()?.substringAfterLast('.')}: ${if (granted) "granted" else "DENIED"}")
        refreshStatus()
    }

    // ------------------------------------------------------------------ UI helpers

    private fun currentLang(): AppLang = Prefs.getLang(this)

    private fun appendLog(line: String) {
        tvLog.append(line + "\n")
        val len = tvLog.text.length
        if (len > 30000) {
            tvLog.text = tvLog.text.subSequence(len - 20000, len)
        }
        scrollLogToEnd()
    }

    private fun scrollLogToEnd() {
        svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun notifGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
        } catch (e: Exception) {
            true
        }
    }

    private fun setStatus(tv: TextView, btn: Button, name: String, ok: Boolean) {
        tv.text = (if (ok) "● $name: OK" else "● $name: missing")
        tv.setTextColor(if (ok) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
        btn.isEnabled = !ok
    }

    private fun refreshStatus() {
        setStatus(tvMic, btnMic, "Microphone", micGranted())
        setStatus(tvNotif, btnNotif, "Notifications", notifGranted())
        val acc = AgentAccessibilityService.isEnabled(this)
        setStatus(tvAcc, btnAcc, "Accessibility", acc)
        btnListen.text = if (AgentService.running) "Stop Listening" else "Start Listening"
    }

    private fun openAppNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (e: Exception) {
            AgentLog.add("ERROR", "Cannot open notification settings: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ actions

    private fun testVoiceAndApi() {
        val lang = currentLang()
        val key = Prefs.getApiKey(this)
        btnTest.isEnabled = false
        AgentLog.add("SYS", "=== Test Voice & API (${lang.label}) ===")
        scope.launch {
            try {
                if (key.isBlank()) {
                    AgentLog.add("ERROR", "API key is empty - enter it above. Testing voice only.")
                    TtsManager.speak(lang.noKey(), lang)
                    return@launch
                }
                AgentLog.add("API", "Sending test prompt to Claude...")
                val t0 = System.currentTimeMillis()
                val system = "You are a friendly voice assistant on a phone. Reply ONLY in ${lang.promptName}. " +
                        "Maximum two short sentences, plain text, no markdown, no emoji."
                when (val r = ClaudeClient.ask(key, system, "Say hello and confirm that the voice and API test works.", 200)) {
                    is ClaudeClient.ApiResult.Ok -> {
                        AgentLog.add("API", "Reply in ${System.currentTimeMillis() - t0} ms: ${r.text}")
                        TtsManager.speak(r.text, lang)
                    }
                    is ClaudeClient.ApiResult.Err -> {
                        AgentLog.add("ERROR", r.message)
                        TtsManager.speak(lang.apiError(), lang)
                    }
                }
            } finally {
                btnTest.isEnabled = true
            }
        }
    }

    private fun toggleListening() {
        if (AgentService.running) {
            AgentService.stop(this)
            AgentLog.add("SYS", "Stopping listening...")
            main.postDelayed({ refreshStatus() }, 300)
            return
        }
        if (!LicenseManager.isLicensed(this)) {
            AgentLog.add("ERROR", "No valid license")
            return
        }
        if (!micGranted()) {
            AgentLog.add("ERROR", "Microphone permission is required - tap 'Grant' first")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }
        if (Prefs.getApiKey(this).isBlank()) {
            AgentLog.add("ERROR", "Enter the API key first")
            return
        }
        if (!notifGranted()) {
            AgentLog.add("SYS", "Notifications are off - the agent still runs, but its status notification is hidden")
        }
        if (!AgentAccessibilityService.isEnabled(this)) {
            AgentLog.add("SYS", "Accessibility is OFF - the agent can talk but cannot control the phone until you enable it")
        }
        try {
            AgentService.start(this)
            AgentLog.add("SYS", "Starting listening service...")
        } catch (e: Exception) {
            AgentLog.add("ERROR", "Cannot start service: ${e.javaClass.simpleName}: ${e.message}")
        }
        main.postDelayed({ refreshStatus() }, 500)
    }
}
