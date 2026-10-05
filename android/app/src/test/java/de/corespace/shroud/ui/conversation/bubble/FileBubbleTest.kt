package de.corespace.shroud.ui.conversation.bubble

import android.app.Application
import android.view.ViewGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.media.files.FileTypes
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.wire.MessageReplyReference
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.conversation.bubble.BubbleRenderFixtures.ME
import de.corespace.shroud.ui.conversation.bubble.BubbleRenderFixtures.at
import de.corespace.shroud.ui.conversation.bubble.BubbleRenderFixtures.message
import de.corespace.shroud.ui.conversation.bubble.BubbleRenderFixtures.row
import de.corespace.shroud.ui.conversation.menu.MessageActions
import de.corespace.shroud.ui.conversation.menu.MessageMenuAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

/** The file bubble's rules and what TalkBack gets (docs/file-sharing.md §6, §7). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileBubbleTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hosts = ArrayList<ComposeHarness>()

    @After
    fun tearDown() {
        scope.cancel()
        hosts.forEach { host ->
            host.activity.findViewById<ViewGroup>(android.R.id.content).removeAllViews()
            host.idle()
        }
        BubbleRenderFixtures.flushSnapshotWrites()
    }

    private val pdf = FileTypes.forExtension("pdf")!!

    /** The one bubble node whose label holds [part]. */
    private fun ComposeHarness.labelled(part: String): androidx.compose.ui.semantics.SemanticsNode = nodes().single { node ->
        node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.any { it.contains(part) } == true
    }
    private val transfer = MediaTransfer(MediaTransfer.Phase.Transferring, isUpload = false, fraction = 0.5, totalBytes = 2_400_000)

    @Test
    fun theTileSaysTheState() {
        assertEquals(FileTileState.Unsupported, FileBubbleMath.tileState(null, failed = true, transfer = transfer, needsDownload = true))
        assertEquals(FileTileState.Retry, FileBubbleMath.tileState(pdf, failed = true, transfer = transfer, needsDownload = false))
        assertEquals(FileTileState.Transferring, FileBubbleMath.tileState(pdf, failed = false, transfer = transfer, needsDownload = true))
        assertEquals(FileTileState.Download, FileBubbleMath.tileState(pdf, failed = false, transfer = null, needsDownload = true))
        assertEquals(FileTileState.Ready, FileBubbleMath.tileState(pdf, failed = false, transfer = null, needsDownload = false))
    }

    @Test
    fun theMetaLineIsSizeAndTypeOrTheProgressOrWhatWentWrong() {
        assertEquals("2.4 MB · PDF", FileBubbleMath.metaLine(pdf, failed = false, transfer = null, byteCount = 2_400_000))
        assertEquals("PDF", FileBubbleMath.metaLine(pdf, failed = false, transfer = null, byteCount = null))
        assertEquals("1.2 MB of 2.4 MB", FileBubbleMath.metaLine(pdf, failed = false, transfer = transfer, byteCount = 2_400_000))
        assertEquals("Zero KB of 2.4 MB", FileBubbleMath.metaLine(pdf, false, transfer.copy(fraction = null), 2_400_000))
        assertEquals("2.4 MB · PDF", FileBubbleMath.metaLine(pdf, false, transfer.copy(phase = MediaTransfer.Phase.Finishing), 2_400_000))
        assertEquals("Not sent", FileBubbleMath.metaLine(pdf, failed = true, transfer = null, byteCount = 2_400_000))
        assertEquals("Unsupported file", FileBubbleMath.metaLine(null, failed = false, transfer = null, byteCount = 2_400_000))
    }

    @Test
    fun theBubbleIs240To300Wide() {
        assertEquals(300.dp, FileBubbleMath.width(324.dp))
        assertEquals(260.dp, FileBubbleMath.width(260.dp))
        assertEquals(232.dp, FileBubbleMath.width(232.dp))
    }

    @Test
    fun talkBackHearsTheNameSizeWarningAndState() {
        val label = FileBubbleMath.accessibilityLabel(
            isMine = false, replyAuthor = null, replyText = null, name = "Budget.xlsm", type = FileTypes.forExtension("xlsm"),
            byteCount = 2_400_000, caption = "Q3", failed = false, sendError = null, transfer = null, needsDownload = true,
            chipsSummary = null, time = "12:04", receipt = ReceiptStatus.Sent,
        )
        assertEquals("Them, File, Budget.xlsm, 2.4 MB, may contain macros, Q3, Not downloaded, 12:04", label)
        val apk = FileBubbleMath.accessibilityLabel(
            isMine = true, replyAuthor = "Jane", replyText = "hi", name = "app.apk", type = FileTypes.forExtension("apk"),
            byteCount = 3_000_000, caption = null, failed = false, sendError = null, transfer = null, needsDownload = false,
            chipsSummary = null, time = "12:05", receipt = ReceiptStatus.Read,
        )
        assertEquals("You, Reply to Jane: hi, File, app.apk, 3 MB, installs an app, 12:05, Read", apk)
    }

    @Test
    fun aDownloadableFileOpensOnDoubleTapAndOffersTheRowActions() {
        val file = message("", mine = false, time = at(12, 4), kind = ChatMessageKind.File, mediaObjectId = UUID.randomUUID(), mediaByteCount = 2_400_000)
            .copy(fileName = "Quarterly report 2026.pdf")
        val context = RecordingBubbleContext()
        val services = RenderBubbleServices(RuntimeEnvironment.getApplication(), scope)
        val host = ComposeHarness {
            CompositionLocalProvider(LocalBubbleServices provides services) { MessageBubble(row(file), context) }
        }
        hosts += host
        val node = host.labelled("File, Quarterly report 2026.pdf, 2.4 MB")
        host.activity.runOnUiThread { node.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke() }
        host.idle()
        assertEquals(listOf("tapMedia"), context.log)
        assertTrue(MessageActions.hasFileActions(file))
        assertEquals(
            listOf(MessageMenuAction.Reply, MessageMenuAction.SaveToDownloads, MessageMenuAction.Share, MessageMenuAction.Pin, MessageMenuAction.Forward, MessageMenuAction.Delete),
            MessageActions.menuActions(file, hasLink = false),
        )
    }

    @Test
    fun aFailedSendRetriesAndAnUnsupportedFileHasNoActions() {
        val failed = message("", mine = true, time = at(12, 6), kind = ChatMessageKind.File, receipt = ReceiptStatus.Failed, hasFullMedia = true, sendError = "Waiting for connection…")
            .copy(fileName = "Old.doc", pendingSync = true)
        val odd = message("", mine = false, time = at(12, 7), kind = ChatMessageKind.File, mediaObjectId = UUID.randomUUID()).copy(fileName = "run.sh")
        val context = RecordingBubbleContext(ME)
        val services = RenderBubbleServices(RuntimeEnvironment.getApplication(), scope)
        val host = ComposeHarness {
            CompositionLocalProvider(LocalBubbleServices provides services) {
                androidx.compose.foundation.layout.Column {
                    MessageBubble(row(failed), context)
                    MessageBubble(row(odd), context)
                }
            }
        }
        hosts += host
        val failedNode = host.labelled("File, Old.doc")
        val retry = failedNode.config.getOrNull(SemanticsActions.CustomActions)!!.first { it.label == "Retry" }
        host.activity.runOnUiThread { retry.action() }
        host.idle()
        assertTrue(context.log.contains("retry"))
        val oddNode = host.labelled("File, run.sh")
        assertTrue(oddNode.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)!!.single().contains("Unsupported file"))
        assertEquals(null, oddNode.config.getOrNull(SemanticsActions.OnClick))
        assertFalse(MessageActions.hasFileActions(odd))
        assertFalse(MessageActions.hasFileActions(failed))
    }

    @Test
    fun aQuotedFileReadsItsName() {
        val file = message("", mine = false, kind = ChatMessageKind.File, mediaObjectId = UUID.randomUUID()).copy(fileName = "deck.pptx")
        val quote = ReplyQuoteContent.make(file, "Jane", ME)
        assertEquals("deck.pptx", quote.text)
        assertFalse(quote.isStandIn)
        val reference = MessageReplyReference(UUID.randomUUID(), file.senderUserId, MessageReplyReference.Kind.File, "")
        assertEquals("File", ReplyQuoteContent.make(reference, null, "Jane", ME).text)
    }
}
