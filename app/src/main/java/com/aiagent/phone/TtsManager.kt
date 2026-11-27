package com.aiagent.phone

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Agent voice.
 *  1. Microsoft Edge neural voices (MadinaNeural / AvaNeural / SvetlanaNeural) over WebSocket -> MP3 -> MediaPlayer.
 *  2. Falls back to Android TextToSpeech (female voice when available) if Edge fails or the phone is offline.
 * [speak]'s onDone is ALWAYS invoked exactly once, on the main thread - also on error, timeout or [stop].
 * A new [speak] interrupts the current one (its onDone still fires), like QUEUE_FLUSH did before.
 */
object TtsManager {

    // ------------------------------------------------------------------ Edge TTS constants

    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WSS_URL =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"
    private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    private const val CHROMIUM_MAJOR_VERSION = "143"
    private const val SEC_MS_GEC_VERSION = "2-$CHROMIUM_FULL_VERSION"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$CHROMIUM_MAJOR_VERSION.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR_VERSION.0.0.0"
    private const val WIN_EPOCH = 11644473600L
    private const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"
    private const val EDGE_CHUNK_TIMEOUT_MS = 12000L
    private const val EDGE_COOLDOWN_MS = 60000L
    private const val MAX_CHUNK_CHARS = 900

    private const val RESULT_OK = 0
    private const val RESULT_ERROR = -1
    private const val RESULT_TIMEOUT = -2
    private const val RESULT_NO_AUDIO = -3

    private class EdgeVoice(val shortName: String, val locale: String) {
        val fullName: String
            get() = "Microsoft Server Speech Text to Speech Voice ($locale, ${shortName.removePrefix("$locale-")})"
    }

    private fun voiceFor(lang: AppLang): EdgeVoice = when (lang) {
        AppLang.UZ -> EdgeVoice("uz-UZ-MadinaNeural", "uz-UZ")
        AppLang.RU -> EdgeVoice("ru-RU-SvetlanaNeural", "ru-RU")
        AppLang.EN -> EdgeVoice("en-US-AvaNeural", "en-US")
    }

    /** Seconds to add to the phone clock (fixed automatically from the server's Date header on HTTP 403). */
    @Volatile
    private var clockSkewSec = 0L

    /** After an Edge failure, skip Edge for a minute so the agent stays fast while offline. */
    @Volatile
    private var edgeBlockedUntil = 0L

    private val edgeHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------------ state (main thread only)

    private class Req(val text: String, val lang: AppLang, val onDone: (() -> Unit)?) {
        val done = AtomicBoolean(false)
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var appCtx: Context? = null

    private var current: Req? = null
    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var playingFile: File? = null

    // Android TTS fallback engine
    private var tts: TextToSpeech? = null
    private var ready = false
    private var initializing = false
    private val pendingFallback = ArrayList<Req>()
    private val utterances = ConcurrentHashMap<String, Req>()
    private val counter = AtomicInteger(0)
    private var lastLoggedKey = ""

    private fun runMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    // ------------------------------------------------------------------ public API (unchanged signatures)

    fun init(ctx: Context) {
        runMain {
            if (appCtx == null) appCtx = ctx.applicationContext
            startEngine() // warm up the offline fallback so it is ready instantly
        }
    }

    /** Speak [text] in [lang]. [onDone] is always invoked exactly once (on the main thread). */
    fun speak(text: String, lang: AppLang, onDone: (() -> Unit)? = null) {
        runMain {
            interruptCurrent()
            val req = Req(text, lang, onDone)
            current = req

            val clean = cleanForEdge(text, lang)
            if (clean.isBlank()) {
                complete(req)
                return@runMain
            }

            // Global safety net: whatever happens (network hang, player stuck), release the caller.
            main.postDelayed({
                if (!req.done.get()) {
                    AgentLog.add("TTS", "Speech timeout - continuing")
                    if (current === req) interruptCurrent() else complete(req)
                }
            }, 20000L + clean.length * 150L)

            if (appCtx == null || SystemClock.elapsedRealtime() < edgeBlockedUntil) {
                speakFallback(req)
                return@runMain
            }

            job = scope.launch {
                val t0 = SystemClock.elapsedRealtime()
                val file = withContext(Dispatchers.IO) { synthesize(clean, lang) }
                if (current !== req || req.done.get()) {
                    file?.delete()
                    return@launch
                }
                if (file != null) {
                    edgeBlockedUntil = 0L
                    AgentLog.add(
                        "TTS",
                        "Edge ${voiceFor(lang).shortName}: ${file.length() / 1024} KB in ${SystemClock.elapsedRealtime() - t0} ms"
                    )
                    play(req, file)
                } else {
                    edgeBlockedUntil = SystemClock.elapsedRealtime() + EDGE_COOLDOWN_MS
                    AgentLog.add("TTS", "Edge neural voice unavailable - using Android TTS for the next minute")
                    speakFallback(req)
                }
            }
        }
    }

