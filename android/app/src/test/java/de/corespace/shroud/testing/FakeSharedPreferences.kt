package de.corespace.shroud.testing

import android.content.SharedPreferences

/**
 * In-memory [SharedPreferences] for JVM tests (the stub `android.jar` returns defaults and stores
 * nothing, `isReturnDefaultValues = true`). Mirrors `SharedPreferencesImpl.commitToMemory`:
 * - an editor's `clear()` runs before its puts and removes, whatever the call order;
 * - `remove(key)` and `putString(key, null)` / `putStringSet(key, null)` delete the key;
 * - writing an equal value is no change (no listener call);
 * - listeners hear `null` first after any `clear()` (API 30+, even of an empty file), then each
 *   changed key, last-modified first; calls are synchronous here (the platform posts them to
 *   the main thread);
 * - a stored or returned string set is a copy.
 *
 * Listeners are held strongly (the platform holds them weakly — keep a reference in production
 * code anyway). Reading a key as the wrong type throws [ClassCastException], as on a device.
 */
class FakeSharedPreferences(initial: Map<String, Any> = emptyMap()) : SharedPreferences {
    private val values = LinkedHashMap<String, Any>()
    private val listeners = LinkedHashSet<SharedPreferences.OnSharedPreferenceChangeListener>()

    /** Edits that reached memory (`apply` or `commit`), changed or not. */
    var editCount: Int = 0
        private set

    init {
        initial.forEach { (key, value) -> values[key] = copyOf(value) }
    }

    /** The stored keys, for "nothing left behind" assertions. */
    val keys: Set<String> get() = values.keys.toSet()

    override fun getAll(): Map<String, *> = values.mapValues { (_, value) -> copyOf(value) }

    override fun getString(key: String, defValue: String?): String? = values[key]?.let { it as String } ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        values[key]?.let { HashSet(it as Set<String>) } ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key]?.let { it as Int } ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key]?.let { it as Long } ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key]?.let { it as Float } ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key]?.let { it as Boolean } ?: defValue

    override fun contains(key: String): Boolean = key in values

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners += listener
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners -= listener
    }

    private inner class Editor : SharedPreferences.Editor {
        private val modified = LinkedHashMap<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?) = put(key, value)

        override fun putStringSet(key: String, values: Set<String>?) = put(key, values?.let { HashSet(it) })

        override fun putInt(key: String, value: Int) = put(key, value)

        override fun putLong(key: String, value: Long) = put(key, value)

        override fun putFloat(key: String, value: Float) = put(key, value)

        override fun putBoolean(key: String, value: Boolean) = put(key, value)

        override fun remove(key: String) = put(key, null)

        override fun clear(): SharedPreferences.Editor = apply { clear = true }

        override fun commit(): Boolean {
            writeToMemory()
            return true
        }

        override fun apply() {
            writeToMemory()
        }

        private fun put(key: String, value: Any?): SharedPreferences.Editor = apply {
            modified.remove(key)
            modified[key] = value
        }

        private fun writeToMemory() {
            editCount++
            val cleared = clear
            if (clear) {
                values.clear()
                clear = false
            }
            val changed = ArrayList<String>()
            for ((key, value) in modified) {
                if (value == null) {
                    if (values.remove(key) == null) continue
                } else {
                    if (values[key] == value) continue
                    values[key] = value
                }
                changed += key
            }
            modified.clear()
            val snapshot = listeners.toList()
            if (cleared) snapshot.forEach { it.onSharedPreferenceChanged(this@FakeSharedPreferences, null) }
            for (key in changed.asReversed()) snapshot.forEach { it.onSharedPreferenceChanged(this@FakeSharedPreferences, key) }
        }
    }

    private companion object {
        fun copyOf(value: Any): Any = if (value is Set<*>) HashSet(value) else value
    }
}
