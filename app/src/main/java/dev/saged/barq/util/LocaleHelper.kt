package dev.saged.barq.util

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.DrawableRes
import dev.saged.barq.R
import java.util.Locale

/** The app language: Android 13+ keeps it in the system (per-app language), older versions in a small preference. */
object LocaleHelper {

    data class Lang(val tag: String, val nativeName: String, @DrawableRes val flag: Int)

    /** The ten most spoken languages in the world (Ethnologue, total speakers); Arabic first. */
    val languages = listOf(
        Lang("ar", "العربية", R.drawable.ic_flag_ps),
        Lang("en", "English", R.drawable.ic_flag_gb),
        Lang("zh", "中文", R.drawable.ic_flag_cn),
        Lang("hi", "हिन्दी", R.drawable.ic_flag_in),
        Lang("es", "Español", R.drawable.ic_flag_es),
        Lang("fr", "Français", R.drawable.ic_flag_fr),
        Lang("bn", "বাংলা", R.drawable.ic_flag_bd),
        Lang("pt", "Português", R.drawable.ic_flag_br),
        Lang("ru", "Русский", R.drawable.ic_flag_ru),
        Lang("ur", "اردو", R.drawable.ic_flag_pk),
    )

    private const val PREFS = "barq_locale"
    private const val KEY = "tag"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The language the user picked, or null when Barq follows the phone. */
    fun chosen(context: Context): String? {
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.let { if (it.isEmpty) null else it[0].language }
        } else {
            prefs(context).getString(KEY, null)
        }
        return tag?.takeIf { t -> languages.any { it.tag == t } }
    }

    /** Switches the language; a null [tag] goes back to the phone language. */
    fun apply(activity: Activity, tag: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            prefs(activity).edit().apply { if (tag == null) remove(KEY) else putString(KEY, tag) }.apply()
            activity.recreate()
        }
    }

    /** For attachBaseContext below Android 13; newer versions are handled by the system. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = prefs(base).getString(KEY, null) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList(locale)) }
        return base.createConfigurationContext(config)
    }
}
