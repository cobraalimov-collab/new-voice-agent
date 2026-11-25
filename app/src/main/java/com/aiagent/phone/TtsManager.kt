package com.aiagent.phone

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Text-to-speech that never fails silently:
 *  - queues speech until the engine is initialised
 *  - falls back through other locales when a voice pack is missing (uz -> tr -> ru -> en)
 *  - always fires the completion callback (even on error / engine hang) so callers never get stuck
 */
object TtsManager {
    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    private var initializing = false
    private val pending = ArrayList<Triple<String, AppLang, (() -> Unit)?>>()
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private val counter = AtomicInteger(0)
    private var usedLocale: Locale? = null
    private var lastLoggedKey = ""

    private fun runMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun init(ctx: Context) {
        runMain {
            if (appCtx == null) appCtx = ctx.applicationContext
            startEngine()
        }
    }

    private fun startEngine() {
        if (tts != null || initializing) return
        val ctx = appCtx ?: return
        initializing = true
        AgentLog.add("TTS", "Initializing TextToSpeech engine...")
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
            AgentLog.add("ERROR", "TTS init failed (status=$status). Install/enable a Text-to-speech engine in Android settings.")
            try {
                t?.shutdown()
            } catch (_: Exception) {
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
        } catch (_: Exception) {
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                finish(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                AgentLog.add("ERROR", "TTS utterance error")
                finish(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                AgentLog.add("ERROR", "TTS utterance error code=$errorCode")
                finish(utteranceId)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                finish(utteranceId)
            }
        })
        ready = true
        AgentLog.add("TTS", "Engine ready: ${t.defaultEngine}")
        flushPending(true)
    }

    private fun finish(id: String?) {
        if (id == null) return
        main.post {
            val cb = callbacks.remove(id)
            if (cb != null) {
                try {
                    cb()
                } catch (e: Exception) {
                    AgentLog.add("ERROR", "TTS callback failed: ${e.message}")
                }
            }
        }
    }

    private fun flushPending(ok: Boolean) {
        val copy = ArrayList(pending)
        pending.clear()
        for ((text, lang, cb) in copy) {
            if (ok) {
                speakNow(text, lang, cb)
            } else {
                try {
                    cb?.invoke()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Speak [text] in [lang]. [onDone] is always invoked exactly once (on the main thread). */
    fun speak(text: String, lang: AppLang, onDone: (() -> Unit)? = null) {
        runMain {
            if (!ready || tts == null) {
                pending.add(Triple(text, lang, onDone))
                if (tts == null && !initializing) startEngine()
            } else {
                speakNow(text, lang, onDone)
            }
        }
    }

    fun stop() {
        runMain {
            try {
                tts?.stop()
            } catch (_: Exception) {
            }
        }
    }

    private fun speakNow(text: String, lang: AppLang, onDone: (() -> Unit)?) {
        val t = tts
        if (t == null) {
            onDone?.invoke()
            return
        }
        val locale = applyLanguage(t, lang)
        val clean = sanitize(text, lang, locale)
        if (clean.isBlank()) {
            onDone?.invoke()
            return
        }
        AgentLog.add("TTS", "Speaking (${locale ?: "default"}): ${clean.take(120)}")
        val id = "u" + counter.incrementAndGet()
        if (onDone != null) callbacks[id] = onDone

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
                if (last) finish(id) else Unit
            }
        }

        // Safety net: if the engine never reports completion, still release the caller.
        main.postDelayed({
            val cb = callbacks.remove(id)
            if (cb != null) {
                AgentLog.add("TTS", "Completion timeout - continuing")
                try {
                    cb()
                } catch (_: Exception) {
                }
            }
        }, 5000L + clean.length * 90L)
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
                usedLocale = c
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
        usedLocale = null
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
    private fun sanitize(text: String, lang: AppLang, locale: Locale?): String {
        var s = text.replace(Regex("[*#_`~>|]"), " ")
        s = s.replace(Regex("[\\p{So}\\p{Cn}]"), " ")
        if (lang == AppLang.UZ && locale != null && locale.language != "uz") {
            // o' / g' (and the various apostrophe characters) confuse non-Uzbek voices
            s = s.replace(Regex("([oOgG])[ʻʼ‘’'`´]"), "\$1")
        }
        s = s.replace(Regex("[ʻʼ‘’`´]"), "'")
        return s.replace(Regex("\\s+"), " ").trim()
    }
}
