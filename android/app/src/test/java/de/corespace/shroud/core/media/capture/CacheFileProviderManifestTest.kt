package de.corespace.shroud.core.media.capture

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * `FileProvider.attachInfo` throws `SecurityException` unless `grantUriPermissions` is true,
 * and that runs while the process is binding, before any screen. The cache provider is how
 * camera captures leave `file://` behind.
 */
class CacheFileProviderManifestTest {
    @Test
    fun cacheFileProviderGrantsUriPermissionsAndIsNotExported() {
        val manifest = manifestFile()
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val doc = factory.newDocumentBuilder().parse(manifest)
        val providers = doc.getElementsByTagName("provider")
        val cache = (0 until providers.length)
            .map { providers.item(it) as Element }
            .single { it.getAttribute("android:name") == "androidx.core.content.FileProvider" }
        assertTrue(cache.getAttribute("android:authorities").endsWith(".cache"))
        assertEquals("false", cache.getAttribute("android:exported"))
        assertEquals("true", cache.getAttribute("android:grantUriPermissions"))
    }

    private fun manifestFile(): File {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
    }
}
