package com.aiagent.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null

        private const val MAX_NODES = 150
        private const val MAX_SHOT_WIDTH = 720
        private const val JPEG_QUALITY = 70
        private const val MAX_HOLD_MS = 55000L

        private val ALIASES: List<Pair<String, List<String>>> = listOf(
            "telegram" to listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram"),
            "телеграм" to listOf("org.telegram.messenger", "org.telegram.messenger.web"),
            "youtube" to listOf("com.google.android.youtube"),
            "ютуб" to listOf("com.google.android.youtube"),
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "ватсап" to listOf("com.whatsapp"),
            "instagram" to listOf("com.instagram.android"),
            "chrome" to listOf("com.android.chrome"),
            "хром" to listOf("com.android.chrome"),
            "gmail" to listOf("com.google.android.gm"),
            "maps" to listOf("com.google.android.apps.maps"),
            "карты" to listOf("com.google.android.apps.maps"),
            "settings" to listOf("com.android.settings"),
            "sozlamalar" to listOf("com.android.settings"),
            "настройки" to listOf("com.android.settings"),
            "camera" to listOf("com.sec.android.app.camera", "com.android.camera", "com.google.android.GoogleCamera"),
            "камера" to listOf("com.sec.android.app.camera", "com.android.camera"),
            "phone" to listOf("com.samsung.android.dialer", "com.google.android.dialer", "com.android.dialer"),
            "телефон" to listOf("com.samsung.android.dialer", "com.google.android.dialer", "com.android.dialer"),
            "messages" to listOf("com.samsung.android.messaging", "com.google.android.apps.messaging"),
            "play store" to listOf("com.android.vending"),
            "gallery" to listOf("com.sec.android.gallery3d", "com.google.android.apps.photos"),
            "calculator" to listOf("com.sec.android.app.popupcalculator", "com.google.android.calculator")
        )

        /** True if the user enabled this service in Android settings (or it is currently connected). */
        fun isEnabled(ctx: Context): Boolean {
            if (instance != null) return true
            return try {
                val enabled = Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
                if (!enabled) return false
                val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    ?: return false
                val mine = ComponentName(ctx.packageName, AgentAccessibilityService::class.java.name)
                list.split(':').any { s ->
                    val cn = ComponentName.unflattenFromString(s)
                    cn != null && cn == mine
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    /** Size of the last screenshot sent to the model, and screen-pixels per screenshot-pixel. */
    @Volatile
    var lastShotWidth = 0
        private set

    @Volatile
    var lastShotHeight = 0
        private set

    @Volatile
    var lastShotScale = 1f
        private set

    @Volatile
    private var lastShotError = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AgentLog.add("SYS", "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used: the agent pulls the screen on demand.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        AgentLog.add("SYS", "Accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Real full-screen size in pixels (includes status and navigation bars, like node bounds and screenshots). */
    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        return try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            if (dm.widthPixels > 0 && dm.heightPixels > 0) dm.widthPixels to dm.heightPixels
            else resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels
        } catch (e: Exception) {
            resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels
        }
    }

    // ------------------------------------------------------------------ screen reading (UI tree)

    fun describeScreen(): String {
        val root = rootInActiveWindow
            ?: return "(screen not readable: no active window - it may be loading or a protected screen)"
        val (w, h) = screenSize()
        val sb = StringBuilder()
        sb.append("Screen ${w}x${h}, foreground package: ${root.packageName}\n")
        val counter = intArrayOf(0)
        walk(root, 0, sb, counter)
        if (counter[0] == 0) sb.append("(no readable nodes)\n")
        return sb.toString()
    }

    private fun clean(s: CharSequence?, max: Int = 80): String =
        (s?.toString() ?: "").replace('\n', ' ').replace('"', '\'').trim().take(max)

    private fun walk(n: AccessibilityNodeInfo?, depth: Int, sb: StringBuilder, counter: IntArray) {
        if (n == null || depth > 40 || counter[0] >= MAX_NODES) return
        if (!n.isVisibleToUser) return

        val text = clean(n.text)
        val desc = clean(n.contentDescription)
        val interactive = n.isClickable || n.isEditable || n.isLongClickable || n.isScrollable || n.isCheckable
        if (interactive || text.isNotEmpty() || desc.isNotEmpty()) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) {
                val id = n.viewIdResourceName?.substringAfter('/') ?: ""
                val flags = ArrayList<String>()
                if (n.isClickable) flags.add("clickable")
                if (n.isLongClickable) flags.add("long-clickable")
                if (n.isEditable) flags.add("editable")
                if (n.isScrollable) flags.add("scrollable")
                if (n.isCheckable) flags.add(if (n.isChecked) "checked" else "checkable")
                if (n.isFocused) flags.add("focused")
                sb.append("[${r.centerX()},${r.centerY()}] text=\"$text\" desc=\"$desc\" id=$id ${flags.joinToString(",")}\n")
                counter[0]++
            }
        }
        for (i in 0 until n.childCount) {
            walk(n.getChild(i), depth + 1, sb, counter)
        }
    }

    // ------------------------------------------------------------------ screenshot (visual eyes)

    /**
     * Takes a screenshot (Android 11+), scales it to max 720 px wide, JPEG 70 %, Base64 NO_WRAP.
     * Returns null on Android < 11, on secure screens (banking, secret chats) or any failure.
     * Requires android:canTakeScreenshot="true" in accessibility_service_config.xml.
     */
    suspend fun captureScreenBase64(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        for (attempt in 0..1) {
            lastShotError = 0
            val bmp = withTimeoutOrNull(4000L) { takeScreenshotBitmap() }
            if (bmp != null) {
                return withContext(Dispatchers.Default) { encodeJpeg(bmp) }
            }
            // Error 3 = screenshots requested too often (min ~333 ms apart): wait and retry once.
            if (lastShotError == 3 && attempt == 0) {
                delay(450)
                continue
            }
            break
        }
        return null
    }

    private suspend fun takeScreenshotBitmap(): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        var soft: Bitmap? = null
                        val buffer = result.hardwareBuffer
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            if (hw != null) {
                                soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                                hw.recycle()
                            }
                        } catch (e: Throwable) {
                            AgentLog.add("ERROR", "Screenshot conversion failed: ${e.javaClass.simpleName}")
                        } finally {
                            try {
                                buffer.close()
                            } catch (ignored: Exception) {
                            }
                        }
                        if (cont.isActive) cont.resume(soft) else soft?.recycle()
                    }

                    override fun onFailure(errorCode: Int) {
                        lastShotError = errorCode
                        val why = when (errorCode) {
                            1 -> "internal error"
                            2 -> "no screenshot permission (add canTakeScreenshot to accessibility config, then re-enable the service)"
                            3 -> "requested too often"
                            4 -> "invalid display"
                            5 -> "invalid window"
                            6 -> "secure screen (app blocks screenshots)"
                            else -> "code $errorCode"
                        }
                        if (errorCode != 3) AgentLog.add("ERROR", "Screenshot failed: $why")
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        } catch (e: Throwable) {
            AgentLog.add("ERROR", "takeScreenshot crashed: ${e.javaClass.simpleName}: ${e.message}")
            if (cont.isActive) cont.resume(null)
        }
    }

    /** Scales + compresses, recycling every bitmap it touches (including [src]). */
    private fun encodeJpeg(src: Bitmap): String? {
        var scaled: Bitmap? = null
        try {
            val w = src.width
            val h = src.height
            if (w <= 0 || h <= 0) return null
            val targetW = min(MAX_SHOT_WIDTH, w)
            val targetH = max(1, (h.toLong() * targetW / w).toInt())
            val s = if (targetW == w) src else Bitmap.createScaledBitmap(src, targetW, targetH, true)
            scaled = s
            val bos = ByteArrayOutputStream(96 * 1024)
            if (!s.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)) return null
            lastShotWidth = targetW
            lastShotHeight = targetH
            lastShotScale = w.toFloat() / targetW.toFloat()
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Throwable) {
            AgentLog.add("ERROR", "Screenshot encode failed: ${e.javaClass.simpleName}")
            return null
        } finally {
            val s = scaled
            if (s != null && s !== src) s.recycle()
            src.recycle()
        }
    }

    // ------------------------------------------------------------------ actions

    suspend fun execute(cmd: JSONObject, lang: AppLang = Prefs.getLang(this)): String {
        return try {
            when (val a = cmd.optString("action").lowercase(Locale.ROOT)) {
                "open_app" -> openApp(cmd.optString("app").ifBlank { cmd.optString("package") })
                "tap_xy" -> {
                    if (!cmd.has("x") || !cmd.has("y")) "failed: tap_xy needs x and y"
                    else {
                        val x = cmd.optInt("x")
                        val y = cmd.optInt("y")
                        if (tap(x, y)) "tapped ($x,$y)" else "failed: gesture rejected at ($x,$y)"
                    }
                }
                "tap_text" -> tapText(cmd.optString("text"))
                "long_click", "long_press" -> longClick(cmd)
                "hold" -> if (cmd.optString("message").isNotBlank()) holdAndSpeak(cmd, lang) else longClick(cmd)
                "hold_and_speak", "voice_message" -> holdAndSpeak(cmd, lang)
                "type_text" -> typeText(cmd.optString("text"), cmd.optBoolean("enter", false))
                "scroll" -> scroll(cmd.optString("direction", "down"))
                "back" -> if (performGlobalAction(GLOBAL_ACTION_BACK)) "back pressed" else "failed: back"
                "home" -> if (performGlobalAction(GLOBAL_ACTION_HOME)) "home pressed" else "failed: home"
                "wait" -> {
                    val s = cmd.optDouble("seconds", 2.0).coerceIn(0.5, 10.0)
                    delay((s * 1000).toLong())
                    "waited ${s}s"
                }
                else -> "failed: unknown action '$a'"
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private suspend fun dispatchPath(path: Path, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val started = dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!started && cont.isActive) cont.resume(false)
        }

    private fun pointPath(x: Int, y: Int): Path {
        val (w, h) = screenSize()
        val path = Path()
        path.moveTo(x.coerceIn(1, w - 1).toFloat(), y.coerceIn(1, h - 1).toFloat())
        return path
    }

    private suspend fun tap(x: Int, y: Int): Boolean = dispatchPath(pointPath(x, y), 60)

    /** Finger down at (x,y), held still for [ms], then released. */
    private suspend fun holdAt(x: Int, y: Int, ms: Long): Boolean =
        dispatchPath(pointPath(x, y), ms.coerceIn(50L, MAX_HOLD_MS))

    private suspend fun scroll(direction: String): String {
        val (wi, hi) = screenSize()
        val w = wi.toFloat()
        val h = hi.toFloat()
        val up = direction.equals("up", ignoreCase = true)
        // "down" = reveal content below -> finger swipes upwards
        val y1 = if (up) h * 0.30f else h * 0.70f
        val y2 = if (up) h * 0.70f else h * 0.30f
        val path = Path()
        path.moveTo(w / 2f, y1)
        path.lineTo(w / 2f, y2)
        val ok = dispatchPath(path, 350)
        return if (ok) "scrolled ${if (up) "up" else "down"}" else "failed: scroll gesture rejected"
    }

    private suspend fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < 7) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            hops++
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        return if (!r.isEmpty) tap(r.centerX(), r.centerY()) else false
    }

    private fun collect(
        n: AccessibilityNodeInfo?, q: String, depth: Int,
        exact: MutableList<AccessibilityNodeInfo>, partial: MutableList<AccessibilityNodeInfo>
    ) {
        if (n == null || depth > 45 || !n.isVisibleToUser) return
        val t = n.text?.toString()?.trim()?.lowercase(Locale.ROOT)
        val d = n.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT)
        if (t == q || d == q) exact.add(n)
        else if ((t != null && t.contains(q)) || (d != null && d.contains(q))) partial.add(n)
        for (i in 0 until n.childCount) collect(n.getChild(i), q, depth + 1, exact, partial)
    }

    private fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val q = text.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return null
        val root = rootInActiveWindow ?: return null
        val exact = ArrayList<AccessibilityNodeInfo>()
        val partial = ArrayList<AccessibilityNodeInfo>()
        collect(root, q, 0, exact, partial)
        return exact.firstOrNull()
            ?: partial.minByOrNull { (it.text?.length ?: it.contentDescription?.length ?: 0) }
    }

    private suspend fun tapText(text: String): String {
        if (text.isBlank()) return "failed: tap_text needs text"
        if (rootInActiveWindow == null) return "failed: no active window"
        val target = findNodeByText(text) ?: return "failed: no visible element matching \"$text\""
        return if (clickNode(target)) "tapped \"$text\"" else "failed: could not click \"$text\""
    }

    /** Target point from "x"/"y", or from the center of the element matching "text". */
    private fun resolvePoint(cmd: JSONObject): Pair<Int, Int>? {
        if (cmd.has("x") && cmd.has("y")) return cmd.optInt("x") to cmd.optInt("y")
        val text = cmd.optString("text")
        if (text.isBlank()) return null
        val node = findNodeByText(text) ?: return null
        val r = Rect()
        node.getBoundsInScreen(r)
        return if (r.isEmpty) null else r.centerX() to r.centerY()
    }

    private suspend fun longClick(cmd: JSONObject): String {
        val ms = cmd.optLong("ms", 800L).coerceIn(400L, 10000L)
        // Default-length long press on a text target: try the accessibility action first.
        if (!cmd.has("x") && ms <= 1000L) {
            val text = cmd.optString("text")
            if (text.isNotBlank()) {
                var cur: AccessibilityNodeInfo? = findNodeByText(text)
                var hops = 0
                while (cur != null && hops < 7) {
                    if (cur.isLongClickable && cur.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                        return "long-clicked \"$text\""
                    }
                    cur = cur.parent
                    hops++
                }
            }
        }
        val pt = resolvePoint(cmd) ?: return "failed: long_click needs x,y or a visible text"
        return if (holdAt(pt.first, pt.second, ms)) "long-pressed (${pt.first},${pt.second}) for ${ms}ms"
        else "failed: long-press gesture rejected"
    }

    private suspend fun speakAndWait(text: String, lang: AppLang, timeoutMs: Long): Boolean {
        val finished = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Unit> { cont ->
                TtsManager.speak(text, lang) { if (cont.isActive) cont.resume(Unit) }
            }
            true
        }
        return finished == true
    }

    /**
     * Voice message: press and hold (x,y) - e.g. Telegram's microphone button - while the
     * TTS voice speaks [message]; releasing the finger sends the recording.
     */
    private suspend fun holdAndSpeak(cmd: JSONObject, lang: AppLang): String {
        val message = cmd.optString("message").ifBlank { cmd.optString("speech") }.trim()
        if (message.isEmpty()) return "failed: hold_and_speak needs \"message\""
        val pt = resolvePoint(cmd) ?: return "failed: hold_and_speak needs x,y (or ix,iy) of the mic button"

        // Estimated speaking time + margin so the end of the sentence is not cut off.
        val speechMs = (message.length * 85L + 1500L).coerceIn(2500L, MAX_HOLD_MS - 2000L)
        val holdMs = (speechMs + 1400L).coerceAtMost(MAX_HOLD_MS)
        AgentLog.add("ACTION", "Holding (${pt.first},${pt.second}) ${holdMs}ms and speaking: ${message.take(60)}")

        return coroutineScope {
            val gesture = async { holdAt(pt.first, pt.second, holdMs) }
            delay(700) // give the app time to start recording
            val spoke = speakAndWait(message, lang, holdMs + 3000L)
            val held = gesture.await()
            when {
                !held -> "failed: hold gesture was interrupted (recording may be cancelled)"
                !spoke -> "held mic ${holdMs}ms and released (TTS did not report completion)"
                else -> "held mic ${holdMs}ms while speaking, then released (voice message should be sent)"
            }
        }
    }

    private fun findEditable(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 45 || !n.isVisibleToUser) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            val f = findEditable(n.getChild(i), depth + 1)
            if (f != null) return f
        }
        return null
    }

    private suspend fun typeText(text: String, enter: Boolean): String {
        val root = rootInActiveWindow ?: return "failed: no active window"
        var target: AccessibilityNodeInfo? = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target == null || !target.isEditable) target = findEditable(root, 0)
        if (target == null) return "failed: no editable field on screen (tap the field first)"

        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        var ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("agent", text))
                ok = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            } catch (e: Exception) {
                AgentLog.add("ERROR", "Clipboard paste failed: ${e.message}")
            }
        }
        if (!ok) return "failed: could not set text"
        var extra = ""
        if (enter) {
            delay(300)
            extra = if (Build.VERSION.SDK_INT >= 30 &&
                target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            ) " + enter" else " (enter key not available - tap the search/send button)"
        }
        return "typed \"${text.take(40)}\"$extra"
    }

    // ------------------------------------------------------------------ open app

    private fun resolvePackage(query: String): String? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val pm = packageManager
        if (q.contains('.') && !q.contains(' ') && pm.getLaunchIntentForPackage(q) != null) return q
        val ql = q.lowercase(Locale.ROOT)
        for ((key, pkgs) in ALIASES) {
            if (ql == key || ql.contains(key)) {
                for (p in pkgs) if (pm.getLaunchIntentForPackage(p) != null) return p
            }
        }
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
        val labelled = apps.map { it.loadLabel(pm).toString().lowercase(Locale.ROOT) to it.activityInfo.packageName }
        return labelled.firstOrNull { it.first == ql }?.second
            ?: labelled.firstOrNull { it.first.contains(ql) }?.second
            ?: labelled.firstOrNull { it.first.length >= 3 && ql.contains(it.first) }?.second
            ?: labelled.firstOrNull { it.second.lowercase(Locale.ROOT).contains(ql.replace(" ", "")) }?.second
    }

    private fun openApp(name: String): String {
        val pkg = resolvePackage(name) ?: return "failed: app \"$name\" not found on this phone"
        val intent = packageManager.getLaunchIntentForPackage(pkg)
            ?: return "failed: no launch intent for $pkg"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            startActivity(intent)
            "opened $pkg"
        } catch (e: Exception) {
            "failed: could not start $pkg (${e.message})"
        }
    }
}
