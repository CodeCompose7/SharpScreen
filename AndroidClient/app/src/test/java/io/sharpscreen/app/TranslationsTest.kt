package io.sharpscreen.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Lint-style checks on the string resources: every translatable string in values/ is
 * translated in each offered language with the same placeholders, and the language lists
 * (locales_config.xml, AppLanguage, resource folders) agree.
 */
class TranslationsTest {
    private val res = File("src/main/res")

    /** Resource folder per offered language tag (English is the default values/). */
    private val localeFolders =
        mapOf(
            "ko" to "values-ko",
            "ja" to "values-ja",
            "de" to "values-de",
            "fr" to "values-fr",
            "ru" to "values-ru",
            "zh-Hans" to "values-b+zh+Hans",
            "es" to "values-es",
        )

    private data class Entry(
        val text: String,
        val translatable: Boolean,
    )

    private fun strings(folder: String): Map<String, Entry> {
        val file = File(res, "$folder/strings.xml")
        assertTrue("missing $file", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("name") to Entry(e.textContent, e.getAttribute("translatable") != "false")
        }
    }

    private val placeholder = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z%]""")

    private fun placeholders(text: String): Set<String> = placeholder.findAll(text).map { it.value }.toSet()

    /** An apostrophe or double quote that aapt2 would drop or choke on. */
    private fun unescapedQuote(text: String): Boolean {
        val quoted = text.length >= 2 && text.startsWith('"') && text.endsWith('"')
        // In a fully quoted value apostrophes are literal; only inner double quotes need escaping.
        return if (quoted) {
            Regex("""(?<!\\)"""").containsMatchIn(text.substring(1, text.length - 1))
        } else {
            Regex("""(?<!\\)['"]""").containsMatchIn(text)
        }
    }

    @Test
    fun everyTranslatableStringIsTranslatedWithMatchingPlaceholders() {
        val base = strings("values")
        val translatable = base.filterValues { it.translatable }
        assertTrue(translatable.size > 100)
        val problems = mutableListOf<String>()
        for ((tag, folder) in localeFolders) {
            val loc = strings(folder)
            for ((name, entry) in translatable) {
                val t = loc[name]
                if (t == null) {
                    problems += "$tag: missing $name"
                    continue
                }
                if (t.text.isBlank()) problems += "$tag: $name is empty"
                if (placeholders(t.text) != placeholders(entry.text)) {
                    problems += "$tag: $name placeholders ${placeholders(t.text)} != ${placeholders(entry.text)}"
                }
                if (unescapedQuote(t.text)) problems += "$tag: $name has an unescaped quote: ${t.text}"
            }
            for (name in loc.keys - translatable.keys) {
                // Includes app_name, which must not override the debug build's label.
                problems += "$tag: $name is not a translatable string in values/"
            }
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }

    @Test
    fun languageListsAgree() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "xml/locales_config.xml"))
        val nodes = doc.getElementsByTagName("locale")
        val configured =
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("android:name") }.toSet()
        val offered = AppLanguage.entries.filter { it != AppLanguage.SYSTEM }.map { it.tag }.toSet()
        assertEquals(offered, configured)
        assertEquals(offered - "en", localeFolders.keys)
        for (language in AppLanguage.entries) {
            assertEquals(language, AppLanguage.fromLanguage(language.tag))
        }
    }
}
