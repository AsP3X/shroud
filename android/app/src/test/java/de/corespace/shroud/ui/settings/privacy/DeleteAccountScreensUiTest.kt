package de.corespace.shroud.ui.settings.privacy

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import de.corespace.shroud.ui.components.ComposeHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Privacy's last card, and the delete screen's §3.3 states, with no server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeleteAccountScreensUiTest {
    private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()

    private fun SemanticsNode.isDisabled(): Boolean = SemanticsProperties.Disabled in config

    private fun ComposeHarness.has(text: String): Boolean = nodesWithText(text).isNotEmpty()

    private fun ComposeHarness.labelled(text: String): SemanticsNode =
        nodes().single { node ->
            node.texts().firstOrNull() == text || node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
        }

    private fun screen(
        password: TextFieldValue = TextFieldValue(""),
        error: String? = null,
        submitting: Boolean = false,
        onDelete: () -> Unit = {},
        onCancel: () -> Unit = {},
    ) = ComposeHarness {
        DeleteAccountContent(
            password = password,
            onPasswordChange = {},
            revealed = false,
            onToggleReveal = {},
            error = error,
            submitting = submitting,
            onDelete = onDelete,
            onCancel = onCancel,
        )
    }

    @Test
    fun theWarningShowsEveryLine() {
        val ui = screen()
        assertTrue(ui.has(DeleteAccountCopy.TITLE))
        DeleteAccountCopy.CONSEQUENCES.forEach { assertTrue(it, ui.has(it)) }
        assertTrue(ui.has(DeleteAccountCopy.CONFIRM))
        assertTrue(ui.has(DeleteAccountCopy.PASSWORD_LABEL))
        assertTrue(ui.has(DeleteAccountCopy.PASSWORD_PLACEHOLDER))
        assertTrue(ui.has(DeleteAccountCopy.DELETE))
        assertTrue(ui.has(DeleteAccountCopy.CANCEL))
        assertTrue(ui.labelled(DeleteAccountCopy.DELETE).isDisabled())
        assertFalse(ui.labelled(DeleteAccountCopy.CANCEL).isDisabled())
    }

    @Test
    fun aPasswordEnablesDeleteAndTheErrorsSitUnderTheField() {
        var deleted = 0
        var cancelled = 0
        val filled = screen(password = TextFieldValue("secret"), onDelete = { deleted++ }, onCancel = { cancelled++ })
        assertFalse(filled.labelled(DeleteAccountCopy.DELETE).isDisabled())
        filled.labelled(DeleteAccountCopy.DELETE).config[SemanticsActions.OnClick].action!!.invoke()
        filled.labelled(DeleteAccountCopy.CANCEL).config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, deleted)
        assertEquals(1, cancelled)

        val wrong = screen(password = TextFieldValue("secret", TextRange(0, "secret".length)), error = DeleteAccountCopy.WRONG_PASSWORD)
        assertTrue(wrong.has(DeleteAccountCopy.WRONG_PASSWORD))
        val field = wrong.nodes().single { it.config.contains(SemanticsProperties.EditableText) }
        assertEquals(TextRange(0, "secret".length), field.config[SemanticsProperties.TextSelectionRange])
        assertFalse(wrong.labelled(DeleteAccountCopy.DELETE).isDisabled())

        val limited = screen(password = TextFieldValue("secret"), error = DeleteAccountCopy.RATE_LIMITED)
        assertTrue(limited.has(DeleteAccountCopy.RATE_LIMITED))
        assertFalse(limited.has(DeleteAccountCopy.WRONG_PASSWORD))
        assertFalse(limited.labelled(DeleteAccountCopy.DELETE).isDisabled())

        val offline = screen(password = TextFieldValue("secret"), error = DeleteAccountCopy.UNREACHABLE)
        assertTrue(offline.has(DeleteAccountCopy.UNREACHABLE))
        assertFalse(offline.labelled(DeleteAccountCopy.DELETE).isDisabled())
    }

    @Test
    fun deletingBlocksTheFieldTheButtonAndCancel() {
        var deleted = 0
        var cancelled = 0
        val ui = screen(
            password = TextFieldValue("secret"),
            submitting = true,
            onDelete = { deleted++ },
            onCancel = { cancelled++ },
        )
        assertTrue(ui.has(DeleteAccountCopy.DELETING))
        val button = ui.labelled(DeleteAccountCopy.DELETING)
        assertTrue(button.isDisabled())
        button.config[SemanticsActions.OnClick].action!!.invoke()
        val field = ui.nodes().single { it.config.contains(SemanticsProperties.EditableText) }
        assertTrue(field.isDisabled())
        val cancel = ui.labelled(DeleteAccountCopy.CANCEL)
        assertTrue(cancel.isDisabled())
        cancel.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(0, deleted)
        assertEquals(0, cancelled)
    }
}
