package de.corespace.shroud.core.auth

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import de.corespace.shroud.core.crypto.Bip39
import de.corespace.shroud.core.crypto.CryptoController
import de.corespace.shroud.core.crypto.CryptoException
import de.corespace.shroud.core.crypto.DeviceNameSeal
import de.corespace.shroud.core.keys.DeviceSecurity
import de.corespace.shroud.core.model.userUuid
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ErrorCodes
import de.corespace.shroud.core.net.IdentityKeyResponse
import de.corespace.shroud.core.net.LimitDeviceDto
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConnectionMode
import de.corespace.shroud.core.net.ShroudApi
import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * Sign-up and log-in for the onboarding screens (the calls `AppContainer` used to expose; W3-INT
 * removes those shims). [register] and [login] need no session — they create one. The phrase calls
 * need the [Session] they are given. Failures are the ones [SessionController] and [CryptoController]
 * already throw ([ApiError], [CryptoException], [Bip39.PhraseException]); screens render them with
 * [SessionController.userMessage] or [CryptoController.userMessage].
 */
interface OnboardingService {
    /** A PIN, pattern or password is set — what the history-key vault is gated on. */
    fun hasScreenLock(): Boolean

    /**
     * Android 17 makes reaching the local network a runtime permission. True only when this
     * process still needs `ACCESS_LOCAL_NETWORK` for the configured server: API 37+, a self-hosted
     * LAN or emulator-host address, and the permission not yet granted. Loopback is exempt.
     */
    fun needsLocalNetworkPermission(): Boolean

    /** Creates the account. Throws [ApiError]. A second call with the same name fails as taken. */
    suspend fun register(username: String, password: String): Session

    /**
     * Signs in. Throws [ApiError]; a full account answers `409 DEVICE_LIMIT` with
     * [ApiError.deviceLimit], and the retry passes its oldest device as [replaceDeviceId] to log it out.
     */
    suspend fun login(username: String, password: String, replaceDeviceId: UUID? = null): Session

    /**
     * Log In on a full account, no session yet: [words] must be the phrase of [identityKey], the
     * published key the `409 DEVICE_LIMIT` carried ([CryptoController.checkPhrase]). Throws a
     * [Bip39.PhraseException] or [CryptoException.PhraseDoesNotMatchAccount]; stores nothing.
     */
    suspend fun checkPhrase(words: List<String>, identityKey: String)

    /**
     * The sealed names of a full account's [devices], opened with the history key of [words] (a
     * phrase [checkPhrase] accepted; [CryptoController.openDeviceNames]). Null for one that doesn't open.
     */
    suspend fun openDeviceNames(words: List<String>, devices: List<LimitDeviceDto>): Map<UUID, DeviceNameSeal.Label?>

    /** What became of the session after an authenticated request failed. Never counts anything itself. */
    fun sessionAfterFailure(): SessionController.Validation

    /**
     * Derives this device's keys from [words], stores them and publishes the bundle
     * ([CryptoController.establishFromSignup]). Throws [CryptoException], [ApiError] or a
     * [Bip39.PhraseException]. There is no `KEYS_EXIST` error anywhere on the server.
     *
     * `PUT /keys/bundle` (`server/crates/shroud-server/src/routes/keys.rs`, `put_bundle`) upserts
     * `device_identity_keys` `ON CONFLICT (device_id) DO UPDATE` and returns 204. A second upload
     * of this device's bundle overwrites the key. [CryptoController.establishFromSignup] already
     * calls `rejectPhraseThatIsNotTheAccountKey`: if `GET /keys/identity` returns a key that does
     * not match the phrase, it throws [CryptoException.PhraseDoesNotMatchAccount]. `KEYS_REQUIRED`
     * (the 404 when the account has no key) is the only server error it swallows. A retry with the
     * same phrase succeeds and overwrites this device's bundle; a different phrase fails with
     * [CryptoException.PhraseDoesNotMatchAccount], not `KEYS_EXIST`.
     */
    suspend fun establishFromSignup(words: List<String>, session: Session)

    /** Log-in phrase step ([CryptoController.unlockWithPhrase]). Throws [CryptoException], [ApiError] or a [Bip39.PhraseException]. */
    suspend fun unlockWithPhrase(words: List<String>, session: Session)

    /**
     * True only when the account has not published an identity key: [ApiError.Server] code
     * [ErrorCodes.KEYS_REQUIRED], or any 404 from `GET /keys/identity/{user}`. A returned key is
     * false. Every other [ApiError] is rethrown.
     */
    suspend fun accountHasNoKey(session: Session): Boolean
}

