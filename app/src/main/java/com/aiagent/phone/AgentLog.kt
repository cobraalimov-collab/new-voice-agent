package com.aiagent.phone

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide debug log. Tags: SYS, MIC, API, ACTION, TTS, ERROR.
 * Listeners may be called from any thread - UI code must post to the main thread.
 */
object AgentLog {
    private const val MAX_LINES = 400
    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(tag: String, msg: String) {
        val line: String
        synchronized(this) {
            line = "${fmt.format(Date())} [$tag] $msg"
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        try {
            Log.d("AIAgent", line)
        } catch (_: Throwable) {
        }
        for (l in listeners) {
            try {
                l(line)
            } catch (_: Throwable) {
            }
        }
    }

    fun snapshot(): List<String> = synchronized(this) { lines.toList() }

    fun addListener(l: (String) -> Unit) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: (String) -> Unit) {
        listeners.remove(l)
    }
}