    fun stop() {
        runMain { interruptCurrent() }
    }

    // ------------------------------------------------------------------ request lifecycle

    private fun complete(req: Req) {
        if (!req.done.compareAndSet(false, true)) return
        if (current === req) current = null
        main.post {
            try {
                req.onDone?.invoke()
            } catch (e: Exception) {
                AgentLog.add("ERROR", "TTS callback failed: ${e.message}")
            }
        }
    }

    /** Stops whatever is speaking now and fires its onDone. */
    private fun interruptCurrent() {
        val req = current ?: return
        current = null
        job?.cancel()
        job = null
        stopPlayer()
        pendingFallback.remove(req)
        try {
            tts?.stop()
        } catch (ignored: Exception) {
        }
        complete(req)
    }

    // ------------------------------------------------------------------ MediaPlayer

    private fun stopPlayer() {
        val mp = player
        player = null
        if (mp != null) {
            try {
                mp.stop()
            } catch (ignored: Exception) {
            }
            try {
                mp.release()
            } catch (ignored: Exception) {
            }
        }
        val f = playingFile
        playingFile = null
        try {
            f?.delete()
        } catch (ignored: Exception) {
        }
    }

    private fun play(req: Req, file: File) {
        stopPlayer()
        val mp = MediaPlayer()
        player = mp
        playingFile = file
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { p ->
                if (current === req && player === p) p.start()
            }
            mp.setOnCompletionListener { p ->
                if (player === p) stopPlayer()
                complete(req)
            }
            mp.setOnErrorListener { p, what, extra ->
                AgentLog.add("ERROR", "MediaPlayer error what=$what extra=$extra - falling back to Android TTS")
                if (player === p) stopPlayer()
                if (current === req && !req.done.get()) speakFallback(req) else complete(req)
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            AgentLog.add("ERROR", "MediaPlayer failed: ${e.javaClass.simpleName}: ${e.message}")
            stopPlayer()
            speakFallback(req)
        }
    }

    // ------------------------------------------------------------------ Edge TTS (IO thread)

    /** Returns an MP3 file in cacheDir, or null on any failure (temp file already deleted). */
    private suspend fun synthesize(text: String, lang: AppLang): File? {
        val ctx = appCtx ?: return null
        val voice = voiceFor(lang)
        val file = try {
            File.createTempFile("tts_", ".mp3", ctx.cacheDir)
        } catch (e: Exception) {
            AgentLog.add("ERROR", "TTS temp file failed: ${e.message}")
            return null
        }
        var ok = false
        try {
            FileOutputStream(file).use { out ->
                for (chunk in splitText(text, MAX_CHUNK_CHARS)) {
                    var r = synthChunk(chunk, voice, out)
                    if (r == 403) {
                        AgentLog.add("TTS", "Edge HTTP 403 - correcting clock skew and retrying")
                        r = synthChunk(chunk, voice, out)
                    }
                    if (r != RESULT_OK) {
                        val why = when (r) {
                            RESULT_TIMEOUT -> "timeout"
                            RESULT_NO_AUDIO -> "no audio returned"
                            RESULT_ERROR -> "network error"
                            else -> "HTTP $r"
                        }
                        AgentLog.add("TTS", "Edge TTS failed ($why)")
                        return null
                    }
                }
            }
            ok = file.length() > 0
            return if (ok) file else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AgentLog.add("TTS", "Edge TTS error: ${e.javaClass.simpleName}: ${e.message}")
            return null
        } finally {
            if (!ok) file.delete()
        }
    }

