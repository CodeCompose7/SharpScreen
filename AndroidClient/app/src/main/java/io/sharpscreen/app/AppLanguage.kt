package io.sharpscreen.app

import java.util.Locale

/**
 * In-app language choice. Applied with AppCompatDelegate.setApplicationLocales, which
 * persists it (framework LocaleManager on API 33+, AppLocalesMetadataHolderService below).
 * An empty tag means "follow the system language".
 *
 * Keep in sync with res/xml/locales_config.xml and the values-* resource folders
 * (Simplified Chinese lives in values-b+zh+Hans, which zh-CN / zh-SG also resolve to).
 */
enum class AppLanguage(
    val tag: String,
) {
    SYSTEM(""),
    ENGLISH("en"),
    KOREAN("ko"),
    JAPANESE("ja"),
    GERMAN("de"),
    FRENCH("fr"),
    RUSSIAN("ru"),
    CHINESE_SIMPLIFIED("zh-Hans"),
    SPANISH("es"),
    ;

    companion object {
        /** Regions whose Chinese is written in Traditional script when no script is given. */
        private val TRADITIONAL_CHINESE_REGIONS = setOf("TW", "HK", "MO")

        /** Maps the first application locale (null when none is set) to a choice. */
        fun fromLocale(locale: Locale?): AppLanguage = fromLanguage(locale?.language, locale?.script, locale?.country)

        /**
         * Maps a language to a choice. [language] is normally a bare language code
         * ("zh", from Locale.getLanguage), but a full tag such as "zh-Hans" or "zh_CN" is
         * accepted too; [script] and [region] come from the same locale when known (LocaleList
         * gives "zh-Hans" as language "zh" with script "Hans"). A language the app does not
         * offer, including Traditional Chinese, maps to SYSTEM.
         */
        fun fromLanguage(
            language: String?,
            script: String? = null,
            region: String? = null,
        ): AppLanguage {
            val parts = language?.trim()?.split('-', '_')?.filter { it.isNotEmpty() }.orEmpty()
            val lang = parts.firstOrNull()?.lowercase() ?: return SYSTEM
            val tagScript = parts.drop(1).firstOrNull { it.length == 4 }
            val tagRegion = parts.drop(1).firstOrNull { it.length == 2 || (it.length == 3 && it.all(Char::isDigit)) }
            val effectiveScript = (script?.takeIf { it.isNotBlank() } ?: tagScript)?.lowercase()
            val effectiveRegion = (region?.takeIf { it.isNotBlank() } ?: tagRegion)?.uppercase()
            return when (lang) {
                "en" -> ENGLISH
                "ko" -> KOREAN
                "ja" -> JAPANESE
                "de" -> GERMAN
                "fr" -> FRENCH
                "ru" -> RUSSIAN
                "es" -> SPANISH
                "zh" ->
                    when {
                        effectiveScript == "hans" -> CHINESE_SIMPLIFIED
                        effectiveScript != null -> SYSTEM
                        effectiveRegion in TRADITIONAL_CHINESE_REGIONS -> SYSTEM
                        else -> CHINESE_SIMPLIFIED
                    }
                else -> SYSTEM
            }
        }
    }
}
