package io.sharpscreen.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class AppSettingsTest {
    @Test
    fun languageFromLocale() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguage(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguage(""))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguage("en"))
        assertEquals(AppLanguage.KOREAN, AppLanguage.fromLanguage("ko"))
        assertEquals(AppLanguage.KOREAN, AppLanguage.fromLanguage("KO"))
        assertEquals(AppLanguage.JAPANESE, AppLanguage.fromLanguage("ja"))
        assertEquals(AppLanguage.GERMAN, AppLanguage.fromLanguage("de"))
        assertEquals(AppLanguage.GERMAN, AppLanguage.fromLanguage("de-AT"))
        assertEquals(AppLanguage.FRENCH, AppLanguage.fromLanguage("fr"))
        assertEquals(AppLanguage.FRENCH, AppLanguage.fromLanguage("fr_CA"))
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.fromLanguage("ru"))
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromLanguage("es"))
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromLanguage("es-419"))
        // A language the app does not offer is shown as "System default".
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguage("it"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguage("pt"))
    }

    @Test
    fun simplifiedChineseFromLocale() {
        // LocaleList gives "zh-Hans" as language "zh" with script "Hans".
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromLanguage("zh", "Hans"))
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromLanguage("zh", "Hans", "CN"))
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromLanguage("zh", "", "CN"))
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromLanguage("zh", null, "SG"))
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromLanguage("zh"))
        // Full tags passed as the language.
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromLanguage("zh-Hans"))
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromLanguage("zh-CN"))
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromLanguage("zh_CN"))
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromLanguage("zh-Hans-CN"))
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromLanguage("ZH-hans"))
        // java.util.Locale, as AppCompatDelegate.getApplicationLocales() returns it.
        for (tag in listOf("zh-Hans", "zh-CN", "zh-Hans-CN", "zh-SG", "zh")) {
            assertEquals(tag, AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromLocale(Locale.forLanguageTag(tag)))
        }
        // Traditional Chinese is not offered.
        for (tag in listOf("zh-Hant", "zh-TW", "zh-HK", "zh-Hant-TW", "zh-MO")) {
            assertEquals(tag, AppLanguage.SYSTEM, AppLanguage.fromLocale(Locale.forLanguageTag(tag)))
            assertEquals(tag, AppLanguage.SYSTEM, AppLanguage.fromLanguage(tag))
        }
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLocale(null))
    }

    @Test
    fun languageTags() {
        assertEquals("ja", AppLanguage.JAPANESE.tag)
        assertEquals("de", AppLanguage.GERMAN.tag)
        assertEquals("fr", AppLanguage.FRENCH.tag)
        assertEquals("ru", AppLanguage.RUSSIAN.tag)
        assertEquals("zh-Hans", AppLanguage.CHINESE_SIMPLIFIED.tag)
        assertEquals("es", AppLanguage.SPANISH.tag)
    }

    @Test
    fun systemDefaultClearsTheLocaleList() {
        assertEquals("", AppLanguage.SYSTEM.tag)
    }

    @Test
    fun usbPortParsing() {
        assertEquals(54321, PreferencesManager.parsePort("54321"))
        assertEquals(8888, PreferencesManager.parsePort(" 8888 "))
        assertEquals(1, PreferencesManager.parsePort("1"))
        assertEquals(65535, PreferencesManager.parsePort("65535"))
        assertNull(PreferencesManager.parsePort(""))
        assertNull(PreferencesManager.parsePort("0"))
        assertNull(PreferencesManager.parsePort("65536"))
        assertNull(PreferencesManager.parsePort("abc"))
    }
}
