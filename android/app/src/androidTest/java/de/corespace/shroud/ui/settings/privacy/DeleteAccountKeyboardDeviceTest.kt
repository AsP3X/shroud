package de.corespace.shroud.ui.settings.privacy

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.corespace.shroud.ui.components.PushedScreen
import de.corespace.shroud.ui.theme.ShroudTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The keyboard on Delete Account, on a device. The password card sits below the fold once the
 * keyboard is up, so focusing it scrolls it into view — a scroll that reports
 * `NestedScrollSource.UserInput` like a finger drag. The screen's hide-on-drag took it for one and
 * closed the keyboard as it opened; the field could not be typed in.
 */
@RunWith(AndroidJUnit4::class)
class DeleteAccountKeyboardDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var password by mutableStateOf(TextFieldValue(""))

    private fun show() {
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
        rule.setContent {
            ShroudTheme(dark = false) {
                PushedScreen(
                    title = DeleteAccountCopy.NAV_TITLE,
                    onBack = {},
                    bottomBar = {
                        DeleteAccountActions(
                            submitting = false,
                            canSubmit = password.text.isNotEmpty(),
                            onDelete = {},
                            onCancel = {},
                            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 8.dp),
                        )
                    },
                ) {
                    DeleteAccountForm(
                        password = password,
                        onPasswordChange = { password = it },
                        revealed = false,
                        onToggleReveal = {},
                        error = null,
                        submitting = false,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun imeVisible(): Boolean {
        var visible = false
        rule.runOnUiThread {
            val insets = ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
            visible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    private fun focusPassword() {
        rule.onNodeWithContentDescription(DeleteAccountCopy.PASSWORD_PLACEHOLDER).performClick()
        rule.waitUntil(KEYBOARD_TIMEOUT_MS) { imeVisible() }
    }

    @Test
    fun theKeyboardStaysUpAndThePasswordCanBeTyped() {
        show()
        focusPassword()
        // Past the keyboard's slide and the scroll that lifts the card above it.
        SystemClock.sleep(SETTLE_MS)
        rule.waitForIdle()
        assertTrue("the keyboard closed again", imeVisible())
        val field = rule.onNodeWithContentDescription(DeleteAccountCopy.PASSWORD_PLACEHOLDER)
        field.assertIsFocused()
        field.performTextInput("hunter2")
        rule.waitForIdle()
        assertEquals("hunter2", password.text)
        assertTrue(imeVisible())
    }

    @Test
    fun aFingerDragStillHidesTheKeyboard() {
        show()
        focusPassword()
        SystemClock.sleep(SETTLE_MS)
        rule.waitForIdle()
        assertTrue("the keyboard closed again", imeVisible())
        rule.onNodeWithText(DeleteAccountCopy.CONFIRM).performTouchInput { swipeDown() }
        rule.waitUntil(KEYBOARD_TIMEOUT_MS) { !imeVisible() }
    }

    private companion object {
        const val KEYBOARD_TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 1_500L
    }
}
