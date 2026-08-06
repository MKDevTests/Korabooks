package snd.komelia.opds

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import snd.komga.client.book.KomgaBookId
import snd.komga.client.sse.KomgaEvent
import snd.komga.client.library.KomgaLibraryId
import snd.komga.client.series.KomgaSeriesId

private val logger = KotlinLogging.logger { }

/**
 * Shelves per transaction.
 *
 * Big enough that the transaction stops being the cost, small enough that
 * stopping a sync loses a second of work rather than a minute of it.
 */
private const val BATCH = 100

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
    /**
     * One event per batch, never one per book.
     *
     * The library redraws on these, and it is the only way it learns that rows
     * appeared underneath it. Twenty thousand of them would spend the sync
     * redrawing instead of writing; one per hundred shelves keeps a grid
     * filling in front of the reader at a cost nobody notices.
     */
    private val events: MutableSharedFlow<KomgaEvent>? = null,
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

        // Buffered, because a transaction costs far more than the seven inserts
        // a shelf needs. One at a time the phone wrote twenty-five books a
        // minute while the network delivered twelve hundred.
        val pending = mutableListOf<MappedShelf>()
        val pendingCovers = mutableMapOf<KomgaBookId, String>()

        suspend fun flush(title: String) {
            if (pending.isEmpty()) return
            currentCoroutineContext().ensureActive()
            writer.write(pending, pendingCovers)
            kept += pending.map { it.series.id }
            books += pending.sumOf { it.books.size }
            covers += pendingCovers.size
            val last = pending.last().series
            pending.clear()
            pendingCovers.clear()
            events?.emit(KomgaEvent.SeriesAdded(last.id, last.libraryId))
            onProgress(OpdsSyncProgress.Writing(books, books, title))
        }

        // Books first, and each one on its own shelf. This is the half that
        // makes a library exist: it costs a few hundred requests, and when it
        // is done everything is there to browse and to read.
        walker.walkBooks(
            rootUrl = catalogueUrl,
            onProgress = { onProgress(OpdsSyncProgress.Walking(it.shelves, it.books, it.current)) },
        ) { shelf ->
            val mapped = mapper.map(shelf)
            if (mapped.books.isNotEmpty()) {
                pending += mapped
                shelf.entries.forEach { entry ->
                    entry.thumbnail?.href?.let { pendingCovers[mapper.bookId(entry)] = it }
                }
                if (pending.size >= BATCH) flush(shelf.title)
            }
        }
        flush("")

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
                pending += mapped
                if (pending.size >= BATCH) {
                    currentCoroutineContext().ensureActive()
                    writer.write(pending, emptyMap())
                    kept += pending.map { it.series.id }
                    pending.clear()
                    onProgress(OpdsSyncProgress.Grouping(kept.size, books, shelf.title))
                }
            }
        }
        if (pending.isNotEmpty()) {
            writer.write(pending, emptyMap())
            kept += pending.map { it.series.id }
            pending.clear()
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
