package de.corespace.shroud.core.net

import de.corespace.shroud.core.auth.USERNAME_KDF_WEAK
import de.corespace.shroud.core.auth.UsernameHash
import de.corespace.shroud.core.auth.UsernameKdfParams
import de.corespace.shroud.core.auth.UsernameMigration
import de.corespace.shroud.core.model.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * Every REST endpoint the app uses, typed — the single facade of plan C3 (api-realtime §6; the iOS
 * `*Service` structs under `Services/API`, `Services/Calls/CallsService.swift` and
 * `Services/Auth/AuthService.swift`). Token first; ids are [UUID]s and travel lower-case through
 * [Ids.wire] (iOS `uuidString.lowercased()` everywhere); free-text path segments go through
 * [ApiClient.pathSegment]. Endpoints added after W1 live in `core/<area>/<Area>Api.kt` as
 * extension functions on [client].
 *
 * Every call that carries a token reports its auth outcome through [ApiClient.authOutcomes].
 */
class ShroudApi(internal val client: ApiClient) {
    private val json: Json get() = client.json
    private val kdfGate = Mutex()
    private var kdfParams: UsernameKdfParams? = null
    private var kdfIsLegacy = false

    /**
     * Partial updates (`NotificationSettingsPatch`, `UpdatePrivacySettingsBody`): unset fields are
     * left out, as Swift's synthesized `encodeIfPresent` does (`NotificationPayloadTests.testPatchEncodesOnlyWhatIsSet`,
     * `ChatDeleteModelsTests.privacyUpdateSendsOnlyTheChangedSwitch`).
     */
    val patchJson: Json = Json(from = client.json) { explicitNulls = false }

    // ---- Health and config ----

    /** `GET /health` — no token (`APIClient.swift:471-475`; server `health.rs`). */
    suspend fun health(): HealthResponse = client.get("health", null, HealthResponse.serializer())

    /** `GET /config` — the reaction limit (`MessagesService.swift:71-74`). */
    suspend fun clientConfig(token: String): ClientConfigDto = client.get("config", token, ClientConfigDto.serializer())

    /**
     * `GET /client-version?platform=&version=` — no token, so it answers before sign-in and on the
     * lock screen, and never reports an auth outcome (server `routes/client_version.rs`).
     */
    suspend fun clientVersion(platform: String, version: String): ClientVersionDto =
        client.get("client-version", null, ClientVersionDto.serializer(), mapOf("platform" to platform, "version" to version))

    // ---- Auth (`AuthService.swift`) ----

    /** `POST /auth/register` (`AuthService.swift:22-35`). No token: a failure never touches a stored session. */
    suspend fun register(username: String, password: String): AuthSessionResponse {
        val name = UsernameHash.normalize(username)
        val (hash, _) = usernameHashes(name, includeLegacy = false)
        val session = client.post(
            "auth/register",
            null,
            RegisterRequest(hash, password),
            RegisterRequest.serializer(),
            AuthSessionResponse.serializer(),
        )
        UsernameMigration.remember(hash)
        return session
    }

    /**
     * `POST /auth/login` (`AuthService.swift:37-54`). [deviceId]: this phone's earlier device row on
     * the account (the anchor), or null for a new one — sent as JSON `null`. [replaceDeviceId]: the
     * device to log out when every slot is signed in, once the phrase checked out and the user agreed
     * to the `409 DEVICE_LIMIT` answer's [ApiError.deviceLimit]; null otherwise.
     */
    suspend fun login(username: String, password: String, deviceId: UUID?, replaceDeviceId: UUID? = null): AuthSessionResponse {
        val name = UsernameHash.normalize(username)
        val (hash, legacy) = usernameHashes(name, includeLegacy = true)
        val session = client.post(
            "auth/login",
            null,
            LoginRequest(hash, password, deviceId, replaceDeviceId, legacy),
            LoginRequest.serializer(),
            AuthSessionResponse.serializer(),
        )
        UsernameMigration.remember(hash)
        return session
    }

