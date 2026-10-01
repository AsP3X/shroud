package de.corespace.shroud.ui.theme

import androidx.compose.ui.graphics.vector.ImageVector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every icon listed in `assets/licenses/icons.txt` exists in [ShroudIcons] under the generator's
 * naming rule and builds (its path data parses), and the union plan §1.7.12 asks for is listed.
 * `gen_shroud_icons.py --check` compares the paths with the pinned SVGs.
 */
class ShroudIconsTest {
    private val listing = File("src/main/assets/licenses/icons.txt").readText()

    private fun names(prefix: String): List<String> =
        listing.lines().first { it.startsWith(prefix) }.substringAfter(": ").split(", ").map { it.trim() }

    private val lucide = names("Lucide 1.49.0:")
    private val phosphor = names("Phosphor 2.1.1:")

    private fun pascal(name: String) = name.split("-").joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

    private fun kotlinNames(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val taken = mutableSetOf<String>()
        lucide.forEach { result["lucide:$it"] = pascal(it).also(taken::add) }
        phosphor.forEach {
            var name = pascal(it)
            if (name in taken) name += "Regular"
            taken += name
            result["phosphor:$it"] = name
        }
        return result
    }

    @Test
    fun everyListedIconExistsAndBuilds() {
        val names = kotlinNames()
        assertEquals(lucide.size + phosphor.size, names.values.toSet().size)
        names.forEach { (design, kotlin) ->
            val getter = runCatching { ShroudIcons::class.java.getMethod("get$kotlin") }.getOrNull()
            assertTrue("$design → ShroudIcons.$kotlin missing", getter != null)
            val vector = getter!!.invoke(ShroudIcons) as ImageVector
            assertEquals(kotlin, vector.name)
        }
    }

    @Test
    fun listedIconsCoverThePlansUnion() {
        // Plan §1.7.12: settings-lock §2/§11 and notifications, plus a sample of each area spec.
        val required = mapOf(
            "lucide" to listOf(
                "key-round", "lock-open", "scan-face", "check", "square-pen", "search", "chevron-left", "check-check",
                "copy", "forward", "pin", "reply", "trash-2", "link", "smile", "mic", "arrow-up", "screen-share",
                "qr-code", "user-plus", "camera-off", "wifi-off", "refresh-cw",
            ),
            "phosphor" to listOf(
                "fingerprint", "caret-up-down", "bell-slash-fill", "bell-fill", "chats-circle-fill", "user-circle-fill",
                "phone-fill", "gear-six-fill", "magnifying-glass-bold", "x-bold", "x-circle-fill", "bookmark-simple-fill",
                "heart-fill", "trash", "wifi-slash", "users", "microphone-fill", "microphone-slash-fill",
                "video-camera-slash-fill", "phone-disconnect-fill", "stop-fill", "camera-rotate-fill", "shield-warning-fill",
                "lock-simple-fill", "lock-simple-open-fill", "arrow-u-up-left", "magic-wand", "chats-teardrop-fill",
                "download-simple", "users-fill", "export-bold", "caret-right-bold", "warning-fill",
            ),
        )
        required.getValue("lucide").forEach { assertTrue("Lucide $it", it in lucide) }
        required.getValue("phosphor").forEach { assertTrue("Phosphor $it", it in phosphor) }
    }

    @Test
    fun theLicencesAreNamed() {
        assertTrue(listing.contains("ISC License"))
        assertTrue(listing.contains("MIT License"))
    }
}
