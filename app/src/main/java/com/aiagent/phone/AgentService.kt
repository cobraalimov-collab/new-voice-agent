package com.aiagent.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground service: continuous wake-word listening + autonomous task execution.
 * Everything touching SpeechRecognizer happens on the main thread.
 */
class AgentService : Service() {

    companion object {
        const val ACTION_START = "com.aiagent.phone.START"
        const val ACTION_STOP = "com.aiagent.phone.STOP"
        private const val CHANNEL_ID = "agent_listening"
        private const val NOTIF_ID = 1001
        private val WAKE_WORDS = listOf("agent", "агент", "yordamchi")

        @Volatile
        var running = false

        fun start(ctx: Context) {
            val i = Intent(ctx, AgentService::class.java).setAction(ACTION_START)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AgentService::class.java))
        }
    }

    private enum class Mode { WAKE, COMMAND, BUSY }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var recognizer: SpeechRecognizer? = null
    private var mode = Mode.WAKE
    private var commandMisses = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    private val restartRunnable = Runnable { startListening() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AgentLog.add("SYS", "Stop requested from notification")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!startForegroundSafe()) {
            AgentLog.add("ERROR", "Could not enter foreground mode (is the microphone permission granted?)")
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        started = true

        if (!LicenseManager.isLicensed(this)) {
            AgentLog.add("ERROR", "License invalid - stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        acquireWakeLock()
        AgentLog.add("SYS", "Listening service started")
        mode = Mode.WAKE
        startListening()
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ foreground

    private fun startForegroundSafe(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "AI Agent listening", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)

        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        @Suppress("DEPRECATION")
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("AI Phone Agent")
            .setContentText("Listening for \"agent\" / \"агент\" / \"yordamchi\"")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AgentLog.add("ERROR", "RECORD_AUDIO not granted")
            // still must satisfy startForegroundService contract; try plain foreground, ignore failure
            return try {
                startForeground(NOTIF_ID, notification)
                false
            } catch (e: Exception) {
                false
            }
        }
        return try {
            when {
                Build.VERSION.SDK_INT >= 34 -> startForeground(
                    NOTIF_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                Build.VERSION.SDK_INT >= 29 -> startForeground(
                    NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
                else -> startForeground(NOTIF_ID, notification)
            }
            true
        } catch (e: Exception) {
            AgentLog.add("ERROR", "startForeground failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aiagent:listen").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            AgentLog.add("ERROR", "WakeLock failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ recognizer

    private fun scheduleRestart(delayMs: Long) {
        main.removeCallbacks(restartRunnable)
        main.postDelayed(restartRunnable, delayMs)
    }

    private fun stopRecognizer() {
        main.removeCallbacks(restartRunnable)
        val r = recognizer
        recognizer = null
        if (r != null) {
            try {
                r.cancel()
            } catch (_: Exception) {
            }
            try {
                r.destroy()
            } catch (_: Exception) {
            }
        }
    }

    private fun startListening() {
        if (!running || mode == Mode.BUSY) return
        main.removeCallbacks(restartRunnable)
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            AgentLog.add("ERROR", "No speech recognition service on this phone (install/enable the Google app / Speech Services). Retrying in 5s")
            scheduleRestart(5000)
            return
        }
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
                    it.setRecognitionListener(listener)
                }
            }
            val lang = Prefs.getLang(this)
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.recognizerTag)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            recognizer?.startListening(i)
        } catch (e: Exception) {
            AgentLog.add("ERROR", "Recognizer start failed: ${e.javaClass.simpleName}: ${e.message}")
            stopRecognizer()
            scheduleRestart(1000)
        }
    }

    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        else -> "code $code"
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            if (!running || mode == Mode.BUSY) return
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                AgentLog.add("MIC", "Recognizer error: ${errorName(error)}")
            }
            if (mode == Mode.COMMAND &&
                (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            ) {
                commandMisses++
                if (commandMisses >= 2) {
                    AgentLog.add("MIC", "No command heard - back to wake-word mode")
                    mode = Mode.WAKE
                }
            }
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(500)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    stopRecognizer()
                    scheduleRestart(500)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    AgentLog.add("ERROR", "Microphone permission missing - stopping")
                    stopSelf()
                }
                SpeechRecognizer.ERROR_CLIENT -> {
                    stopRecognizer()
                    scheduleRestart(700)
                }
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> {
                    stopRecognizer()
                    scheduleRestart(1500)
                }
                else -> {
                    stopRecognizer()
                    scheduleRestart(1000)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            if (!running || mode == Mode.BUSY) return
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            if (list.isEmpty()) {
                scheduleRestart(500)
                return
            }
            AgentLog.add("MIC", "Heard: ${list.joinToString(" | ")}")
            handleResults(list)
        }
    }

    // ------------------------------------------------------------------ wake word / commands

    /** Returns (index, wakeWord) of the earliest wake word in [text], case-insensitive. */
    private fun findWake(text: String): Pair<Int, String>? {
        val l = text.lowercase(Locale.ROOT)
        var best = -1
        var word = ""
        for (w in WAKE_WORDS) {
            val i = l.indexOf(w)
            if (i >= 0 && (best < 0 || i < best)) {
                best = i
                word = w
            }
        }
        return if (best < 0) null else best to word
    }

    private fun commandAfter(text: String, idx: Int, word: String): String {
        val from = (idx + word.length).coerceAtMost(text.length)
        return text.substring(from).trim(' ', ',', '.', '!', '?', ':', ';', '-')
    }

    private fun handleResults(list: List<String>) {
        if (mode == Mode.WAKE) {
            for (t in list) {
                val f = findWake(t) ?: continue
                val cmd = commandAfter(t, f.first, f.second)
                AgentLog.add("MIC", "Wake word '${f.second}' detected")
                if (cmd.length >= 3) runTask(cmd) else promptForCommand()
                return
            }
            scheduleRestart(200)
        } else if (mode == Mode.COMMAND) {
            var cmd = list[0].trim()
            val f = findWake(cmd)
            if (f != null && f.first == 0) cmd = commandAfter(cmd, f.first, f.second)
            if (cmd.length < 2) {
                scheduleRestart(300)
            } else {
                runTask(cmd)
            }
        }
    }

    private fun promptForCommand() {
        mode = Mode.BUSY
        stopRecognizer()
        val lang = Prefs.getLang(this)
        TtsManager.speak(lang.listening(), lang) {
            main.postDelayed({
                if (running) {
                    mode = Mode.COMMAND
                    commandMisses = 0
                    startListening()
                }
            }, 300)
        }
    }

    private fun runTask(command: String) {
        mode = Mode.BUSY
        stopRecognizer()
        val lang = Prefs.getLang(this)
        AgentLog.add("MIC", "Command: $command")
        job?.cancel()
        job = scope.launch {
            val text = try {
                AgentLoop.run(applicationContext, command, lang)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AgentLog.add("ERROR", "Agent loop crashed: ${e.javaClass.simpleName}: ${e.message}")
                lang.failed()
            }
            TtsManager.speak(text, lang) { resumeWake() }
        }
    }

    private fun resumeWake() {
        if (!running) return
        mode = Mode.WAKE
        AgentLog.add("MIC", "Back to wake-word listening")
        main.postDelayed({ startListening() }, 500)
    }

    // ------------------------------------------------------------------ teardown

    override fun onDestroy() {
        running = false
        job?.cancel()
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        stopRecognizer()
        TtsManager.stop()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        AgentLog.add("SYS", "Listening service stopped")
        super.onDestroy()
    }
}