    /**
     * The digest login sends, and the SHA-256 while this install has not yet signed that
     * account in. A 404 is an old server: the SHA-256 stays the only digest.
     */
    private suspend fun usernameHashes(name: String, includeLegacy: Boolean): Pair<String, String?> {
        val params = usernameKdf() ?: return UsernameHash.digest(name) to null
        val slow = withContext(Dispatchers.Default) { UsernameHash.argon2id(name, params) }
        val legacy = if (includeLegacy && !UsernameMigration.isRemembered(slow)) UsernameHash.digest(name) else null
        return slow to legacy
    }

    private suspend fun usernameKdf(): UsernameKdfParams? {
        kdfGate.withLock {
            if (kdfIsLegacy) return null
            kdfParams?.let { return it }
        }
        val raw = client.raw("GET", "auth/username-kdf", null)
        when (raw.status) {
            404 -> {
                kdfGate.withLock { kdfIsLegacy = true }
                return null
            }
            200 -> {
                val params = usernameKdfParams(json.decodeFromString(UsernameKdfDto.serializer(), raw.body))
                kdfGate.withLock { kdfParams = params }
                return params
            }
            else -> throw client.errorFor(raw.status, raw.body)
        }
    }

    private fun usernameKdfParams(dto: UsernameKdfDto): UsernameKdfParams {
        val salt = try {
            Base64.getDecoder().decode(dto.salt)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException(USERNAME_KDF_WEAK)
        }
        if (
            dto.algorithm != "argon2id" || dto.version != 19 || dto.parallelism != 1 || dto.outputBytes != 32 ||
            dto.memoryKiB < 65536 || dto.iterations < 8 || salt.size !in 16..64
        ) {
            throw IllegalArgumentException(USERNAME_KDF_WEAK)
        }
        return UsernameKdfParams(salt, dto.memoryKiB, dto.iterations, dto.parallelism, dto.outputBytes)
    }

    /** `GET /auth/me` (`AuthService.swift:72-74`). */
    suspend fun me(token: String): MeResponse = client.get("auth/me", token, MeResponse.serializer())

    /** `POST /auth/logout`, no body → 204 (`AuthService.swift:60-70`). */
    suspend fun logout(token: String) = client.postEmpty("auth/logout", token)

    /**
     * `DELETE /auth/account` with the account password → 204. The password is the request body only;
     * [DeleteAccountRequest] does not print it.
     */
    suspend fun deleteAccount(token: String, password: String) {
        client.deleteUnit(
            ApiClient.ACCOUNT_DELETION_PATH,
            token,
            DeleteAccountRequest(password),
            DeleteAccountRequest.serializer(),
        )
    }

    // ---- Devices (`DevicesService.swift`) ----

    /** `GET /devices` — non-revoked devices, oldest first (`DevicesService.swift:14-21`). */
    suspend fun devices(token: String): List<LinkedDeviceDto> =
        client.get("devices", token, DevicesResponse.serializer()).devices

    /** `PUT /devices/{id}/name` with a `DeviceNameSeal` blob → 204 (`DevicesService.swift:23-30`). */
    suspend fun putDeviceName(token: String, deviceId: UUID, sealedName: String) =
        client.putUnit("devices/${Ids.wire(deviceId)}/name", token, PutDeviceNameRequest(sealedName), PutDeviceNameRequest.serializer())

    /** `DELETE /devices/{id}` → 204 (`DevicesService.swift:32-37`). */
    suspend fun revokeDevice(token: String, deviceId: UUID) = client.deleteUnit("devices/${Ids.wire(deviceId)}", token)

    // ---- Keys (`KeyBundleService.swift`) ----

    /** `GET /keys/status` (`KeyBundleService.swift:14-16`). */
    suspend fun keyStatus(token: String): KeyStatusResponse = client.get("keys/status", token, KeyStatusResponse.serializer())

    /** `PUT /keys/bundle` → 204 (`KeyBundleService.swift:10-12`). */
    suspend fun putKeyBundle(token: String, bundle: PutKeyBundleRequest) =
        client.putUnit("keys/bundle", token, bundle, PutKeyBundleRequest.serializer())

    /** `GET /keys/identity/{user}` — no one-time prekey consumed (`KeyBundleService.swift:35-42`). */
    suspend fun identityKey(token: String, userId: UUID): IdentityKeyResponse =
        client.get("keys/identity/${Ids.wire(userId)}", token, IdentityKeyResponse.serializer())

    // ---- Users (`ContactsService.swift:63-88`, `PrivacyService.swift:28-35`) ----

