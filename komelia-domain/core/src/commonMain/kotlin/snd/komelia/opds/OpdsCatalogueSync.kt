package snd.komelia.opds

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import snd.komga.client.library.KomgaLibraryId
import snd.komga.client.series.KomgaSeriesId

private val logger = KotlinLogging.logger { }

data class OpdsSyncResult(
    val libraryId: KomgaLibraryId,
    val shelves: Int,
    val books: Int,
    val covers: Int,
)

/** Where the sync is, for a screen that shows it. */
sealed interface OpdsSyncProgress {
    data class Walking(val shelves: Int, val books: Int, val current: String) : OpdsSyncProgress
    data class Writing(val done: Int, val total: Int, val current: String) : OpdsSyncProgress

    /** The second pass: the library is already usable while this runs. */
    data class Grouping(val series: Int, val books: Int, val current: String) : OpdsSyncProgress
}

/**
 * Reads a catalogue and writes it into the local mirror.
 *
 * The three steps are kept apart on purpose — walking is where a server's shape
 * is guessed at, mapping is where meaning is decided, writing is where rows are
 * made — and each is tested without the other two. This class is only the order
 * they happen in.
 *
 * Covers are fetched shelf by shelf rather than up front: a library of five
 * hundred books is five hundred small requests, and doing them alongside the
 * writes means the first shelves are already browsable while the rest arrive.
 */
class OpdsCatalogueSync(
    private val client: OpdsClient,
    private val writer: OpdsMirrorWriter,
) {

    suspend fun sync(
        catalogueUrl: String,
        catalogueName: String,
        onProgress: (OpdsSyncProgress) -> Unit = {},
    ): OpdsSyncResult {
        val libraryId = writer.library(catalogueUrl, catalogueName)
        val mapper = OpdsMapper(libraryId = libraryId, catalogueId = catalogueUrl)
        // Logged per request: when a sync looks stuck, the only useful question
        // is whether it is still asking the server for things, and logcat can
        // answer it without a screen.
        val walker = OpdsCatalogueWalker(fetch = { url ->
            logger.info { "OPDS fetch $url" }
            client.feed(url)
        })

        val kept = mutableSetOf<KomgaSeriesId>()
        var books = 0
        var covers = 0

        suspend fun write(shelf: OpdsShelf, phase: (Int, String) -> OpdsSyncProgress) {
            currentCoroutineContext().ensureActive()
            val mapped = mapper.map(shelf)
            if (mapped.books.isEmpty()) return
            val coverUrls = shelf.entries.mapNotNull { entry ->
                entry.thumbnail?.href?.let { mapper.bookId(entry) to it }
            }.toMap()

            writer.write(mapped, coverUrls)
            kept += mapped.series.id
            books += mapped.books.size
            covers += coverUrls.size
            onProgress(phase(books, shelf.title))
        }

        // Books first, and each one on its own shelf. This is the half that
        // makes a library exist: it costs a few hundred requests, and when it
        // is done everything is there to browse and to read.
        walker.walkBooks(
            rootUrl = catalogueUrl,
            onProgress = { onProgress(OpdsSyncProgress.Walking(it.shelves, it.books, it.current)) },
        ) { shelf -> write(shelf) { count, title -> OpdsSyncProgress.Writing(count, count, title) } }

        // Series afterwards, and this is the slow half: one request per series,
        // thousands of them. Each shelf regroups books that are already in the
        // library — the book rows keep their identity and change parent — so a
        // grouping pass interrupted halfway leaves a library that is merely
        // less tidy, never one that is missing something.
        walker.walkSeries(
            rootUrl = catalogueUrl,
            onProgress = { onProgress(OpdsSyncProgress.Grouping(it.shelves, it.books, it.current)) },
        ) { shelf ->
            val mapped = mapper.map(shelf)
            if (mapped.books.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                writer.write(mapped, emptyMap())
                kept += mapped.series.id
                onProgress(OpdsSyncProgress.Grouping(kept.size, books, shelf.title))
            }
        }

        writer.prune(libraryId, kept)
        // The one-book shelves whose book has just moved into a series. They
        // were real a minute ago, which is why they are found by being empty
        // rather than by being predicted.
        val emptied = writer.pruneEmptySeries(libraryId)
        logger.info { "OPDS sync done: ${kept.size - emptied} shelves, $books books" }
        return OpdsSyncResult(libraryId, kept.size - emptied, books, covers)
    }

}