    /** One WebSocket session: send config + SSML, append MP3 frames to [out] until turn.end. */
    private suspend fun synthChunk(chunk: String, voice: EdgeVoice, out: OutputStream): Int {
        val result = withTimeoutOrNull(EDGE_CHUNK_TIMEOUT_MS) {
            suspendCancellableCoroutine<Int> { cont ->
                val finished = AtomicBoolean(false)
                var audioBytes = 0L

                fun finish(code: Int) {
                    if (finished.compareAndSet(false, true) && cont.isActive) cont.resume(code)
                }

                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val ts = timestamp()
                        webSocket.send(configMessage(ts))
                        webSocket.send(ssmlMessage(ts, chunk, voice))
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("Path:turn.end")) {
                            finish(if (audioBytes > 0) RESULT_OK else RESULT_NO_AUDIO)
                            webSocket.close(1000, null)
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val data = bytes.toByteArray()
                        if (data.size < 2) return
                        val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                        if (2 + headerLen > data.size) return
                        val header = String(data, 2, headerLen, Charsets.UTF_8)
                        if (!header.contains("Path:audio")) return
                        val n = data.size - 2 - headerLen
                        if (n <= 0) return
                        try {
                            out.write(data, 2 + headerLen, n)
                            audioBytes += n
                        } catch (e: Exception) {
                            finish(RESULT_ERROR)
                            webSocket.cancel()
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        finish(if (audioBytes > 0) RESULT_OK else RESULT_NO_AUDIO)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        val http = response?.code ?: 0
                        if (http == 403) {
                            response?.header("Date")?.let { adjustClockSkew(it) }
                        }
                        if (!finished.get()) {
                            AgentLog.add("TTS", "Edge connection failed: ${if (http > 0) "HTTP $http" else t.javaClass.simpleName}")
                        }
                        finish(if (http > 0) http else RESULT_ERROR)
                    }
                }

                val ws = edgeHttp.newWebSocket(buildRequest(), listener)
                cont.invokeOnCancellation { ws.cancel() }
            }
        }
        return result ?: RESULT_TIMEOUT
    }

    private fun buildRequest(): Request {
        val url = "$WSS_URL&ConnectionId=${uuidHex()}&Sec-MS-GEC=${secMsGec()}&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"
        return Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${randomHexUpper(16)};")
            .build()
    }

    private fun configMessage(ts: String): String =
        "X-Timestamp:$ts\r\n" +
                "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n" +
                "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
                "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                "\"outputFormat\":\"$OUTPUT_FORMAT\"}}}}\r\n"

    private fun ssmlMessage(ts: String, chunk: String, voice: EdgeVoice): String =
        "X-RequestId:${uuidHex()}\r\n" +
                "Content-Type:application/ssml+xml\r\n" +
                "X-Timestamp:${ts}Z\r\n" +
                "Path:ssml\r\n\r\n" +
                "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='${voice.locale}'>" +
                "<voice name='${voice.fullName}'>" +
                "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>${xmlEscape(chunk)}</prosody>" +
                "</voice></speak>"