    /** `GET /users/{id}` (`ContactsService.swift:63-69`). */
    suspend fun user(token: String, userId: UUID): UserCardDto =
        client.get("users/${Ids.wire(userId)}", token, UserCardDto.serializer())

    /** `GET /users/me/contact-names`: the account's sealed name book (`ContactNameBookSeal`). */
    suspend fun contactNames(token: String): ContactNamesDto =
        client.get("users/me/contact-names", token, ContactNamesDto.serializer())

    /** `PUT /users/me/contact-names`: 409 `VERSION_CONFLICT` when another device wrote since [version]. */
    suspend fun putContactNames(token: String, sealed: String, version: Long): ContactNamesDto =
        client.put(
            "users/me/contact-names",
            token,
            PutContactNamesRequest(sealed, version),
            PutContactNamesRequest.serializer(),
            ContactNamesDto.serializer(),
        )

    /** `PUT /contacts/{id}/sealed-name` → 204. The body is an opaque sealed box. */
    suspend fun putContactName(token: String, userId: UUID, sealed: String) =
        client.putUnit(
            "contacts/${Ids.wire(userId)}/sealed-name",
            token,
            PutContactNameRequest(sealed),
            PutContactNameRequest.serializer(),
        )

    /** `GET /users/by-code/{code}`: normalized first, then path-encoded (`ContactsService.swift:80-88`). */
    suspend fun userByShareCode(token: String, code: String): UserCardDto =
        client.get("users/by-code/${ApiClient.pathSegment(normalizeShareCode(code))}", token, UserCardDto.serializer())

    /** `POST /users/me/share-code`, no body → the new code; the old one stops resolving (`PrivacyService.swift:28-35`). */
    suspend fun rotateShareCode(token: String): String =
        client.postEmpty("users/me/share-code", token, ShareCodeResponse.serializer()).shareCode

    // ---- Contacts (`ContactsService.swift:7-61`) ----

    /** `GET /contacts`, sorted by username (`ContactsService.swift:7-14`). */
    suspend fun contacts(token: String): List<ContactItemDto> =
        client.get("contacts", token, ContactsResponse.serializer()).contacts

    /** `GET /contacts/requests?box=&status=` (iOS asks `incoming` + `pending`, `ContactsService.swift:16-24`). */
    suspend fun contactRequests(token: String, box: String = "incoming", status: String = ContactRequestStatus.PENDING): List<ContactRequestDto> =
        client.get("contacts/requests", token, ContactRequestsResponse.serializer(), mapOf("box" to box, "status" to status)).requests

    /**
     * `POST /contacts/requests {"user_id"}` → 201 pending, or 200 accepted when they had asked us
     * already (`ContactsService.swift:26-33`). Never read `user` from the mutual-accept answer: it
     * is the caller's own card (contacts §2.2).
     */
    suspend fun createContactRequest(token: String, userId: UUID): ContactRequestDto =
        client.post("contacts/requests", token, UserIdBody(userId), UserIdBody.serializer(), ContactRequestDto.serializer())

    /** `POST /contacts/requests/{id}/accept` with body `{}` (`ContactsService.swift:35-44`). */
    suspend fun acceptContactRequest(token: String, requestId: UUID): ContactRequestDto =
        client.post("contacts/requests/${Ids.wire(requestId)}/accept", token, EmptyBody, EmptyBody.serializer(), ContactRequestDto.serializer())

    /** `POST /contacts/requests/{id}/reject` with body `{}` (`ContactsService.swift:46-54`). */
    suspend fun rejectContactRequest(token: String, requestId: UUID): ContactRequestDto =
        client.post("contacts/requests/${Ids.wire(requestId)}/reject", token, EmptyBody, EmptyBody.serializer(), ContactRequestDto.serializer())

    /** `DELETE /contacts/{user}` → 204 (`ContactsService.swift:56-61`). */
    suspend fun deleteContact(token: String, userId: UUID) = client.deleteUnit("contacts/${Ids.wire(userId)}", token)

    // ---- Blocks (`BlocksService.swift`) ----

    /** `GET /blocks`, newest first (`BlocksService.swift:12-19`). */
    suspend fun blocks(token: String): List<BlockItemDto> = client.get("blocks", token, BlocksResponse.serializer()).blocks

