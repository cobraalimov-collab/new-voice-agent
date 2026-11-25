package com.aiagent.phone

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.util.Locale

/** Autonomous loop: read screen -> ask Claude -> perform one action -> wait -> repeat until "done". */
object AgentLoop {
    const val MAX_STEPS = 15
    private const val STEP_DELAY_MS = 1500L

    private fun systemPrompt(lang: AppLang): String = """
You are an AI agent that controls an Android phone for the user. Each turn you receive the user's TASK, the ACTIONS ALREADY DONE and the CURRENT SCREEN as a list of UI nodes. Each node shows its center coordinates [x,y], its text, its content description (desc), its view id and flags.

Reply with ONLY one JSON object (no markdown, no code fences, no explanation). Allowed actions:
{"action":"open_app","app":"<app name or package>"}
{"action":"tap_text","text":"<visible text or desc of the element>"}
{"action":"tap_xy","x":<int>,"y":<int>}
{"action":"type_text","text":"<text to enter>","enter":false}
{"action":"scroll","direction":"down"}   (down = see content further below, up = back toward the top)
{"action":"back"}
{"action":"home"}
{"action":"wait","seconds":2}
{"action":"done","speech":"<final answer to the user>"}

Rules:
- Do exactly ONE action per turn and choose the most direct step toward the task.
- Prefer tap_text; use tap_xy with the node's [x,y] when text is missing or ambiguous.
- Before type_text, make sure a text field is focused/open (tap it first if needed). Set "enter":true to submit a search.
- If the screen does not change after an action, try a different approach instead of repeating it.
- If the task needs no phone control (a plain question), answer immediately with "done".
- Never purchase, pay, delete data, or send messages unless the task explicitly asks for it.
- When the task is finished (or impossible), reply with "done". The "speech" is read aloud: write it in ${lang.promptName}, one or two short plain sentences, no markdown, no emoji.
""".trimIndent()

    suspend fun run(ctx: Context, task: String, lang: AppLang): String {
        if (!LicenseManager.isLicensed(ctx)) {
            AgentLog.add("ERROR", "License check failed")
            return lang.licenseInvalid()
        }
        val key = Prefs.getApiKey(ctx)
        if (key.isBlank()) {
            AgentLog.add("ERROR", "API key is empty")
            return lang.noKey()
        }

        val history = ArrayList<String>()
        var badReplies = 0
        AgentLog.add("ACTION", "Task: $task")

        for (step in 1..MAX_STEPS) {
            currentCoroutineContext().ensureActive()
            val svc = AgentAccessibilityService.instance
            val screen = svc?.describeScreen()
                ?: "(accessibility service is NOT enabled - phone control is unavailable; only answer with done)"

            val user = buildString {
                append("TASK: ").append(task).append("\n\n")
                append("ACTIONS ALREADY DONE:\n")
                if (history.isEmpty()) append("(none yet)\n") else history.forEach { append(it).append('\n') }
                append("\nCURRENT SCREEN:\n").append(screen)
            }

            AgentLog.add("API", "Step $step/$MAX_STEPS: asking Claude")
            val reply = when (val r = ClaudeClient.ask(key, systemPrompt(lang), user, 500)) {
                is ClaudeClient.ApiResult.Ok -> r.text
                is ClaudeClient.ApiResult.Err -> {
                    AgentLog.add("ERROR", r.message)
                    return lang.apiError()
                }
            }

            val obj = parseJson(reply)
            if (obj == null) {
                badReplies++
                AgentLog.add("ERROR", "Could not parse Claude reply: ${reply.take(150)}")
                history.add("step $step: (your reply was not valid JSON - reply with ONE JSON object only)")
                if (badReplies >= 3) return lang.failed()
                continue
            }

            val action = obj.optString("action").lowercase(Locale.ROOT)
            AgentLog.add("ACTION", "Step $step: ${obj.toString().take(200)}")

            if (action == "done") {
                val speech = obj.optString("speech").ifBlank { obj.optString("text") }.ifBlank { lang.done() }
                return speech
            }
            if (svc == null) {
                AgentLog.add("ERROR", "Accessibility service is not enabled")
                return lang.noAccessibility()
            }

            val result = svc.execute(obj)
            AgentLog.add("ACTION", "Result: $result")
            history.add("step $step: ${obj.toString().take(160)} -> $result")
            delay(STEP_DELAY_MS)
        }
        AgentLog.add("ERROR", "Step limit ($MAX_STEPS) reached")
        return lang.stepLimit()
    }

    private fun parseJson(text: String): JSONObject? {
        val s = text.indexOf('{')
        val e = text.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        return try {
            JSONObject(text.substring(s, e + 1))
        } catch (ex: Exception) {
            null
        }
    }
}
