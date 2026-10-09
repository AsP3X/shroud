package de.corespace.shroud.core.net

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import de.corespace.shroud.core.auth.UsernameHash
import de.corespace.shroud.core.auth.UsernameKdfParams
import de.corespace.shroud.core.auth.UsernameMigration
import de.corespace.shroud.testing.serveUsernameKdf
import de.corespace.shroud.testing.takeApiRequest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * Every `ShroudApi` endpoint against a fake server (api-realtime §6, §13 row *ShroudApiTest*):
 * method, path, lower-case ids, query and body keys as the iOS services send them (the
 * `…Service.swift` files under `Services/API`, `Services/Calls/CallsService.swift`,
 * `Services/Auth/AuthService.swift`), and the answers decoded.
 */
class ShroudApiTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var api: ShroudApi
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    @Before
    fun setUp() {
        UsernameMigration.reset()
        server = MockWebServer()
        server.serveUsernameKdf()
        server.start()
        api = ShroudApi(ApiClient(baseUrl = { server.url("/api/v1").toString() }, json = json))
    }

    @After
    fun tearDown() = server.close()

    private val session = """{"token":"tok","user":{"id":"8F14E45F-CEEA-467A-9575-3A6B7A1E6C0E","username":"noah","share_code":"ABCDEFGHJK"},"device":{"id":"2E6F9B0C-1D3A-4E5B-8C7D-9F0A1B2C3D4E"}}"""
    private val anchor = UUID.fromString("2e6f9b0c-1d3a-4e5b-8c7d-9f0a1b2c3d4e")

    // Upper-case on the way in: every path must still carry the lower-case form.
    private val user = UUID.fromString("ABCDEF01-2345-4678-9ABC-DEF012345678")
    private val userWire = "abcdef01-2345-4678-9abc-def012345678"
    private val other = UUID.fromString("6BA7B810-9DAD-11D1-80B4-00C04FD430C8")
    private val otherWire = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"

    private val userCard = """{"id":"11111111-1111-1111-1111-111111111111","username":"alice","share_code":"ABCD234567"}"""
    private val contactRequest = """{"id":"44444444-4444-4444-4444-444444444444","from_user_id":"11111111-1111-1111-1111-111111111111","to_user_id":"22222222-2222-2222-2222-222222222222","status":"pending","created_at":"2026-09-24T12:00:00.123456Z","user":{"id":"11111111-1111-1111-1111-111111111111","username":"alice"}}"""
    private val message = """{"id":"6ba7b810-9dad-11d1-80b4-00c04fd430c8","conversation_id":"44444444-4444-4444-4444-444444444444","sender_user_id":"11111111-1111-1111-1111-111111111111","sender_device_id":"22222222-2222-2222-2222-222222222222","client_message_id":"33333333-3333-3333-3333-333333333333","content_type":"text","ciphertext":"c2VhbGVk","deleted_for_everyone":false,"created_at":"2026-09-24T12:00:00.123456Z"}"""
    private val call = """{"id":"55555555-5555-5555-5555-555555555555","caller_user_id":"11111111-1111-1111-1111-111111111111","caller_device_id":"22222222-2222-2222-2222-222222222222","caller_username":"alice","callee_user_id":"33333333-3333-3333-3333-333333333333","callee_username":null,"modality":"video","status":"ringing","protocol":2,"created_at":"2026-09-30T09:41:00.5Z"}"""
    private val reaction = """{"message_id":"7c9e6679-7425-40de-944b-e07fc1f90ae7","user_id":"3f2504e0-4f89-41d3-9a0c-0305e82c3301","ciphertext":"aGVhcnQ=","seq":43,"updated_at":"2026-09-23T21:08:36Z"}"""
    private val notificationSettings = """{"enabled":true,"show_sender":true,"reactions":true,"contact_requests":true,"sound":"default","badge":true,"badge_includes_muted":false}"""
    private val privacy = """{"allow_peer_chat_delete":true,"send_read_receipts":true,"send_typing":true,"share_presence":false,"discoverable_by_username":true}"""

    /** Enqueues [response], runs [block], and hands back its result with the request the server saw. */
    private suspend fun <T> exchange(response: MockResponse, block: suspend () -> T): Pair<T, RecordedRequest> {
        server.enqueue(response)
        val result = block()
        return result to server.takeApiRequest()
    }

    private fun ok(body: String, code: Int = 200) = MockResponse(code = code, body = body)
    private val noContent get() = MockResponse(code = 204)

    private fun RecordedRequest.assertRoute(method: String, path: String, query: String? = null) {
        assertEquals(method, this.method)
        assertEquals("/api/v1/$path", url.encodedPath)
        assertEquals(query, url.encodedQuery)
        assertEquals("application/json, application/octet-stream, */*", headers["Accept"])
    }

    private fun RecordedRequest.assertToken(token: String? = "tok") = assertEquals(token?.let { "Bearer $it" }, headers["Authorization"])

    /** A JSON body equal to [expected] (key order aside), sent as `application/json`. */
    private fun RecordedRequest.assertJson(expected: String) {
        assertEquals("application/json", headers["Content-Type"])
        assertEquals(json.parseToJsonElement(expected), json.parseToJsonElement(body!!.utf8()))
    }

    private fun RecordedRequest.assertNoBody() {
        assertEquals(0L, bodySize)
        assertNull(headers["Content-Type"])
    }

    private fun jsonOf(request: RecordedRequest): JsonElement = json.parseToJsonElement(request.body!!.utf8())

    // ---- Auth (AuthService.swift) ----

    @Test
    fun registerSendsOnlyUsernameAndPassword() = runTest {
        val (response, request) = exchange(ok(session, 201)) { api.register("noah", "secret") }
        request.assertRoute("POST", "auth/register")
        request.assertToken(null)
        assertEquals(setOf("username_hash", "password"), jsonOf(request).jsonObject.keys)
        assertEquals(
            UsernameHash.argon2id("noah", UsernameKdfParams.TEST),
            jsonOf(request).jsonObject["username_hash"]!!.jsonPrimitive.content,
        )
        assertEquals("tok", response.token)
        assertEquals("ABCDEFGHJK", response.user.shareCode)
        // Upper-case ids from the wire become UUIDs; their wire form is lower-case.
        assertEquals("8f14e45f-ceea-467a-9575-3a6b7a1e6c0e", response.user.id.toString())
        assertEquals(anchor, response.device.id)
    }

    @Test
    fun loginSendsTheDeviceIdOrNull() = runTest {
        server.enqueue(MockResponse(code = 200, body = session))
        server.enqueue(MockResponse(code = 200, body = session))
        api.login("noah", "pw", anchor)
        api.login("noah", "pw", null)
        val first = server.takeApiRequest()
        first.assertRoute("POST", "auth/login")
        first.assertToken(null)
        val second = json.parseToJsonElement(server.takeApiRequest().body!!.utf8()).jsonObject
        assertEquals("2e6f9b0c-1d3a-4e5b-8c7d-9f0a1b2c3d4e", jsonOf(first).jsonObject["device_id"]!!.jsonPrimitive.content)
        assertEquals(UsernameHash.digest("noah"), jsonOf(first).jsonObject["legacy_username_hash"]!!.jsonPrimitive.content)
        assertEquals(UsernameHash.argon2id("noah", UsernameKdfParams.TEST), jsonOf(first).jsonObject["username_hash"]!!.jsonPrimitive.content)
        assertEquals("null", second["legacy_username_hash"].toString())
        assertTrue(second.containsKey("device_id"))
        assertEquals("null", second["device_id"].toString())
    }

    @Test
    fun theDeviceLimitRetrySendsTheDeviceToLogOut() = runTest {
        val oldest = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
        server.enqueue(
            MockResponse(
                code = 409,
                body = """{"error":{"code":"DEVICE_LIMIT","message":"full"},"oldest_device":{"id":"$oldest","created_at":"2025-03-12T08:30:00Z"}}""",
            ),
        )
        server.enqueue(ok(session))
        val limit = runCatching { api.login("noah", "pw", anchor) }.exceptionOrNull() as ApiError.Server
        assertEquals(oldest, limit.deviceLimit?.oldestDevice?.id)
        api.login("noah", "pw", anchor, replaceDeviceId = oldest)
        val first = jsonOf(server.takeApiRequest()).jsonObject
        val retry = jsonOf(server.takeApiRequest()).jsonObject
        // A plain login names no device; the retry keeps the anchor and adds the one the user saw.
        // A 409 does not count as a finished login, so both still send the old digest.
        assertEquals("null", first["replace_device_id"].toString())
        assertEquals(first["device_id"], retry["device_id"])
        assertEquals(first["username_hash"], retry["username_hash"])
        assertEquals(UsernameHash.digest("noah"), first["legacy_username_hash"]!!.jsonPrimitive.content)
        assertEquals(first["legacy_username_hash"], retry["legacy_username_hash"])
        assertEquals(oldest.toString(), retry["replace_device_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun errorEnvelopeBecomesServerError() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"error":{"code":"INVALID_CREDENTIALS","message":"Invalid username or password."}}"""))
        val error = runCatching { api.login("noah", "bad", null) }.exceptionOrNull() as ApiError.Server
        assertEquals("INVALID_CREDENTIALS", error.code)
        assertEquals("Invalid username or password.", error.userMessage)
        assertTrue(error.isUnauthorized)
        assertFalse(error.isDeviceRemoved)
    }

    @Test
    fun bareUnauthorizedAndOtherStatuses() = runTest {
        server.enqueue(MockResponse(code = 401, body = ""))
        server.enqueue(MockResponse(code = 502, body = "<html>bad gateway</html>"))
        val unauthorized = runCatching { api.me("t") }.exceptionOrNull() as ApiError.Server
        assertEquals(ErrorCodes.UNAUTHORIZED, unauthorized.code)
        val transport = runCatching { api.me("t") }.exceptionOrNull()
        assertTrue(transport is ApiError.Transport)
    }

    @Test
    fun meAndLogout() = runTest {
        val (me, meRequest) = exchange(ok("""{"user":{"id":"8f14e45f-ceea-467a-9575-3a6b7a1e6c0e","username":"noah"},"device":{"id":"2e6f9b0c-1d3a-4e5b-8c7d-9f0a1b2c3d4e","sealed_name":"AAAA"}}""")) { api.me("tok") }
        meRequest.assertRoute("GET", "auth/me")
        meRequest.assertToken()
        assertEquals("AAAA", me.device.sealedName)
        val (_, logout) = exchange(noContent) { api.logout("tok") }
        logout.assertRoute("POST", "auth/logout")
        logout.assertToken()
        logout.assertNoBody()
    }

    @Test
    fun healthAndConfig() = runTest {
        val (health, healthRequest) = exchange(ok("""{"status":"ok","database":"ok","redis":"skipped","media":"ok"}""")) { api.health() }
        healthRequest.assertRoute("GET", "health")
        healthRequest.assertToken(null)
        assertEquals("ok", health.status)
        val (config, configRequest) = exchange(ok("""{"reactions":{"max_per_user":5}}""")) { api.clientConfig("tok") }
        configRequest.assertRoute("GET", "config")
        configRequest.assertToken()
        assertEquals(5, config.reactions.maxPerUser)
    }

    // ---- Devices (DevicesService.swift) ----

    @Test
    fun devices() = runTest {
        val (list, listRequest) = exchange(ok("""{"devices":[{"id":"$userWire","created_at":"2026-09-20T10:15:00.123456Z","is_current":true}]}""")) { api.devices("tok") }
        listRequest.assertRoute("GET", "devices")
        listRequest.assertToken()
        assertEquals(user, list.single().id)
        val (_, name) = exchange(noContent) { api.putDeviceName("tok", user, "c2VhbGVk") }
        name.assertRoute("PUT", "devices/$userWire/name")
        name.assertJson("""{"sealed_name":"c2VhbGVk"}""")
        val (_, revoke) = exchange(noContent) { api.revokeDevice("tok", user) }
        revoke.assertRoute("DELETE", "devices/$userWire")
        revoke.assertToken()
        revoke.assertNoBody()
    }

    // ---- Keys (KeyBundleService.swift) ----

    @Test
    fun bearerTokenAndKeyPaths() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"device_id":"$userWire","has_identity":false,"otpk_count":0}"""))
        server.enqueue(MockResponse(code = 404, body = """{"error":{"code":"KEYS_REQUIRED","message":"No keys."}}"""))
        server.enqueue(MockResponse(code = 204))
        assertFalse(api.keyStatus("tok").hasIdentity)
        server.takeRequest().assertRoute("GET", "keys/status")
        val missing = runCatching { api.identityKey("tok", user) }.exceptionOrNull() as ApiError.Server
        assertEquals(ErrorCodes.KEYS_REQUIRED, missing.code)
        server.takeRequest().assertRoute("GET", "keys/identity/$userWire")
        api.putKeyBundle("tok", PutKeyBundleRequest(1, "a", SignedPreKeyDto(2, "b", "c"), listOf(OneTimePreKeyDto(1, "d"))))
        val put = server.takeRequest()
        put.assertRoute("PUT", "keys/bundle")
        assertEquals(setOf("registration_id", "identity_key", "signed_pre_key", "one_time_pre_keys"), jsonOf(put).jsonObject.keys)
    }

    // ---- Users (ContactsService.swift:63-88, PrivacyService.swift:28-35) ----

    @Test
    fun userLookups() = runTest {
        val (byId, idRequest) = exchange(ok(userCard)) { api.user("tok", user) }
        idRequest.assertRoute("GET", "users/$userWire")
        assertEquals("alice", byId.username)
        // Normalized like ContactInviteParser.normalizeShareCode (ContactInviteParser.swift:38-44).
        val (_, byCode) = exchange(ok(userCard)) { api.userByShareCode("tok", "  @abcd-2345 67  ") }
        byCode.assertRoute("GET", "users/by-code/ABCD234567")
        byCode.assertToken()
        val (code, rotate) = exchange(ok("""{"share_code":"NEWC0DE234"}""")) { api.rotateShareCode("tok") }
        rotate.assertRoute("POST", "users/me/share-code")
        rotate.assertNoBody()
        assertEquals("NEWC0DE234", code)
    }

    @Test
    fun shareCodesNormalizeLikeIos() {
        assertEquals("ABCD234567", ShroudApi.normalizeShareCode("abcd-2345-67"))
        assertEquals("XYZW987654", ShroudApi.normalizeShareCode("  XYZW987654  "))
        assertEquals("BOB", ShroudApi.normalizeShareCode("@Bob@"))
    }

    // ---- Contacts (ContactsService.swift) ----

    @Test
    fun contactsAndRequests() = runTest {
        val (contacts, list) = exchange(ok("""{"contacts":[{"user_id":"$userWire","username":"bob","created_at":"2026-09-24T12:00:00Z"}]}""")) { api.contacts("tok") }
        list.assertRoute("GET", "contacts")
        assertEquals("bob", contacts.single().username)
        val (requests, incoming) = exchange(ok("""{"requests":[$contactRequest]}""")) { api.contactRequests("tok") }
        incoming.assertRoute("GET", "contacts/requests", "box=incoming&status=pending")
        assertEquals(ContactRequestStatus.PENDING, requests.single().status)
        val (_, outgoing) = exchange(ok("""{"requests":[]}""")) { api.contactRequests("tok", box = "outgoing", status = "accepted") }
        outgoing.assertRoute("GET", "contacts/requests", "box=outgoing&status=accepted")
        val (created, create) = exchange(ok(contactRequest, 201)) { api.createContactRequest("tok", user) }
        create.assertRoute("POST", "contacts/requests")
        create.assertJson("""{"user_id":"$userWire"}""")
        assertEquals("alice", created.user?.username)
        // iOS and web send `{}` (ContactsService.swift:35-54).
        val (_, accept) = exchange(ok(contactRequest.replace("pending", "accepted"))) { api.acceptContactRequest("tok", other) }
        accept.assertRoute("POST", "contacts/requests/$otherWire/accept")
        accept.assertJson("{}")
        val (rejected, reject) = exchange(ok(contactRequest.replace("pending", "rejected"))) { api.rejectContactRequest("tok", other) }
        reject.assertRoute("POST", "contacts/requests/$otherWire/reject")
        reject.assertJson("{}")
        assertEquals(ContactRequestStatus.REJECTED, rejected.status)
        val (_, remove) = exchange(noContent) { api.deleteContact("tok", user) }
        remove.assertRoute("DELETE", "contacts/$userWire")
        remove.assertNoBody()
    }

    // ---- Blocks (BlocksService.swift) ----

    @Test
    fun blocks() = runTest {
        val (blocks, list) = exchange(ok("""{"blocks":[{"user_id":"33333333-3333-3333-3333-333333333333","username":"mallory","created_at":"2026-08-07T10:11:12Z"}]}""")) { api.blocks("tok") }
        list.assertRoute("GET", "blocks")
        assertEquals("mallory", blocks.single().username)
        val (_, block) = exchange(noContent) { api.block("tok", user) }
        block.assertRoute("POST", "blocks")
        block.assertJson("""{"user_id":"$userWire"}""")
        val (_, unblock) = exchange(noContent) { api.unblock("tok", user) }
        unblock.assertRoute("DELETE", "blocks/$userWire")
    }

    // ---- Conversations and messages (MessagesService.swift) ----

    @Test
    fun historyCursorGoesOnlyWhenBothHalvesAreSet() = runTest {
        val page = """{"conversation_id":"44444444-4444-4444-4444-444444444444","messages":[$message],"has_more":true,"reaction_seq":3}"""
        val (first, firstRequest) = exchange(ok(page)) { api.messages("tok", user) }
        firstRequest.assertRoute("GET", "messages", "limit=50&peer_user_id=$userWire")
        assertEquals("2026-09-24T12:00:00.123456Z", first.messages.single().createdAtWire)
        val (_, half) = exchange(ok(page)) { api.messages("tok", user, limit = 40, beforeCreatedAt = "2026-09-24T12:00:00.123456Z") }
        half.assertRoute("GET", "messages", "limit=40&peer_user_id=$userWire")
        val (_, older) = exchange(ok(page)) { api.messages("tok", user, limit = 100, beforeCreatedAt = "2026-09-24T12:00:00.123456Z", beforeId = other) }
        assertEquals("2026-09-24T12:00:00.123456Z", older.url.queryParameter("before_created_at"))
        assertEquals(otherWire, older.url.queryParameter("before_id"))
        assertEquals("100", older.url.queryParameter("limit"))
        assertEquals(listOf("before_created_at", "before_id", "limit", "peer_user_id"), older.url.queryParameterNames.toList())
    }

    @Test
    fun conversationsAndSends() = runTest {
        val (list, listRequest) = exchange(ok("""{"conversations":[{"id":"44444444-4444-4444-4444-444444444444","peer":{"id":"$userWire","username":"bob"},"created_at":"2026-09-24T12:00:00Z","unread_count":3,"mute":null}]}""")) { api.conversations("tok") }
        listRequest.assertRoute("GET", "conversations")
        assertEquals(3, list.single().unreadCount)
        val send = SendMessageRequest(user, other, ContentType.TEXT, "c2VhbGVk")
        val (sent, sendRequest) = exchange(ok(message, 201)) { api.sendMessage("tok", send) }
        sendRequest.assertRoute("POST", "messages")
        sendRequest.assertJson("""{"peer_user_id":"$userWire","client_message_id":"$otherWire","content_type":"text","ciphertext":"c2VhbGVk","media_object_id":null}""")
        assertEquals(UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"), sent.id)
        val (_, delete) = exchange(noContent) { api.deleteMessage("tok", other, MessageDeleteScope.Everyone) }
        delete.assertRoute("DELETE", "messages/$otherWire", "scope=everyone")
        delete.assertNoBody()
        val (cleared, chat) = exchange(ok("""{"cleared_for_me":true,"cleared_for_peer":false,"tombstoned":3,"contact_removed":false}""")) {
            api.deleteConversation("tok", user, ConversationDeleteScope.Me)
        }
        chat.assertRoute("DELETE", "conversations/$userWire", "scope=me")
        assertEquals(3L, cleared.tombstoned)
    }

    @Test
    fun receiptsAndReadMarkers() = runTest {
        val (_, delivered) = exchange(noContent) { api.markDelivered("tok", other) }
        delivered.assertRoute("POST", "messages/$otherWire/delivered")
        delivered.assertNoBody()
        val (bulk, bulkRequest) = exchange(ok("""{"marked":2,"read_at":"2026-09-24T12:00:00.123456Z"}""")) { api.markReadBulk("tok", user, other) }
        bulkRequest.assertRoute("POST", "messages/read")
        bulkRequest.assertJson("""{"peer_user_id":"$userWire","up_to_message_id":"$otherWire"}""")
        assertEquals(2L, bulk.marked)
        val (read, readRequest) = exchange(ok("""{"read_at":null,"unread_count":0,"receipts":0}""")) { api.markChatRead("tok", user) }
        readRequest.assertRoute("POST", "conversations/$userWire/read")
        readRequest.assertNoBody()
        assertNull(read.readAt)
    }

    // ---- Reactions (MessagesService.swift:76-155) ----

    @Test
    fun reactionWritesGoThroughRawAndMapTheConflict() = runTest {
        val conflict = """{"error":{"code":"REACTION_CHANGED","message":"Your reaction changed on another device."},"current":{"message_id":"7c9e6679-7425-40de-944b-e07fc1f90ae7","user_id":"3f2504e0-4f89-41d3-9a0c-0305e82c3301","ciphertext":null,"seq":42,"updated_at":"2026-09-23T21:08:35.759754Z"}}"""
        val (changed, put) = exchange(ok(conflict, 409)) { api.putReaction("tok", other, "heart".encodeToByteArray(), baseSeq = 42, added = true) }
        put.assertRoute("PUT", "messages/$otherWire/reaction")
        put.assertToken()
        put.assertJson("""{"ciphertext":"aGVhcnQ=","base_seq":42,"added":true}""")
        assertEquals(42L, (changed as ReactionWriteResult.ChangedElsewhere).current.seq)
        val (saved, _) = exchange(ok(reaction)) { api.putReaction("tok", other, ByteArray(0), baseSeq = 0, added = false) }
        assertEquals(43L, (saved as ReactionWriteResult.Saved).reaction?.seq)
        val (gone, delete) = exchange(noContent) { api.deleteReaction("tok", other, baseSeq = 43) }
        delete.assertRoute("DELETE", "messages/$otherWire/reaction", "base_seq=43")
        delete.assertNoBody()
        assertEquals(ReactionWriteResult.Saved(null), gone)
        server.enqueue(ok("""{"error":{"code":"NOT_FOUND","message":"Message not found."}}""", 404))
        val error = runCatching { api.deleteReaction("tok", other, baseSeq = 1) }.exceptionOrNull() as ApiError.Server
        assertEquals("Message not found.", error.userMessage)
    }

    @Test
    fun reactionFeedAndSeenMarker() = runTest {
        val (feed, feedRequest) = exchange(ok("""{"reactions":[$reaction],"next_seq":43,"has_more":false}""")) { api.reactionChanges("tok", user, afterSeq = 7) }
        feedRequest.assertRoute("GET", "conversations/$userWire/reactions", "after_seq=7&limit=200")
        assertEquals(43L, feed.nextSeq)
        val (seen, seenRequest) = exchange(ok("""{"seen_seq":9}""")) { api.markReactionsSeen("tok", user, upToSeq = 9) }
        seenRequest.assertRoute("POST", "conversations/$userWire/reactions/seen")
        seenRequest.assertJson("""{"up_to_seq":9}""")
        assertEquals(9L, seen.seenSeq)
    }

    // ---- Notifications and mutes (NotificationsService.swift) ----

    @Test
    fun notificationSettingsSendOnlyWhatIsSet() = runTest {
        val (settings, get) = exchange(ok(notificationSettings)) { api.notificationSettings("tok") }
        get.assertRoute("GET", "notifications/settings")
        assertEquals("default", settings.sound)
        val (_, put) = exchange(ok(notificationSettings.replace("\"reactions\":true", "\"reactions\":false"))) {
            api.updateNotificationSettings("tok", NotificationSettingsPatch(reactions = false))
        }
        put.assertRoute("PUT", "notifications/settings")
        // NotificationPayloadTests.testPatchEncodesOnlyWhatIsSet: exactly one key.
        put.assertJson("""{"reactions":false}""")
    }

    @Test
    fun muteSendsSecondsOrAnExplicitNull() = runTest {
        val answer = """{"peer_user_id":"$userWire","mute":{"until":null}}"""
        val (forever, put) = exchange(ok(answer)) { api.muteChat("tok", user, null) }
        put.assertRoute("PUT", "conversations/$userWire/mute")
        // `MuteChatBody(seconds: nil)` encodes `"seconds": null` (NotificationPayloadTests.swift:148-149).
        assertEquals("""{"seconds":null}""", put.body!!.utf8())
        assertEquals("application/json", put.headers["Content-Type"])
        assertNull(forever.mute.until)
        val (_, hour) = exchange(ok(answer)) { api.muteChat("tok", user, 3_600) }
        assertEquals("""{"seconds":3600}""", hour.body!!.utf8())
        val (_, unmute) = exchange(noContent) { api.unmuteChat("tok", user) }
        unmute.assertRoute("DELETE", "conversations/$userWire/mute")
        assertEquals("""{"seconds":null}""", json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), ShroudApi.muteChatBody(null)))
    }

    @Test
    fun testPushAndUnifiedPushSubscription() = runTest {
        val (outcome, test) = exchange(ok("""{"channel":"unifiedpush","status":"sent"}""")) { api.sendTestPush("tok") }
        test.assertRoute("POST", "push/test")
        test.assertNoBody()
        assertEquals("sent", outcome.status)
        val (key, keyRequest) = exchange(ok("""{"public_key":"BKey"}""")) { api.webPushKey("tok") }
        keyRequest.assertRoute("GET", "push/web/key")
        assertEquals("BKey", key.publicKey)
        val body = WebPushSubscriptionBody("https://ntfy.sh/upAbc?up=1", WebPushSubscriptionBody.Keys("BPub", "c2VjcmV0"))
        val (_, subscribe) = exchange(noContent) { api.putWebPushSubscription("tok", body) }
        subscribe.assertRoute("PUT", "push/web/subscription")
        subscribe.assertJson("""{"endpoint":"https://ntfy.sh/upAbc?up=1","keys":{"p256dh":"BPub","auth":"c2VjcmV0"},"client":"android"}""")
        val (_, unsubscribe) = exchange(noContent) { api.deleteWebPushSubscription("tok") }
        unsubscribe.assertRoute("DELETE", "push/web/subscription")
        unsubscribe.assertToken()
        // No Web Push on this server: a 404 the caller can tell apart.
        server.enqueue(ok("""{"error":{"code":"NOT_FOUND","message":"Web Push is not available on this server."}}""", 404))
        assertTrue((runCatching { api.webPushKey("tok") }.exceptionOrNull() as ApiError).isNotFound)
    }

    // ---- Privacy (PrivacyService.swift) ----

    @Test
    fun privacySettingsSendOnlyTheChangedSwitch() = runTest {
        val (settings, get) = exchange(ok(privacy)) { api.privacySettings("tok") }
        get.assertRoute("GET", "privacy/settings")
        assertFalse(settings.sharePresence)
        val (_, put) = exchange(ok(privacy)) { api.updatePrivacySettings("tok", UpdatePrivacySettingsBody(sharePresence = false)) }
        put.assertRoute("PUT", "privacy/settings")
        put.assertJson("""{"share_presence":false}""")
    }

    // ---- Media (MediaService.swift) ----

    @Test
    fun mediaUploadAndDownload() = runTest {
        val (upload, create) = exchange(ok("""{"media_object_id":"$userWire","upload_url":"media/$userWire/content","object_key":"k","expires_at":"2026-09-24T13:00:00Z"}""", 201)) {
            api.createMediaUpload("tok", sizeBytes = 1_234)
        }
        create.assertRoute("POST", "media/uploads")
        create.assertJson("""{"size_bytes":1234,"content_type":"application/octet-stream"}""")
        assertEquals(user, upload.mediaObjectId)

        val sealed = ByteArray(100_000) { (it % 13).toByte() }
        val progress = mutableListOf<Double>()
        // A body without a type still goes as octet-stream (MediaService.swift:57).
        val (_, put) = exchange(noContent) { api.uploadMediaContent("tok", user, sealed.toRequestBody(null)) { synchronized(progress) { progress += it } } }
        put.assertRoute("PUT", "media/$userWire/content")
        put.assertToken()
        assertEquals("application/octet-stream", put.headers["Content-Type"])
        assertArrayEquals(sealed, put.body!!.toByteArray())
        assertEquals(1.0, synchronized(progress) { progress.last() }, 0.0)

        val (bytes, get) = exchange(MockResponse.Builder().code(200).body(Buffer().write(sealed)).build()) { api.downloadMediaContent("tok", user) }
        get.assertRoute("GET", "media/$userWire/content")
        assertArrayEquals(sealed, bytes)
        val target = File(temp.root, "video.sealed")
        val (written, toFile) = exchange(MockResponse.Builder().code(200).body(Buffer().write(sealed)).build()) { api.downloadMediaContentTo("tok", user, target) }
        toFile.assertRoute("GET", "media/$userWire/content")
        assertEquals(sealed.size.toLong(), written)
        assertArrayEquals(sealed, target.readBytes())
    }

    @Test
    fun anUploadOverTwoGibibytesFailsBeforeAnyRequestWithTheIosText() = runTest {
        val tooLarge = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = MAX_SEALED_MEDIA_BYTES + 1
            override fun writeTo(sink: BufferedSink) = fail("nothing may be sent")
        }
        val error = runCatching { api.uploadMediaContent("tok", user, tooLarge) }.exceptionOrNull() as ApiError.Server
        assertEquals(ErrorCodes.VALIDATION_ERROR, error.code)
        assertEquals(400, error.status)
        assertEquals("This media is too large after encryption (2048 MB). Try a shorter video or lower photo quality.", error.userMessage)
        assertEquals(0, server.requestCount)
        assertEquals(2_147_483_648L, MAX_SEALED_MEDIA_BYTES)
    }

    // ---- Presence (ContactsService.swift:90-96) ----

    @Test
    fun presence() = runTest {
        val (presence, request) = exchange(ok("""{"user_id":"$userWire","online":true}""")) { api.presence("tok", user) }
        request.assertRoute("GET", "presence/$userWire")
        assertTrue(presence.online)
    }

    // ---- Calls (CallsService.swift) ----

    @Test
    fun callLifecycle() = runTest {
        val callWire = "55555555-5555-5555-5555-555555555555"
        val callId = UUID.fromString(callWire)
        val (ice, iceRequest) = exchange(ok("""{"ice_servers":[{"urls":"stun:stun.example:3478"},{"urls":["turn:t.example"],"username":"1:u","credential":"c"}]}""")) { api.iceServers("tok") }
        iceRequest.assertRoute("GET", "calls/ice-servers")
        assertEquals(listOf("stun:stun.example:3478"), ice.first().urls)
        val (placed, place) = exchange(ok(call, 201)) { api.createCall("tok", user, CallModality.Video) }
        place.assertRoute("POST", "calls")
        place.assertJson("""{"peer_user_id":"$userWire","modality":"video","protocol":2}""")
        assertEquals(CallStatus.Ringing, placed.callStatus)
        val (_, read) = exchange(ok(call)) { api.call("tok", callId) }
        read.assertRoute("GET", "calls/$callWire")
        // Accept needs a JSON body: the handler takes Json<AcceptCallRequest> and answers 415 without one.
        val (_, accept) = exchange(ok(call.replace("ringing", "active"))) { api.acceptCall("tok", callId) }
        accept.assertRoute("POST", "calls/$callWire/accept")
        accept.assertJson("{}")
        val (_, reject) = exchange(ok(call)) { api.rejectCall("tok", callId) }
        val (_, hangup) = exchange(ok(call)) { api.hangupCall("tok", callId) }
        val (_, heartbeat) = exchange(ok(call)) { api.callHeartbeat("tok", callId) }
        for ((suffix, request) in listOf("reject" to reject, "hangup" to hangup, "heartbeat" to heartbeat)) {
            request.assertRoute("POST", "calls/$callWire/$suffix")
            request.assertNoBody()
        }
        val (_, signal) = exchange(noContent) { api.callSignal("tok", callId, CallSignalType.SDP_OFFER, "c1.YWJj") }
        signal.assertRoute("POST", "calls/$callWire/signal")
        signal.assertJson("""{"signal_type":"sdp_offer","payload":"c1.YWJj"}""")
    }

    @Test
    fun callHistoryPagesWithTheRawCreatedAt() = runTest {
        val (calls, first) = exchange(ok("""{"calls":[$call]}""")) { api.callHistory("tok") }
        first.assertRoute("GET", "calls", "limit=50")
        val before = calls.single().createdAtWire
        assertEquals("2026-09-30T09:41:00.5Z", before)
        val (_, older) = exchange(ok("""{"calls":[]}""")) { api.callHistory("tok", limit = 20, before = before) }
        assertEquals(before, older.url.queryParameter("before"))
        assertEquals("20", older.url.queryParameter("limit"))
    }

    @Test
    fun patchJsonLeavesUnsetFieldsOut() {
        assertEquals("{}", api.patchJson.encodeToString(NotificationSettingsPatch.serializer(), NotificationSettingsPatch()))
        assertEquals("""{"send_typing":true}""", api.patchJson.encodeToString(UpdatePrivacySettingsBody.serializer(), UpdatePrivacySettingsBody(sendTyping = true)))
    }
}
