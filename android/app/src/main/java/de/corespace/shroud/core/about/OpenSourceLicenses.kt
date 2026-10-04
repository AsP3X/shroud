package de.corespace.shroud.core.about

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream

/**
 * One third-party component of the app (Settings › About Shroud › Open-Source Licenses), as
 * `scripts/generate_licenses.py` wrote it to `assets/licenses/third_party.json`.
 *
 * @property version null for a pooled entry ("AndroidX libraries"), whose [artifacts] carry theirs.
 * @property license an SPDX expression ("Apache-2.0", "Apache-2.0 AND BSD-3-Clause").
 * @property text the license-text file in `assets/licenses/`.
 * @property kind "Library", "Native code", "Font", "Icons", "Data" or "Model".
 * @property artifacts the Maven modules ("group:name:version") or the files it ships as.
 */
@Serializable
data class LicensedComponent(
    val id: String,
    val name: String,
    val version: String?,
    val license: String,
    val text: String,
    val kind: String,
    val artifacts: List<String>,
)

/**
 * The bundled license list and texts, read from the APK's assets off the main thread. The list is
 * read once per process; texts are read each time one is opened. [open] opens an asset path
 * (`AssetManager.open`).
 */
class OpenSourceLicenses(
    private val open: (String) -> InputStream,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()
    private var cached: List<LicensedComponent>? = null

    /** Every component, sorted by name as the asset lists them. Throws when the asset is unreadable. */
    suspend fun components(): List<LicensedComponent> = lock.withLock {
        cached ?: withContext(io) { parse(read(CATALOG)) }.also { cached = it }
    }

    /** The component with [id], or null. */
    suspend fun component(id: String): LicensedComponent? = components().firstOrNull { it.id == id }

    /** [component]'s full license text. Throws when the asset is unreadable. */
    suspend fun text(component: LicensedComponent): String = withContext(io) { read(DIRECTORY + component.text) }

    private fun read(path: String): String = open(path).bufferedReader().use { it.readText() }

    companion object {
        const val DIRECTORY = "licenses/"
        const val CATALOG = DIRECTORY + "third_party.json"

        /** The order the kinds are listed in; unknown kinds go last. */
        val KIND_ORDER = listOf("Library", "Native code", "Font", "Icons", "Data", "Model")

        private val json = Json { ignoreUnknownKeys = true }

        @Serializable
        private data class Catalog(val components: List<LicensedComponent>)

        fun parse(text: String): List<LicensedComponent> = json.decodeFromString(Catalog.serializer(), text).components
    }
}
