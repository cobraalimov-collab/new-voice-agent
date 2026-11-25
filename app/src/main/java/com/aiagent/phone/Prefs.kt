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
}
