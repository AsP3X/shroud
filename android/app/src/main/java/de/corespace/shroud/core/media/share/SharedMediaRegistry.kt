package de.corespace.shroud.core.media.share

import java.util.concurrent.ConcurrentHashMap

/**
 * Plaintext a share target may read, held only in memory. [DecryptedMediaProvider] is created
 * before [de.corespace.shroud.ShroudApplication.onCreate], so it reads this object and never the
 * container. [clear] drops every grant; a later [DecryptedMediaProvider.openFile] throws.
 */
internal object SharedMediaRegistry {
    class Item(val bytes: ByteArray, val mime: String)

    private val items = ConcurrentHashMap<String, Item>()

    fun put(id: String, bytes: ByteArray, mime: String) {
        items[id] = Item(bytes, mime)
    }

    fun open(id: String?): Item? = if (id.isNullOrEmpty()) null else items[id]

    fun clear() {
        items.clear()
    }
}