    /** `POST /blocks {"user_id"}` → 204 (`BlocksService.swift:21-27`). */
    suspend fun block(token: String, userId: UUID) = client.postUnit("blocks", token, UserIdBody(userId), UserIdBody.serializer())

    /** `DELETE /blocks/{user}` → 204 (`BlocksService.swift:29-34`). */
    suspend fun unblock(token: String, userId: UUID) = client.deleteUnit("blocks/${Ids.wire(userId)}", token)

    // ---- Conversations and messages (`MessagesService.swift`, `NotificationsService.swift:36-44`) ----

    /** `GET /conversations` (`MessagesService.swift:7-14`). */
    suspend fun conversations(token: String): List<ConversationItemDto> =
        client.get("conversations", token, ConversationsResponse.serializer()).conversations

    /**
     * `GET /messages?peer_user_id=&limit=[&before_created_at=&before_id=]` (`MessagesService.swift:16-39`).
     * The cursor goes only when both halves are given; [beforeCreatedAt] is the server's own
     * `created_at` text ([MessageDto.createdAtWire]), never a re-formatted date (`HistoryCursorTests`).
     */
    suspend fun messages(
        token: String,
        peerUserId: UUID,
        limit: Int = 50,
        beforeCreatedAt: String? = null,
        beforeId: UUID? = null,
    ): ListMessagesResponse {
        val query = buildMap {
            put("peer_user_id", Ids.wire(peerUserId))
            put("limit", limit.toString())
            if (beforeCreatedAt != null && beforeId != null) {
                put("before_created_at", beforeCreatedAt)
                put("before_id", Ids.wire(beforeId))
            }
        }
        return client.get("messages", token, ListMessagesResponse.serializer(), query)
    }

    /** `POST /messages` → 201, or 200 on an idempotent replay of the same `client_message_id` (`MessagesService.swift:41-43`). */
    suspend fun sendMessage(token: String, request: SendMessageRequest): MessageDto =
        client.post("messages", token, request, SendMessageRequest.serializer(), MessageDto.serializer())

    /** `DELETE /messages/{id}?scope=me|everyone` → 204 (`MessagesService.swift:45-53`). */
    suspend fun deleteMessage(token: String, messageId: UUID, scope: MessageDeleteScope) =
        client.deleteUnit("messages/${Ids.wire(messageId)}", token, mapOf("scope" to scope.wire))

    /** `DELETE /conversations/{peer}?scope=me|everyone` (`MessagesService.swift:55-69`). */
    suspend fun deleteConversation(token: String, peerUserId: UUID, scope: ConversationDeleteScope): DeleteConversationResponse =
        client.delete("conversations/${Ids.wire(peerUserId)}", token, DeleteConversationResponse.serializer(), mapOf("scope" to scope.wire))

    /** `POST /messages/{id}/delivered`, no body → 204 (`MessagesService.swift:157-162`). */
    suspend fun markDelivered(token: String, messageId: UUID) = client.postEmpty("messages/${Ids.wire(messageId)}/delivered", token)

    /** `POST /messages/read` — servers without read markers only (`MessagesService.swift:171-179`). */
    suspend fun markReadBulk(token: String, peerUserId: UUID, upToMessageId: UUID): MarkReadBulkResponse =
        client.post("messages/read", token, MarkReadBulkBody(peerUserId, upToMessageId), MarkReadBulkBody.serializer(), MarkReadBulkResponse.serializer())

    /** `POST /conversations/{peer}/read`, no body (`NotificationsService.swift:36-44`). */
    suspend fun markChatRead(token: String, peerUserId: UUID): MarkChatReadResponse =
        client.postEmpty("conversations/${Ids.wire(peerUserId)}/read", token, MarkChatReadResponse.serializer())

    // ---- Reactions (`MessagesService.swift:76-155`) ----

