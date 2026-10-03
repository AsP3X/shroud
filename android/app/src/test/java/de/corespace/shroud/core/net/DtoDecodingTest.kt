package de.corespace.shroud.core.net

import de.corespace.shroud.core.net.wire.ApiTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * The wire DTOs against the server's shapes (api-realtime §5, §13 row *DtoDecodingTest*). Vectors
 * are the iOS tests' JSON verbatim: `AuthModelsTests`, `DeviceModelsTests`, `HistoryCursorTests`,
 * `ChatDeleteModelsTests`, `NotificationPayloadTests`, `CallHistoryTests`, `MessageReactionTests`.
 */
class DtoDecodingTest {
    /** The container's API Json (AppContainer; api-realtime §2.7). */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    /** `ShroudApi.patchJson` (W1-NET): partial updates leave unset fields out. */
    private val patchJson = Json(from = json) { explicitNulls = false }

    private fun <T> decode(serializer: KSerializer<T>, text: String): T = json.decodeFromString(serializer, text)

    private fun <T> encodeObject(json: Json, serializer: KSerializer<T>, value: T): JsonObject =
        json.parseToJsonElement(json.encodeToString(serializer, value)).jsonObject

    private fun uuid(text: String): UUID = UUID.fromString(text)

    // ---- Auth (AuthModelsTests) ----

    @Test
    fun authSessionResponseDecodesSnakeCaseFields() {
        // AuthModelsTests.swift:7-26.
        val text = """
        {
          "token": "opaque-token-value",
          "user": {
            "id": "11111111-1111-1111-1111-111111111111",
            "username": "alice",
            "share_code": "ABCD234567"
          },
          "device": { "id": "22222222-2222-2222-2222-222222222222", "sealed_name": "c2VhbGVk" }
        }
        """
        val decoded = decode(AuthSessionResponse.serializer(), text)
        assertEquals("opaque-token-value", decoded.token)
        assertEquals("alice", decoded.user.username)
        assertEquals("ABCD234567", decoded.user.shareCode)
        assertEquals("c2VhbGVk", decoded.device.sealedName)
        assertEquals("11111111-1111-1111-1111-111111111111", decoded.user.id.toString())
        assertEquals(uuid("22222222-2222-2222-2222-222222222222"), decoded.device.id)
    }

    @Test
    fun meResponseWithoutShareCodeOrSealedName() {
        val me = decode(MeResponse.serializer(), """{"user":{"id":"11111111-1111-1111-1111-111111111111","username":"alice"},"device":{"id":"22222222-2222-2222-2222-222222222222"}}""")
        assertNull(me.user.shareCode)
        assertNull(me.device.sealedName)
    }

    @Test
    fun loginSendsAnExplicitNullDeviceIdAndLowerCaseIds() {
        // The container Json writes null on purpose (iOS LoginRequest encodes `device_id: null`).
        val fresh = encodeObject(json, LoginRequest.serializer(), LoginRequest("alice", "pw", null))
        assertEquals(setOf("username_hash", "password", "device_id"), fresh.keys)
        assertEquals(JsonNull, fresh["device_id"])
        val again = encodeObject(json, LoginRequest.serializer(), LoginRequest("alice", "pw", uuid("ABCDEF01-2345-4678-9ABC-DEF012345678")))
        assertEquals("abcdef01-2345-4678-9abc-def012345678", again["device_id"]!!.jsonPrimitive.content)
    }

    // ---- Devices (DeviceModelsTests) ----

    @Test
    fun devicesListDecodesServerShape() {
        // DeviceModelsTests.swift:7-36: the server omits `sealed_name` and `last_seen_at` when null.
        val text = """
        {
          "devices": [
            {
              "id": "11111111-1111-1111-1111-111111111111",
              "sealed_name": "c2VhbGVk",
              "created_at": "2026-09-20T10:15:00.123456Z",
              "last_seen_at": "2026-09-23T08:00:00Z",
              "is_current": true
            },
            {
              "id": "22222222-2222-2222-2222-222222222222",
              "created_at": "2026-09-21T10:15:00Z",
              "is_current": false
            }
          ]
        }
        """
        val decoded = decode(DevicesResponse.serializer(), text)
        assertEquals(2, decoded.devices.size)
        assertTrue(decoded.devices[0].isCurrent)
        assertEquals(Instant.parse("2026-09-23T08:00:00Z"), decoded.devices[0].lastSeenAt)
        assertEquals("c2VhbGVk", decoded.devices[0].sealedName)
        assertEquals(Instant.parse("2026-09-20T10:15:00.123456Z"), decoded.devices[0].createdAt)
        assertNull(decoded.devices[1].sealedName)
        assertNull(decoded.devices[1].lastSeenAt)
        assertFalse(decoded.devices[1].isCurrent)
        assertEquals(5, DEVICE_LIMIT)
    }

    @Test
    fun putDeviceNameBody() {
        assertEquals("""{"sealed_name":"c2VhbGVk"}""", json.encodeToString(PutDeviceNameRequest.serializer(), PutDeviceNameRequest("c2VhbGVk")))
    }

    // ---- Keys ----

