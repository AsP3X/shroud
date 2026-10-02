package de.corespace.shroud.core.push

/**
 * Drops a second delivery of the same message or call within [windowMs] (plan §1.7.10: 10 minutes).
 * The key includes the kind, so a ring and the later `call_ended` for one call both pass.
 */
class PushDedup(private val windowMs: Long = WINDOW_MS) {
    private val seen = LinkedHashMap<String, Long>()

    /** True the first time [key] is seen inside the window. */
    fun accept(key: String, now: Long): Boolean {
        prune(now)
        val previous = seen[key]
        if (previous != null && now - previous < windowMs) return false
        seen[key] = now
        return true
    }

    private fun prune(now: Long) {
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value >= windowMs) iterator.remove()
        }
    }

    companion object {
        const val WINDOW_MS = 10 * 60 * 1000L
    }
}