    /**
     * `PUT /messages/{id}/reaction {"ciphertext","base_seq","added"}` — the whole sealed set, built
     * on our record at [baseSeq] (0: none) (`MessagesService.swift:85-106`). A `409` with our current
     * record is [ReactionWriteResult.ChangedElsewhere], not an error.
     */
    suspend fun putReaction(token: String, messageId: UUID, ciphertext: ByteArray, baseSeq: Long, added: Boolean): ReactionWriteResult {
        val body = json.encodeToString(PutReactionBody.serializer(), PutReactionBody(Base64.getEncoder().encodeToString(ciphertext), baseSeq, added))
        val answer = client.raw("PUT", "messages/${Ids.wire(messageId)}/reaction", token, jsonBody = body)
        return ReactionWriteResult.from(answer.status, answer.body, json)
    }

    /** `DELETE /messages/{id}/reaction?base_seq=` — 200 removed, 204 nothing to remove, 409 as above (`MessagesService.swift:108-117`). */
    suspend fun deleteReaction(token: String, messageId: UUID, baseSeq: Long): ReactionWriteResult {
        val answer = client.raw("DELETE", "messages/${Ids.wire(messageId)}/reaction", token, query = mapOf("base_seq" to baseSeq.toString()))
        return ReactionWriteResult.from(answer.status, answer.body, json)
    }

    /** `GET /conversations/{peer}/reactions?after_seq=&limit=` — oldest first, removals included (`MessagesService.swift:131-144`). */
    suspend fun reactionChanges(token: String, peerUserId: UUID, afterSeq: Long, limit: Int = 200): ReactionChangesResponse =
        client.get(
            "conversations/${Ids.wire(peerUserId)}/reactions",
            token,
            ReactionChangesResponse.serializer(),
            mapOf("after_seq" to afterSeq.toString(), "limit" to limit.toString()),
        )

    /** `POST /conversations/{peer}/reactions/seen {"up_to_seq"}` (`MessagesService.swift:146-155`). */
    suspend fun markReactionsSeen(token: String, peerUserId: UUID, upToSeq: Long): MarkReactionsSeenResponse =
        client.post(
            "conversations/${Ids.wire(peerUserId)}/reactions/seen",
            token,
            MarkReactionsSeenBody(upToSeq),
            MarkReactionsSeenBody.serializer(),
            MarkReactionsSeenResponse.serializer(),
        )

    // ---- Notifications and mutes (`NotificationsService.swift`) ----

    /** `GET /notifications/settings` (`NotificationsService.swift:10-12`). */
    suspend fun notificationSettings(token: String): NotificationSettingsDto =
        client.get("notifications/settings", token, NotificationSettingsDto.serializer())

    /** `PUT /notifications/settings` with only the set fields; returns what the server stored (`NotificationsService.swift:14-17`). */
    suspend fun updateNotificationSettings(token: String, patch: NotificationSettingsPatch): NotificationSettingsDto =
        client.put(
            "notifications/settings",
            token,
            patchJson.encodeToJsonElement(NotificationSettingsPatch.serializer(), patch),
            JsonElement.serializer(),
            NotificationSettingsDto.serializer(),
        )

    /**
     * `PUT /conversations/{peer}/mute {"seconds": n | null}` — null mutes until unmuted and is sent
     * as an explicit `null` (`MuteChatBody`, `NotificationModels.swift:67-77`;
     * `NotificationPayloadTests.testPatchEncodesOnlyWhatIsSet`) (`NotificationsService.swift:19-27`).
     */
    suspend fun muteChat(token: String, peerUserId: UUID, seconds: Long?): MuteChatResponse =
        client.put("conversations/${Ids.wire(peerUserId)}/mute", token, muteChatBody(seconds), JsonObject.serializer(), MuteChatResponse.serializer())

    /** `DELETE /conversations/{peer}/mute` → 204 (`NotificationsService.swift:29-34`). */
    suspend fun unmuteChat(token: String, peerUserId: UUID) = client.deleteUnit("conversations/${Ids.wire(peerUserId)}/mute", token)

    /** `POST /push/test`, no body (`NotificationsService.swift:50-52`). */
    suspend fun sendTestPush(token: String): TestPushOutcomeDto = client.postEmpty("push/test", token, TestPushOutcomeDto.serializer())

    // ---- Push: UnifiedPush over the Web Push routes (decision record 1–3; X1-SRV-UP; no FCM) ----

    /** `GET /push/web/key` — the VAPID public key for `REGISTER`; a 404 means the server has no Web Push (server `push.rs` web_key). */
    suspend fun webPushKey(token: String): WebPushKeyResponse = client.get("push/web/key", token, WebPushKeyResponse.serializer())