    @Test
    fun keyStatusDefaultsAndIdentity() {
        val status = decode(KeyStatusResponse.serializer(), """{"device_id":"22222222-2222-2222-2222-222222222222","has_identity":false}""")
        assertFalse(status.hasIdentity)
        assertNull(status.signedPreKeyId)
        assertEquals(0L, status.otpkCount)
        val identity = decode(
            IdentityKeyResponse.serializer(),
            """{"user_id":"11111111-1111-1111-1111-111111111111","device_id":"22222222-2222-2222-2222-222222222222","registration_id":7,"identity_key":"mTuaX8n1TBpa7jCzFg2HyYcLPjB5ZW7YJ8YUtlmaayQ="}""",
        )
        assertEquals(7, identity.registrationId)
        assertEquals(uuid("11111111-1111-1111-1111-111111111111"), identity.userId)
    }

    @Test
    fun peerBundlesDecodeWithAndWithoutAOneTimeKey() {
        val text = """{"user_id":"11111111-1111-1111-1111-111111111111","bundles":[
            {"device_id":"22222222-2222-2222-2222-222222222222","registration_id":1,"identity_key":"a",
             "signed_pre_key":{"key_id":2,"public_key":"b","signature":"c"},"one_time_pre_key":{"key_id":3,"public_key":"d"}},
            {"device_id":"33333333-3333-3333-3333-333333333333","registration_id":4,"identity_key":"e",
             "signed_pre_key":{"key_id":5,"public_key":"f","signature":"g"},"one_time_pre_key":null}]}"""
        val bundles = decode(PeerKeyBundlesResponse.serializer(), text).bundles
        assertEquals(3, bundles[0].oneTimePreKey?.keyId)
        assertNull(bundles[1].oneTimePreKey)
    }

    // ---- Messages (HistoryCursorTests) ----

    @Test
    fun messageCreatedAtWireIsTheServerText() {
        // HistoryCursorTests.swift:8-27. iOS writes `UUID().uuidString` — upper case.
        val created = "2026-09-24T12:00:00.123456Z"
        val id = "6BA7B810-9DAD-11D1-80B4-00C04FD430C8"
        val text = """
        {
          "id": "$id",
          "conversation_id": "${UUID.randomUUID().toString().uppercase()}",
          "sender_user_id": "${UUID.randomUUID().toString().uppercase()}",
          "sender_device_id": "${UUID.randomUUID().toString().uppercase()}",
          "client_message_id": "${UUID.randomUUID().toString().uppercase()}",
          "content_type": "text",
          "ciphertext": null,
          "deleted_for_everyone": false,
          "created_at": "$created"
        }
        """
        val dto = decode(MessageDto.serializer(), text)
        assertEquals(created, dto.createdAtWire)
        // iOS keeps milliseconds only ("…00.123Z"); Android keeps the microseconds.
        assertEquals(Instant.parse("2026-09-24T12:00:00Z").plusNanos(123_456_000), dto.createdAt)
        assertEquals(created, ApiTime.format(dto.createdAt))
        assertEquals("6ba7b810-9dad-11d1-80b4-00c04fd430c8", dto.id.toString())
        assertNull(dto.ciphertext)
        assertNull(dto.mediaObjectId)
        assertNull(dto.delivered)
        assertNull(dto.read)
        assertNull(dto.reactions)
    }

    @Test
    fun messageWithAnInvalidDateFailsTheDecode() {
        // MessageModels.swift:157-164: dataCorruptedError.
        val text = message(createdAt = "yesterday")
        assertThrows(SerializationException::class.java) { decode(MessageDto.serializer(), text) }
    }

    @Test
    fun messageWithAMalformedIdFailsTheDecode() {
        // UUID(uuidString:) is strict; so is UuidSerializer (UUID.fromString would take "1-1-1-1-1").
        assertThrows(SerializationException::class.java) { decode(MessageDto.serializer(), message(id = "1-1-1-1-1")) }
        assertThrows(SerializationException::class.java) { decode(MessageDto.serializer(), message(id = "not-a-uuid")) }
    }

    @Test
    fun historyPageWithReceiptsReactionsAndNoHasMore() {
        val page = """{"conversation_id":"44444444-4444-4444-4444-444444444444","messages":[${message(extra = ""","delivered":true,"read":false,"media_object_id":"55555555-5555-5555-5555-555555555555","reactions":[{"message_id":"7c9e6679-7425-40de-944b-e07fc1f90ae7","user_id":"3f2504e0-4f89-41d3-9a0c-0305e82c3301","ciphertext":"aGVhcnQ=","seq":43,"updated_at":"2026-09-23T21:08:36Z"}]""")}],"reaction_seq":43}"""
        val decoded = decode(ListMessagesResponse.serializer(), page)
        assertNull(decoded.hasMore)
        assertEquals(43L, decoded.reactionSeq)
        val first = decoded.messages.single()
        assertEquals(true, first.delivered)
        assertEquals(false, first.read)
        assertEquals(uuid("55555555-5555-5555-5555-555555555555"), first.mediaObjectId)
        assertEquals("aGVhcnQ=", first.reactions!!.single().ciphertext)
        // A page from a server without reactions: no `reaction_seq`, `has_more` present.
        val old = decode(ListMessagesResponse.serializer(), """{"messages":[],"has_more":false}""")
        assertNull(old.conversationId)
        assertEquals(false, old.hasMore)
        assertNull(old.reactionSeq)
    }

