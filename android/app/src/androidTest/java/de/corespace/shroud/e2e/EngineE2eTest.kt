package de.corespace.shroud.e2e

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.corespace.shroud.AppContainer
import de.corespace.shroud.LOCAL_NETWORK_PERMISSION
import de.corespace.shroud.ShroudApplication
import de.corespace.shroud.core.auth.WipePhase
import de.corespace.shroud.core.auth.WipeReason
import de.corespace.shroud.core.calls.CallController
import de.corespace.shroud.core.calls.CallPhase
import de.corespace.shroud.core.keys.DeviceLock
import de.corespace.shroud.core.links.LinkPreviewAttachment
import de.corespace.shroud.core.media.MediaComposeQuality
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.video.VideoSendPlan
import de.corespace.shroud.core.media.share.FileOpenOutcome
import de.corespace.shroud.core.media.video.VideoUploadQuality
import de.corespace.shroud.core.messaging.MessagingController
import de.corespace.shroud.core.model.AddContactOutcome
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.model.ChatDeleteOutcome
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.model.MuteDuration
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.model.replyReference
import de.corespace.shroud.core.net.CallModality
import de.corespace.shroud.core.net.ConversationDeleteScope
import de.corespace.shroud.core.net.MessageDeleteScope
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.wire.LinkPreview
import de.corespace.shroud.core.net.wire.MessageReplyReference
import de.corespace.shroud.core.voice.AacM4aWriter
import de.corespace.shroud.core.voice.VoiceFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/**
 * The wave 2 exit gate (00-plan §0, §2.3 W2-INT, §6.3) and the engine acceptance of every W2 card:
 * this phone's engines — the real `AppContainer` with every W2 package wired — against scripted web
 * peers (`android/e2e/peer`, the web client's own crypto, API and call modules) through the local
 * server. Each peer is one web account in its own process; one of them logs in as a second device of
 * this phone's account.
 *
 * - [theEnginesExchangeEveryKindWithTheWebPeer] (W2-MSG-CORE, W2-MSG-SEND): text, a reply, link
 *   previews small and large, photos both ways (passthrough and HD), a video, a voice note, receipts,
 *   typing both ways, reactions both ways, deletes for everyone both ways; then the Log Out wipe.
 * - [aSecondDeviceSharesReadsMutesNotesAndDeletesConflictsAReactionAndRevokesThisPhone] (W2-MSG-CORE,
 *   W2-MSG-SEND, W2-AUTH-WIPE): the other device opens this phone's sealed name as "Android app",
 *   unread/read sync both ways, mutes, Notes, delete for me, a reaction 409 from the other device,
 *   and its revoke wipes this phone.
 * - [filesTravelBothWaysWithTheWebPeer] (docs/file-sharing.md): SHRF1 files both ways through the
 *   web's own file modules — names, sizes, MIME, warnings, the content check — and file quotes.
 * - [chatDeletesFollowThePeersConsent] (W2-MSG-CORE): a chat deleted for both, without and with the
 *   peer's consent.
 * - [contactsComeByShareCodeLinkAndNameWithPresenceBlocksAndKeyChanges] (W2-CONTACTS).
 * - [callsRingConnectAndHangUpBothWays] (W2-CALLS-CORE): call signalling with a fake media engine.
 *
 * Run by `android/e2e/engine-e2e.sh` (stack, peers, instrumentation). Skipped when the API or a peer
 * cannot be reached, or the device has no screen lock (the vault needs one: `emulator-setup.sh`).
 * Instrumentation arguments: `shroudApi` (default `http://10.0.2.2:8080/api/v1`), `shroudPeer` (the
 * first peer, default `http://10.0.2.2:8099`; the others on the following ports), `shroudPeerCount`
 * (default 4), `shroudRequired` (`true`: fail instead of skip). Each test signs up its own account and leaves nothing of it on the phone.
 */
