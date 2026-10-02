package de.corespace.shroud.ui.conversation.bubble

import androidx.compose.runtime.mutableStateMapOf
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import java.util.UUID

/**
 * How voice notes show their transcripts — iOS `VoiceTranscriptDisclosure`
 * (`VoiceTranscriptDisclosure.swift:4-75`; conversation-thread §11.7). The newest voice notes at the
 * bottom of a thread — two at most, with nothing newer under them — unfold by themselves unless the
 * transcript runs long. Anything newer folds them away again and the thread drops the reader's own
 * choice for them (W3-THREAD-LIST calls [clearChoice], `ConversationView.swift:1065-1073`). Everywhere
 * else a transcript stays folded until tapped. The web applies the same rule (`transcriptView.ts`).
 *
 * In-memory, session-only view state (ids only, no content) shared by the list bubble and the
 * long-press hero, observable from composition. Messaging hands a note's state over when the server
 * re-keys it ([handOff], through `BubbleMemory`'s artifact sink). Main-thread only.
 */
object VoiceTranscriptDisclosure {
    /** How many of the newest voice notes show their transcript without a tap (`:19`). */
    const val TAIL_COUNT = 2

    /** Longer transcripts wait for a tap even at the bottom (`:21`). */
    const val AUTO_OPEN_MAX_CHARACTERS = 400

    /** A note still being transcribed only unfolds to show progress when it is this short (`:23`). */
    const val AUTO_OPEN_MAX_WORKING_MS = 30_000

    /** Notes younger than this when their bubble appears arrived while the reader was watching (`:25`). */
    const val ARRIVAL_WINDOW_MS = 15_000L

    /** How long a new note stays folded so it can land before the transcript unfolds (`:27`). */
    const val LANDING_DELAY_MS = 350L

    /** The reader's own fold or unfold; wins over the automatic rule until the note leaves the tail. */
    private val choices = mutableStateMapOf<UUID, Boolean>()

    /** Server ids that took over a sent bubble, which carries on rather than arriving anew. */
    private val handedOff = HashSet<UUID>()

    /**
     * Notes whose landing already ran in this session. iOS pins it in the bubble's `@State`; a lazy
     * list disposes rows scrolled away, so the pin lives here (ids only) and a note lands once.
     */
    private val landed = HashSet<UUID>()

    fun choice(id: UUID): Boolean? = choices[id]

    fun setOpen(open: Boolean, id: UUID) {
        choices[id] = open
    }

    fun clearChoice(id: UUID) {
        choices.remove(id)
    }

    /** The server re-keyed a sent note; its bubble carries on under the new id (`:47-54`). */
    fun handOff(from: UUID, to: UUID) {
        if (from == to) return
        handedOff += to
        choices.remove(from)?.let { choices[to] = it }
        if (landed.remove(from)) landed += to
    }

    fun wasHandedOff(id: UUID): Boolean = id in handedOff

    fun hasLanded(id: UUID): Boolean = id in landed

    fun markLanded(id: UUID) {
        landed += id
    }

    /** Purged notes: forget their ids. */
    fun forget(ids: Collection<UUID>) {
        for (id in ids) {
            choices.remove(id)
            handedOff -= id
            landed -= id
        }
    }

    /** Ids of the newest voice notes with nothing newer under them, newest first (`:60-68`). */
    fun tail(messages: List<ChatMessage>): List<UUID> {
        val tail = ArrayList<UUID>(TAIL_COUNT)
        for (index in messages.indices.reversed()) {
            val message = messages[index]
            if (tail.size >= TAIL_COUNT || message.kind != ChatMessageKind.Voice || message.deleted) break
            tail += message.id
        }
        return tail
    }

    /**
     * Whether a note in the tail unfolds by itself: short text, or a short note still transcribing
     * (`:71-74`). Characters are counted as Swift does, by grapheme cluster.
     */
    fun opensUnasked(transcript: String?, durationMs: Int, isWorking: Boolean): Boolean {
        if (transcript != null) return graphemeCount(transcript) <= AUTO_OPEN_MAX_CHARACTERS
        return isWorking && durationMs <= AUTO_OPEN_MAX_WORKING_MS
    }

    private fun graphemeCount(text: String): Int {
        // Fast path: a transcript shorter than the limit in UTF-16 units is shorter in graphemes too.
        if (text.length <= AUTO_OPEN_MAX_CHARACTERS) return text.length
        val iterator = java.text.BreakIterator.getCharacterInstance()
        iterator.setText(text)
        var count = 0
        while (iterator.next() != java.text.BreakIterator.DONE) count++
        return count
    }

    /** For tests: forget everything. */
    internal fun reset() {
        choices.clear()
        handedOff.clear()
        landed.clear()
    }
}
