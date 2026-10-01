package de.corespace.shroud.core.net

import de.corespace.shroud.core.model.Ids
import java.util.UUID

/**
 * The endpoints this build uses, typed. Ids are [UUID]s; paths carry them lower-case through
 * [Ids.wire] (iOS `uuidString.lowercased()`). W1-NET grows this into the full surface of plan §1.7.2.
 */
class ShroudApi(private val client: ApiClient) {
    suspend fun register(username: String, password: String): AuthSessionResponse =
        client.post("auth/register", null, RegisterRequest(username, password), RegisterRequest.serializer(), AuthSessionResponse.serializer())

    /** [deviceId]: this phone's earlier device row on the account (the anchor), or null for a new one. */
    suspend fun login(username: String, password: String, deviceId: UUID?): AuthSessionResponse =
        client.post("auth/login", null, LoginRequest(username, password, deviceId), LoginRequest.serializer(), AuthSessionResponse.serializer())

    suspend fun me(token: String): MeResponse = client.get("auth/me", token, MeResponse.serializer())

    suspend fun logout(token: String) = client.postEmpty("auth/logout", token)

    suspend fun keyStatus(token: String): KeyStatusResponse = client.get("keys/status", token, KeyStatusResponse.serializer())

    suspend fun putKeyBundle(token: String, bundle: PutKeyBundleRequest) =
        client.put("keys/bundle", token, bundle, PutKeyBundleRequest.serializer())

    suspend fun identityKey(token: String, userId: UUID): IdentityKeyResponse =
        client.get("keys/identity/${Ids.wire(userId)}", token, IdentityKeyResponse.serializer())
}
