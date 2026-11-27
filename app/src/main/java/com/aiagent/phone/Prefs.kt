package com.aiagent.phone

import android.content.Context

object Prefs {
    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)

    fun getApiKey(c: Context): String = sp(c).getString("api_key", "") ?: ""

    fun setApiKey(c: Context, key: String) {
        sp(c).edit().putString("api_key", key).apply()
    }

    fun getLang(c: Context): AppLang = AppLang.fromCode(sp(c).getString("lang", "uz"))

    fun setLang(c: Context, lang: AppLang) {
        sp(c).edit().putString("lang", lang.code).apply()
    }

    // ------------------------------------------------------------------ multi-key support

    /** Separators the user may type between keys. */
    private val SEPARATORS = Regex("[,;\\s]+")

    /**
     * Zero-width split in front of every known key prefix. MainActivity strips spaces/newlines
     * when saving, so "gsk_AAA sk-ant-BBB" is stored as "gsk_AAAsk-ant-BBB" - this still splits it.
     */
    private val PREFIX_SPLIT = Regex("(?=gsk_|sk-ant-|AIza)")

    /** All keys found in the API key field, in the order they were entered. */
    fun getAllKeys(c: Context): List<String> =
        getApiKey(c)
            .split(SEPARATORS)
            .flatMap { it.split(PREFIX_SPLIT) }
            .map { it.trim().trim('"', '\'') }
            .filter { it.length >= 8 }

    /** Groq key (speech-to-text): the token starting with "gsk_", or "" if none. */
    fun getGroqKey(c: Context): String =
        getAllKeys(c).firstOrNull { it.startsWith("gsk_") } ?: ""

    /** Brain key: prefers an Anthropic key (sk-ant-), otherwise the first key that is not a Groq key. */
    fun getBrainKey(c: Context): String {
        val keys = getAllKeys(c)
        return keys.firstOrNull { it.startsWith("sk-ant-") }
            ?: keys.firstOrNull { !it.startsWith("gsk_") }
            ?: ""
    }
}
