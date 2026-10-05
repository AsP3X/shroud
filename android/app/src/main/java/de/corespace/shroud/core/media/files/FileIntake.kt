package de.corespace.shroud.core.media.files

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * A file picked to send (docs/file-sharing.md §2, §7): its cleaned [name], its [type] by that
 * name's extension, its size as the document provider reported it ([UNKNOWN_SIZE] when it did not —
 * the send then counts while it copies), and how to read it. Nothing is copied or persisted at
 * pick time; the send streams [open] into the sealed media cache. [uri] is the picked document, so
 * an image or video can go to the photo/video compose instead. [toString] never prints the name.
 */
class PickedFile(
    val name: String,
    val sizeBytes: Long,
    val type: FileType,
    val uri: Uri? = null,
    private val opener: () -> InputStream?,
) {
    /** A fresh stream over the file's bytes; the caller closes it. */
    fun open(): InputStream = opener() ?: throw FileNotFoundException("picked file is gone")

    override fun toString(): String = "PickedFile(type=${type.extension}, size=$sizeBytes)"

    companion object {
        /** The provider did not say how large the file is. */
        const val UNKNOWN_SIZE = -1L
    }
}

/**
 * Reads what `ACTION_OPEN_DOCUMENT` handed back (docs/file-sharing.md §7 "Attach"): the display
 * name and size through [OpenableColumns], no read grant persisted. Each pick is cleaned (§5) and
 * checked against the type table (§4) and the limits (§2); the refusals come back as the toasts'
 * sentences, one per pick, in pick order.
 */
class FileIntake(private val resolver: ContentResolver, private val io: CoroutineDispatcher = Dispatchers.IO) {
    /** The files to stage (at most [FileLimits.MAX_FILES_PER_SEND], in pick order) and what was refused. */
    data class Result(val files: List<PickedFile>, val refusals: List<String>)

    /** One pick as the provider described it. */
    class Candidate(val displayName: String?, val sizeBytes: Long, val uri: Uri? = null, val opener: () -> InputStream?)

    /** Describes and checks [uris] off the main thread. */
    suspend fun inspect(uris: List<Uri>): Result = withContext(io) { evaluate(uris.map(::describe)) }

    private fun describe(uri: Uri): Candidate {
        var name: String? = null
        var size = PickedFile.UNKNOWN_SIZE
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A provider that refuses the query: the name falls back to the URI, the size to the descriptor.
        }
        if (size < 0) size = descriptorLength(uri)
        return Candidate(name ?: uri.lastPathSegment, size, uri) { resolver.openInputStream(uri) }
    }

    private fun descriptorLength(uri: Uri): Long = try {
        resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it != AssetFileDescriptor.UNKNOWN_LENGTH && it >= 0 }
        } ?: PickedFile.UNKNOWN_SIZE
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        PickedFile.UNKNOWN_SIZE
    }

    companion object {
        /**
         * The rules of §2 and §7 over described picks, in order: an unsupported extension, an empty
         * file and one over 2 GB are each refused with their own sentence; past ten accepted files the
         * rest are dropped with "You can send up to 10 files at once." (once).
         */
        fun evaluate(candidates: List<Candidate>): Result {
            val files = ArrayList<PickedFile>()
            val refusals = ArrayList<String>()
            var dropped = false
            for (candidate in candidates) {
                val name = FileNames.clean(candidate.displayName.orEmpty())
                val type = FileTypes.forExtension(FileNames.extension(name))
                when {
                    type == null -> refusals += FileCopy.unsupported(name)
                    candidate.sizeBytes == 0L -> refusals += FileCopy.empty(name)
                    candidate.sizeBytes > FileLimits.MAX_PLAINTEXT_BYTES -> refusals += FileCopy.tooLarge(name)
                    files.size >= FileLimits.MAX_FILES_PER_SEND -> dropped = true
                    else -> files += PickedFile(name, candidate.sizeBytes, type, candidate.uri, candidate.opener)
                }
            }
            if (dropped) refusals += FileCopy.TOO_MANY
            return Result(files, refusals)
        }
    }
}
