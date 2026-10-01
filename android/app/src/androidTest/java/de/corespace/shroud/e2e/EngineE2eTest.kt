package de.corespace.shroud.e2e

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.corespace.shroud.AppContainer
import de.corespace.shroud.ShroudApplication
import de.corespace.shroud.core.auth.WipePhase
import de.corespace.shroud.core.auth.WipeReason
import de.corespace.shroud.core.keys.DeviceLock
import de.corespace.shroud.core.links.LinkPreviewAttachment
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.video.VideoSendPlan
import de.corespace.shroud.core.media.video.VideoUploadQuality
import de.corespace.shroud.core.messaging.MessagingController
import de.corespace.shroud.core.model.AddContactOutcome
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.Ids
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
import kotlinx.serialization.json.jsonArray
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
 * The wave 2 exit gate (00-plan §0, §2.3 W2-INT, §6.3): this phone's engines — the real
 * `AppContainer` with every W2 package wired — exchange every kind with the scripted web peer
 * (`android/e2e/peer`, the web client's own crypto and API modules) through the local server:
 * text, a reply, link previews both ways, a photo both ways, a video, a voice note, reactions both
 * ways and deletes for everyone both ways. The peer opens what this phone sealed and the other way
 * round, byte for byte where there are bytes (media SHA-256). At the end the Log Out wipe runs and
 * must leave nothing behind.
 *
 * Run by `android/e2e/engine-e2e.sh` (stack, peer, instrumentation). Skipped when the API or the peer
 * cannot be reached, or the device has no screen lock (the vault needs one: `emulator-setup.sh`).
 * Instrumentation arguments: `shroudApi` (default `http://10.0.2.2:8080/api/v1`), `shroudPeer`
 * (default `http://10.0.2.2:8099`).
 */
@RunWith(AndroidJUnit4::class)
class EngineE2eTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as ShroudApplication
    private val container: AppContainer get() = app.container
    private val arguments = InstrumentationRegistry.getArguments()
    private val baseUrl: String = arguments.getString("shroudApi") ?: "http://10.0.2.2:8080/api/v1"
    private val peerUrl: String = arguments.getString("shroudPeer") ?: "http://10.0.2.2:8099"
    private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private var previousServer: ServerConfiguration? = null
    private var signedUp = false

    private val messaging: MessagingController get() = container.messaging.controller

    @Before
    fun setUp() {
        assumeTrue("the local stack is not reachable at $baseUrl (android/e2e/stack-up.sh)", reachable("$baseUrl/health/live"))
        assumeTrue("the web peer is not reachable at $peerUrl (android/e2e/engine-e2e.sh)", reachable("$peerUrl/health"))
        DeviceLock.ensureUnlocked()
        assumeTrue("needs a screen lock: android/e2e/emulator-setup.sh", DeviceLock.isSecure)
        val server = container.serverConfiguration
        val url = baseUrl.toHttpUrl()
        if (server.configuration.value.resolvedBaseUrl != baseUrl) {
            previousServer = server.configuration.value
            server.save(ServerConfiguration.localDevelopment(url.host, url.port))
        }
    }

    @After
    fun tearDown() {
        runCatching { peer("POST", "/logout") }
        if (signedUp && container.auth.sessionController.session.value != null) {
            // A failed run still leaves nothing of its account on the phone.
            runCatching { logOutThroughTheWipe() }
        }
        previousServer?.let { container.serverConfiguration.save(it) }
    }

    @Test
    fun theEnginesExchangeEveryKindWithTheWebPeer() {
        // ---- Accounts and contact --------------------------------------------------------------
        val peerAccount = peer("POST", "/account")
        val peerId = UUID.fromString(peerAccount.str("userId"))
        val peerName = peerAccount.str("username")
        signUp()
        val me = UUID.fromString(container.auth.sessionController.session.value!!.userId)
        onMain { messaging.start() }
        val added = onMain { container.contacts.controller.add(peerName) }
        assertTrue("add contact: $added", added is AddContactOutcome.Requested || added is AddContactOutcome.Added)
        assertEquals(1, peer("POST", "/contacts/accept").int("accepted"))
        eventually("the peer becomes a contact") {
            container.contacts.controller.refresh(force = true)
            container.contacts.controller.contacts.value.firstOrNull { it.userId == peerId }
        }
        onMain { messaging.loadThread(peerId) }

        // ---- Text, both ways, with a reply and link previews -----------------------------------
        onMain { messaging.sendText("Hello from Android", peerId) }
        val hello = mine("Hello from Android", peerId)
        peerSees("the text") { it.str("id") == Ids.wire(hello.id) && it.optStr("text") == "Hello from Android" }

        val peerReply = peer(
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
        ).str("id").let(UUID::fromString)
        val received = incoming(peerId, "the peer's reply") { it.id == peerReply }
        assertEquals("Hi from the web", received.text)
        assertEquals(hello.id, received.replyTo?.messageId)
        assertEquals("https://example.com/web", received.linkPreview?.url)
        assertEquals("From the web", received.linkPreview?.title)

        val preview = LinkPreviewAttachment(LinkPreview("https://example.org/android", title = "From Android"), null, null, null)
        val replyRef = MessageReplyReference(peerReply, peerId, MessageReplyReference.Kind.Text, "Hi from the web")
        onMain { messaging.sendText("Answer with a link https://example.org/android", peerId, replyTo = replyRef, linkPreview = preview) }
        val answer = mine("Answer with a link https://example.org/android", peerId)
        peerSees("the reply with its link preview") {
            it.str("id") == Ids.wire(answer.id) &&
                it.obj("replyTo")?.optStr("id") == Ids.wire(peerReply) &&
                it.obj("linkPreview")?.optStr("title") == "From Android"
        }

        // ---- Photos, both ways -----------------------------------------------------------------
        val photo = jpeg()
        val photoError = onMain { messaging.sendImage(MediaImageSource.FileBytes(photo), peerId, caption = "A photo") }
        assertNull(photoError)
        val sentPhoto = eventually("the photo is sent") {
            messaging.threads.value[peerId]?.lastOrNull { it.isMine && it.kind == ChatMessageKind.Image && !it.pendingSync && it.sendError == null && it.mediaObjectId != null }
        }
        peerSees("the photo") {
            val media = it.obj("media")
            it.str("id") == Ids.wire(sentPhoto.id) && media?.optStr("t") == "image" && media.optStr("caption") == "A photo" &&
                (media["bytes"]?.jsonPrimitive?.content?.toInt() ?: 0) > 0
        }

        val peerPhoto = peer("POST", "/photo", buildJsonObject { put("peer", Ids.wire(me)); put("caption", "From the web") })
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
        peerSees("the video", timeoutMs = 120_000) {
            val media = it.obj("media")
            media?.optStr("t") == "video" && media.optStr("mime") == "video/mp4" && (media["bytes"]?.jsonPrimitive?.content?.toInt() ?: 0) > 0
        }

        val voice = voiceNote()
        val voiceError = onMain { messaging.sendVoice(voice, 1_000, peerId) }
        assertNull(voiceError)
        peerSees("the voice note") {
            val media = it.obj("media")
            media?.optStr("t") == "voice" && media.optStr("mime") == "audio/mp4" && (media["bytes"]?.jsonPrimitive?.content?.toInt() ?: 0) == voice.size
        }

        // ---- Reactions, both ways --------------------------------------------------------------
        onMain { messaging.toggleReaction("👍", peerReply, peerId) }
        peerSees("our reaction") { message ->
            message.str("id") == Ids.wire(peerReply) &&
                message.arr("reactions").any { it.jsonObject.str("userId") == Ids.wire(me) && it.jsonObject.arr("emojis").map { e -> e.jsonPrimitive.content } == listOf("👍") }
        }
        peer("POST", "/react", buildJsonObject {
            put("peer", Ids.wire(me))
            put("messageId", Ids.wire(hello.id))
            put("emojis", buildJsonArray { add(JsonPrimitive("❤️")) })
        })
        eventually("the peer's reaction arrives") {
            messaging.threads.value[peerId]?.firstOrNull { it.id == hello.id }?.reactions?.firstOrNull { it.userId == peerId && it.emojis == listOf("❤️") }
        }

        // ---- Deletes for everyone, both ways ---------------------------------------------------
        val deleteError = onMain { messaging.deleteMessage(messaging.threads.value[peerId]!!.first { it.id == hello.id }, MessageDeleteScope.Everyone) }
        assertNull(deleteError)
        peerSees("our delete") { it.str("id") == Ids.wire(hello.id) && it.bool("deleted") }
        peer("POST", "/delete", buildJsonObject { put("messageId", Ids.wire(peerPhotoId)) })
        eventually("the peer's delete arrives") { messaging.threads.value[peerId]?.firstOrNull { it.id == peerPhotoId && it.deleted } }
        // The tombstone purged the photo from the sealed cache.
        eventually("the deleted photo's media is gone") { if (container.media.localMedia.has(peerPhotoId)) null else Unit }

        // ---- Log Out: the wipe leaves nothing --------------------------------------------------
        logOutThroughTheWipe()
    }

    // ---- Android account ---------------------------------------------------------------------------

    private fun signUp() {
        val auth = container.auth.sessionController
        val name = "e2e_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val session = onMain { auth.register(name, "Engine e2e passphrase " + UUID.randomUUID()) }
        signedUp = true
        val keys = container.keys
        onMain { keys.cryptoController.establishFromSignup(keys.bip39.generate(), session) }
        assertEquals(session.userId, keys.cryptoController.unlockedUserId.value)
    }

    private fun logOutThroughTheWipe() {
        val wipe = container.auth.deviceWipe
        onMain { wipe.start(WipeReason.Logout) }
        eventually("the wipe finishes", timeoutMs = 60_000) { if (wipe.phase.value == WipePhase.Idle && !wipe.isPresented.value) Unit else null }
        assertTrue("leftovers: ${wipe.leftovers.value}", wipe.leftovers.value.isEmpty())
        assertNull(container.auth.sessionController.session.value)
        assertNull(container.keys.cryptoController.unlockedUserId.value)
        assertTrue("the wipe's verify step passed", runBlocking(Dispatchers.IO) { container.auth.deviceDataWipe.leftovers() }.isEmpty())
        signedUp = false
    }

    // ---- Thread helpers ----------------------------------------------------------------------------

    /** Our sent text bubble, once the server re-keyed it (`ThreadState.rekey`). */
    private fun mine(text: String, peer: UUID): ChatMessage = eventually("\"$text\" is sent") {
        messaging.threads.value[peer]?.lastOrNull { it.isMine && it.text == text && !it.pendingSync && it.sendError == null }
            ?.takeIf { serverKnows(it.id) }
    }

    private val serverIds = HashSet<UUID>()

    /** The peer's list holds [id]: the bubble carries the server's id (re-keyed). */
    private fun serverKnows(id: UUID): Boolean {
        if (id in serverIds) return true
        val known = peerMessages().any { it.str("id") == Ids.wire(id) }
        if (known) serverIds += id
        return known
    }

    private fun incoming(peer: UUID, what: String, match: (ChatMessage) -> Boolean): ChatMessage {
        var polls = 0
        return eventually(what) {
            // The socket delivers; a reconcile every few polls covers a dropped event (api-realtime §17.2).
            if (++polls % 10 == 0) messaging.loadThread(peer, activate = false, reconcile = true)
            messaging.threads.value[peer]?.firstOrNull(match)
        }
    }

    // ---- Peer --------------------------------------------------------------------------------------

    private fun peerMessages(): List<JsonObject> {
        val me = container.auth.sessionController.session.value?.userId ?: return emptyList()
        return peer("GET", "/messages?peer=$me").arr("messages").map { it.jsonObject }
    }

    private fun peerSees(what: String, timeoutMs: Long = 30_000, match: (JsonObject) -> Boolean) {
        eventuallyBlocking(what, timeoutMs) { peerMessages().firstOrNull(match) }
    }

    private fun peer(method: String, path: String, body: JsonObject? = null): JsonObject {
        val request = Request.Builder().url(peerUrl + path).apply {
            if (method == "POST") post((body ?: JsonObject(emptyMap())).toString().toRequestBody(JSON))
        }.build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            check(response.isSuccessful) { "peer $method $path: ${response.code} $text" }
            return container.json.parseToJsonElement(text).jsonObject
        }
    }

    // ---- Media -------------------------------------------------------------------------------------

    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(47, 168, 91)) }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
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
