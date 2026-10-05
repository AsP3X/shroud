package de.corespace.shroud.ui.conversation.composer

import android.app.Application
import android.net.Uri
import de.corespace.shroud.core.media.files.FileCopy
import de.corespace.shroud.core.media.files.FileIntake
import de.corespace.shroud.core.media.files.FileTypes
import de.corespace.shroud.core.media.files.FileWarning
import de.corespace.shroud.core.media.files.PickedFile
import de.corespace.shroud.core.media.share.FileOpenOutcome
import de.corespace.shroud.core.media.share.SaveOutcome
import de.corespace.shroud.core.media.share.ShareTarget
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.testing.MainDispatcherRule
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.conversation.attach.ChatAttachOption
import de.corespace.shroud.ui.conversation.composer.FakeComposeServices.Companion.PEER
import de.corespace.shroud.ui.conversation.composer.FakeComposeServices.Companion.message
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * The composer's half of file sharing (docs/file-sharing.md §6, §7) against [FakeComposeServices]:
 * the picker, refusals, the file composer, sends, and a file bubble's open / save / share with the
 * §6 warning in front of received files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ComposeControllerFileTest {
    @get:Rule
    val main = MainDispatcherRule()

    private class Env(val services: FakeComposeServices, val host: FakeComposeHost, val controller: ComposeController, val effects: MutableList<ComposeEffect>)

    private fun TestScope.env(configure: FakeComposeServices.() -> Unit = {}): Env {
        val services = FakeComposeServices(backgroundScope).apply(configure)
        val host = FakeComposeHost()
        val controller = ComposeController(PEER, false, services, backgroundScope, host, "jane")
        val effects = ArrayList<ComposeEffect>()
        backgroundScope.launch { controller.effects.collect { effects += it } }
        return Env(services, host, controller, effects)
    }

    private fun picked(name: String, size: Long = 2_400_000): PickedFile =
        PickedFile(name, size, requireNotNull(FileTypes.forName(name))) { null }

    private fun file(name: String, isMine: Boolean = false, hasFullMedia: Boolean = true, id: UUID = UUID.randomUUID()): ChatMessage =
        message(id = id, text = "", kind = ChatMessageKind.File, isMine = isMine, hasFullMedia = hasFullMedia, mediaObjectId = UUID.randomUUID())
            .copy(fileName = name, mediaByteCount = 2_400_000)

    private val target = ShareTarget(Uri.parse("content://de.corespace.shroud.media/abc"), "application/pdf")

    @Test
    fun `the attach sheet's File opens the document picker`() = runTest(main.dispatcher) {
        val env = env()
        env.controller.handleAttach(ChatAttachOption.File)
        advanceUntilIdle()
        assertEquals(listOf<ComposeEffect>(ComposeEffect.OpenFilePicker), env.effects)
        assertTrue(env.host.toasts.isEmpty())
    }

    @Test
    fun `each refused pick says why and the rest open the composer`() = runTest(main.dispatcher) {
        val env = env {
            fileIntake = FileIntake.Result(
                files = listOf(picked("Quarterly report 2026.pdf"), picked("notes.txt", 12)),
                refusals = listOf(FileCopy.unsupported("setup.exe"), FileCopy.TOO_MANY),
            )
        }
        env.controller.loadPickedFiles(listOf(Uri.parse("content://docs/1")))
        assertEquals(
            listOf(
                Toast.failure("Shroud can't send “setup.exe”: this file type isn't supported.", ComposeController.LONG_FAILURE_MS),
                Toast.failure("You can send up to 10 files at once.", ComposeController.LONG_FAILURE_MS),
            ),
            env.host.toasts,
        )
        assertEquals(listOf("Quarterly report 2026.pdf", "notes.txt"), env.controller.fileDraft?.files?.map { it.name })
        assertEquals("Send 2 Files", FileCopy.composerTitle(env.controller.fileDraft!!.files.size))
    }

    @Test
    fun `nothing picked does nothing`() = runTest(main.dispatcher) {
        val env = env()
        env.controller.loadPickedFiles(emptyList())
        assertTrue(env.services.inspectedUris.isEmpty())
        assertNull(env.controller.fileDraft)
    }

    @Test
    fun `removing the last file closes the composer`() = runTest(main.dispatcher) {
        val env = env { fileIntake = FileIntake.Result(listOf(picked("a.pdf"), picked("b.pdf")), emptyList()) }
        env.controller.loadPickedFiles(listOf(Uri.parse("content://docs/1")))
        env.controller.removeComposeFile(0)
        assertEquals(listOf("b.pdf"), env.controller.fileDraft?.files?.map { it.name })
        env.controller.removeComposeFile(0)
        assertNull(env.controller.fileDraft)
    }

    @Test
    fun `send puts the caption and the reply on the first file only`() = runTest(main.dispatcher) {
        val quoted = message(text = "Look")
        val env = env {
            threads.value = mapOf(PEER to listOf(quoted))
            fileIntake = FileIntake.Result(listOf(picked("a.pdf"), picked("b.docx"), picked("c.xlsx")), emptyList())
            fileErrors += listOf(null, "Waiting for connection…", null)
        }
        env.controller.startReply(quoted)
        env.controller.loadPickedFiles(listOf(Uri.parse("content://docs/1")))
        env.controller.sendComposedFiles("For you")
        advanceUntilIdle()
        assertNull(env.controller.fileDraft)
        assertNull(env.controller.replyTarget)
        assertEquals(1, env.host.pins)
        assertEquals(listOf("a.pdf", "b.docx", "c.xlsx"), env.services.files.map { it.file.name })
        assertEquals(listOf("For you", "", ""), env.services.files.map { it.caption })
        assertEquals(quoted.id, env.services.files[0].replyTo?.messageId)
        assertNull(env.services.files[1].replyTo)
        assertEquals(listOf(Toast.failure("Waiting for connection…", ComposeController.LONG_FAILURE_MS)), env.host.toasts)
        assertEquals(listOf(Haptic.Error), env.effects.haptics())
    }

    @Test
    fun `a received macro file asks first, and Cancel does nothing`() = runTest(main.dispatcher) {
        val doc = file("budget.xlsm")
        val env = env { threads.value = mapOf(PEER to listOf(doc)) }
        env.controller.handleMediaTap(doc)
        val prompt = requireNotNull(env.controller.fileWarning)
        assertEquals(FileWarning.Macros, prompt.warning)
        assertEquals("This file may contain macros", prompt.title)
        assertEquals(
            "Macros in Office files can run harmful code. Only continue if you trust jane and expected this file, and don't turn on macros unless you're sure.",
            prompt.text,
        )
        env.controller.dismissFileWarning()
        advanceUntilIdle()
        assertNull(env.controller.fileWarning)
        assertTrue(env.services.fileActions.isEmpty())
    }

    @Test
    fun `Continue downloads a received file first, then opens it`() = runTest(main.dispatcher) {
        val oldDoc = file("old.doc", hasFullMedia = false)
        val env = env {
            threads.value = mapOf(PEER to listOf(oldDoc))
            downloadable += oldDoc.id
            openOutcome = FileOpenOutcome.Ready(target, "doc")
        }
        env.controller.handleMediaTap(oldDoc)
        val prompt = env.controller.fileWarning!!
        env.controller.dismissFileWarning() // the sheet closes before Continue runs
        env.controller.confirmFileWarning(prompt)
        advanceUntilIdle()
        assertEquals(listOf(oldDoc.id), env.services.downloads)
        assertEquals(listOf("open:old.doc"), env.services.fileActions)
        assertEquals(listOf(ComposeEffect.OpenFile(target, "doc")), env.effects.filterIsInstance<ComposeEffect.OpenFile>())
        // Nothing is remembered: the next tap asks again.
        env.controller.handleMediaTap(env.services.threads.value[PEER]!!.single())
        assertNotNull(env.controller.fileWarning)
    }

    @Test
    fun `our own files never ask, and a mismatch is the core's sentence`() = runTest(main.dispatcher) {
        val mine = file("deck.pptm", isMine = true)
        val env = env {
            threads.value = mapOf(PEER to listOf(mine))
            openOutcome = FileOpenOutcome.Refused(FileCopy.mismatch("pptm"))
        }
        env.controller.handleMediaTap(mine)
        advanceUntilIdle()
        assertNull(env.controller.fileWarning)
        assertEquals(listOf("open:deck.pptm"), env.services.fileActions)
        assertEquals(listOf(Toast.failure("This file doesn't match its .pptm type, so Shroud won't open it.")), env.host.toasts)
    }

    @Test
    fun `a received APK downloads without asking, then shows its menu, never an Open`() = runTest(main.dispatcher) {
        val apk = file("app.apk", hasFullMedia = false)
        val env = env {
            threads.value = mapOf(PEER to listOf(apk))
            downloadable += apk.id
        }
        env.controller.handleMediaTap(apk)
        // Filling the sealed cache hands nothing to another app: no §6 question for it.
        assertNull(env.controller.fileWarning)
        advanceUntilIdle()
        assertEquals(listOf(apk.id), env.services.downloads)
        assertEquals(listOf(apk.id), env.host.menus)
        assertTrue(env.services.fileActions.isEmpty())
        // On this phone now: a tap shows the menu again, still no Open and no question.
        env.controller.handleMediaTap(env.services.threads.value[PEER]!!.single())
        advanceUntilIdle()
        assertNull(env.controller.fileWarning)
        assertEquals(listOf(apk.id, apk.id), env.host.menus)
        assertTrue(env.services.fileActions.isEmpty())
        // Save and Share from that menu ask first, each time.
        env.controller.requestFileAction(env.services.threads.value[PEER]!!.single(), FileAction.Save)
        assertEquals(FileWarning.App, env.controller.fileWarning?.warning)
        assertEquals("This file can install an app", env.controller.fileWarning?.title)
    }

    @Test
    fun `a tap during the hold that opened a menu does nothing`() = runTest(main.dispatcher) {
        val apk = file("app.apk")
        val env = env { threads.value = mapOf(PEER to listOf(apk)) }
        env.host.isShowingMessageMenu = true
        env.controller.handleMediaTap(apk)
        advanceUntilIdle()
        assertTrue(env.host.menus.isEmpty())
    }

    @Test
    fun `save to downloads and share report back`() = runTest(main.dispatcher) {
        val pdf = file("report.pdf", isMine = true)
        val env = env {
            threads.value = mapOf(PEER to listOf(pdf))
            shareTarget = target
        }
        env.controller.requestFileAction(pdf, FileAction.Save)
        env.controller.requestFileAction(pdf, FileAction.Share)
        advanceUntilIdle()
        assertEquals(listOf("save:report.pdf", "share:report.pdf"), env.services.fileActions)
        assertEquals(listOf(Toast.success("Saved to Downloads")), env.host.toasts)
        assertEquals(listOf(ComposeEffect.ShareFile(target)), env.effects.filterIsInstance<ComposeEffect.ShareFile>())

        env.services.saveOutcome = SaveOutcome.Failed(FileCopy.COULD_NOT_SAVE)
        env.services.shareTarget = null
        env.controller.requestFileAction(pdf, FileAction.Save)
        env.controller.requestFileAction(pdf, FileAction.Share)
        advanceUntilIdle()
        assertEquals(
            listOf(Toast.success("Saved to Downloads"), Toast.failure("Could not save that file."), Toast.failure("Could not share that file.")),
            env.host.toasts,
        )
    }

    @Test
    fun `no app for the type says so`() = runTest(main.dispatcher) {
        val env = env()
        env.controller.onNoAppForFile("xlsb")
        assertEquals(listOf(Toast.failure("No app on this phone can open .xlsb files.")), env.host.toasts)
    }

    @Test
    fun `an unsupported or deleted file does nothing`() = runTest(main.dispatcher) {
        val odd = file("script.sh", hasFullMedia = false)
        val gone = file("a.pdf").copy(deleted = true)
        val env = env { threads.value = mapOf(PEER to listOf(odd, gone)) }
        env.controller.handleMediaTap(odd)
        env.controller.handleMediaTap(gone)
        env.controller.requestFileAction(odd, FileAction.Save)
        advanceUntilIdle()
        assertTrue(env.services.downloads.isEmpty())
        assertTrue(env.services.fileActions.isEmpty())
        assertNull(env.controller.fileWarning)
    }

    @Test
    fun `a download that ends empty-handed says so, a stopped one does not`() = runTest(main.dispatcher) {
        val pdf = file("report.pdf", isMine = true, hasFullMedia = false)
        val env = env { threads.value = mapOf(PEER to listOf(pdf)) }
        env.controller.handleMediaTap(pdf)
        advanceUntilIdle()
        assertEquals(listOf(Toast.failure("Could not download that file.")), env.host.toasts)
    }

    @Test
    fun `the lock drops the staged files and the warning`() = runTest(main.dispatcher) {
        val doc = file("a.doc")
        val env = env {
            threads.value = mapOf(PEER to listOf(doc))
            fileIntake = FileIntake.Result(listOf(picked("a.pdf")), emptyList())
        }
        env.controller.loadPickedFiles(listOf(Uri.parse("content://docs/1")))
        env.controller.handleMediaTap(doc)
        assertNotNull(env.controller.fileDraft)
        assertNotNull(env.controller.fileWarning)
        env.controller.onLock()
        assertNull(env.controller.fileDraft)
        assertNull(env.controller.fileWarning)
    }
}
