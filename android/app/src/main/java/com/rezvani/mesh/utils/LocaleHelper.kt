package com.rezvani.mesh.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import java.util.*

/**
 * Manages app localization, persisting the user's language preference
 * and applying it to the application context.
 *
 * Supported languages:
 * - "fa" : Farsi (Persian) - Default / Primary
 * - "en" : English - Fallback
 */
object LocaleHelper {
    private const val PREFS_NAME = "rezvan_prefs"
    private const val KEY_LANGUAGE = "app_language"
    private const val DEFAULT_LANGUAGE = "fa"

    /**
     * Creates a wrapped context with the saved locale applied.
     * Call this from Activity.attachBaseContext().
     *
     * @param context The base context.
     * @return Context with locale configuration applied.
     */
    fun setLocale(context: Context): Context {
        val languageCode = getSavedLanguage(context)
        return updateResources(context, languageCode)
    }

    /**
     * Overload for specifying language code directly.
     */
    fun setLocale(context: Context, languageCode: String): Context {
        return updateResources(context, languageCode)
    }

    /**
     * Gets the currently saved language code.
     *
     * @param context Application context.
     * @return Language code ("fa" or "en").
     */
    fun getSavedLanguage(context: Context): String {
        val prefs = getPreferences(context)
        return prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    }

    /**
     * Saves the user's language preference and updates the app configuration.
     *
     * @param context Application context.
     * @param languageCode Language code ("fa" or "en").
     */
    fun saveLanguage(context: Context, languageCode: String) {
        val prefs = getPreferences(context)
        prefs.edit().putString(KEY_LANGUAGE, languageCode).apply()
    }

    /**
     * Returns the current Locale based on saved language preference.
     */
    fun getCurrentLocale(context: Context): Locale {
        val languageCode = getSavedLanguage(context)
        return when (languageCode) {
            "fa" -> Locale("fa")
            "fa_IR" -> Locale("fa", "IR")
            else -> Locale("en")
        }
    }

    /**
     * Checks if the current language is RTL (Farsi).
     */
    fun isRtl(context: Context): Boolean {
        val languageCode = getSavedLanguage(context)
        return languageCode == "fa"
    }

    private fun updateResources(context: Context, languageCode: String): Context {
        val locale = when (languageCode) {
            "fa" -> Locale("fa")
            "fa_IR" -> Locale("fa", "IR")
            else -> Locale("en")
        }

        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            context
        }
    }

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Returns a list of supported language codes.
     */
    fun getSupportedLanguages(): List<LanguageOption> {
        return listOf(
            LanguageOption("fa", "فارسی", "Farsi"),
            LanguageOption("en", "English", "English")
        )
    }

    data class LanguageOption(
        val code: String,
        val nativeName: String,
        val englishName: String
    )
}
