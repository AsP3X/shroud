package de.corespace.shroud.core.auth

import android.content.SharedPreferences

/**
 * Digests this install has already signed in with. Until then, login also sends the SHA-256.
 * The name itself is not stored. Missing preferences (a test, or a process that has not started
 * the network module) keep the memory for this process only.
 */
object UsernameMigration {
    private const val KEY = "digests"
    private val memory = mutableSetOf<String>()
    private var prefs: SharedPreferences? = null

    fun install(preferences: SharedPreferences) {
        prefs = preferences
    }

    fun isRemembered(digest: String): Boolean {
        if (digest in memory) return true
        val stored = prefs?.getStringSet(KEY, emptySet()).orEmpty()
        if (digest in stored) {
            memory.add(digest)
            return true
        }
        return false
    }

    fun remember(digest: String) {
        memory.add(digest)
        val current = prefs ?: return
        val next = current.getStringSet(KEY, emptySet()).orEmpty() + digest
        current.edit().putStringSet(KEY, next).apply()
    }

    /** Tests start each case with no remembered account. */
    internal fun reset() {
        memory.clear()
    }
}
