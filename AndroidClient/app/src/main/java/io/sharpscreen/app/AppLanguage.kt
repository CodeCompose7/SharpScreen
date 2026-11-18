package io.sharpscreen.app

/**
 * In-app language choice. Applied with AppCompatDelegate.setApplicationLocales, which
 * persists it (framework LocaleManager on API 33+, AppLocalesMetadataHolderService below).
 * An empty tag means "follow the system language".
 */
enum class AppLanguage(
    val tag: String,
) {
    SYSTEM(""),
    ENGLISH("en"),
    KOREAN("ko"),
    ;

    companion object {
        /** Maps the first application locale's language (null when none is set) to a choice. */
        fun fromLanguage(language: String?): AppLanguage =
            when (language?.lowercase()) {
                null, "" -> SYSTEM
                "en" -> ENGLISH
                "ko" -> KOREAN
                else -> SYSTEM
            }
    }
}
