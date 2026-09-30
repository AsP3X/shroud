package de.corespace.shroud.core.net


/** The endpoints this build uses, typed. */
class ShroudApi(private val client: ApiClient) {
    suspend fun register(username: String, password: String): AuthSessionResponse =
        client.post("auth/register", null, RegisterRequest(username, password), RegisterRequest.serializer(), AuthSessionResponse.serializer())

    suspend fun login(username: String, password: String, deviceId: String?): AuthSessionResponse =
        client.post("auth/login", null, LoginRequest(username, password, deviceId), LoginRequest.serializer(), AuthSessionResponse.serializer())

    suspend fun me(token: String): MeResponse = client.get("auth/me", token, MeResponse.serializer())

    suspend fun logout(token: String) = client.postEmpty("auth/logout", token)

    suspend fun keyStatus(token: String): KeyStatusResponse = client.get("keys/status", token, KeyStatusResponse.serializer())

    suspend fun putKeyBundle(token: String, bundle: PutKeyBundleRequest) =
        client.put("keys/bundle", token, bundle, PutKeyBundleRequest.serializer())

    suspend fun identityKey(token: String, userId: String): IdentityKeyResponse =
        client.get("keys/identity/${userId.lowercase()}", token, IdentityKeyResponse.serializer())
}
