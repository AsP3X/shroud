package de.corespace.shroud.core.keys

import de.corespace.shroud.core.crypto.LocalHistoryCrypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.EnumMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * The history key for the sealed stores outside `CryptoController` (iOS `SealedLocalState`,
 * `ios/shroud/Services/Crypto/SealedLocalState.swift:4-38`; crypto spec §8): ratchet sessions,
 * sender-tag watermarks, the keyed file names, the voice language statistics and the message
 * stores of later waves. These follow the chat lock exactly: while chats are locked the key is not
 * here, so their records cannot be read, and nothing is written in the clear instead.
 *
 * Set by `CryptoController` whenever its material changes (unlock → [unlock], nil → [lock];
 * `CryptoController.swift:13-21`). iOS's unlock also runs plaintext migrations
 * (`SealedLocalState.swift:23-27`); Android has no older build, so there is nothing to migrate —
 * consumers that need to react register a [Listener] (the voice language memory, W3).
 *
 * Key lifetime (crypto spec §1.6, plan §1.4): the key, its derived subkeys and the [LocalNames]
 * live behind one [ReentrantReadWriteLock]. Readers run inside [withKey] / [withSubkey] (read lock)
 * and must not let the array escape the block; [lock] takes the write lock and zeroes everything
 * first, so a lock racing a seal never lets HKDF read a half-zeroed key. Never logs the key.
 */
class SealedLocalState {
    private val rw = ReentrantReadWriteLock()
    private var key: ByteArray? = null // guarded by rw
    private var localNames: LocalNames? = null // guarded by rw
    private val subkeys = EnumMap<LocalHistoryCrypto.Context, ByteArray>(LocalHistoryCrypto.Context::class.java) // guarded by rw
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val state = MutableStateFlow(false)

    /** True while the history key is in memory (chats unlocked). */
    val unlocked: StateFlow<Boolean> = state.asStateFlow()

    val isUnlocked: Boolean get() = rw.read { key != null }

    /**
     * Runs [block] with the history key under the read lock; null while locked. The array is only
     * valid inside [block] — never keep or return it.
     */
    fun <T> withKey(block: (ByteArray) -> T): T? {
        rw.read {
            val k = key ?: return null
            return block(k)
        }
    }

    /**
     * Runs [block] with the [context] subkey (`LocalHistoryCrypto.subkey`), derived once per unlock
     * and zeroed by [lock]; null while locked. The array is only valid inside [block].
     */
    fun <T> withSubkey(context: LocalHistoryCrypto.Context, block: (ByteArray) -> T): T? {
        rw.read {
            val k = key ?: return null
            val sub = synchronized(subkeys) { subkeys.getOrPut(context) { LocalHistoryCrypto.subkey(k, context) } }
            return block(sub)
        }
    }

    /**
     * Runs [block] with the history key and the keyed file names together, under the read lock;
     * null while locked (the stores of `keys/` use this).
     */
    fun <T> withKeyAndNames(block: (historyKey: ByteArray, names: LocalNames) -> T): T? {
        rw.read {
            val k = key ?: return null
            val n = localNames ?: return null
            return block(k, n)
        }
    }

    /**
     * The keyed file names of this unlock (plan §1.5), null while locked. The instance is wiped by
     * [lock]; a caller still holding it then gets `CryptoError.Locked` from `name`.
     */
    fun names(): LocalNames? = rw.read { localNames }

    /**
     * Stores a copy of [historyKey] (32 bytes) and derives the keyed names, replacing any previous
     * key (which is zeroed). Then tells every [Listener] (outside the lock). [unlocked] changes
     * inside the write lock, so a racing [lock] can never leave it `true` without a key.
     */
    fun unlock(historyKey: ByteArray) {
        require(historyKey.size == LocalHistoryCrypto.MASTER_KEY_BYTES) { "the history key is 32 bytes" }
        rw.write {
            clearLocked()
            key = historyKey.copyOf()
            localNames = LocalNames.derive(historyKey)
            state.value = true
        }
        if (listeners.isEmpty()) return
        val copy = withKey { it.copyOf() } ?: return
        try {
            for (listener in listeners) listener.onUnlock(copy)
        } finally {
            copy.fill(0)
        }
    }

    /** Zeroes the key, its subkeys and the names (write lock), then tells every [Listener]. Idempotent. */
    fun lock() {
        val wasUnlocked = rw.write {
            val had = key != null
            clearLocked()
            state.value = false
            had
        }
        if (wasUnlocked) for (listener in listeners) listener.onLock()
    }

    /** Tests: the key alone, no listeners (iOS `setHistoryKey`, `SealedLocalState.swift:34-37`). Null locks. */
    fun setHistoryKeyForTesting(historyKey: ByteArray?) {
        rw.write {
            clearLocked()
            if (historyKey != null) {
                key = historyKey.copyOf()
                localNames = LocalNames.derive(historyKey)
            }
            state.value = historyKey != null
        }
    }

    /** Registers [listener]; closing the result unregisters it. */
    fun addListener(listener: Listener): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    private fun clearLocked() {
        key?.fill(0)
        key = null
        localNames?.wipe()
        localNames = null
        synchronized(subkeys) {
            subkeys.values.forEach { it.fill(0) }
            subkeys.clear()
        }
    }

    /**
     * Hears the chat lock. [onUnlock]'s array is a copy that is zeroed when the call returns:
     * derive what you need inside the call, never keep it.
     */
    interface Listener {
        fun onUnlock(historyKey: ByteArray)

        fun onLock()
    }
}