    /**
     * `PUT /push/web/subscription` with `client:"android"` → 204 (server `push.rs`
     * put_web_subscription; web `client.ts:533-541`). A 400 `VALIDATION_ERROR` means the server
     * refused the distributor's host.
     */
    suspend fun putWebPushSubscription(token: String, body: WebPushSubscriptionBody) =
        client.putUnit("push/web/subscription", token, body, WebPushSubscriptionBody.serializer())

    /** `DELETE /push/web/subscription` → 204. */
    suspend fun deleteWebPushSubscription(token: String) = client.deleteUnit("push/web/subscription", token)

    // ---- Privacy (`PrivacyService.swift`) ----

    /** `GET /privacy/settings` (`PrivacyService.swift:9-15`). */
    suspend fun privacySettings(token: String): PrivacySettingsDto = client.get("privacy/settings", token, PrivacySettingsDto.serializer())

    /** `PUT /privacy/settings` with only the changed switches; returns what the server stored (`PrivacyService.swift:17-26`). */
    suspend fun updatePrivacySettings(token: String, change: UpdatePrivacySettingsBody): PrivacySettingsDto =
        client.put(
            "privacy/settings",
            token,
            patchJson.encodeToJsonElement(UpdatePrivacySettingsBody.serializer(), change),
            JsonElement.serializer(),
            PrivacySettingsDto.serializer(),
        )

    // ---- Media (`MediaService.swift`) ----

    /** `POST /media/uploads` → 201 (`MediaService.swift:7-21`). [sizeBytes] is the exact sealed size. */
    suspend fun createMediaUpload(token: String, sizeBytes: Long, contentType: String = OCTET_STREAM): CreateMediaUploadResponse =
        client.post(
            "media/uploads",
            token,
            CreateMediaUploadRequest(sizeBytes, contentType),
            CreateMediaUploadRequest.serializer(),
            CreateMediaUploadResponse.serializer(),
        )

    /**
     * `PUT /media/{id}/content` as `application/octet-stream` → 204 (`MediaService.swift:33-69`).
     * [body] must have a known length. Over 2 GiB it fails before any request with the iOS text
     * (`MediaService.swift:42-51`), N = max(1, bytes / 1 048 576).
     */
    suspend fun uploadMediaContent(token: String, mediaId: UUID, body: RequestBody, onProgress: ((Double) -> Unit)? = null) {
        val length = body.contentLength()
        require(length >= 0) { "A media upload needs a known length." }
        if (length > MAX_SEALED_MEDIA_BYTES) {
            val megabytes = maxOf(1L, length / 1_048_576L)
            throw ApiError.Server(
                ErrorCodes.VALIDATION_ERROR,
                "This media is too large after encryption ($megabytes MB). Try a shorter video or lower photo quality.",
                400,
            )
        }
        client.putBytes("media/${Ids.wire(mediaId)}/content", token, OctetStreamBody(body), onProgress)
    }

    /** `GET /media/{id}/content` into memory (photos, voice) (`MediaService.swift:71-84`). */
    suspend fun downloadMediaContent(token: String, mediaId: UUID, onProgress: ((Double) -> Unit)? = null): ByteArray =
        client.getBytes("media/${Ids.wire(mediaId)}/content", token, onProgress)

    /** `GET /media/{id}/content` into [target] (videos up to 2 GiB); returns the bytes written (api-realtime §17-8). */
    suspend fun downloadMediaContentTo(token: String, mediaId: UUID, target: File, onProgress: ((Double) -> Unit)? = null): Long =
        client.getToFile("media/${Ids.wire(mediaId)}/content", token, target, onProgress)

    // ---- Presence (`ContactsService.swift:90-96`) ----

    /** `GET /presence/{user}`. */
    suspend fun presence(token: String, userId: UUID): PresenceDto =
        client.get("presence/${Ids.wire(userId)}", token, PresenceDto.serializer())

    // ---- Calls (`CallsService.swift`) ----

    /** `GET /calls/ice-servers` (`CallsService.swift:13-20`). */
    suspend fun iceServers(token: String): List<IceServerDto> =
        client.get("calls/ice-servers", token, IceServersResponse.serializer()).iceServers