/**
 * [OnboardingService] on the process's session, crypto and device security. Callers use
 * `auth.onboarding`.
 */
class ShroudOnboardingService internal constructor(
    private val registerAccount: suspend (String, String) -> Session,
    private val loginAccount: suspend (String, String, UUID?) -> Session,
    private val checkAccountPhrase: suspend (List<String>, String) -> Unit,
    private val openNames: suspend (List<String>, List<LimitDeviceDto>) -> Map<UUID, DeviceNameSeal.Label?>,
    private val afterFailure: () -> SessionController.Validation,
    private val establish: suspend (List<String>, Session) -> Unit,
    private val unlockPhrase: suspend (List<String>, Session) -> Unit,
    private val fetchIdentity: suspend (String, UUID) -> IdentityKeyResponse,
    private val deviceSecure: () -> Boolean,
    private val context: Context,
    private val configuration: () -> ServerConfiguration,
) : OnboardingService {
    constructor(
        sessions: SessionController,
        crypto: CryptoController,
        deviceSecurity: DeviceSecurity,
        api: ShroudApi,
        context: Context,
        configuration: () -> ServerConfiguration,
    ) : this(
        registerAccount = { username, password -> sessions.register(username, password) },
        loginAccount = { username, password, replace -> sessions.login(username, password, replace) },
        checkAccountPhrase = { words, identityKey -> crypto.checkPhrase(words, identityKey) },
        openNames = { words, devices -> crypto.openDeviceNames(words, devices) },
        afterFailure = { sessions.sessionAfterFailure() },
        establish = { words, session -> crypto.establishFromSignup(words, session) },
        unlockPhrase = { words, session -> crypto.unlockWithPhrase(words, session) },
        fetchIdentity = { token, userId -> api.identityKey(token, userId) },
        deviceSecure = { deviceSecurity.isDeviceSecure },
        context = context,
        configuration = configuration,
    )

    override fun hasScreenLock(): Boolean = deviceSecure()

    override fun needsLocalNetworkPermission(): Boolean = needsLocalNetworkPermission(
        sdkInt = Build.VERSION.SDK_INT,
        configuration = configuration(),
        permissionGranted = context.checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED,
    )

    override suspend fun register(username: String, password: String): Session = registerAccount(username, password)

    override suspend fun login(username: String, password: String, replaceDeviceId: UUID?): Session =
        loginAccount(username, password, replaceDeviceId)

    override suspend fun checkPhrase(words: List<String>, identityKey: String) = checkAccountPhrase(words, identityKey)

    override suspend fun openDeviceNames(words: List<String>, devices: List<LimitDeviceDto>): Map<UUID, DeviceNameSeal.Label?> =
        openNames(words, devices)

    override fun sessionAfterFailure(): SessionController.Validation = afterFailure()

    override suspend fun establishFromSignup(words: List<String>, session: Session) = establish(words, session)

    override suspend fun unlockWithPhrase(words: List<String>, session: Session) = unlockPhrase(words, session)

    override suspend fun accountHasNoKey(session: Session): Boolean {
        try {
            fetchIdentity(session.token, session.userUuid)
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            if (isMissingAccountKey(e)) return true
            throw e
        }
    }

    companion object {
        /** Same string as `LOCAL_NETWORK_PERMISSION` in `AppContainer.kt` (the UI still requests it). */
        internal const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

        /** API 37+ (Android 17), self-hosted LAN or emulator host, permission still missing. Loopback is exempt. */
        internal fun needsLocalNetworkPermission(sdkInt: Int, configuration: ServerConfiguration, permissionGranted: Boolean): Boolean {
            if (sdkInt < 37) return false
            if (configuration.mode != ServerConnectionMode.SelfHosted) return false
            val host = configuration.host.trim().trim('[', ']').lowercase()
            if (host == "localhost" || host == "::1" || host.startsWith("127.")) return false
            if (!ServerConfiguration.isLocalNetworkHost(host)) return false
            return !permissionGranted
        }

        /** [ErrorCodes.KEYS_REQUIRED], or a 404 from the identity-key fetch. Nothing else. */
        internal fun isMissingAccountKey(error: ApiError): Boolean = when (error) {
            is ApiError.Server -> error.code == ErrorCodes.KEYS_REQUIRED || error.status == 404
            else -> false
        }
    }
}
