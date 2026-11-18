package io.sharpscreen.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppSettingsTest {
    @Test
    fun languageFromLocale() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguage(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguage(""))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguage("en"))
        assertEquals(AppLanguage.KOREAN, AppLanguage.fromLanguage("ko"))
        assertEquals(AppLanguage.KOREAN, AppLanguage.fromLanguage("KO"))
        // A language the app does not offer is shown as "System default".
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguage("fr"))
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
