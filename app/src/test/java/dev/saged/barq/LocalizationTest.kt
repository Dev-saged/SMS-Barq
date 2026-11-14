package dev.saged.barq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalizationTest {

    private val res = File("src/main/res")
    private val tags = listOf("ar", "en", "zh", "hi", "es", "fr", "bn", "pt", "ru", "ur")
    private val rtl = setOf("ar", "ur")

    private fun folder(tag: String) = File(res, if (tag == "ar") "values" else "values-$tag")

    private fun strings(tag: String): Map<String, String> =
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(File(folder(tag), "strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun placeholders(v: String) = Regex("""%\d\$[sd]""").findAll(v).map { it.value }.sorted().toList()

    @Test
    fun everyLanguageHasEveryKeyWithTheSamePlaceholders() {
        val base = strings("en")
        tags.filter { it != "en" }.forEach { tag ->
            val other = strings(tag)
            assertEquals("missing keys in $tag", emptyList<String>(), base.keys.filter { it !in other })
            assertEquals("extra keys in $tag", emptyList<String>(), other.keys.filter { it !in base })
            val bad = base.filter { (k, v) -> placeholders(v) != placeholders(other.getValue(k)) }.keys
            assertEquals("placeholder mismatch in $tag", emptySet<String>(), bad)
        }
    }

    @Test
    fun noUnescapedApostropheOrAmpersand() {
        tags.forEach { tag ->
            strings(tag).forEach { (k, v) ->
                assertTrue("$tag/$k apostrophe", !Regex("""(?<!\\)'""").containsMatchIn(v))
                assertTrue("$tag/$k ampersand", !Regex("""&(?!(amp|lt|gt|quot|apos);)""").containsMatchIn(v))
            }
        }
    }

    @Test
    fun layoutDirectionFlagsMatchTheLanguage() {
        tags.forEach { tag ->
            val file = File(folder(tag), "bools.xml").readText()
            fun flag(name: String) = Regex("""<bool name="$name">(true|false)</bool>""").find(file)?.groupValues?.get(1)
            assertEquals("barq_rtl in $tag", (tag in rtl).toString(), flag("barq_rtl"))
            assertEquals("barq_arabic in $tag", (tag == "ar").toString(), flag("barq_arabic"))
        }
    }

    @Test
    fun everyLanguageIsDeclaredAndHasAFlag() {
        val config = File(res, "xml/locales_config.xml").readText()
        val helper = File("src/main/java/dev/saged/barq/util/LocaleHelper.kt").readText()
        tags.forEach { tag ->
            assertTrue("locales_config $tag", config.contains("""android:name="$tag""""))
            assertTrue("LocaleHelper $tag", helper.contains("""Lang("$tag","""))
        }
        Regex("""R\.drawable\.(ic_flag_\w+)""").findAll(helper).forEach {
            assertTrue("flag ${it.groupValues[1]}", File(res, "drawable/${it.groupValues[1]}.xml").exists())
        }
    }
}
