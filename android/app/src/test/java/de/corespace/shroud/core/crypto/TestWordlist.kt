package de.corespace.shroud.core.crypto

import java.io.File

/** The app's own asset, read from the source tree (unit tests have no AssetManager). */
object TestWordlist {
    val bip39: Bip39 by lazy {
        val file = listOf("src/main/assets/bip39-english.txt", "app/src/main/assets/bip39-english.txt").map(::File).first { it.exists() }
        Bip39(file.readLines().filter { it.isNotBlank() })
    }
}