    @Test
    fun sendMessageRequestWritesLowerCaseIdsAndTheContentType() {
        val body = encodeObject(
            json,
            SendMessageRequest.serializer(),
            SendMessageRequest(
                peerUserId = uuid("ABCDEF01-2345-4678-9ABC-DEF012345678"),
                clientMessageId = uuid("6BA7B810-9DAD-11D1-80B4-00C04FD430C8"),
                ciphertext = "c2VhbGVk",
            ),
        )
        assertEquals("abcdef01-2345-4678-9abc-def012345678", body["peer_user_id"]!!.jsonPrimitive.content)
        assertEquals("6ba7b810-9dad-11d1-80b4-00c04fd430c8", body["client_message_id"]!!.jsonPrimitive.content)
        assertEquals(ContentType.TEXT, body["content_type"]!!.jsonPrimitive.content)
        assertEquals("c2VhbGVk", body["ciphertext"]!!.jsonPrimitive.content)
    }

    @Test
    fun markReadBulk() {
        val body = encodeObject(json, MarkReadBulkBody.serializer(), MarkReadBulkBody(uuid("11111111-1111-1111-1111-111111111111"), uuid("22222222-2222-2222-2222-222222222222")))
        assertEquals(setOf("peer_user_id", "up_to_message_id"), body.keys)
        val answer = decode(MarkReadBulkResponse.serializer(), """{"marked":4,"read_at":"2026-09-24T12:00:00.123456Z"}""")
        assertEquals(4L, answer.marked)
        assertEquals(Instant.parse("2026-09-24T12:00:00.123456Z"), answer.readAt)
    }

    // ---- Chat delete, privacy, blocks (ChatDeleteModelsTests) ----

    @Test
    fun deleteConversationResponseDecodesSnakeCaseFields() {
        // ChatDeleteModelsTests.swift:10-26.
        val text = """
        {
          "cleared_for_me": true,
          "cleared_for_peer": false,
          "tombstoned": 3,
          "contact_removed": true
        }
        """
        val decoded = decode(DeleteConversationResponse.serializer(), text)
        assertTrue(decoded.clearedForMe)
        assertFalse(decoded.clearedForPeer)
        assertEquals(3L, decoded.tombstoned)
        assertTrue(decoded.contactRemoved)
        // Today's server no longer sends `contact_removed`.
        assertFalse(decode(DeleteConversationResponse.serializer(), """{"cleared_for_me":true,"cleared_for_peer":true,"tombstoned":0}""").contactRemoved)
    }

    @Test
    fun deleteScopesMatchServerQueryValues() {
        // ChatDeleteModelsTests.swift:29-32.
        assertEquals("me", ConversationDeleteScope.Me.wire)
        assertEquals("everyone", ConversationDeleteScope.Everyone.wire)
        assertEquals("me", MessageDeleteScope.Me.wire)
        assertEquals("everyone", MessageDeleteScope.Everyone.wire)
    }

    @Test
    fun privacySettingsRoundTripsThroughTheApiCoders() {
        // ChatDeleteModelsTests.swift:35-47.
        val decoded = decode(PrivacySettingsDto.serializer(), """
        { "allow_peer_chat_delete": true }
        """)
        assertTrue(decoded.allowPeerChatDelete)
        val body = patchJson.encodeToString(UpdatePrivacySettingsBody.serializer(), UpdatePrivacySettingsBody(allowPeerChatDelete = false))
        assertTrue(body.contains("allow_peer_chat_delete"))
        assertTrue(body.contains("false"))
    }

    @Test
    fun visibilitySwitchesDecodeAndDefaultToOnForOlderServers() {
        // ChatDeleteModelsTests.swift:50-65.
        val current = decode(PrivacySettingsDto.serializer(), """
        { "allow_peer_chat_delete": false, "send_read_receipts": false, "send_typing": true, "share_presence": false }
        """)
        assertFalse(current.sendReadReceipts)
        assertTrue(current.sendTyping)
        assertFalse(current.sharePresence)
        // A server from before the switches enforces none of them, which is "all on".
        val older = decode(PrivacySettingsDto.serializer(), """{ "allow_peer_chat_delete": true }""")
        assertTrue(older.sendReadReceipts && older.sendTyping && older.sharePresence && older.discoverableByUsername)
        // MessagingController.swift:543 resets to this: everything visible.
        assertEquals(older.copy(allowPeerChatDelete = false), PrivacySettingsDto(allowPeerChatDelete = false))
    }

    @Test
    fun discoverabilityAndNewShareCodeDecode() {
        // ChatDeleteModelsTests.swift:68-76.
        val settings = decode(PrivacySettingsDto.serializer(), """{ "allow_peer_chat_delete": false, "discoverable_by_username": false }""")
        assertFalse(settings.discoverableByUsername)
        val rotated = decode(ShareCodeResponse.serializer(), """{ "share_code": "ABCD234567" }""")
        assertEquals("ABCD234567", rotated.shareCode)
    }

