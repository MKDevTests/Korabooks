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
import snd.komelia.offline.media.model.OfflineMedia
import snd.komelia.offline.mediacontainer.BookContentExtractors
import snd.komelia.offline.media.repository.OfflineMediaRepository
import snd.komelia.offline.readHeader
import snd.komelia.offline.series.repository.OfflineSeriesRepository
import snd.komga.client.book.KomgaBookId
import snd.komga.client.book.MediaProfile
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
    /** Absent on platforms with no extractors; the download then just skips the count. */
    private val extractors: BookContentExtractors? = null,
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

        val media = mediaRepository.find(book.id)
        val problem = looksWrong(file, media?.mediaProfile)
        if (problem != null) {
            deleteFile(file)
            throw IllegalStateException("${book.name}: $problem")
        }

        // localFileLastModified is what tells the rest of the app a book is
        // downloaded — the mirror writes zero for it, so a book only becomes
        // readable here, after the bytes are on disk.
        val downloaded = book.copy(
            fileDownloadPath = file,
            localFileLastModified = Clock.System.now(),
        )
        bookRepository.save(downloaded)
        if (media != null) countPages(downloaded, media)
        logger.info { "downloaded ${book.name} from ${book.url}" }
        komgaEvents.emit(KomgaEvent.BookChanged(book.id, book.seriesId, book.libraryId))
    }

    /**
     * Fills in what only the file itself can answer.
     *
     * The mirror stores a page count of zero for every book, because an OPDS
     * entry does not carry one. A PDF reader asks for that list before it draws
     * anything, so until somebody opens the file the book is a cover and
     * nothing else. This is the first moment the file exists.
     *
     * Best effort on purpose: a PDF that cannot be counted is still a PDF worth
     * keeping, and the reader falls back to asking again on open.
     */
    private suspend fun countPages(book: OfflineBook, media: OfflineMedia) {
        val extractors = extractors ?: return
        val counted = try {
            extractors.readPageList(book, media)
        } catch (e: Exception) {
            logger.warn(e) { "could not count the pages of ${book.name}" }
            null
        } ?: return
        mediaRepository.save(counted)
        logger.info { "${book.name}: ${counted.pageCount} pages" }
    }

    /**
     * Says why the downloaded bytes are not a book, or null when they look like one.
     *
     * Nothing on the way here reads the response: an expired Calibre-Web session
     * answers 200 with its login page, a proxy answers 200 with an error page,
     * and both were written to disk and marked downloaded. The reader then opened
     * an HTML page as if it were an epub. Four bytes tell them apart.
     */
    private suspend fun looksWrong(file: PlatformFile, profile: MediaProfile?): String? {
        val header = runCatching { file.readHeader(8) }.getOrNull() ?: return null
        if (header.isEmpty()) return "the server sent an empty file"

        val (magic, name) = when (profile) {
            MediaProfile.PDF -> PDF_MAGIC to "PDF"
            MediaProfile.EPUB, MediaProfile.DIVINA -> ZIP_MAGIC to "zip container"
            // Nothing to check against, and refusing what we cannot describe
            // would lose books over a profile the catalogue failed to state.
            null -> return null
        }
        if (header.size >= magic.size && magic.indices.all { header[it] == magic[it] }) return null

        // Almost always a page meant for a browser rather than a reader.
        val start = header.decodeToString().trimStart().lowercase()
        if (start.startsWith("<!do") || start.startsWith("<htm")) {
            return "the server sent a web page instead of the file — the session may have expired"
        }
        return "the downloaded file is not a $name"
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

    private companion object {
        /** "%PDF" */
        val PDF_MAGIC = byteArrayOf(0x25, 0x50, 0x44, 0x46)

        /** "PK" — epub, cbz and every other zip container. */
        val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    }
}
