package de.corespace.shroud.core.transcription

import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.LocalHistoryCrypto
import de.corespace.shroud.core.crypto.utf8
import de.corespace.shroud.core.keys.SealedLocalState
import de.corespace.shroud.core.messaging.local.HistoryBlob
import de.corespace.shroud.core.storage.StorageSeal
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * Which language was actually spoken, per conversation and overall (iOS `TranscriptionLanguageMemory`).
 * Language is stable per chat, and a two-second note cannot decide it, so notes whose audio was
 * decisive teach a [history] that later short notes lean on.
 *
 * Sealed at `noBackupFilesDir/shroud/voice/language-stats.sealed` with history-key context
 * [LocalHistoryCrypto.Context.LanguageStats] (`shroud-local-language-stats-v1`) and AAD
 * [AAD] (plan C10). Peer keys inside are lowercase UUIDs. The file is deleted with the voice
 * directory on Log Out (`DeviceDataWipe` settings step). While chats are locked, reads are the
 * empty history and writes are dropped — transcription still runs, it just does not learn.
 * [StorageSeal] drops a write during a wipe. Never logs stats, and the bytes never leave the device.
 */
class TranscriptionLanguageMemory(
    private val file: File,
    private val state: SealedLocalState,
    private val seal: StorageSeal,
) : SealedLocalState.Listener {
    private val lock = Any()
    private var open = false
    private var stats: Map<String, Map<String, Double>> = emptyMap()

    init {
        state.addListener(this)
        if (state.isUnlocked) reload()
    }

    /**
     * The languages heard in [conversationId], by weight: this chat's own record, or the overall one
     * while the chat has none yet (iOS `history(peerID:)`). Empty while locked. [SpokenLanguagePick]
     * weighs Whisper's probabilities with it; it never replaces what the audio says.
     */
    fun history(conversationId: UUID?): Map<String, Double> {
        val all = snapshot()
        val peer = conversationId?.let { all[it.toString()] }.orEmpty()
        return if (peer.values.sum() > 0.0) peer else all[GLOBAL].orEmpty()
    }

    /**
     * Records one decisive observation (`record`). [weight] ≤ 0 is ignored. While
     * locked or sealed, the call does nothing and does not throw — a transcription must not fail
     * because the stats could not be saved.
     */
    fun record(languageCode: String, conversationId: UUID?, weight: Double) {
        if (weight <= 0.0 || seal.isSealed) return
        val snapshot = synchronized(lock) {
            if (!open) return
            stats = updated(stats, languageCode, conversationId, weight)
            stats
        }
        val blob = state.withSubkey(LocalHistoryCrypto.Context.LanguageStats) { subkey ->
            HistoryBlob.seal(subkey, encode(snapshot), AAD)
        } ?: return
        synchronized(lock) {
            if (!open || seal.isSealed || stats != snapshot) return
            write(blob)
        }
    }

    override fun onUnlock(historyKey: ByteArray) {
        val subkey = LocalHistoryCrypto.subkey(historyKey, LocalHistoryCrypto.Context.LanguageStats)
        try {
            val loaded = read(subkey)
            synchronized(lock) {
                stats = loaded
                open = true
            }
        } finally {
            subkey.fill(0)
        }
    }

    override fun onLock() {
        synchronized(lock) {
            open = false
            stats = emptyMap()
        }
    }

    private fun reload() {
        val loaded = state.withSubkey(LocalHistoryCrypto.Context.LanguageStats) { subkey -> read(subkey) }
        synchronized(lock) {
            if (loaded == null || !state.isUnlocked) {
                open = false
                stats = emptyMap()
            } else {
                open = true
                stats = loaded
            }
        }
    }

    private fun snapshot(): Map<String, Map<String, Double>> = synchronized(lock) {
        if (!open) emptyMap() else stats
    }

    /** The file's JSON, or an empty map when there is none or it does not open under [subkey]. */
    private fun read(subkey: ByteArray): Map<String, Map<String, Double>> {
        if (!file.isFile) return emptyMap()
        val blob = try {
            file.readBytes()
        } catch (_: IOException) {
            return emptyMap()
        }
        val plain = try {
            HistoryBlob.open(subkey, blob, AAD)
        } catch (_: CryptoError) {
            return emptyMap()
        }
        return decode(plain) ?: emptyMap()
    }

    private fun write(blob: ByteArray) {
        if (seal.isSealed) return
        val dir = file.parentFile ?: return
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return
        val tmp = File(dir, file.name + "." + java.lang.Long.toHexString(ThreadLocalRandom.current().nextLong()) + ".tmp")
        try {
            FileOutputStream(tmp).use {
                it.write(blob)
                it.fd.sync()
            }
            if (!tmp.renameTo(file)) return
        } catch (_: IOException) {
            return
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    companion object {
        /** `noBackupFilesDir/shroud/voice/` (00-plan §1.5). */
        const val DIRECTORY = "shroud/voice"
        const val FILE_NAME = "language-stats.sealed"

        /** Logical name bound as AAD, so the blob is not a language-stats file under another name. */
        internal val AAD = utf8("language-stats")

        private const val GLOBAL = "*"
        private const val DECAY = 0.9
        private const val MIN_WEIGHT = 0.05

        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = MapSerializer(String.serializer(), MapSerializer(String.serializer(), Double.serializer()))

        /** Seals [stats] under [historyKey]. Tests round-trip through this and [open]. */
        fun seal(stats: Map<String, Map<String, Double>>, historyKey: ByteArray): ByteArray =
            LocalHistoryCrypto.seal(encode(stats), historyKey, LocalHistoryCrypto.Context.LanguageStats, AAD)

        /** Null when [blob] does not open under [historyKey] (wrong account, tampered, damaged). */
        fun open(blob: ByteArray, historyKey: ByteArray): Map<String, Map<String, Double>>? = try {
            decode(LocalHistoryCrypto.open(blob, historyKey, LocalHistoryCrypto.Context.LanguageStats, AAD))
        } catch (_: CryptoError) {
            null
        }

        private fun encode(stats: Map<String, Map<String, Double>>): ByteArray =
            json.encodeToString(serializer, stats).toByteArray(Charsets.UTF_8)

        private fun decode(plain: ByteArray): Map<String, Map<String, Double>>? = try {
            json.decodeFromString(serializer, plain.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }

        /** Decay every weight, add [weight] to [languageCode], drop noise under [MIN_WEIGHT] (`:366-379`). */
        private fun updated(
            stats: Map<String, Map<String, Double>>,
            languageCode: String,
            conversationId: UUID?,
            weight: Double,
        ): Map<String, Map<String, Double>> {
            val next = stats.mapValues { (_, counts) -> counts.toMap() }.toMutableMap()
            for (scope in listOfNotNull(conversationId?.toString(), GLOBAL)) {
                val counts = (next[scope] ?: emptyMap()).mapValues { (_, value) -> value * DECAY }.toMutableMap()
                counts[languageCode] = (counts[languageCode] ?: 0.0) + weight
                next[scope] = counts.filterValues { it >= MIN_WEIGHT }
            }
            return next
        }
    }
}