@RunWith(AndroidJUnit4::class)
class EngineE2eTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as ShroudApplication
    private val container: AppContainer get() = app.container
    private val arguments = InstrumentationRegistry.getArguments()
    private val baseUrl: String = arguments.getString("shroudApi") ?: "http://10.0.2.2:8080/api/v1"
    private val peerUrls: List<String> = run {
        // The first peer's URL and how many there are: the rest listen on the following ports.
        val first = (arguments.getString("shroudPeer") ?: "http://10.0.2.2:8099").toHttpUrl()
        val count = arguments.getString("shroudPeerCount")?.toIntOrNull() ?: 4
        (0 until count).map { first.newBuilder().port(first.port + it).build().toString().trimEnd('/') }
    }
    private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private var previousServer: ServerConfiguration? = null
    private var signedUp = false

    /** This phone's test account: its password and phrase go to the peer that plays its other device. */
    private var myName = ""
    private var myPassword = ""
    private var myWords: List<String> = emptyList()

    private val messaging: MessagingController get() = container.messaging.controller
    private val me: UUID get() = UUID.fromString(container.auth.sessionController.session.value!!.userId)

    /** `engine-e2e.sh` passes `shroudRequired=true`: there a missing stack fails instead of skipping. */
    private val required = arguments.getString("shroudRequired") == "true"

    private fun require(message: String, condition: Boolean) = if (required) assertTrue(message, condition) else assumeTrue(message, condition)

    /** A web peer: index into [peerUrls]; [id] and [name] once its account exists. */
    private inner class Peer(val index: Int) {
        lateinit var id: UUID
        lateinit var name: String
        var shareCode = ""

        fun call(method: String, path: String, body: JsonObject? = null): JsonObject = peerCall(peerUrls[index], method, path, body)

        fun create(): Peer = apply {
            val account = call("POST", "/account")
            id = UUID.fromString(account.str("userId"))
            name = account.str("username")
            shareCode = account.str("shareCode")
        }

        fun messages(with: UUID = me): List<JsonObject> = call("GET", "/messages?peer=${Ids.wire(with)}").arr("messages").map { it.jsonObject }

        fun sees(what: String, with: UUID = me, timeoutMs: Long = 30_000, match: (JsonObject) -> Boolean): JsonObject =
            eventuallyBlocking(what, timeoutMs) { messages(with).firstOrNull(match) }

        fun sendText(text: String, to: UUID = me): UUID =
            UUID.fromString(call("POST", "/text", buildJsonObject { put("peer", Ids.wire(to)); put("text", text) }).str("id"))

        fun socket(on: Boolean) {
            call("POST", "/socket", buildJsonObject { put("on", on) })
        }
    }

    private val usedPeers = ArrayList<Peer>()

    private fun peer(index: Int): Peer = Peer(index).also { usedPeers += it }

    @Before
    fun setUp() {
        // Android 17: the server and the peers are on the host's loopback (10.0.2.2), a local-network
        // address. The test run reinstalls the app, which drops emulator-setup.sh's grant.
        if (Build.VERSION.SDK_INT >= 37) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, LOCAL_NETWORK_PERMISSION)
        require("the local stack is not reachable at $baseUrl (android/e2e/stack-up.sh)", reachable("$baseUrl/health/live"))
        for (url in peerUrls) require("the web peer is not reachable at $url (android/e2e/engine-e2e.sh)", reachable("$url/health"))
        DeviceLock.ensureUnlocked()
        require("needs a screen lock: android/e2e/emulator-setup.sh", DeviceLock.isSecure)
        val server = container.serverConfiguration
        val url = baseUrl.toHttpUrl()
        if (server.configuration.value.resolvedBaseUrl != baseUrl) {
            previousServer = server.configuration.value
            server.save(ServerConfiguration.localDevelopment(url.host, url.port))
        }
    }

    @After
    fun tearDown() {
        for (peer in usedPeers) runCatching { peer.call("POST", "/logout") }
        if (signedUp && container.auth.sessionController.session.value != null) {
            // A failed run still leaves nothing of its account on the phone.
            runCatching { logOutThroughTheWipe() }
        }
        previousServer?.let { container.serverConfiguration.save(it) }
    }

    // ---- 1. Every kind, both ways ------------------------------------------------------------------

    @Test
    fun theEnginesExchangeEveryKindWithTheWebPeer() {
        val peer = peer(0).create()
        signUp()
        startAndBefriend(peer)
        peer.socket(on = true)
        val peerId = peer.id

        // ---- Text, both ways, with a reply and link previews -----------------------------------
        onMain { messaging.sendText("Hello from Android", peerId) }
        val hello = mine("Hello from Android", peerId, peer)
        peer.sees("the text") { it.str("id") == Ids.wire(hello.id) && it.optStr("text") == "Hello from Android" }

        // Receipts (W2-MSG-CORE): delivered, then read, reach the sender's bubble.
        peer.call("POST", "/delivered", buildJsonObject { put("messageId", Ids.wire(hello.id)) })
        eventually("the delivery receipt") { bubble(peerId, hello.id)?.takeIf { it.receipt.rank >= ReceiptStatus.Delivered.rank } }
        peer.call("POST", "/read", buildJsonObject { put("peer", Ids.wire(me)) })
        eventually("the read receipt") { bubble(peerId, hello.id)?.takeIf { it.receipt == ReceiptStatus.Read } }

        // Typing both ways, seen by the peer's socket and by this phone's engine.
        val since = peer.call("GET", "/events?since=0").int("last")
        onMain { messaging.setTyping(peerId, true) }
        eventuallyBlocking("the peer sees us typing", 15_000) {
            peer.call("GET", "/events?since=$since&type=typing").arr("events").firstOrNull { e ->
                e.jsonObject.obj("raw")?.optStr("is_typing") == "true"
            }
        }
        onMain { messaging.setTyping(peerId, false) }
        peer.call("POST", "/typing", buildJsonObject { put("peer", Ids.wire(me)); put("typing", true) })
        eventually("we see the peer typing") { messaging.peerActivity(peerId)?.takeIf { it == ChatPeerActivity.Typing } }
        peer.call("POST", "/typing", buildJsonObject { put("peer", Ids.wire(me)); put("typing", false) })
        eventually("the peer stopped typing") { if (messaging.peerActivity(peerId) == null) Unit else null }

        val peerReply = UUID.fromString(
            peer.call(
                "POST",
                "/text",
                buildJsonObject {
                    put("peer", Ids.wire(me))
                    put("text", "Hi from the web")
                    put("replyTo", buildJsonObject {
                        put("id", Ids.wire(hello.id))
                        put("senderUserId", Ids.wire(me))
                        put("kind", "text")
                        put("snippet", "Hello from Android")
                    })
                    put("linkPreview", buildJsonObject {
                        put("url", "https://example.com/web")
                        put("title", "From the web")
                    })
                },
            ).str("id"),
        )
        val received = incoming(peerId, "the peer's reply") { it.id == peerReply }
        assertEquals("Hi from the web", received.text)
        assertEquals(hello.id, received.replyTo?.messageId)
        assertEquals("https://example.com/web", received.linkPreview?.url)
        assertEquals("From the web", received.linkPreview?.title)

        val preview = LinkPreviewAttachment(LinkPreview("https://example.org/android", title = "From Android"), null, null, null)
        val replyRef = MessageReplyReference(peerReply, peerId, MessageReplyReference.Kind.Text, "Hi from the web")
        onMain { messaging.sendText("Answer with a link https://example.org/android", peerId, replyTo = replyRef, linkPreview = preview) }
        val answer = mine("Answer with a link https://example.org/android", peerId, peer)
        peer.sees("the reply with its link preview") {
            it.str("id") == Ids.wire(answer.id) &&
                it.obj("replyTo")?.optStr("id") == Ids.wire(peerReply) &&
                it.obj("linkPreview")?.optStr("title") == "From Android"
        }

        // A large link image goes as a link media message (W2-MSG-SEND).
        val linkImage = jpeg(1200, 630)
        val large = LinkPreviewAttachment(LinkPreview("https://example.org/large", title = "Large picture"), Bytes.of(linkImage), 1200, 630)
        onMain { messaging.sendText("A large preview https://example.org/large", peerId, linkPreview = large) }
        peer.sees("the large link image") {
            val media = it.obj("media")
            media?.optStr("t") == "link" && (media["bytes"]?.jsonPrimitive?.content?.toInt() ?: 0) == linkImage.size
        }

        // ---- Photos, both ways: passthrough and HD ----------------------------------------------
        val photo = jpeg(320, 240)
        assertNull(onMain { messaging.sendImage(MediaImageSource.FileBytes(photo), peerId, caption = "A photo") })
        val sentPhoto = eventually("the photo is sent") { sentMedia(peerId, ChatMessageKind.Image, "A photo") }
        peer.sees("the photo") {
            val media = it.obj("media")
            it.str("id") == Ids.wire(sentPhoto.id) && media?.optStr("t") == "image" && media.optStr("caption") == "A photo" &&
                (media["bytes"]?.jsonPrimitive?.content?.toInt() ?: 0) > 0
        }

        val big = jpeg(3200, 2400)
        assertNull(onMain { messaging.sendImage(MediaImageSource.FileBytes(big), peerId, caption = "In HD", quality = MediaComposeQuality.HD) })
        val sentHd = eventually("the HD photo is sent") { sentMedia(peerId, ChatMessageKind.Image, "In HD") }
        val hd = peer.sees("the HD photo") { it.str("id") == Ids.wire(sentHd.id) && it.obj("media")?.optStr("t") == "image" }.obj("media")!!
        // HD re-encodes to at most 2560 px on the long edge.
        assertEquals(2560, hd.int("w"))
        assertEquals(1920, hd.int("h"))
        assertTrue(hd.int("bytes") < big.size)

        val peerPhoto = peer.call("POST", "/photo", buildJsonObject { put("peer", Ids.wire(me)); put("caption", "From the web") })
        val peerPhotoId = UUID.fromString(peerPhoto.str("id"))
        val incomingPhoto = incoming(peerId, "the peer's photo") { it.id == peerPhotoId && it.kind == ChatMessageKind.Image }
        assertEquals("From the web", incomingPhoto.text)
        onMain { messaging.ensureImageLoaded(incomingPhoto) }
        val photoBytes = eventually("the peer's photo downloads") { messaging.mediaBytes(peerPhotoId) }
        assertEquals(peerPhoto.str("sha256"), sha256(photoBytes))

        // ---- Video and voice -------------------------------------------------------------------
        val clip = asset("media/sealed-playback.mp4")
        val videoError = onMain {
            messaging.sendVideo(VideoSendPlan(Uri.fromFile(clip), caption = "A clip", quality = VideoUploadQuality.Original), peerId)
        }
        assertNull(videoError)
        peer.sees("the video", timeoutMs = 120_000) {
            val media = it.obj("media")
            media?.optStr("t") == "video" && media.optStr("mime") == "video/mp4" && (media["bytes"]?.jsonPrimitive?.content?.toInt() ?: 0) > 0
        }

        val voice = voiceNote()
        assertNull(onMain { messaging.sendVoice(voice, 1_000, peerId) })
        peer.sees("the voice note") {
            val media = it.obj("media")
            media?.optStr("t") == "voice" && media.optStr("mime") == "audio/mp4" && (media["bytes"]?.jsonPrimitive?.content?.toInt() ?: 0) == voice.size
        }

        // ---- Reactions, both ways --------------------------------------------------------------
        onMain { messaging.toggleReaction("👍", peerReply, peerId) }
        peer.sees("our reaction") { message ->
            message.str("id") == Ids.wire(peerReply) && emojisOf(message, me) == listOf("👍")
        }
        peer.call("POST", "/react", buildJsonObject {
            put("peer", Ids.wire(me))
            put("messageId", Ids.wire(hello.id))
            put("emojis", buildJsonArray { add(JsonPrimitive("❤️")) })
        })
        eventually("the peer's reaction arrives") {
            bubble(peerId, hello.id)?.reactions?.firstOrNull { it.userId == peerId && it.emojis == listOf("❤️") }
        }

        // ---- Deletes for everyone, both ways ---------------------------------------------------
        assertNull(onMain { messaging.deleteMessage(bubble(peerId, hello.id)!!, MessageDeleteScope.Everyone) })
        peer.sees("our delete") { it.str("id") == Ids.wire(hello.id) && it.bool("deleted") }
        peer.call("POST", "/delete", buildJsonObject { put("messageId", Ids.wire(peerPhotoId)) })
        eventually("the peer's delete arrives") { messaging.threads.value[peerId]?.firstOrNull { it.id == peerPhotoId && it.deleted } }
        // The tombstone purged the photo from the sealed cache.
        eventually("the deleted photo's media is gone") { if (container.media.localMedia.has(peerPhotoId)) null else Unit }

        // ---- Log Out: the wipe leaves nothing --------------------------------------------------
        logOutThroughTheWipe()
    }

    // ---- 1b. Files, both ways (docs/file-sharing.md) -----------------------------------------------

    /**
     * Shared files against the web client's own file modules (`crypto/fileBlob.ts`, `files.ts`,
     * `messaging.ts` `sendFile` / `messageFromMediaPayload`): what Android seals as SHRF1 the web
     * opens byte for byte with the cleaned name, `s`, the canonical MIME and the warning; what the
     * web sends — a hostile name, a multi-segment file, a ".pdf" that is not one — Android
     * downloads into the SHRM1 cache, names cleanly and checks before opening; file quotes travel
     * as `k: "file"` with the name both ways.
     */
    @Test
    fun filesTravelBothWaysWithTheWebPeer() {
        val peer = peer(0).create()
        signUp()
        startAndBefriend(peer)
        peer.socket(on = true)
        val peerId = peer.id

        // ---- Android → web: a PDF with a caption and a macro workbook, through the real intake ----
        val pdfBytes = "%PDF-1.7\n".toByteArray() + ByteArray(150_000) { (it % 251).toByte() }
        val xlsmBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(70_000) { (it * 7 % 256).toByte() }
        val picks = listOf(cacheFile("Quarterly report 2026.pdf", pdfBytes), cacheFile("Budget Q3.xlsm", xlsmBytes))
        val intake = runBlocking { container.media.fileIntake.inspect(picks.map { Uri.fromFile(it) }) }
        assertEquals(emptyList<String>(), intake.refusals)
        assertEquals(listOf("Quarterly report 2026.pdf", "Budget Q3.xlsm"), intake.files.map { it.name })
        assertNull(onMain { messaging.sendFile(intake.files[0], peerId, caption = "Q3 numbers") })
        assertNull(onMain { messaging.sendFile(intake.files[1], peerId) })
        picks.forEach { it.delete() }
        val sentPdf = eventually("the PDF is sent") { sentFile(peerId, "Quarterly report 2026.pdf") }
        val sentXlsm = eventually("the workbook is sent") { sentFile(peerId, "Budget Q3.xlsm") }
        assertEquals("Q3 numbers", sentPdf.text)

        val webPdf = peer.sees("the PDF on the web", timeoutMs = 60_000) { it.str("id") == Ids.wire(sentPdf.id) && it.obj("file")?.get("sha256") != null }
        val pdfFile = webPdf.obj("file")!!
        assertEquals("file", webPdf.obj("media")!!.str("t"))
        assertEquals("application/pdf", webPdf.obj("media")!!.str("mime"))
        assertEquals(pdfBytes.size, webPdf.obj("media")!!.int("size"))
        assertEquals("file", pdfFile.str("kind"))
        assertEquals("Quarterly report 2026.pdf", pdfFile.str("rawName"))
        assertEquals("Quarterly report 2026.pdf", pdfFile.str("fileName"))
        assertEquals("application/pdf", pdfFile.str("tableMime"))
        assertEquals("Q3 numbers", pdfFile.str("caption"))
        assertNull(pdfFile.optStr("warning"))
        assertEquals(sha256(pdfBytes), pdfFile.str("sha256"))
        assertEquals(16 + pdfBytes.size + 16 * 3, pdfFile.int("sealedBytes"))
        assertTrue(pdfFile.bool("contentMatches"))

        val webXlsm = peer.sees("the workbook on the web", timeoutMs = 60_000) { it.str("id") == Ids.wire(sentXlsm.id) && it.obj("file")?.get("sha256") != null }
        val xlsmFile = webXlsm.obj("file")!!
        assertEquals("application/vnd.ms-excel.sheet.macroEnabled.12", webXlsm.obj("media")!!.str("mime"))
        assertEquals("Budget Q3.xlsm", xlsmFile.str("fileName"))
        assertEquals("macros", xlsmFile.str("warning"))
        assertNull(xlsmFile.optStr("caption"))
        assertEquals(sha256(xlsmBytes), xlsmFile.str("sha256"))
        assertTrue(xlsmFile.bool("contentMatches"))

        // ---- Web → Android: a hostile name, a multi-segment file, a ".pdf" that is not a PDF ------
        val hostile = peer.call("POST", "/file", buildJsonObject {
            put("peer", Ids.wire(me)); put("name", "../evil\u202Etxt.exe.txt"); put("raw", true); put("text", "hello from the web\n"); put("caption", "read me")
        })
        val csv = peer.call("POST", "/file", buildJsonObject { put("peer", Ids.wire(me)); put("name", "Big notes.csv"); put("text", "a,b,c\n".repeat(40_000)) })
        val fakePdf = peer.call("POST", "/file", buildJsonObject { put("peer", Ids.wire(me)); put("name", "invoice.pdf"); put("text", "this is not a pdf at all") })
        for (sent in listOf(hostile, csv, fakePdf)) {
            val id = UUID.fromString(sent.str("id"))
            val received = incoming(peerId, "the web's ${sent.str("name")}") { it.id == id && it.kind == ChatMessageKind.File }
            assertEquals(sent.int("size").toLong(), received.mediaByteCount)
            onMain { messaging.ensureFileLoaded(received) }
            val held = eventually("${received.fileName} downloads", timeoutMs = 60_000) { bubble(peerId, id)?.takeIf { it.hasFullMedia } }
            val cached = runBlocking { container.media.localMedia.readAll(id) }!!
            assertEquals(sent.str("sha256"), sha256(cached))
            val outcome = runBlocking { container.media.fileSharing.openTarget(id, held.fileName!!) }
            when (id) {
                UUID.fromString(hostile.str("id")) -> {
                    // The receiver's own cleaning: no path, no right-to-left override.
                    assertEquals("eviltxt.exe.txt", held.fileName)
                    assertEquals("read me", held.text)
                    assertTrue("$outcome", outcome is FileOpenOutcome.Ready)
                    assertEquals("text/plain", (outcome as FileOpenOutcome.Ready).target.mime)
                }
                UUID.fromString(csv.str("id")) -> {
                    assertEquals("Big notes.csv", held.fileName)
                    assertEquals(240_000L, held.mediaByteCount)
                    assertTrue("$outcome", outcome is FileOpenOutcome.Ready)
                }
                else -> {
                    assertEquals("invoice.pdf", held.fileName)
                    assertEquals(FileOpenOutcome.Refused("This file doesn't match its .pdf type, so Shroud won't open it."), outcome)
                    // Sharing stays possible (§4).
                    assertNotNull(runBlocking { container.media.fileSharing.fileShareTarget(id, held.fileName!!) })
                }
            }
        }
        container.media.sharing.revokeAll()

        // ---- File quotes, both ways: `k: "file"`, `x` = the name ----------------------------------
        val csvId = UUID.fromString(csv.str("id"))
        val quoteOfCsv = bubble(peerId, csvId)!!.replyReference!!
        assertEquals(MessageReplyReference.Kind.File, quoteOfCsv.kind)
        assertEquals("Big notes.csv", quoteOfCsv.snippet)
        onMain { messaging.sendText("Got the CSV", peerId, replyTo = quoteOfCsv) }
        val answer = mine("Got the CSV", peerId, peer)
        peer.sees("our quote of the web's file") {
            it.str("id") == Ids.wire(answer.id) && it.obj("replyTo")?.optStr("kind") == "file" &&
                it.obj("replyTo")?.optStr("snippet") == "Big notes.csv" && it.obj("replyTo")?.optStr("id") == Ids.wire(csvId)
        }
        val webQuote = UUID.fromString(
            peer.call("POST", "/text", buildJsonObject {
                put("peer", Ids.wire(me))
                put("text", "Which sheet?")
                put("replyTo", buildJsonObject {
                    put("id", Ids.wire(sentXlsm.id)); put("senderUserId", Ids.wire(me)); put("kind", "file"); put("snippet", "Budget Q3.xlsm")
                })
            }).str("id"),
        )
        val quoted = incoming(peerId, "the web's quote of our file") { it.id == webQuote }
        assertEquals(MessageReplyReference.Kind.File, quoted.replyTo?.kind)
        assertEquals("Budget Q3.xlsm", quoted.replyTo?.snippet)
        assertEquals(sentXlsm.id, quoted.replyTo?.messageId)
        // A file that itself replies: the web sends one quoting our PDF; it arrives with the quote.
        val fileReply = peer.call("POST", "/file", buildJsonObject {
            put("peer", Ids.wire(me)); put("name", "answers.txt"); put("text", "see page 2")
            put("replyTo", buildJsonObject {
                put("id", Ids.wire(sentPdf.id)); put("senderUserId", Ids.wire(me)); put("kind", "file"); put("snippet", "Quarterly report 2026.pdf")
            })
        })
        val quotingFile = incoming(peerId, "the web's file that quotes our PDF") { it.id == UUID.fromString(fileReply.str("id")) }
        assertEquals(ChatMessageKind.File, quotingFile.kind)
        assertEquals(MessageReplyReference.Kind.File, quotingFile.replyTo?.kind)
        assertEquals(sentPdf.id, quotingFile.replyTo?.messageId)

        logOutThroughTheWipe()
    }

    // ---- 2. Our other device ---------------------------------------------------------------------

    @Test
    fun aSecondDeviceSharesReadsMutesNotesAndDeletesConflictsAReactionAndRevokesThisPhone() {
        val peer = peer(0).create()
        signUp()
        startAndBefriend(peer)
        val peerId = peer.id
        // The unlock syncs the sealed device name (InterimSession.unlocked); the test has no root.
        onMain { container.auth.syncDeviceName() }
        val other = peer(3)
        other.call("POST", "/login", buildJsonObject {
            put("username", myName)
            put("password", myPassword)
            put("words", buildJsonArray { myWords.forEach { add(JsonPrimitive(it)) } })
        })
        val myDevice = container.auth.sessionController.session.value!!.deviceId.lowercase()

        // The account's other device opens this phone's sealed name as kind 4: the web's "Android app".
        val listed = eventuallyBlocking("the other device reads our device name", 20_000) {
            other.call("GET", "/devices").arr("devices").map { it.jsonObject }.firstOrNull { it.str("id") == myDevice && it.optStr("kind") != null }
        }
        assertEquals("android", listed.optStr("kind"))
        assertFalse(listed.optStr("name").isNullOrBlank())

        // ---- Unread / read sync -------------------------------------------------------------------
        // The chat exists first: opening it before it had a message left a read marker to send with
        // the next list (ReadStateEngine.chatReadRetries), which would mark the first arrival read.
        onMain { messaging.sendText("Hello there", peerId) }
        mine("Hello there", peerId, peer)
        onMain { messaging.refreshConversations(force = true) }
        Thread.sleep(1_000)
        onMain { messaging.setActivePeer(null) }
        val first = peer.sendText("Unread one")
        val second = peer.sendText("Unread two")
        incoming(peerId, "both unread texts") { it.id == second }
        eventually("two unread") { if (messaging.unreadCount(peerId) == 2) Unit else null }
        // Read on the other device: the count clears here too, and the peer gets read receipts.
        other.call("POST", "/read", buildJsonObject { put("peer", Ids.wire(peerId)) })
        eventually("the other device's read reaches this phone") { if (messaging.unreadCount(peerId) == 0) Unit else null }
        peer.sees("read receipts for both") { it.str("id") == Ids.wire(first) && it.bool("read") }
        // Read here: the other device's list shows the chat read.
        val third = peer.sendText("Unread three")
        incoming(peerId, "the third text") { it.id == third }
        eventually("one unread") { if (messaging.unreadCount(peerId) == 1) Unit else null }
        onMain { messaging.setActivePeer(peerId) }
        eventuallyBlocking("the other device sees the chat read", 20_000) {
            other.call("GET", "/conversations").arr("conversations").map { it.jsonObject }.firstOrNull { it.str("peer") == Ids.wire(peerId) && it.int("unread") == 0 }
        }
        peer.sees("the read receipt") { it.str("id") == Ids.wire(third) && it.bool("read") }

        // ---- Mutes ----------------------------------------------------------------------------------
        assertNull(onMain { messaging.muteChat(peerId, MuteDuration.Hour) })
        assertTrue(onMain { messaging.isMuted(peerId) })
        eventuallyBlocking("the other device sees the mute", 20_000) {
            other.call("GET", "/conversations").arr("conversations").map { it.jsonObject }.firstOrNull { it.str("peer") == Ids.wire(peerId) && it.bool("muted") }
        }
        assertNull(onMain { messaging.unmuteChat(peerId) })
        assertFalse(onMain { messaging.isMuted(peerId) })
        eventuallyBlocking("the other device sees the unmute", 20_000) {
            other.call("GET", "/conversations").arr("conversations").map { it.jsonObject }.firstOrNull { it.str("peer") == Ids.wire(peerId) && !it.bool("muted") }
        }

        // ---- Notes: synced to Saved Messages, re-keyed, readable on the other device ----------------
        onMain { messaging.sendText("Note from Android", NOTES_PEER_ID) }
        val note = eventuallyBlocking("the note is synced", 30_000) {
            onMain { messaging.threads.value[NOTES_PEER_ID]?.lastOrNull { it.text == "Note from Android" && !it.pendingSync } }
                ?.takeIf { n -> other.messages(me).any { it.str("id") == Ids.wire(n.id) } }
        }
        val synced = other.messages(me).first { it.str("id") == Ids.wire(note.id) }
        assertEquals("Note from Android", synced.optStr("text"))

        // ---- Delete for me: gone here and on the other device, the peer keeps it ------------------
        onMain { messaging.sendText("Forget this for me", peerId) }
        val forget = mine("Forget this for me", peerId, peer)
        assertNull(onMain { messaging.deleteMessage(bubble(peerId, forget.id)!!, MessageDeleteScope.Me) })
        eventually("gone here") { if (bubble(peerId, forget.id) == null) Unit else null }
        eventuallyBlocking("gone on the other device", 20_000) {
            if (other.messages(peerId).none { it.str("id") == Ids.wire(forget.id) }) Unit else null
        }
        assertFalse(peer.messages().first { it.str("id") == Ids.wire(forget.id) }.bool("deleted"))

        // ---- A reaction 409: our other device wrote first ------------------------------------------
        val target = peer.sendText("React to me")
        incoming(peerId, "the message to react to") { it.id == target }
        onMain { messaging.toggleReaction("👍", target, peerId) }
        peer.sees("our first reaction") { it.str("id") == Ids.wire(target) && emojisOf(it, me) == listOf("👍") }
        // Off the socket, this phone cannot hear the other device's write: its base goes stale.
        onMain { messaging.leaveForeground(keepSocket = false) }
        other.call("POST", "/react", buildJsonObject {
            put("peer", Ids.wire(peerId))
            put("messageId", Ids.wire(target))
            put("emojis", buildJsonArray { add(JsonPrimitive("👍")); add(JsonPrimitive("🎉")) })
        })
        peer.sees("the other device's reaction") { it.str("id") == Ids.wire(target) && emojisOf(it, me).toSet() == setOf("👍", "🎉") }
        onMain { messaging.toggleReaction("❤️", target, peerId) }
        // The server answers 409 with the other device's record; ours goes on top of it (MC:5188-5218).
        peer.sees("the merged reaction") { it.str("id") == Ids.wire(target) && emojisOf(it, me).toSet() == setOf("👍", "🎉", "❤️") }
        onMain { messaging.handleAppBecameActive() }
        eventually("this phone shows the merged set") {
            bubble(peerId, target)?.reactions?.firstOrNull { it.userId == me && it.emojis.toSet() == setOf("👍", "🎉", "❤️") && !it.pending }
        }

        // ---- The other device removes this phone: the running app wipes ---------------------------
        other.call("POST", "/revoke", buildJsonObject { put("deviceId", myDevice) })
        val auth = container.auth
        eventually("this phone learns it was removed") {
            runCatching { messaging.refreshConversations(force = true) }
            if (auth.sessionController.pendingFullLocalWipe.value || auth.deviceWipe.isPresented.value) Unit else null
        }
        // What the root does with it (InterimSession, `RootView.swift:187-194`).
        onMain { auth.deviceWipe.startIfSessionEnded() }
        awaitWipe(expectReason = WipeReason.Removed)
    }

    // ---- 3. Chat deletes ---------------------------------------------------------------------------

    @Test
    fun chatDeletesFollowThePeersConsent() {
        val peer = peer(0).create()
        signUp()
        startAndBefriend(peer)
        val peerId = peer.id

        // Without consent: our messages are unsent on their side, theirs stay with them.
        peer.call("POST", "/privacy", buildJsonObject { put("allow_peer_chat_delete", false) })
        val theirs = peer.sendText("Theirs, kept")
        incoming(peerId, "their text") { it.id == theirs }
        onMain { messaging.sendText("Mine, unsent", peerId) }
        val mine = mine("Mine, unsent", peerId, peer)
        assertEquals(ChatDeleteOutcome.UnsentForPeer, onMain { messaging.deleteConversation(peerId, ConversationDeleteScope.Everyone) })
        eventually("the chat is empty here") { if (messaging.threads.value[peerId].isNullOrEmpty()) Unit else null }
        eventuallyBlocking("ours is unsent on their side", 20_000) {
            peer.messages().firstOrNull { it.str("id") == Ids.wire(mine.id) }.let { if (it == null || it.bool("deleted")) Unit else null }
        }
        assertEquals("Theirs, kept", peer.messages().first { it.str("id") == Ids.wire(theirs) }.optStr("text"))

        // With consent: the chat is cleared for both.
        peer.call("POST", "/privacy", buildJsonObject { put("allow_peer_chat_delete", true) })
        eventually("this phone sees the consent") {
            runCatching { messaging.refreshConversations(force = true) }
            Unit
        }
        val theirsAgain = peer.sendText("Theirs, cleared")
        incoming(peerId, "their second text") { it.id == theirsAgain }
        onMain { messaging.sendText("Mine, cleared", peerId) }
        val mineAgain = mine("Mine, cleared", peerId, peer)
        assertEquals(ChatDeleteOutcome.ClearedForBoth, onMain { messaging.deleteConversation(peerId, ConversationDeleteScope.Everyone) })
        eventuallyBlocking("the chat is cleared on their side", 20_000) {
            val left = peer.messages().filter { !it.bool("deleted") }.map { it.str("id") }
            if (Ids.wire(theirsAgain) !in left && Ids.wire(mineAgain.id) !in left) Unit else null
        }
        eventually("and here") { if (messaging.threads.value[peerId].isNullOrEmpty()) Unit else null }

        logOutThroughTheWipe()
    }

    // ---- 4. Contacts -------------------------------------------------------------------------------

    @Test
    fun contactsComeByShareCodeLinkAndNameWithPresenceBlocksAndKeyChanges() {
        val byCode = peer(1).create()
        val byLink = peer(2).create()
        val byName = peer(0).create()
        signUp()
        onMain { messaging.start() }
        val contacts = container.contacts.controller

        // Share code and link (contacts §4.2; the invite parser and its lookup). A username is
        // refused before anything reaches the server: it is shared only between contacts.
        addContact(byCode, byCode.shareCode)
        addContact(byLink, "https://shroud.corespace.de/u/${byLink.shareCode}")
        assertEquals(AddContactOutcome.Failed("Add someone with their QR code or share code."), onMain { contacts.add(byName.name) })
        addContact(byName, byName.shareCode)

        // Presence: a connected, focused socket is online; a closed one is not.
        byName.socket(on = true)
        eventually("the peer is online") {
            contacts.refreshPresence(listOf(byName.id))
            contacts.presence.value[byName.id]?.takeIf { it.online }
        }
        byName.socket(on = false)
        eventually("the peer is offline") {
            contacts.refreshPresence(listOf(byName.id))
            contacts.presence.value[byName.id]?.takeIf { !it.online }
        }

        // Block: the contact goes, their messages are refused; unblock and add again.
        assertNull(onMain { contacts.block(byCode.id, byCode.name) })
        eventually("blocked") {
            contacts.refreshBlocks()
            contacts.blocked.value.firstOrNull { it.userId == byCode.id }
        }
        eventually("no longer a contact") {
            contacts.refresh(force = true)
            if (contacts.contacts.value.none { it.userId == byCode.id }) Unit else null
        }
        // The server hides our keys from them ("No pre-key bundle…") and refuses their messages ("…while blocked.").
        val refused = runCatching { byCode.sendText("Are you there?") }.exceptionOrNull()?.message.orEmpty()
        assertTrue("a blocked peer could send: $refused", "blocked" in refused || "pre-key bundle" in refused)
        assertNull(onMain { contacts.unblock(byCode.id) })
        eventually("unblocked") {
            contacts.refreshBlocks()
            if (contacts.blocked.value.none { it.userId == byCode.id }) Unit else null
        }
        addContact(byCode, byCode.shareCode)
        val again = byCode.sendText("Back again")
        incoming(byCode.id, "the unblocked peer's text") { it.id == again && it.text == "Back again" }

        // Key change: detected before a send, trusted, then both ways work again.
        onMain { messaging.loadThread(byName.id) }
        onMain { messaging.sendText("Before the key change", byName.id) }
        mine("Before the key change", byName.id, byName)
        byName.call("POST", "/rekey")
        byName.socket(on = true)
        onMain { messaging.sendText("After the key change", byName.id) }
        val identities = container.contacts.peerIdentities
        eventually("the key change is detected") { identities.identityChange(byName.id) }
        val queued = eventually("the message waits for the user") {
            messaging.threads.value[byName.id]?.lastOrNull { it.text == "After the key change" }?.takeIf { it.pendingSync }
        }
        assertTrue(byName.messages().none { it.optStr("text") == "After the key change" })
        onMain { identities.acceptNewIdentity(byName.id) }
        assertNull(identities.identityChange(byName.id))
        onMain { messaging.handleAppBecameActive() }
        byName.sees("the queued message under the new key") { it.optStr("text") == "After the key change" }
        assertNotNull(queued)
        val fresh = byName.sendText("New key, same friend")
        val decoded = incoming(byName.id, "their message under the new key") { it.id == fresh }
        assertEquals("New key, same friend", decoded.text)

        logOutThroughTheWipe()
    }

    // ---- 5. Calls ----------------------------------------------------------------------------------

    @Test
    fun callsRingConnectAndHangUpBothWays() {
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        val peer = peer(0).create()
        signUp()
        startAndBefriend(peer)
        peer.socket(on = true)
        val peerId = peer.id
        // A message first: both sides pin each other's key, which the call secret is derived from.
        onMain { messaging.sendText("Call me", peerId) }
        mine("Call me", peerId, peer)
        val secrets = container.calls.secrets
        eventuallyBlocking("the call secret exists", 20_000) {
            onMain { secrets.refreshAll() }
            runBlocking(Dispatchers.IO) { secrets.secret(peerId) }
        }

        val calls: CallController = container.calls.controller
        val engine = E2eCallEngine()
        val system = E2eCallSystem()
        onMain { calls.attach(engine, system) }

        // Android calls the web: ring, accept, sealed offer / answer / candidates, connected, hang up.
        onMain { calls.startCall(peerId, peer.name, CallModality.Voice) }
        eventuallyBlocking("the web rings", 20_000) { peer.call("GET", "/call").takeIf { it.str("phase") == "incoming" } }
        peer.call("POST", "/call/accept")
        eventually("connected here", 30_000) { calls.ui.value.active?.takeIf { it.phase == CallPhase.Active && it.isOutgoing } }
        eventuallyBlocking("connected on the web", 30_000) { peer.call("GET", "/call").takeIf { it.str("phase") == "active" && it.bool("connected") } }
        assertTrue("the web's answer reached the engine", engine.receivedAnswers.isNotEmpty())
        assertTrue(engine.isConnected)
        onMain { calls.hangup() }
        eventuallyBlocking("the web hears the hangup", 20_000) { peer.call("GET", "/call").takeIf { it.str("phase") == "ended" || it.str("phase") == "idle" } }
        eventually("ended here", 20_000) { if (calls.ui.value.active.let { it == null || it.phase == CallPhase.Ending }) Unit else null }

        // The web calls Android: the ring arrives over the socket, we accept, the web offers.
        eventually("idle again", 20_000) { if (calls.ui.value.active == null) Unit else null }
        peer.call("POST", "/call/start", buildJsonObject { put("peer", Ids.wire(me)); put("username", myName) })
        eventually("Android rings", 20_000) { calls.ui.value.active?.takeIf { it.phase == CallPhase.IncomingRinging && !it.isOutgoing } }
        onMain { calls.acceptIncoming() }
        eventually("connected here again", 30_000) { calls.ui.value.active?.takeIf { it.phase == CallPhase.Active } }
        eventuallyBlocking("connected on the web again", 30_000) { peer.call("GET", "/call").takeIf { it.str("phase") == "active" && it.bool("connected") } }
        assertTrue("the web's offer reached the engine", engine.receivedOffers.isNotEmpty())
        peer.call("POST", "/call/hangup")
        eventually("the web's hangup ends the call here", 20_000) { if (calls.ui.value.active.let { it == null || it.phase == CallPhase.Ending }) Unit else null }
        assertTrue("the engine was closed", engine.closes >= 2)

        // Both calls are in the history.
        eventually("the history lists both calls") {
            calls.refreshHistory()
            calls.history.value.recent.takeIf { it.size >= 2 }
        }

        logOutThroughTheWipe()
    }

    // ---- Android account ---------------------------------------------------------------------------

    private fun signUp() {
        val auth = container.auth.sessionController
        myName = "e2e_" + UUID.randomUUID().toString().replace("-", "").take(12)
        myPassword = "Engine e2e passphrase " + UUID.randomUUID()
        val session = onMain { auth.register(myName, myPassword) }
        signedUp = true
        val keys = container.keys
        myWords = keys.bip39.generate()
        onMain { keys.cryptoController.establishFromSignup(myWords, session) }
        assertEquals(session.userId, keys.cryptoController.unlockedUserId.value)
    }

    /** Messaging on, [peer] added by share code and accepted, its chat open. */
    private fun startAndBefriend(peer: Peer) {
        onMain { messaging.start() }
        addContact(peer, peer.shareCode)
        onMain { messaging.loadThread(peer.id) }
    }

    private fun addContact(peer: Peer, invite: String) {
        val contacts = container.contacts.controller
        val added = onMain { contacts.add(invite) }
        assertTrue("add contact by '$invite': $added", added is AddContactOutcome.Requested || added is AddContactOutcome.Added)
        assertEquals(1, peer.call("POST", "/contacts/accept").int("accepted"))
        eventually("${peer.name} becomes a contact") {
            contacts.refresh(force = true)
            contacts.contacts.value.firstOrNull { it.userId == peer.id }
        }
    }

    private fun logOutThroughTheWipe() {
        onMain { container.auth.deviceWipe.start(WipeReason.Logout) }
        awaitWipe(expectReason = WipeReason.Logout)
    }

    private fun awaitWipe(expectReason: WipeReason) {
        val wipe = container.auth.deviceWipe
        eventually("the wipe runs") { if (wipe.isPresented.value || wipe.phase.value != WipePhase.Idle) Unit else null }
        assertEquals(expectReason, wipe.reason.value)
        eventually("the wipe finishes", timeoutMs = 60_000) { if (wipe.phase.value == WipePhase.Idle && !wipe.isPresented.value) Unit else null }
        assertTrue("leftovers: ${wipe.leftovers.value}", wipe.leftovers.value.isEmpty())
        assertNull(container.auth.sessionController.session.value)
        assertNull(container.keys.cryptoController.unlockedUserId.value)
        assertTrue("the wipe's verify step passed", runBlocking(Dispatchers.IO) { container.auth.deviceDataWipe.leftovers() }.isEmpty())
        signedUp = false
    }

    // ---- Thread helpers ----------------------------------------------------------------------------

    private fun bubble(peer: UUID, id: UUID): ChatMessage? = messaging.threads.value[peer]?.firstOrNull { it.id == id }

    /** Our sent text bubble, once the server re-keyed it (`ThreadState.rekey`). */
    private fun mine(text: String, peer: UUID, web: Peer): ChatMessage = eventuallyBlocking("\"$text\" is sent", 30_000) {
        onMain { messaging.threads.value[peer]?.lastOrNull { it.isMine && it.text == text && !it.pendingSync && it.sendError == null } }
            ?.takeIf { sent -> web.messages().any { it.str("id") == Ids.wire(sent.id) } }
    }

    private fun sentMedia(peer: UUID, kind: ChatMessageKind, text: String): ChatMessage? =
        messaging.threads.value[peer]?.lastOrNull { it.isMine && it.kind == kind && it.text == text && !it.pendingSync && it.sendError == null && it.mediaObjectId != null }

    /** Our sent file of [name], once the server re-keyed it. */
    private fun sentFile(peer: UUID, name: String): ChatMessage? =
        messaging.threads.value[peer]?.lastOrNull { it.isMine && it.kind == ChatMessageKind.File && it.fileName == name && !it.pendingSync && it.sendError == null && it.mediaObjectId != null }

    /** A picked file as a document provider would hand it over: here a file of the app's cache. */
    private fun cacheFile(name: String, bytes: ByteArray): File =
        File(File(app.cacheDir, "e2e-picks").apply { mkdirs() }, name).apply { writeBytes(bytes) }

    private fun incoming(peer: UUID, what: String, match: (ChatMessage) -> Boolean): ChatMessage {
        var polls = 0
        return eventually(what) {
            // The socket delivers; a reconcile every few polls covers a dropped event (api-realtime §17.2).
            if (++polls % 10 == 0) messaging.loadThread(peer, activate = false, reconcile = true)
            messaging.threads.value[peer]?.firstOrNull(match)
        }
    }

    /** The emojis [user] reacted with on a peer-side message, as the web opened them. */
    private fun emojisOf(message: JsonObject, user: UUID): List<String> =
        message.arr("reactions").map { it.jsonObject }.firstOrNull { it.str("userId") == Ids.wire(user) }
            ?.arr("emojis")?.map { it.jsonPrimitive.content }.orEmpty()

    // ---- Peer --------------------------------------------------------------------------------------

    private fun peerCall(base: String, method: String, path: String, body: JsonObject? = null): JsonObject {
        val request = Request.Builder().url(base + path).apply {
            if (method == "POST") post((body ?: JsonObject(emptyMap())).toString().toRequestBody(JSON))
        }.build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            check(response.isSuccessful) { "peer $method $path: ${response.code} $text" }
            return container.json.parseToJsonElement(text).jsonObject
        }
    }

    // ---- Media -------------------------------------------------------------------------------------

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(47, 168, 91)) }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    /** One second of a 440 Hz tone as a voice note (the recorder's own AAC-LC writer). */
    private fun voiceNote(): ByteArray {
        val file = File(app.cacheDir, "shroud-e2e-voice-${UUID.randomUUID()}.m4a")
        try {
            val writer = AacM4aWriter(file)
            val pcm = ShortArray(VoiceFormat.SAMPLE_RATE) { i -> (sin(2 * PI * 440 * i / VoiceFormat.SAMPLE_RATE) * 8_000).toInt().toShort() }
            var offset = 0
            while (offset < pcm.size) {
                val size = minOf(4_096, pcm.size - offset)
                writer.write(pcm, offset, size)
                offset += size
            }
            writer.finish()
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    private fun asset(name: String): File {
        val file = File(app.cacheDir, "shroud-e2e-${name.substringAfterLast('/')}")
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    // ---- Plumbing ----------------------------------------------------------------------------------

    private fun reachable(url: String): Boolean = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    /** The engines are main-confined (plan §1.1 rule 3). */
    private fun <T> onMain(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    private fun <T : Any> eventually(what: String, timeoutMs: Long = 30_000, block: suspend () -> T?): T =
        eventuallyBlocking(what, timeoutMs) { onMain(block) }

    private fun <T : Any> eventuallyBlocking(what: String, timeoutMs: Long, block: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                block()?.let { return it }
            } catch (e: Exception) {
                last = e
            }
            Thread.sleep(300)
        }
        throw AssertionError("timed out: $what${last?.let { " (last error: $it)" } ?: ""}")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.optStr(key: String): String? = (get(key) as? JsonPrimitive)?.takeUnless { it.content == "null" && !it.isString }?.content
    private fun JsonObject.int(key: String): Int = str(key).toInt()
    private fun JsonObject.bool(key: String): Boolean = str(key).toBoolean()
    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
    private fun JsonObject.arr(key: String): JsonArray = (get(key) as? JsonArray) ?: JsonArray(emptyList())

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
