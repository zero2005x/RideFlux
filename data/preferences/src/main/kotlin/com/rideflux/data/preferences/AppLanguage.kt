package com.rideflux.data.preferences

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** Empty tag means the device language. Both APKs keep their own preference. */
object AppLanguage {
    private const val STORE = "rideflux_language"
    private const val KEY = "language_tag"

    val supportedTags = listOf(
        "en", "ar", "de", "es", "fr", "hi", "id", "it", "ja", "ko",
        "nl", "pt", "ru", "uk", "ur", "vi", "zh-CN", "zh-TW",
    )

    fun selected(context: Context): String = if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(LocaleManager::class.java)
            ?.applicationLocales?.get(0)?.toLanguageTag().orEmpty()
    } else {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
    }

    fun set(activity: Activity, tag: String) {
        require(tag.isEmpty() || tag in supportedTags)
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences(STORE, Context.MODE_PRIVATE)
                .edit().putString(KEY, tag).apply()
            activity.recreate()
        }
    }

    /** Called before Activity.onCreate on Android 9–12. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = selected(base)
        if (tag.isEmpty()) return base
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(config)
    }
}