    /** `POST /calls {"peer_user_id","modality","protocol":2}` → 201 ringing (`CallsService.swift:22-29`). */
    suspend fun createCall(token: String, peerUserId: UUID, modality: CallModality): CallDto =
        client.post("calls", token, CreateCallRequest(peerUserId, modality.wire), CreateCallRequest.serializer(), CallDto.serializer())

    /** `GET /calls/{id}` — with `peer_media_state` for a device in the live call (`CallsService.swift:31-33`). */
    suspend fun call(token: String, callId: UUID): CallDto = client.get(callPath(callId), token, CallDto.serializer())

    /**
     * `GET /calls?limit=[&before=]`, newest first (`CallsService.swift:35-48`). [before] is a call's
     * raw `created_at` ([CallDto.createdAtWire]) — what the web sends (`client.ts:599-605`); the
     * server takes it as well as iOS's millisecond format (api-realtime §2.7).
     */
    suspend fun callHistory(token: String, limit: Int = 50, before: String? = null): List<CallDto> {
        val query = buildMap {
            put("limit", limit.toString())
            if (before != null) put("before", before)
        }
        return client.get("calls", token, CallListResponse.serializer(), query).calls
    }

    /** `POST /calls/{id}/accept` with body `{}` — required: the handler takes JSON and answers 415 without it (`CallsService.swift:50-52`). */
    suspend fun acceptCall(token: String, callId: UUID): CallDto =
        client.post(callPath(callId, "/accept"), token, EmptyBody, EmptyBody.serializer(), CallDto.serializer())

    /** `POST /calls/{id}/reject`, no body (`CallsService.swift:54-56`). */
    suspend fun rejectCall(token: String, callId: UUID): CallDto = client.postEmpty(callPath(callId, "/reject"), token, CallDto.serializer())

    /** `POST /calls/{id}/hangup`, no body (`CallsService.swift:58-60`). */
    suspend fun hangupCall(token: String, callId: UUID): CallDto = client.postEmpty(callPath(callId, "/hangup"), token, CallDto.serializer())

    /** `POST /calls/{id}/heartbeat`, no body (`CallsService.swift:62-64`). */
    suspend fun callHeartbeat(token: String, callId: UUID): CallDto =
        client.postEmpty(callPath(callId, "/heartbeat"), token, CallDto.serializer())

    /** `POST /calls/{id}/signal {"signal_type","payload"}` → 204 (`CallsService.swift:66-72`). */
    suspend fun callSignal(token: String, callId: UUID, signalType: String, payload: String) =
        client.postUnit(callPath(callId, "/signal"), token, CallSignalRequest(signalType, payload), CallSignalRequest.serializer())

    /** `CallsService.path` (`CallsService.swift:9-11`). */
    private fun callPath(id: UUID, suffix: String = "") = "calls/${Ids.wire(id)}$suffix"

    /** A media body sent as `application/octet-stream` whatever [delegate] says (`MediaService.swift:57`). */
    private class OctetStreamBody(private val delegate: RequestBody) : RequestBody() {
        override fun contentType(): MediaType = OCTET_STREAM_TYPE
        override fun contentLength(): Long = delegate.contentLength()
        override fun isOneShot(): Boolean = delegate.isOneShot()
        override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
    }

    companion object {
        private const val OCTET_STREAM = "application/octet-stream"
        private val OCTET_STREAM_TYPE = OCTET_STREAM.toMediaType()

        /**
         * `{"seconds": n}` or `{"seconds": null}` — the `null` is written on purpose, which no single
         * Json configuration gives together with the patches (`MuteChatBody`, `NotificationModels.swift:67-77`).
         */
        internal fun muteChatBody(seconds: Long?): JsonObject = buildJsonObject {
            put("seconds", if (seconds != null) JsonPrimitive(seconds) else JsonNull)
        }

        /**
         * `ContactInviteParser.normalizeShareCode` (`ContactInviteParser.swift:38-44`): trimmed, `@`
         * stripped from both ends, `-` and spaces removed, upper-cased. The server normalizes the
         * same way (`auth/share_code.rs:21-37`); W2-CONTACTS' parser must agree with it.
         */
        internal fun normalizeShareCode(raw: String): String =
            raw.trim().trim('@').replace("-", "").replace(" ", "").uppercase()
    }
}