    /** Sec-MS-GEC: SHA-256 of (Windows file time rounded down to 5 min, in 100 ns units) + token, upper-case hex. */
    private fun secMsGec(): String {
        var ticks = System.currentTimeMillis() / 1000L + clockSkewSec + WIN_EPOCH
        ticks -= ticks % 300L
        val value = ticks * 10_000_000L
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$value$TRUSTED_CLIENT_TOKEN".toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    /** On HTTP 403 the phone clock is usually wrong: shift it by the difference to the server's Date header. */
    private fun adjustClockSkew(dateHeader: String) {
        try {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            val server = fmt.parse(dateHeader)?.time ?: return
            val local = System.currentTimeMillis() + clockSkewSec * 1000L
            clockSkewSec += (server - local) / 1000L
            AgentLog.add("TTS", "Clock skew corrected: ${clockSkewSec}s")
        } catch (ignored: Exception) {
        }
    }

    private fun timestamp(): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun uuidHex(): String = UUID.randomUUID().toString().replace("-", "")

    private fun randomHexUpper(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02X".format(it) }
    }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** Splits long text at sentence ends so each WebSocket request stays small. */
    private fun splitText(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (sentence in text.split(Regex("(?<=[.!?\u2026])\\s+"))) {
            if (sb.isNotEmpty() && sb.length + sentence.length + 1 > max) {
                out.add(sb.toString())
                sb.setLength(0)
            }
            if (sentence.length > max) {
                out.addAll(sentence.chunked(max))
            } else {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(sentence)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out.filter { it.isNotBlank() }
    }

    /** Strips markdown/emoji; for Uzbek turns o' / g' into the proper Uzbek letter (U+02BB) the neural voice expects. */
    private fun cleanForEdge(text: String, lang: AppLang): String {
        var s = text.replace(Regex("[*#_`~>|]"), " ")
        s = s.replace(Regex("[\\p{So}\\p{Cn}]"), " ")
        if (lang == AppLang.UZ) {
            s = s.replace(Regex("([oOgG])['\u2018\u2019\u02BC`\u00B4]"), "\$1\u02BB")
        }
        return s.replace(Regex("\\s+"), " ").trim()
    }

    // ------------------------------------------------------------------ Android TTS fallback

    private fun startEngine() {
        if (tts != null || initializing) return
        val ctx = appCtx ?: return
        initializing = true
        AgentLog.add("TTS", "Initializing Android TextToSpeech (offline fallback)...")
        try {
            tts = TextToSpeech(ctx) { status -> main.post { onInit(status) } }
        } catch (e: Exception) {
            initializing = false
            tts = null
            AgentLog.add("ERROR", "TTS engine creation failed: ${e.message}")
            flushPending(false)
        }
    }

    private fun onInit(status: Int) {
        initializing = false
        val t = tts
        if (status != TextToSpeech.SUCCESS || t == null) {
            AgentLog.add("ERROR", "Android TTS init failed (status=$status). Install/enable a Text-to-speech engine in settings.")
            try {
                t?.shutdown()
            } catch (ignored: Exception) {
            }
            tts = null
            ready = false
            flushPending(false)
            return
        }
        try {
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        } catch (ignored: Exception) {
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                finishUtterance(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                AgentLog.add("ERROR", "TTS utterance error")
                finishUtterance(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                AgentLog.add("ERROR", "TTS utterance error code=$errorCode")
                finishUtterance(utteranceId)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                finishUtterance(utteranceId)
            }
        })
        ready = true
        AgentLog.add("TTS", "Android TTS ready: ${t.defaultEngine}")
        flushPending(true)
    }

    private fun finishUtterance(id: String?) {
        if (id == null) return
        main.post {
            val req = utterances.remove(id)
            if (req != null) complete(req)
        }
    }

    private fun flushPending(ok: Boolean) {
        val copy = ArrayList(pendingFallback)
        pendingFallback.clear()
        for (req in copy) {
            if (req.done.get()) continue
            val t = tts
            if (ok && t != null) speakFallbackNow(t, req) else complete(req)
        }
    }

    private fun speakFallback(req: Req) {
        if (req.done.get()) return
        val t = tts
        if (t != null && ready) {
            speakFallbackNow(t, req)
            return
        }
        pendingFallback.add(req)
        if (tts == null && !initializing) startEngine()
        if (tts == null && !initializing) {
            // Engine could not even be created - never leave the caller hanging.
            pendingFallback.remove(req)
            complete(req)
        }
    }

    private fun speakFallbackNow(t: TextToSpeech, req: Req) {
        val locale = applyLanguage(t, req.lang)
        if (locale != null) pickFemaleVoice(t, locale)
        val clean = sanitizeForAndroid(req.text, req.lang, locale)
        if (clean.isBlank()) {
            complete(req)
            return
        }
        AgentLog.add("TTS", "Android TTS (${locale ?: "default"}): ${clean.take(120)}")
        val id = "u" + counter.incrementAndGet()
        utterances[id] = req

        val chunks = clean.chunked(3000)
        chunks.forEachIndexed { i, chunk ->
            val last = i == chunks.lastIndex
            val uid = if (last) id else "$id-$i"
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val r = try {
                t.speak(chunk, mode, null, uid)
            } catch (e: Exception) {
                AgentLog.add("ERROR", "TTS speak exception: ${e.message}")
                TextToSpeech.ERROR
            }
            if (r != TextToSpeech.SUCCESS) {
                AgentLog.add("ERROR", "TTS speak() returned error")
                if (last) finishUtterance(id)
            }
        }
    }

    /** Prefers an offline female voice for [locale] (Samsung "SMTf..", Google "...#female..." names). */
    private fun pickFemaleVoice(t: TextToSpeech, locale: Locale) {
        try {
            val voices: Set<Voice> = t.voices ?: return
            val candidates = voices.filter {
                it.locale.language == locale.language && !it.isNetworkConnectionRequired
            }
            val best = candidates.filter { isFemale(it) }.maxByOrNull { it.quality } ?: return
            if (t.voice?.name != best.name) {
                t.voice = best
                AgentLog.add("TTS", "Fallback voice: ${best.name}")
            }
        } catch (ignored: Exception) {
        }
    }

    private fun isFemale(v: Voice): Boolean {
        val n = v.name.lowercase(Locale.ROOT)
        if (n.contains("female")) return true
        if (Regex("smtf\\d").containsMatchIn(n)) return true
        return v.features?.any { it.lowercase(Locale.ROOT).contains("female") } == true
    }

    /** Picks the best available locale; Uzbek falls back to Turkish, then Russian. */
    private fun applyLanguage(t: TextToSpeech, lang: AppLang): Locale? {
        val candidates: List<Locale> = when (lang) {
            AppLang.UZ -> listOf(
                Locale("uz", "UZ"), Locale("uz"),
                Locale("tr", "TR"), Locale("tr"),
                Locale("ru", "RU"), Locale("ru"),
                Locale.US
            )
            AppLang.RU -> listOf(Locale("ru", "RU"), Locale("ru"), Locale.US)
            AppLang.EN -> listOf(Locale.US, Locale.ENGLISH)
        }
        for (c in candidates) {
            val r = try {
                t.setLanguage(c)
            } catch (e: Exception) {
                TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (r >= TextToSpeech.LANG_AVAILABLE) {
                val key = "${lang.code}->$c"
                if (key != lastLoggedKey) {
                    lastLoggedKey = key
                    val note = if (c.language != lang.locale.language) " (FALLBACK for ${lang.label})" else ""
                    AgentLog.add("TTS", "Voice locale: $c$note")
                }
                return c
            } else {
                val key = "${lang.code}-miss-$c"
                if (key != lastLoggedKey) {
                    AgentLog.add("TTS", "setLanguage($c) = ${resultName(r)}, trying next")
                }
            }
        }
        AgentLog.add("ERROR", "No TTS language available for ${lang.label}; using engine default")
        return null
    }

    private fun resultName(r: Int): String = when (r) {
        TextToSpeech.LANG_MISSING_DATA -> "LANG_MISSING_DATA"
        TextToSpeech.LANG_NOT_SUPPORTED -> "LANG_NOT_SUPPORTED"
        TextToSpeech.LANG_AVAILABLE -> "LANG_AVAILABLE"
        TextToSpeech.LANG_COUNTRY_AVAILABLE -> "LANG_COUNTRY_AVAILABLE"
        TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "LANG_COUNTRY_VAR_AVAILABLE"
        else -> "code $r"
    }

    /** Strips markdown/emoji and makes Uzbek Latin text readable by a Turkish/Russian fallback voice. */
    private fun sanitizeForAndroid(text: String, lang: AppLang, locale: Locale?): String {
        var s = text.replace(Regex("[*#_`~>|]"), " ")
        s = s.replace(Regex("[\\p{So}\\p{Cn}]"), " ")
        if (lang == AppLang.UZ && locale != null && locale.language != "uz") {
            // o' / g' (and the various apostrophe characters) confuse non-Uzbek voices
            s = s.replace(Regex("([oOgG])[\u02BB\u02BC\u2018\u2019'`\u00B4]"), "\$1")
        }
        s = s.replace(Regex("[\u02BB\u02BC\u2018\u2019`\u00B4]"), "'")
        return s.replace(Regex("\\s+"), " ").trim()
    }
}
