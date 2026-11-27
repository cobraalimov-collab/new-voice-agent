package com.aiagent.phone

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Autonomous loop: screenshot + UI tree -> ask Gemini (vision) -> perform one action -> wait -> repeat
 * until "done", then the caller speaks the final answer.
 */
object AgentLoop {
    const val MAX_STEPS = 15
    private const val STEP_DELAY_MS = 1500L

    private fun languageRule(lang: AppLang): String = when (lang) {
        AppLang.UZ -> "Write \"speech\" in natural, pure Uzbek (Latin script). Never mix in Russian or English words - " +
                "use proper Uzbek words (for example \"xabar yuborildi\", \"ilova ochildi\", \"topilmadi\")."
        AppLang.RU -> "Write \"speech\" in natural Russian."
        AppLang.EN -> "Write \"speech\" in natural English."
    }

    private fun systemPrompt(lang: AppLang): String = """
You are an AI agent that controls an Android phone for the user. Each turn you receive:
- the user's TASK and the ACTIONS ALREADY DONE (with their results),
- a SCREENSHOT of the current screen (when available),
- the UI ELEMENTS list from the accessibility tree: center [x,y] in SCREEN pixels, text, desc, id and flags.

Look at the screenshot carefully. Many important buttons are icons without text (search magnifier, microphone, send arrow, attach clip, back arrow, three-dot menu). Find them visually.

Reply with ONLY one JSON object (no markdown, no explanation). Allowed actions:
{"action":"open_app","app":"<app name or package>"}
{"action":"tap_text","text":"<visible text or desc of the element>"}
{"action":"tap_xy","x":<int>,"y":<int>}            (x,y taken from the UI ELEMENTS list)
{"action":"tap_xy","ix":<int>,"iy":<int>}          (ix,iy = pixel position inside the SCREENSHOT image, for icons not in the list)
{"action":"long_click","ix":<int>,"iy":<int>,"ms":800}   (or "x","y", or "text")
{"action":"hold_and_speak","ix":<int>,"iy":<int>,"message":"<exact words of the voice message>"}
{"action":"type_text","text":"<text to enter>","enter":false}
{"action":"scroll","direction":"down"}   (down = see content further below, up = back toward the top)
{"action":"back"}
{"action":"home"}
{"action":"wait","seconds":2}
{"action":"done","speech":"<final answer to the user>"}

Rules:
- Do exactly ONE action per turn and choose the most direct step toward the task.
- Prefer tap_text or UI-list coordinates when the element is in the list; use ix,iy from the screenshot for icons that are not.
- Before type_text, make sure the text field is focused (tap it first). Set "enter":true to submit a search.
- If the screen did not change after an action, try a different approach instead of repeating it.
- If the task needs no phone control (a plain question), answer immediately with "done".
- Never purchase, pay, transfer money, delete data or send messages unless the task explicitly asks for it.

Telegram voice message (when the user asks to send a voice / audio message):
1. open_app Telegram. 2. Tap the search icon (magnifier, top right) and type the contact name. 3. Open the right chat.
4. Look at the round button at the bottom right of the chat. With an empty text field it is a MICROPHONE.
   If it shows a CAMERA (video message) icon, tap it ONCE to switch it to the microphone, then look again.
   Do not type anything into the message field (that turns the button into Send).
5. Use hold_and_speak on the microphone button with "message" = only the words the user wants to say.
   The phone speaks the message aloud while holding, and releasing sends it.
6. Check the next screenshot: a new voice message bubble means success -> "done".

When the task is finished (or impossible), reply with "done". The "speech" is read aloud: one or two short plain sentences, no markdown, no emoji. ${languageRule(lang)}
""".trimIndent()

    suspend fun run(ctx: Context, task: String, lang: AppLang): String {
        if (!LicenseManager.isLicensed(ctx)) {
            AgentLog.add("ERROR", "License check failed")
            return lang.licenseInvalid()
        }
        // Full field is passed; ClaudeClient picks the AIza... Gemini key out of "gsk_..., AIza..." itself.
        val key = Prefs.getApiKey(ctx)
        if (ClaudeClient.resolveGeminiKey(key).isBlank()) {
            AgentLog.add("ERROR", "Gemini API key (AIza...) not found in the API key field")
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
            val shot = svc?.captureScreenBase64()
            val scale = svc?.lastShotScale ?: 1f
            if (shot != null && svc != null) {
                AgentLog.add("ACTION", "Screenshot ${svc.lastShotWidth}x${svc.lastShotHeight} (${shot.length / 1024} KB)")
            }

            val user = buildString {
                append("TASK: ").append(task).append("\n\n")
                append("ACTIONS ALREADY DONE:\n")
                if (history.isEmpty()) append("(none yet)\n") else history.forEach { append(it).append('\n') }
                append('\n')
                if (shot != null && svc != null) {
                    append("SCREENSHOT: attached, ${svc.lastShotWidth}x${svc.lastShotHeight} px ")
                    append("(use ix,iy in these image pixels for icons not in the list).\n\n")
                } else {
                    append("SCREENSHOT: not available on this screen - use the UI ELEMENTS list only (x,y).\n\n")
                }
                append("UI ELEMENTS:\n").append(screen)
            }

            AgentLog.add("API", "Step $step/$MAX_STEPS: asking Gemini")
            val reply = when (val r = ClaudeClient.askWithVision(key, systemPrompt(lang), user, shot, 600)) {
                is ClaudeClient.ApiResult.Ok -> r.text
                is ClaudeClient.ApiResult.Err -> {
                    AgentLog.add("ERROR", r.message)
                    return lang.apiError()
                }
            }

            val obj = parseJson(reply)
            if (obj == null) {
                badReplies++
                AgentLog.add("ERROR", "Could not parse Gemini reply: ${reply.take(150)}")
                history.add("step $step: (your reply was not valid JSON - reply with ONE JSON object only)")
                if (badReplies >= 3) return lang.failed()
                continue
            }

            mapImageCoordinates(obj, scale)
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

            val result = svc.execute(obj, lang)
            AgentLog.add("ACTION", "Result: $result")
            history.add("step $step: ${obj.toString().take(160)} -> $result")
            delay(STEP_DELAY_MS)
        }
        AgentLog.add("ERROR", "Step limit ($MAX_STEPS) reached")
        return lang.stepLimit()
    }

    /**
     * Converts screenshot coordinates (ix,iy) to real screen pixels (x,y) using the screenshot scale,
     * and normalises a few action aliases the model may use.
     */
    private fun mapImageCoordinates(obj: JSONObject, scale: Float) {
        if (obj.has("ix") && obj.has("iy") && !(obj.has("x") && obj.has("y"))) {
            val ix = obj.optDouble("ix")
            val iy = obj.optDouble("iy")
            if (!ix.isNaN() && !iy.isNaN()) {
                obj.put("x", (ix * scale).roundToInt())
                obj.put("y", (iy * scale).roundToInt())
            }
        }
        when (obj.optString("action").lowercase(Locale.ROOT)) {
            "tap", "click", "tap_image" -> {
                if (obj.has("x") && obj.has("y")) obj.put("action", "tap_xy")
                else if (obj.optString("text").isNotBlank()) obj.put("action", "tap_text")
            }
        }
    }

    private fun parseJson(text: String): JSONObject? {
        val t = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (t.startsWith("[")) {
            try {
                val first = JSONArray(t).optJSONObject(0)
                if (first != null) return first
            } catch (ignored: Exception) {
            }
        }
        val s = t.indexOf('{')
        val e = t.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        return try {
            JSONObject(t.substring(s, e + 1))
        } catch (ex: Exception) {
            null
        }
    }
}