    @Test
    fun privacyUpdateSendsOnlyTheChangedSwitch() {
        // ChatDeleteModelsTests.swift:79-84.
        val body = encodeObject(patchJson, UpdatePrivacySettingsBody.serializer(), UpdatePrivacySettingsBody(sharePresence = false))
        assertEquals(1, body.size)
        assertEquals(false, body["share_presence"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun blockListDecodesUserIdAndTimestamp() {
        // ChatDeleteModelsTests.swift:87-106.
        val text = """
        {
          "blocks": [
            {
              "user_id": "33333333-3333-3333-3333-333333333333",
              "username": "mallory",
              "created_at": "2026-08-07T10:11:12Z"
            }
          ]
        }
        """
        val first = decode(BlocksResponse.serializer(), text).blocks.first()
        assertEquals("mallory", first.username)
        assertEquals("33333333-3333-3333-3333-333333333333", first.userId.toString())
        assertEquals(Instant.parse("2026-08-07T10:11:12Z"), first.createdAt)
    }

    // ---- Conversations and notifications (NotificationPayloadTests) ----

    @Test
    fun conversationDecodesUnreadCountAndMute() {
        // NotificationPayloadTests.swift:170-195 (`UUID().uuidString` is upper case).
        val text = """
        {"id":"${UUID.randomUUID().toString().uppercase()}","peer":{"id":"${UUID.randomUUID().toString().uppercase()}","username":"alice"},
         "created_at":"2026-09-24T10:00:00Z","last_message_at":"2026-09-24T11:00:00.123456Z",
         "reaction_seq":0,"unseen_reactions":0,"unread_count":3,
         "mute":{"until":"2026-09-24T19:00:00.5Z"}}
        """
        val item = decode(ConversationItemDto.serializer(), text)
        assertEquals(3, item.unreadCount)
        assertEquals(Instant.parse("2026-09-24T19:00:00.500Z"), item.mute?.until)
        assertEquals(Instant.parse("2026-09-24T11:00:00.123456Z"), item.lastMessageAt)
        assertEquals(0L, item.reactionSeq)
        assertEquals(0, item.unseenReactions)
        val unmuted = decode(
            ConversationItemDto.serializer(),
            text.replace(""""mute":{"until":"2026-09-24T19:00:00.5Z"}""", """"mute":null"""),
        )
        assertNull(unmuted.mute)
        // Older servers send neither.
        val old = decode(
            ConversationItemDto.serializer(),
            """
            {"id":"${UUID.randomUUID().toString().uppercase()}","peer":{"id":"${UUID.randomUUID().toString().uppercase()}","username":"bob"},
             "created_at":"2026-09-24T10:00:00Z"}
            """,
        )
        assertNull(old.unreadCount)
        assertNull(old.mute)
        assertNull(old.reactionSeq)
        assertNull(old.unseenReactions)
        assertNull(old.lastMessageAt)
    }

    @Test
    fun aMuteWithoutAnEndIsForeverAndAPassedOneIsOver() {
        // NotificationModels.swift:56-65.
        val now = Instant.ofEpochSecond(1_790_000_000)
        val forever = decode(ChatMuteDto.serializer(), """{"until":null}""")
        assertNull(forever.until)
        assertTrue(forever.isActive(now))
        assertTrue(decode(ChatMuteDto.serializer(), "{}").isActive(now))
        assertFalse(ChatMuteDto(now.minusSeconds(60)).isActive(now))
        assertFalse(ChatMuteDto(now).isActive(now))
        assertTrue(ChatMuteDto(now.plusSeconds(1)).isActive(now))
        val response = decode(MuteChatResponse.serializer(), """{"peer_user_id":"11111111-1111-1111-1111-111111111111","mute":{"until":"2026-09-24T19:00:00Z"}}""")
        assertEquals(uuid("11111111-1111-1111-1111-111111111111"), response.peerUserId)
    }

    @Test
    fun patchEncodesOnlyWhatIsSet() {
        // NotificationPayloadTests.swift:143-150. (Its MuteChatBody half is the hand encoder of W1-NET.)
        val body = encodeObject(patchJson, NotificationSettingsPatch.serializer(), NotificationSettingsPatch(reactions = false))
        assertEquals(1, body.size)
        assertEquals(false, body["reactions"]!!.jsonPrimitive.boolean)
        val full = encodeObject(patchJson, NotificationSettingsPatch.serializer(), NotificationSettingsPatch(enabled = true, sound = "default", badgeIncludesMuted = false))
        assertEquals(setOf("enabled", "sound", "badge_includes_muted"), full.keys)
    }

    @Test
    fun notificationSettingsAndTheTestPushOutcome() {
        val settings = decode(
            NotificationSettingsDto.serializer(),
            """{"enabled":true,"show_sender":false,"reactions":true,"contact_requests":true,"sound":"chime","badge":true,"badge_includes_muted":false}""",
        )
        assertFalse(settings.showSender)
        assertEquals("chime", settings.sound)
        val outcome = decode(TestPushOutcomeDto.serializer(), """{"channel":"unifiedpush","status":"sent"}""")
        assertEquals("unifiedpush", outcome.channel)
        assertEquals("sent", outcome.status)
        assertNull(outcome.detail)
        val none = decode(TestPushOutcomeDto.serializer(), """{"status":"not_registered","detail":"no subscription"}""")
        assertNull(none.channel)
        assertEquals("no subscription", none.detail)
    }

    @Test
    fun readMarkersPresenceAndConfig() {
        val read = decode(MarkChatReadResponse.serializer(), """{"read_at":null,"unread_count":0,"receipts":0}""")
        assertNull(read.readAt)
        val marked = decode(MarkChatReadResponse.serializer(), """{"read_at":"2026-09-24T12:00:00.123456Z","unread_count":2,"receipts":5}""")
        assertEquals(2, marked.unreadCount)
        assertEquals(5L, marked.receipts)
        // Whole seconds parse too (iOS's `apiFlexible` drops these, MessagingController.swift:5882-5886).
        val presence = decode(PresenceDto.serializer(), """{"user_id":"11111111-1111-1111-1111-111111111111","online":false,"last_seen_at":"2026-07-15T12:00:00Z"}""")
        assertEquals(Instant.parse("2026-07-15T12:00:00Z"), presence.lastSeenAt)
        assertNull(decode(PresenceDto.serializer(), """{"user_id":"11111111-1111-1111-1111-111111111111","online":true}""").lastSeenAt)
        assertEquals(20, decode(ClientConfigDto.serializer(), """{"reactions":{"max_per_user":20}}""").reactions.maxPerUser)
    }

    // ---- Reactions (MessageReactionTests vectors; the status mapping is W1-NET's) ----

    @Test
    fun reactionConflictBodyDecodesItsCurrentRecord() {
        // MessageReactionTests.swift:131 — the error envelope beside `current` is ignored.
        val conflict = """{"error":{"code":"REACTION_CHANGED","message":"Your reaction changed on another device."},"current":{"message_id":"7c9e6679-7425-40de-944b-e07fc1f90ae7","user_id":"3f2504e0-4f89-41d3-9a0c-0305e82c3301","ciphertext":null,"seq":42,"updated_at":"2026-09-23T21:08:35.759754Z"}}"""
        val current = decode(ReactionConflictDto.serializer(), conflict).current
        assertEquals(42L, current.seq)
        assertNull(current.ciphertext)
        assertEquals(uuid("7c9e6679-7425-40de-944b-e07fc1f90ae7"), current.messageId)
        assertEquals(Instant.parse("2026-09-23T21:08:35.759754Z"), current.updatedAt)
        // MessageReactionTests.swift:139.
        val saved = """{"message_id":"7c9e6679-7425-40de-944b-e07fc1f90ae7","user_id":"3f2504e0-4f89-41d3-9a0c-0305e82c3301","ciphertext":"aGVhcnQ=","seq":43,"updated_at":"2026-09-23T21:08:36Z"}"""
        val dto = decode(ReactionDto.serializer(), saved)
        assertEquals(43L, dto.seq)
        assertEquals("aGVhcnQ=", dto.ciphertext)
        // A 409 without `current` is not a conflict body.
        assertThrows(SerializationException::class.java) {
            decode(ReactionConflictDto.serializer(), """{"error":{"code":"REACTION_CHANGED","message":"Try again."}}""")
        }
    }

    @Test
    fun reactionBodiesAndTheCatchUpFeed() {
        val put = encodeObject(json, PutReactionBody.serializer(), PutReactionBody("aGVhcnQ=", 42, true))
        assertEquals(setOf("ciphertext", "base_seq", "added"), put.keys)
        val feed = decode(ReactionChangesResponse.serializer(), """{"reactions":[],"next_seq":42,"has_more":false}""")
        assertEquals(42L, feed.nextSeq)
        assertFalse(feed.hasMore)
        assertEquals("""{"up_to_seq":42}""", json.encodeToString(MarkReactionsSeenBody.serializer(), MarkReactionsSeenBody(42)))
        assertEquals(42L, decode(MarkReactionsSeenResponse.serializer(), """{"seen_seq":42}""").seenSeq)
    }

    // ---- Users and contacts ----

    @Test
    fun contactRequestsWithAndWithoutTheRequesterCard() {
        val text = """{"requests":[
            {"id":"11111111-1111-1111-1111-111111111111","from_user_id":"22222222-2222-2222-2222-222222222222","to_user_id":"33333333-3333-3333-3333-333333333333","status":"pending","created_at":"2026-09-24T10:00:00.123456Z","responded_at":null,"user":{"id":"22222222-2222-2222-2222-222222222222","username":"alice"}},
            {"id":"44444444-4444-4444-4444-444444444444","from_user_id":"22222222-2222-2222-2222-222222222222","to_user_id":"33333333-3333-3333-3333-333333333333","status":"rejected","created_at":"2026-09-24T10:00:00Z","responded_at":"2026-09-24T11:00:00Z"}]}"""
        val requests = decode(ContactRequestsResponse.serializer(), text).requests
        assertEquals(ContactRequestStatus.PENDING, requests[0].status)
        assertEquals("alice", requests[0].user?.username)
        assertNull(requests[0].user?.shareCode)
        assertNull(requests[0].respondedAt)
        assertEquals(ContactRequestStatus.REJECTED, requests[1].status)
        assertNull(requests[1].user)
        assertEquals(Instant.parse("2026-09-24T11:00:00Z"), requests[1].respondedAt)
        val contacts = decode(ContactsResponse.serializer(), """{"contacts":[{"user_id":"22222222-2222-2222-2222-222222222222","username":"alice","created_at":"2026-09-24T10:00:00Z"}]}""")
        assertEquals("alice", contacts.contacts.single().username)
        val card = decode(UserCardDto.serializer(), """{"id":"22222222-2222-2222-2222-222222222222","username":"alice","share_code":"ABCD234567"}""")
        assertEquals("ABCD234567", card.shareCode)
        assertEquals(
            """{"user_id":"abcdef01-2345-4678-9abc-def012345678"}""",
            json.encodeToString(UserIdBody.serializer(), UserIdBody(uuid("ABCDEF01-2345-4678-9ABC-DEF012345678"))),
        )
    }

    // ---- Media ----

    @Test
    fun mediaUploadBodies() {
        val request = encodeObject(json, CreateMediaUploadRequest.serializer(), CreateMediaUploadRequest(1234))
        assertEquals(1234L, request["size_bytes"]!!.jsonPrimitive.content.toLong())
        assertEquals("application/octet-stream", request["content_type"]!!.jsonPrimitive.content)
        val response = decode(
            CreateMediaUploadResponse.serializer(),
            """{"media_object_id":"55555555-5555-5555-5555-555555555555","upload_url":"media/55555555-5555-5555-5555-555555555555/content","object_key":"k","expires_at":"2026-09-24T12:15:00.5Z"}""",
        )
        assertEquals(uuid("55555555-5555-5555-5555-555555555555"), response.mediaObjectId)
        assertEquals("media/55555555-5555-5555-5555-555555555555/content", response.uploadUrl)
        assertEquals(2_147_483_648L, MAX_SEALED_MEDIA_BYTES)
    }

    // ---- Calls (CallHistoryTests' JSON builder) ----

    private val me = "00000000-0000-0000-0000-00000000000a"
    private val them = "00000000-0000-0000-0000-00000000000b"
    private val myPhone = "00000000-0000-0000-0000-0000000000d1"
    private val theirPhone = "00000000-0000-0000-0000-0000000000d3"

    /** CallHistoryTests.swift:15-44. */
    private fun call(
        caller: String,
        callerDevice: String,
        callee: String,
        calleeDevice: String?,
        status: String,
        reason: String? = null,
        answered: String? = null,
        ended: String? = "2026-09-30T09:45:00.123456Z",
        calleeName: String? = "anna",
    ): CallDto {
        val fields = mutableListOf(
            """"id": "11111111-1111-1111-1111-111111111111"""",
            """"caller_user_id": "$caller"""",
            """"caller_device_id": "$callerDevice"""",
            """"caller_username": "${if (caller == me) "me" else "anna"}"""",
            """"callee_user_id": "$callee"""",
            """"modality": "video"""",
            """"status": "$status"""",
            """"protocol": 2""",
            """"created_at": "2026-09-30T09:41:00.5Z"""",
        )
        fields += calleeName?.let { """"callee_username": "$it"""" } ?: """"callee_username": null"""
        if (calleeDevice != null) fields += """"callee_device_id": "$calleeDevice""""
        if (reason != null) fields += """"ended_reason": "$reason""""
        if (answered != null) fields += """"answered_at": "$answered""""
        if (ended != null) fields += """"ended_at": "$ended""""
        return decode(CallDto.serializer(), "{" + fields.joinToString(",") + "}")
    }

    @Test
    fun callHistoryRowsDecode() {
        val outgoing = call(me, myPhone, them, theirPhone, "ended", reason = "hangup", answered = "2026-09-30T09:41:48.123456Z")
        assertEquals("2026-09-30T09:41:00.5Z", outgoing.createdAtWire)
        assertEquals(Instant.parse("2026-09-30T09:41:00.500Z"), outgoing.createdAt)
        assertEquals(Instant.parse("2026-09-30T09:41:48.123456Z"), outgoing.answeredAt)
        assertEquals(Instant.parse("2026-09-30T09:45:00.123456Z"), outgoing.endedAt)
        assertEquals(uuid(theirPhone), outgoing.calleeDeviceId)
        assertEquals(CallModality.Video, outgoing.callModality)
        assertEquals(CallStatus.Ended, outgoing.callStatus)
        assertEquals("hangup", outgoing.endedReason)
        assertEquals(2, outgoing.callProtocol)
        assertFalse(outgoing.isLive)

        val missed = call(them, theirPhone, me, null, "missed", reason = "timeout")
        assertNull(missed.calleeDeviceId)
        assertNull(missed.answeredAt)
        assertEquals(CallStatus.Missed, missed.callStatus)

        // CallHistoryTests.swift:124-130: the peer's account is gone.
        val deleted = call(me, myPhone, them, null, "cancelled", reason = "cancelled", calleeName = null)
        assertNull(deleted.calleeUsername)
        assertEquals(CallStatus.Cancelled, deleted.callStatus)

        assertTrue(call(them, theirPhone, me, null, "ringing", ended = null).isLive)
        assertTrue(call(me, myPhone, them, theirPhone, "active", answered = "2026-09-30T09:42:00Z", ended = null).isLive)
    }

    @Test
    fun unknownModalityAndStatusFallBackLikeIos() {
        // CallModels.swift:92-98.
        assertEquals(CallModality.Voice, CallModality.of("hologram"))
        assertEquals(CallStatus.Ended, CallStatus.of("exploded"))
        CallModality.entries.forEach { assertEquals(it, CallModality.of(it.wire)) }
        CallStatus.entries.forEach { assertEquals(it, CallStatus.of(it.wire)) }
    }

    @Test
    fun aLiveCallCarriesThePeersMediaState() {
        val text = """{"calls":[{"id":"11111111-1111-1111-1111-111111111111","caller_user_id":"$me","caller_device_id":"$myPhone","callee_user_id":"$them","callee_device_id":"$theirPhone","modality":"voice","status":"active","created_at":"2026-09-30T09:41:00.123456Z","peer_media_state":{"from_device_id":"$theirPhone","payload":"c2VhbGVk"}}]}"""
        val dto = decode(CallListResponse.serializer(), text).calls.single()
        assertEquals(uuid(theirPhone), dto.peerMediaState?.fromDeviceId)
        assertEquals("c2VhbGVk", dto.peerMediaState?.payload)
        assertNull(dto.callerUsername)
        assertNull(dto.callProtocol)
        assertEquals("2026-09-30T09:41:00.123456Z", dto.createdAtWire)
    }

    @Test
    fun iceServerUrlsComeAsAStringOrAnArray() {
        // CallModels.swift:133-145: a list, else one string, else [].
        val text = """{"ice_servers":[
            {"urls":"stun:stun.example.org:3478"},
            {"urls":["turn:turn.example.org:3478?transport=udp","turns:turn.example.org:5349"],"username":"1790000000:u","credential":"secret"},
            {"urls":null},
            {},
            {"urls":42},
            {"urls":[1,"turn:x"]},
            {"urls":[]}]}"""
        val servers = decode(IceServersResponse.serializer(), text).iceServers
        assertEquals(listOf("stun:stun.example.org:3478"), servers[0].urls)
        assertNull(servers[0].username)
        assertEquals(listOf("turn:turn.example.org:3478?transport=udp", "turns:turn.example.org:5349"), servers[1].urls)
        assertEquals("1790000000:u", servers[1].username)
        assertEquals("secret", servers[1].credential)
        (2..6).forEach { assertEquals("server $it", emptyList<String>(), servers[it].urls) }
    }

    @Test
    fun callRequestBodies() {
        // `protocol` is always written, whatever the Json's encodeDefaults (CallModels.swift:32-36).
        val noDefaults = Json(from = json) { encodeDefaults = false; explicitNulls = false }
        val create = encodeObject(noDefaults, CreateCallRequest.serializer(), CreateCallRequest(uuid(them), CallModality.Video.wire))
        assertEquals(setOf("peer_user_id", "modality", "protocol"), create.keys)
        assertEquals(CALL_PROTOCOL, create["protocol"]!!.jsonPrimitive.int)
        assertEquals("video", create["modality"]!!.jsonPrimitive.content)
        // AcceptCallRequest: `{}` (the server answers 415 without a JSON body).
        assertEquals("{}", json.encodeToString(EmptyBody.serializer(), EmptyBody))
        val signal = encodeObject(json, CallSignalRequest.serializer(), CallSignalRequest(CallSignalType.SDP_OFFER, "c2VhbGVk"))
        assertEquals("sdp_offer", signal["signal_type"]!!.jsonPrimitive.content)
    }

    // ---- Push (UnifiedPush over the Web Push routes) ----

    @Test
    fun webPushBodiesAlwaysSayAndroid() {
        val body = WebPushSubscriptionBody(
            endpoint = "https://ntfy.sh/upAbCdEf?up=1",
            keys = WebPushSubscriptionBody.Keys(p256dh = "BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QTpQtUbVlUls0VJXg7A8u-Ts1XbjhazAkj7I99e8QcYP7DkM", auth = "tBHItJI5svbpez7KI4CCXg"),
        )
        listOf(json, patchJson, Json(from = json) { encodeDefaults = false }).forEach { coder ->
            val encoded = encodeObject(coder, WebPushSubscriptionBody.serializer(), body)
            assertEquals(setOf("endpoint", "keys", "client"), encoded.keys)
            assertEquals("android", encoded["client"]!!.jsonPrimitive.content)
            assertEquals(setOf("p256dh", "auth"), encoded["keys"]!!.jsonObject.keys)
        }
        assertEquals("BKey", decode(WebPushKeyResponse.serializer(), """{"public_key":"BKey"}""").publicKey)
    }

    // ---- Ids and unknown keys across the board ----

    @Test
    fun unknownKeysAreIgnored() {
        val me = decode(MeResponse.serializer(), """{"user":{"id":"11111111-1111-1111-1111-111111111111","username":"alice","new_field":1},"device":{"id":"22222222-2222-2222-2222-222222222222"},"extra":[1,2]}""")
        assertEquals("alice", me.user.username)
    }

    @Test
    fun healthTolerantOfAMissingDatabase() {
        assertEquals("ok", decode(HealthResponse.serializer(), """{"status":"ok"}""").status)
        assertEquals("up", decode(HealthResponse.serializer(), """{"status":"ok","database":"up"}""").database)
    }

    /** Plan §2.1 W0-B acceptance: "no String id in any DTO". */
    @Test
    fun noStringIdInAnyDto() {
        val notUuids = setOf("key_id", "registration_id", "signed_pre_key_id")   // integer prekey / registration numbers
        val seen = mutableSetOf<String>()
        val offenders = mutableListOf<String>()
        var uuidFields = 0
        fun walk(descriptor: SerialDescriptor) {
            if (!seen.add(descriptor.serialName)) return
            for (i in 0 until descriptor.elementsCount) {
                val name = descriptor.getElementName(i)
                val element = descriptor.getElementDescriptor(i)
                val isIdName = descriptor.kind is StructureKind.CLASS && (name == "id" || name.endsWith("_id"))
                if (isIdName && name !in notUuids) {
                    if (element.serialName.removeSuffix("?") == UUID_SERIAL_NAME) uuidFields++
                    else offenders += "${descriptor.serialName}.$name: ${element.serialName}"
                }
                walk(element)
            }
        }
        allDtoSerializers.forEach { walk(it.descriptor) }
        assertTrue(offenders.joinToString(), offenders.isEmpty())
        assertTrue("only $uuidFields id fields checked", uuidFields >= 40)
        // The walk reached nested types that are not in the list (sanity).
        assertTrue(seen.toString(), "de.corespace.shroud.core.net.ClientConfigDto.Reactions" in seen)
        assertTrue(seen.toString(), "de.corespace.shroud.core.net.WebPushSubscriptionBody.Keys" in seen)
    }

    @Test
    fun everyDtoIsListedOnce() {
        // The list below names each DTO once, so the id walk covers all of them exactly.
        val names = allDtoSerializers.map { it.descriptor.serialName }
        assertEquals(names.size, names.toSet().size)
    }

    /**
     * The id walk above only sees what [allDtoSerializers] lists, so the list must hold every
     * top-level `@Serializable` type declared under `core/net/dto/` — found from the sources, not
     * by hand, so a new DTO that is not listed fails here instead of slipping past the id check.
     */
    @Test
    fun everyDtoUnderCoreNetDtoIsListed() {
        val declared = dtoSourceFiles()
            .flatMap { file -> SERIALIZABLE_DECLARATION.findAll(file.readText()).map { it.groupValues[1] } }
            .toList()
        assertTrue("found only ${declared.size} DTOs under core/net/dto", declared.size >= 60)
        val fromSources = declared.map { simpleName ->
            val type = Class.forName("de.corespace.shroud.core.net.$simpleName")
            serializer(type).descriptor.serialName
        }.toSortedSet()
        val listed = allDtoSerializers.map { it.descriptor.serialName }.toSortedSet()
        assertEquals("not listed in allDtoSerializers", emptySet<String>(), fromSources - listed)
        assertEquals("listed but not declared under core/net/dto", emptySet<String>(), listed - fromSources)
    }

    /** The `.kt` files of `core/net/dto`, from the module directory (Gradle) or the repository's `android/`. */
    private fun dtoSourceFiles(): List<File> {
        val relative = "src/main/java/de/corespace/shroud/core/net/dto"
        val dir = listOf(File(relative), File("app/$relative")).firstOrNull { it.isDirectory }
            ?: error("core/net/dto not found from ${File(".").absolutePath}")
        return dir.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }
    }

    private fun message(
        id: String = "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
        createdAt: String = "2026-09-24T12:00:00.123456Z",
        extra: String = "",
    ) = """{"id":"$id","conversation_id":"44444444-4444-4444-4444-444444444444","sender_user_id":"11111111-1111-1111-1111-111111111111","sender_device_id":"22222222-2222-2222-2222-222222222222","client_message_id":"33333333-3333-3333-3333-333333333333","content_type":"text","ciphertext":"c2VhbGVk","deleted_for_everyone":false,"created_at":"$createdAt"$extra}"""

    private companion object {
        const val UUID_SERIAL_NAME = "de.corespace.shroud.UUID"

        /** A top-level (unindented) `@Serializable` class or object; nested types are reached by the walk. */
        val SERIALIZABLE_DECLARATION = Regex(
            """(?m)^@Serializable(?:\([^)]*\))?\s+(?:@\S+\s+)*(?:(?:data|sealed|enum|value)\s+)*(?:class|object|interface)\s+(\w+)""",
        )

        val allDtoSerializers: List<KSerializer<*>> = listOf(
            // Auth
            RegisterRequest.serializer(), LoginRequest.serializer(), UserDto.serializer(), DeviceDto.serializer(),
            AuthSessionResponse.serializer(), MeResponse.serializer(), PasswordChangeRequest.serializer(),
            DeleteAccountRequest.serializer(), HealthResponse.serializer(),
            // Devices
            LinkedDeviceDto.serializer(), DevicesResponse.serializer(), PutDeviceNameRequest.serializer(),
            // Keys
            SignedPreKeyDto.serializer(), OneTimePreKeyDto.serializer(), PutKeyBundleRequest.serializer(),
            KeyStatusResponse.serializer(), IdentityKeyResponse.serializer(), PeerKeyBundleResponse.serializer(),
            PeerDeviceBundle.serializer(), PeerKeyBundlesResponse.serializer(), PostOtpkRequest.serializer(),
            // Users, contacts, blocks
            UserCardDto.serializer(), ContactRequestDto.serializer(), ContactRequestsResponse.serializer(),
            ContactItemDto.serializer(), ContactsResponse.serializer(), UserIdBody.serializer(),
            PutContactNameRequest.serializer(), ContactNamesDto.serializer(), PutContactNamesRequest.serializer(),
            ShareCodeResponse.serializer(),
            BlockItemDto.serializer(), BlocksResponse.serializer(),
            // Messages, conversations, reactions
            SendMessageRequest.serializer(), MessageDto.serializer(), ListMessagesResponse.serializer(),
            DeleteConversationResponse.serializer(), MarkReadBulkBody.serializer(), MarkReadBulkResponse.serializer(),
            ConversationPeerDto.serializer(), ConversationItemDto.serializer(), ConversationsResponse.serializer(),
            MarkChatReadResponse.serializer(), PresenceDto.serializer(), ClientConfigDto.serializer(),
            ReactionDto.serializer(), PutReactionBody.serializer(), ReactionConflictDto.serializer(),
            ReactionChangesResponse.serializer(), MarkReactionsSeenBody.serializer(), MarkReactionsSeenResponse.serializer(),
            // Media
            CreateMediaUploadRequest.serializer(), CreateMediaUploadResponse.serializer(), MediaDownloadResponse.serializer(),
            // Calls
            CreateCallRequest.serializer(), EmptyBody.serializer(), CallSignalRequest.serializer(), CallDto.serializer(),
            PeerMediaStateDto.serializer(), CallListResponse.serializer(), IceServerDto.serializer(), IceServersResponse.serializer(),
            // Notifications, privacy, push
            NotificationSettingsDto.serializer(), NotificationSettingsPatch.serializer(), ChatMuteDto.serializer(),
            MuteChatResponse.serializer(), TestPushOutcomeDto.serializer(),
            PrivacySettingsDto.serializer(), UpdatePrivacySettingsBody.serializer(),
            WebPushKeyResponse.serializer(), WebPushSubscriptionBody.serializer(),
        )
    }
}
