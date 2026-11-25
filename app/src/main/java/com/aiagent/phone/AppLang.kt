package com.aiagent.phone

import java.util.Locale

/** UI / speech languages plus the short phrases the agent speaks on its own. */
enum class AppLang(
    val code: String,
    val label: String,
    val locale: Locale,
    val recognizerTag: String,
    val promptName: String
) {
    UZ("uz", "O'zbek", Locale("uz", "UZ"), "uz-UZ", "Uzbek (Latin script)"),
    RU("ru", "Русский", Locale("ru", "RU"), "ru-RU", "Russian"),
    EN("en", "English", Locale.US, "en-US", "English");

    fun listening(): String = when (this) {
        UZ -> "Tinglayapman. Buyruqni ayting."
        RU -> "Слушаю. Говорите команду."
        EN -> "Listening. Say your command."
    }

    fun noAccessibility(): String = when (this) {
        UZ -> "Maxsus imkoniyatlar xizmati yoqilmagan. Sozlamalardan yoqing."
        RU -> "Служба специальных возможностей не включена. Включите её в настройках."
        EN -> "The accessibility service is not enabled. Please enable it in settings."
    }

    fun failed(): String = when (this) {
        UZ -> "Kechirasiz, vazifani bajara olmadim."
        RU -> "Извините, не удалось выполнить задачу."
        EN -> "Sorry, I could not complete the task."
    }

    fun stepLimit(): String = when (this) {
        UZ -> "Vazifani tugatib bo'lmadi, juda ko'p qadam ketdi."
        RU -> "Не удалось завершить задачу, слишком много шагов."
        EN -> "I could not finish the task within the step limit."
    }

    fun apiError(): String = when (this) {
        UZ -> "Claude bilan bog'lanishda xatolik yuz berdi. API kalit va internetni tekshiring."
        RU -> "Ошибка связи с Claude. Проверьте API ключ и интернет."
        EN -> "Could not reach Claude. Check the API key and internet connection."
    }

    fun noKey(): String = when (this) {
        UZ -> "API kalit kiritilmagan."
        RU -> "API ключ не введён."
        EN -> "The API key is missing."
    }

    fun licenseInvalid(): String = when (this) {
        UZ -> "Litsenziya topilmadi."
        RU -> "Лицензия не найдена."
        EN -> "No valid license found."
    }

    fun done(): String = when (this) {
        UZ -> "Tayyor."
        RU -> "Готово."
        EN -> "Done."
    }

    companion object {
        fun fromCode(code: String?): AppLang = values().firstOrNull { it.code == code } ?: UZ
    }
}
