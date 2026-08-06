package snd.komelia.offline.sync

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.io.Sink
import snd.komelia.offline.book.model.OfflineBook
import snd.komelia.offline.book.repository.OfflineBookRepository
import snd.komelia.offline.library.repository.OfflineLibraryRepository
import snd.komelia.offline.media.repository.OfflineMediaRepository
import snd.komelia.offline.series.repository.OfflineSeriesRepository
import snd.komga.client.book.KomgaBookId
import snd.komga.client.sse.KomgaEvent
import kotlin.time.Clock

private val logger = KotlinLogging.logger { }

/**
 * Streams one file out of a mirrored catalogue.
 *
 * Kept behind an interface because the offline module has no idea what OPDS is,
 * and should not: it knows a book row holds an address, and that somebody able
 * to reach that address will write the bytes into the sink it opened.
 */
interface CatalogueFileDownloader {
    /** Writes the whole file into [sink]. Used when keeping it. */
    suspend fun download(url: String, sink: Sink, onProgress: (read: Long, total: Long) -> Unit)

    /** Hands the file over chunk by chunk. Used when reading it once. */
    suspend fun stream(url: String, onChunk: suspend (ByteArray) -> Unit)
}

/**
 * Downloads a mirrored book so it can be read.
 *
 * A catalogue mirror holds everything about a book except the book: twenty
 * thousand rows describing files that live on a server. Opening one of them
 * failed with a file-not-found on an empty path, which is the honest result of
 * asking a reader to open a book nobody fetched.
 *
 * Deliberately separate from [BookDownloadService], which downloads from Komga
 * and needs a Komga server to answer four different endpoints before it can
 * start. Here the address is already in the row, put there by the sync.
 */
class CatalogueBookDownloader(
    private val libraryDownloadPath: Flow<PlatformFile>,
    private val bookRepository: OfflineBookRepository,
    private val seriesRepository: OfflineSeriesRepository,
    private val libraryRepository: OfflineLibraryRepository,
    private val mediaRepository: OfflineMediaRepository,
    private val downloader: CatalogueFileDownloader,
    private val komgaEvents: MutableSharedFlow<KomgaEvent>,
) {

    /** True for books this downloader is the right one for. */
    fun handles(book: OfflineBook) = book.url.startsWith("http://") || book.url.startsWith("https://")

    suspend fun download(
        bookId: KomgaBookId,
        onProgress: (read: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        val book = bookRepository.get(bookId)
        require(handles(book)) { "book ${bookId.value} has no catalogue address" }

        val series = seriesRepository.get(book.seriesId)
        val library = libraryRepository.get(book.libraryId)
        val (file, output) = prepareOutput(
            downloadRoot = libraryDownloadPath.first(),
            serverName = "catalogue",
            libraryName = library.name,
            seriesName = series.name,
            bookFileName = fileName(book),
        )

        try {
            downloader.download(book.url, output, onProgress)
        } catch (e: Exception) {
            output.close()
            deleteFile(file)
            throw e
        }
        output.close()

        // localFileLastModified is what tells the rest of the app a book is
        // downloaded — the mirror writes zero for it, so a book only becomes
        // readable here, after the bytes are on disk.
        bookRepository.save(
            book.copy(
                fileDownloadPath = file,
                localFileLastModified = Clock.System.now(),
            )
        )
        logger.info { "downloaded ${book.name} from ${book.url}" }
        komgaEvents.emit(KomgaEvent.BookChanged(book.id, book.seriesId, book.libraryId))
    }

    /**
     * A name a file system will accept, with the extension the reader expects.
     *
     * An OPDS acquisition link is a route, not a path: `/opds/download/4213/epub`
     * says nothing about what to call the result. The title does, once the
     * characters no directory tolerates are gone.
     */
    private suspend fun fileName(book: OfflineBook): String {
        val extension = when (val type = mediaRepository.find(book.id)?.mediaType) {
            null -> "epub"
            else -> type.substringAfterLast('/')
                .removePrefix("x-")
                .removePrefix("vnd.")
                .substringBefore('+')
                .takeIf { it.isNotBlank() && it.length <= 5 }
                ?: "epub"
        }
        val safe = book.name
            .map { if (it.isLetterOrDigit() || it in " -_.()'") it else '_' }
            .joinToString("")
            .trim()
            .take(120)
            .ifBlank { book.id.value }
        return if (safe.endsWith(".$extension", ignoreCase = true)) safe else "$safe.$extension"
    }
}
