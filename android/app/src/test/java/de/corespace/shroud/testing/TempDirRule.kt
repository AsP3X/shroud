package de.corespace.shroud.testing

import org.junit.rules.ExternalResource
import java.io.File
import java.nio.file.Files

/**
 * A fresh directory per test, deleted afterwards, with stand-ins for the app's storage roots
 * (00-plan §1.5): sealed stores take a directory, never a `Context`, so tests point them here.
 *
 * ```
 * @get:Rule val temp = TempDirRule()
 * val store = SealedFile(temp.file("session.sealed"), XorSealer())
 * ```
 */
class TempDirRule : ExternalResource() {
    private var dir: File? = null

    /** The test's directory; only valid while the test runs. */
    val root: File get() = checkNotNull(dir) { "TempDirRule is not active: declare it as a @get:Rule" }

    /** Stand-in for `Context.noBackupFilesDir`. */
    val noBackupFilesDir: File get() = dir("no_backup")

    /** Stand-in for `Context.cacheDir` (where `SensitiveTempFiles` live). */
    val cacheDir: File get() = dir("cache")

    /** A file under [root] (parents created, the file itself not). */
    fun file(path: String): File = File(root, path).also { it.parentFile?.mkdirs() }

    /** A directory under [root], created. */
    fun dir(path: String): File = File(root, path).also { it.mkdirs() }

    /** Every file under [root], relative and sorted — for "nothing left behind" assertions. */
    fun listFiles(): List<String> =
        root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.sorted().toList()

    override fun before() {
        dir = Files.createTempDirectory("shroud-test").toFile()
    }

    override fun after() {
        dir?.deleteRecursively()
        dir = null
    }
}
