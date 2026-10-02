package de.corespace.shroud.ui.contacts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.contacts.Contacts
import de.corespace.shroud.core.contacts.PeerIdentities
import de.corespace.shroud.core.lifecycle.AppPhase
import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.model.ChatDeleteOutcome
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.core.model.MuteDuration
import de.corespace.shroud.core.net.CallModality
import de.corespace.shroud.core.net.ChatMuteDto
import de.corespace.shroud.core.net.ConversationDeleteScope
import de.corespace.shroud.core.net.ConversationItemDto
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.ui.LocalAppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Everything the contacts screens read and call (contacts §7.2), as one UI-side port so the
 * screens draw and act the same on a fake (JVM tests, the PNG renders) as on the engines.
 *
 * R4: [ContainerContactsPorts] only forwards, member for member, to the public K1 surface
 * (`contacts.controller`, `contacts.peerIdentities`, `auth.sessionController`, the server
 * configuration, `appPhase`, `messaging.controller`, `calls.controller`,
 * `notifications.controller`, `appScope`, `clock`). Called from the main thread (R1).
 */
interface ContactsPorts {
    /** The roster, requests, presence, blocks and the App Link invite (`contacts.controller`). */
    val contacts: Contacts

    /** Key pins, changes, verification and safety numbers (`contacts.peerIdentities`). */
    val identities: PeerIdentities

    /** The signed-in session: its username and share code (`auth.sessionController.session`). */
    val session: StateFlow<Session?>

    /** `SessionController.validate()`: fetches the share code a session from before it lacks. */
    suspend fun validateSession()

    /** The server the share link points at (`serverConfiguration.configuration`). */
    val server: StateFlow<ServerConfiguration>

    /** Whether the app is in front (`appPhase.phase`). */
    val appPhase: StateFlow<AppPhase>

    /** Typing and recording per peer (`messaging.controller.peerActivities`). */
    val peerActivities: StateFlow<Map<UUID, ChatPeerActivity>>

    /** The chat list; a change can change a mute (`messaging.controller.conversations`). */
    val conversations: StateFlow<List<ConversationItemDto>>

    fun mute(peer: UUID): ChatMuteDto?
    fun isMuted(peer: UUID): Boolean
    fun canMute(peer: UUID): Boolean

    /** Null when saved, else the user-facing error. */
    suspend fun muteChat(peer: UUID, duration: MuteDuration): String?

    /** Null when saved, else the user-facing error. */
    suspend fun unmuteChat(peer: UUID): String?
    suspend fun deleteConversation(peer: UUID, scope: ConversationDeleteScope): ChatDeleteOutcome

    /** `calls.controller.startCall`; what went wrong lands in [callError]. */
    suspend fun startCall(peer: UUID, username: String, modality: CallModality)

    /** `calls.controller.lastError`. */
    val callError: StateFlow<String?>

    /** `notifications.controller.clearContactRequestNotifications()`. */
    fun clearContactRequestNotifications()

    /** The app's scope: an Add, Accept, block or delete the user started outlives its screen. */
    val actionScope: CoroutineScope

    /** The wall clock the presence and mute lines are worded against. */
    val clock: AppClock
}

/** [ContactsPorts] over the process's [AppContainer]. */
class ContainerContactsPorts(private val container: AppContainer) : ContactsPorts {
    override val contacts: Contacts get() = container.contacts.controller
    override val identities: PeerIdentities get() = container.contacts.peerIdentities
    override val session: StateFlow<Session?> get() = container.auth.sessionController.session

    override suspend fun validateSession() {
        container.auth.sessionController.validate()
    }

    override val server: StateFlow<ServerConfiguration> get() = container.serverConfiguration.configuration
    override val appPhase: StateFlow<AppPhase> get() = container.appPhase.phase
    override val peerActivities: StateFlow<Map<UUID, ChatPeerActivity>> get() = container.messaging.controller.peerActivities
    override val conversations: StateFlow<List<ConversationItemDto>> get() = container.messaging.controller.conversations
    override fun mute(peer: UUID): ChatMuteDto? = container.messaging.controller.mute(peer)
    override fun isMuted(peer: UUID): Boolean = container.messaging.controller.isMuted(peer)
    override fun canMute(peer: UUID): Boolean = container.messaging.controller.canMute(peer)
    override suspend fun muteChat(peer: UUID, duration: MuteDuration): String? = container.messaging.controller.muteChat(peer, duration)
    override suspend fun unmuteChat(peer: UUID): String? = container.messaging.controller.unmuteChat(peer)
    override suspend fun deleteConversation(peer: UUID, scope: ConversationDeleteScope): ChatDeleteOutcome =
        container.messaging.controller.deleteConversation(peer, scope)

    override suspend fun startCall(peer: UUID, username: String, modality: CallModality) {
        container.calls.controller.startCall(peer, username, modality)
    }

    override val callError: StateFlow<String?> get() = container.calls.controller.lastError
    override fun clearContactRequestNotifications() = container.notifications.controller.clearContactRequestNotifications()
    override val actionScope: CoroutineScope get() = container.appScope
    override val clock: AppClock get() = container.clock
}

/** A [ContactsPorts] for the contacts subtree; unset in the app, where the container's is used. */
val LocalContactsPorts: ProvidableCompositionLocal<ContactsPorts?> = staticCompositionLocalOf { null }

/** The provided [ContactsPorts], else one over [LocalAppContainer]. */
@Composable
internal fun rememberContactsPorts(): ContactsPorts {
    LocalContactsPorts.current?.let { return it }
    val container = LocalAppContainer.current
    return remember(container) { ContainerContactsPorts(container) }
}
