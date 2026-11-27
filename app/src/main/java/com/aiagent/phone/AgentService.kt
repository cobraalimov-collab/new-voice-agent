package com.aiagent.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Foreground service: continuous wake-word listening + autonomous task execution.
 *
 * Ears = AudioRecord (16 kHz mono PCM16) + energy VAD on Dispatchers.IO.
 * Each detected utterance is written to a temporary WAV file in cacheDir and transcribed by
 * Google Gemini 2.5 Flash (inline audio, no fixed language -> Uzbek/English/Russian code-switching works).
 * The microphone is released whenever the agent is BUSY (speaking or acting) so it never hears itself.
 */
class AgentService : Service() {

    companion object {
        const val ACTION_START = "com.aiagent.phone.START"
        const val ACTION_STOP = "com.aiagent.phone.STOP"
        private const val CHANNEL_ID = "agent_listening"
        private const val NOTIF_ID = 1001
        private val WAKE_WORDS = listOf("agent", "агент", "yordamchi", "ёрдамчи")

        // ---- audio / VAD ----
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SAMPLES = 512                       // 32 ms per frame
        private const val FRAME_MS = FRAME_SAMPLES * 1000 / SAMPLE_RATE
        private const val VAD_THRESHOLD = 1100                      // base amplitude threshold
        private const val VAD_MAX_THRESHOLD = 6000                  // cap for the adaptive threshold
        private const val START_FRAMES = 2                          // loud frames in a row to start
        private const val PRE_ROLL_FRAMES = 10                      // ~320 ms kept before speech start
        private const val MIN_SPEECH_MS = 250                       // shorter = click/bump, ignored
        private const val WAKE_SILENCE_MS = 900
        private const val COMMAND_SILENCE_MS = 1300
        private const val WAKE_MAX_MS = 5000
        private const val COMMAND_MAX_MS = 12000
        private const val COMMAND_WAIT_MS = 8000                    // no speech in COMMAND mode -> back to WAKE

        // ---- Google Gemini (speech-to-text) ----
        private const val GEMINI_MODEL = "gemini-2.5-flash"
        private const val GEMINI_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent"
        private const val GEMINI_PROMPT =
            "Transcribe this voice command accurately into plain text. The user speaks Uzbek, English, or Russian " +
                "(often mixing Uzbek and English). Return ONLY the exact spoken words, nothing else. " +
                "If it is just background noise or silence, return an empty string."

        /** Exact (normalized) phrases speech models invent on silence/noise. */
        private val HALLUCINATIONS = setOf(
            "thank you", "thank you very much", "thanks", "thanks for watching", "thank you for watching",
            "you", "bye", "bye bye", "okay", "music", "applause", "subscribe", "like and subscribe",
            "silence", "empty string", "no speech", "background noise", "noise",
            "спасибо", "спасибо за внимание", "спасибо за просмотр", "продолжение следует", "до свидания",
            "музыка", "тишина", "rahmat", "katta rahmat"
        )

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

    private sealed class Capture {
        class Speech(val pcm: ByteArray, val durationMs: Int) : Capture()
        object NoSpeech : Capture()
        object Aborted : Capture()
        object RecorderFailed : Capture()
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var listenJob: Job? = null

    @Volatile
    private var mode = Mode.WAKE
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    /** Adaptive noise floor (only touched on the IO listening coroutine). */
    private var noiseFloor = 0.0

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

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
        if (!geminiKey().startsWith("AIza")) {
            AgentLog.add(
                "ERROR",
                "Gemini API key (AIza...) not found. Paste your Google Gemini key into the API key field."
            )
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        acquireWakeLock()
        AgentLog.add("SYS", "Listening service started (Gemini $GEMINI_MODEL)")
        mode = Mode.WAKE
        startListening()
        return START_NOT_STICKY
    }

    /**
     * The single Gemini key used for both ears and brain.
     * Tolerates a comma-separated field by picking the entry that starts with "AIza".
     */
    private fun geminiKey(): String {
        val raw = Prefs.getBrainKey(this).ifBlank { Prefs.getApiKey(this) }
        val parts = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return parts.firstOrNull { it.startsWith("AIza") } ?: parts.firstOrNull().orEmpty()
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
            // still must satisfy the startForegroundService contract; try plain foreground, ignore failure
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

    // ------------------------------------------------------------------ listening control (main thread)

    /** (Re)starts the IO listening loop. The previous loop is fully stopped first so only one AudioRecord records. */
    private fun startListening() {
        if (!running || mode == Mode.BUSY) return
        val prev = listenJob
        listenJob = scope.launch(Dispatchers.IO) {
            prev?.cancelAndJoin()
            listenLoop()
        }
    }

    private fun stopListening() {
        listenJob?.cancel()
    }

    // ------------------------------------------------------------------ audio loop (IO thread)

    @SuppressLint("MissingPermission")
    private suspend fun listenLoop() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AgentLog.add("ERROR", "Microphone permission missing - stopping")
            withContext(Dispatchers.Main) { stopSelf() }
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            AgentLog.add("ERROR", "This device does not support 16 kHz mono recording (minBuffer=$minBuf)")
            return
        }
        val bufBytes = max(minBuf, SAMPLE_RATE * 2) // ~1 s internal buffer

        while (currentCoroutineContext().isActive && running && mode != Mode.BUSY) {
            val rec: AudioRecord? = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes
                )
            } catch (e: Exception) {
                AgentLog.add("ERROR", "AudioRecord create failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                try {
                    rec?.release()
                } catch (ignored: Exception) {
                }
                AgentLog.add("ERROR", "Microphone unavailable (used by a call or another app?) - retrying in 2 s")
                delay(2000)
                continue
            }

            var recorderFailed = false
            try {
                AgentLog.add("MIC", "Microphone open (${mode.name} mode, VAD threshold $VAD_THRESHOLD)")
                while (currentCoroutineContext().isActive && running && mode != Mode.BUSY) {
                    val captureMode = mode
                    when (val result = capture(rec, captureMode)) {
                        is Capture.Aborted -> break
                        is Capture.RecorderFailed -> {
                            recorderFailed = true
                            break
                        }
                        is Capture.NoSpeech -> withContext(Dispatchers.Main) { onCommandTimeout(captureMode) }
                        is Capture.Speech -> {
                            val text = transcribeSpeech(result)
                            if (!text.isNullOrBlank()) {
                                withContext(Dispatchers.Main) {
                                    if (running && mode == captureMode) handleText(text, captureMode)
                                }
                            }
                        }
                    }
                }
            } finally {
                try {
                    rec.stop()
                } catch (ignored: Exception) {
                }
                try {
                    rec.release()
                } catch (ignored: Exception) {
                }
            }
            if (recorderFailed) delay(500)
        }
    }

    /**
     * Records one utterance with an energy VAD.
     * Mic is stopped again before returning, so nothing is recorded while Gemini transcribes.
     */
    private suspend fun capture(rec: AudioRecord, captureMode: Mode): Capture {
        try {
            rec.startRecording()
        } catch (e: Exception) {
            AgentLog.add("ERROR", "startRecording failed: ${e.javaClass.simpleName}: ${e.message}")
            return Capture.RecorderFailed
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            AgentLog.add("ERROR", "Microphone did not start recording (blocked by another app?)")
            return Capture.RecorderFailed
        }

        val silenceLimit = if (captureMode == Mode.COMMAND) COMMAND_SILENCE_MS else WAKE_SILENCE_MS
        val maxMs = if (captureMode == Mode.COMMAND) COMMAND_MAX_MS else WAKE_MAX_MS
        val frame = ShortArray(FRAME_SAMPLES)
        val preRoll = ArrayDeque<ShortArray>()
        val out = ByteArrayOutputStream(SAMPLE_RATE * 2 * 4)
        var recording = false
        var loudRun = 0
        var speechMs = 0
        var silentMs = 0
        var totalMs = 0
        var waitedMs = 0

        try {
            while (true) {
                if (!currentCoroutineContext().isActive || !running || mode != captureMode) return Capture.Aborted

                val n = rec.read(frame, 0, FRAME_SAMPLES)
                if (n < 0) {
                    AgentLog.add("ERROR", "AudioRecord.read error $n - reopening microphone")
                    return Capture.RecorderFailed
                }
                if (n == 0) continue

                var peak = 0
                for (i in 0 until n) {
                    val a = abs(frame[i].toInt())
                    if (a > peak) peak = a
                }
                // Adaptive: in a noisy room the threshold rises above the noise; never below ~1100.
                val threshold = max(VAD_THRESHOLD, min((noiseFloor * 2.5).toInt(), VAD_MAX_THRESHOLD))

                if (!recording) {
                    noiseFloor = if (noiseFloor <= 0.0) peak.toDouble() else noiseFloor * 0.97 + peak * 0.03
                    preRoll.addLast(frame.copyOf(n))
                    while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()

                    loudRun = if (peak > threshold) loudRun + 1 else 0
                    if (loudRun >= START_FRAMES) {
                        recording = true
                        out.reset()
                        for (f in preRoll) writePcm(out, f, f.size)
                        preRoll.clear()
                        speechMs = loudRun * FRAME_MS
                        totalMs = speechMs
                        silentMs = 0
                    } else if (captureMode == Mode.COMMAND) {
                        waitedMs += FRAME_MS
                        if (waitedMs >= COMMAND_WAIT_MS) return Capture.NoSpeech
                    }
                } else {
                    writePcm(out, frame, n)
                    totalMs += FRAME_MS
                    if (peak > threshold) {
                        speechMs += FRAME_MS
                        silentMs = 0
                    } else {
                        silentMs += FRAME_MS
                    }
                    if (silentMs >= silenceLimit || totalMs >= maxMs) {
                        if (speechMs < MIN_SPEECH_MS) {
                            // Just a click / bump - keep waiting for real speech.
                            recording = false
                            loudRun = 0
                            out.reset()
                            continue
                        }
                        val durationMs = (out.size() / 2) * 1000 / SAMPLE_RATE
                        return Capture.Speech(out.toByteArray(), durationMs)
                    }
                }
            }
        } finally {
            try {
                rec.stop()
            } catch (ignored: Exception) {
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return Capture.Aborted
    }

    private fun writePcm(out: ByteArrayOutputStream, samples: ShortArray, count: Int) {
        val b = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = samples[i].toInt()
            b[2 * i] = (v and 0xFF).toByte()
            b[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        out.write(b, 0, b.size)
    }

    /** Standard 44-byte PCM WAV header (16 kHz, mono, 16-bit, little-endian). */
    private fun wavHeader(dataLen: Int): ByteArray {
        val h = ByteArray(44)
        fun putStr(off: Int, s: String) {
            for (i in s.indices) h[off + i] = s[i].code.toByte()
        }
        fun putInt(off: Int, v: Int) {
            h[off] = (v and 0xFF).toByte()
            h[off + 1] = ((v shr 8) and 0xFF).toByte()
            h[off + 2] = ((v shr 16) and 0xFF).toByte()
            h[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun putShort(off: Int, v: Int) {
            h[off] = (v and 0xFF).toByte()
            h[off + 1] = ((v shr 8) and 0xFF).toByte()
        }
        putStr(0, "RIFF")
        putInt(4, 36 + dataLen)
        putStr(8, "WAVE")
        putStr(12, "fmt ")
        putInt(16, 16)                 // PCM fmt chunk size
        putShort(20, 1)                // audio format = PCM
        putShort(22, 1)                // channels = mono
        putInt(24, SAMPLE_RATE)        // sample rate
        putInt(28, SAMPLE_RATE * 2)    // byte rate = rate * channels * 2
        putShort(32, 2)                // block align
        putShort(34, 16)               // bits per sample
        putStr(36, "data")
        putInt(40, dataLen)
        return h
    }

    // ------------------------------------------------------------------ Gemini speech-to-text (IO thread)

    /** Suspends until the OkHttp call completes; cancelling the coroutine cancels the HTTP call. */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation {
            try {
                cancel()
            } catch (ignored: Throwable) {
            }
        }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }

    /**
     * Writes the utterance to a temporary .wav file in cacheDir, transcribes it with Gemini,
     * and always deletes the file afterwards.
     * Returns the transcript ("" for silence), or null on failure (already logged, with back-off applied).
     */
    private suspend fun transcribeSpeech(speech: Capture.Speech): String? {
        var wav: File? = null
        try {
            val f = File.createTempFile("utt_", ".wav", cacheDir)
            wav = f
            FileOutputStream(f).use { os ->
                os.write(wavHeader(speech.pcm.size))
                os.write(speech.pcm)
            }
            AgentLog.add("MIC", "Speech ${speech.durationMs} ms -> Gemini...")
            return transcribeWithGemini(f)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AgentLog.add("ERROR", "Could not prepare audio: ${e.javaClass.simpleName}: ${e.message}")
            delay(1000)
            return null
        } finally {
            try {
                wav?.delete()
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * Sends the WAV file to Gemini 2.5 Flash as inline base64 audio.
     * Returns the transcript (possibly empty for silence/noise), or null on any failure.
     */
    private suspend fun transcribeWithGemini(wavFile: File): String? {
        val key = geminiKey()
        if (key.isBlank()) {
            AgentLog.add("ERROR", "Gemini API key (AIza...) missing")
            delay(5000)
            return null
        }
        try {
            val base64Audio = Base64.encodeToString(wavFile.readBytes(), Base64.NO_WRAP)

            val inlineData = JSONObject()
                .put("mimeType", "audio/wav")
                .put("data", base64Audio)
            val parts = JSONArray()
                .put(JSONObject().put("inlineData", inlineData))
                .put(JSONObject().put("text", GEMINI_PROMPT))
            val contents = JSONArray()
                .put(JSONObject().put("role", "user").put("parts", parts))
            val generationConfig = JSONObject()
                .put("temperature", 0)
                .put("thinkingConfig", JSONObject().put("thinkingBudget", 0)) // no "thinking" -> faster STT
            val payload = JSONObject()
                .put("contents", contents)
                .put("generationConfig", generationConfig)

            val req = Request.Builder()
                .url("$GEMINI_BASE_URL?key=$key")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val t0 = System.currentTimeMillis()
            var retryAfterSec = 0
            val (code, respBody) = http.newCall(req).await().use { r ->
                retryAfterSec = r.header("retry-after")?.toDoubleOrNull()?.toInt() ?: 0
                r.code to (r.body?.string() ?: "")
            }
            val ms = System.currentTimeMillis() - t0

            if (code != 200) {
                var msg = ""
                try {
                    msg = JSONObject(respBody).optJSONObject("error")?.optString("message").orEmpty()
                } catch (ignored: Exception) {
                }
                if (msg.isBlank()) msg = respBody.take(160)
                when (code) {
                    400, 401, 403 -> {
                        AgentLog.add("ERROR", "Gemini HTTP $code: request/key rejected ($msg)")
                        delay(5000)
                    }
                    429 -> {
                        val wait = retryAfterSec.coerceIn(5, 60)
                        AgentLog.add("ERROR", "Gemini rate limit (HTTP 429) - pausing ${wait}s")
                        delay(wait * 1000L)
                    }
                    else -> {
                        AgentLog.add("ERROR", "Gemini HTTP $code: $msg")
                        delay(1500)
                    }
                }
                return null
            }

            // candidates[0].content.parts[0].text  (missing when the reply is empty or blocked -> treated as silence)
            val text = JSONObject(respBody)
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()
                .trim()
                .trim('"')
                .trim()
            AgentLog.add("MIC", "Gemini ${ms} ms")
            return text
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnknownHostException) {
            AgentLog.add("ERROR", "No internet (DNS failed for generativelanguage.googleapis.com) - check Wi-Fi/VPN")
            delay(3000)
        } catch (e: SocketTimeoutException) {
            AgentLog.add("ERROR", "Gemini request timed out")
            delay(1500)
        } catch (e: IOException) {
            AgentLog.add("ERROR", "Gemini network error: ${e.javaClass.simpleName}: ${e.message}")
            delay(2000)
        } catch (e: Exception) {
            AgentLog.add("ERROR", "Transcription failed: ${e.javaClass.simpleName}: ${e.message}")
            delay(1000)
        }
        return null
    }

    private fun isHallucination(text: String): Boolean {
        val n = text.lowercase(Locale.ROOT).replace(Regex("[\\p{P}\\p{S}\\s]+"), " ").trim()
        if (n.isEmpty()) return true
        if (n in HALLUCINATIONS) return true
        if (n.contains("субтитр") || n.contains("subtitle") || n.contains("amara org") ||
            n.contains("thanks for watching") || n.contains("thank you for watching") ||
            n.contains("спасибо за просмотр")
        ) return true
        // The model sometimes echoes the instruction back instead of a transcript.
        if (n.contains("transcribe this voice") || n.contains("return only the exact spoken words")) return true
        return false
    }

    // ------------------------------------------------------------------ wake word / commands (main thread)

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
        return text.substring(from).trim(' ', ',', '.', '!', '?', ':', ';', '-', '—')
    }

    private fun handleText(text: String, captureMode: Mode) {
        if (isHallucination(text)) {
            AgentLog.add("MIC", "Ignored speech-model silence artefact: \"$text\"")
            return
        }
        AgentLog.add("MIC", "Heard: $text")

        if (captureMode == Mode.WAKE) {
            val f = findWake(text) ?: return
            val cmd = commandAfter(text, f.first, f.second)
            AgentLog.add("MIC", "Wake word '${f.second}' detected")
            if (cmd.length >= 3) runTask(cmd) else promptForCommand()
        } else if (captureMode == Mode.COMMAND) {
            var cmd = text.trim()
            val f = findWake(cmd)
            if (f != null && f.first <= 1) cmd = commandAfter(cmd, f.first, f.second)
            if (cmd.length >= 2) runTask(cmd)
        }
    }

    private fun onCommandTimeout(captureMode: Mode) {
        if (running && captureMode == Mode.COMMAND && mode == Mode.COMMAND) {
            AgentLog.add("MIC", "No command heard - back to wake-word mode")
            mode = Mode.WAKE // the running loop picks up the new mode on its next capture
        }
    }

    private fun promptForCommand() {
        mode = Mode.BUSY
        stopListening() // release the mic so it doesn't hear the TTS prompt
        val lang = Prefs.getLang(this)
        TtsManager.speak(lang.listening(), lang) {
            main.postDelayed({
                if (running) {
                    mode = Mode.COMMAND
                    AgentLog.add("MIC", "Waiting for command...")
                    startListening()
                }
            }, 300)
        }
    }

    private fun runTask(command: String) {
        mode = Mode.BUSY
        stopListening()
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
        main.postDelayed({ startListening() }, 400)
    }

    // ------------------------------------------------------------------ teardown

    override fun onDestroy() {
        running = false
        job?.cancel()
        listenJob?.cancel()
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        TtsManager.stop()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (ignored: Exception) {
        }
        try {
            http.connectionPool.evictAll()
        } catch (ignored: Exception) {
        }
        AgentLog.add("SYS", "Listening service stopped")
        super.onDestroy()
    }
}
