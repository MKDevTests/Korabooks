package snd.komelia.opds

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
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
 *
 * Raised from a hundred once the batch started sharing its statements: the
 * per-book cost is now low enough that the commit itself is what shows.
 */
private const val BATCH = 250

/**
 * Shelves the network may run ahead of the disk.
 *
 * Deep enough that a slow transaction does not stall the walk, shallow enough
 * that a catalogue arriving faster than SQLite can absorb it waits rather than
 * fills the heap. Ten pages of sixty.
 */
private const val QUEUE_DEPTH = 600

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

    /**
     * Only what the catalogue gained since last time.
     *
     * The full sync re-reads twenty thousand books to discover the four that
     * were added, and takes twenty minutes doing it. This reads the catalogue's
     * newest-first feed and stops at the first page it already knows entirely —
     * a catalogue that gained nothing costs one request.
     *
     * It deliberately does not regroup and does not prune. Grouping is one
     * request per series and would undo the whole point; pruning cannot be
     * decided from a feed that was never meant to be exhaustive. Both remain
     * the full sync's business.
     */
    suspend fun syncRecent(
        catalogueUrl: String,
        catalogueName: String,
        onProgress: (OpdsSyncProgress) -> Unit = {},
    ): OpdsSyncResult {
        val libraryId = writer.library(catalogueUrl, catalogueName)
        val mapper = OpdsMapper(libraryId = libraryId, catalogueId = catalogueUrl)
        val walker = OpdsCatalogueWalker(fetch = { url ->
            logger.info { "OPDS fetch $url" }
            client.feed(url)
        })

        var added = 0
        var covers = 0
        val touched = mutableSetOf<KomgaSeriesId>()

        walker.walkRecent(
            rootUrl = catalogueUrl,
            onProgress = { onProgress(OpdsSyncProgress.Walking(added, it.books, it.current)) },
        ) { page ->
            currentCoroutineContext().ensureActive()
            // One shelf per book, exactly as the first pass writes them: a
            // newly added book has no series until a full sync says otherwise.
            val shelves = page.entries.map { entry ->
                mapper.map(OpdsShelf(entry.title, listOf(entry), standalone = true))
            }.filter { it.books.isNotEmpty() }

            val unknown = shelves.filter { !writer.hasBook(it.books.first().id) }
            if (unknown.isNotEmpty()) {
                val newCovers = mutableMapOf<KomgaBookId, String>()
                page.entries.forEach { entry ->
                    entry.thumbnail?.href?.let { newCovers[mapper.bookId(entry)] = it }
                }
                writer.write(unknown, newCovers)
                added += unknown.size
                covers += newCovers.size
                touched += unknown.map { it.series.id }
                events?.emit(KomgaEvent.SeriesAdded(unknown.last().series.id, libraryId))
                onProgress(OpdsSyncProgress.Writing(added, added, page.title))
            }
            unknown.isEmpty()
        }

        logger.info { "OPDS quick sync done: $added new books" }
        return OpdsSyncResult(libraryId, touched.size, added, covers)
    }

    /**
     * Reads a whole catalogue.
     *
     * [resume] picks the grouping pass up where a stopped one left off instead
     * of paying for it again. It is a separate button rather than the default
     * because the two are different questions: a resumed pass trusts what the
     * mirror already says about a shelf, and re-reading a catalogue precisely
     * to find out whether that is still true is what the full one is for.
     */
    suspend fun sync(
        catalogueUrl: String,
        catalogueName: String,
        resume: Boolean = false,
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
        //
        // The walk hands shelves to a queue instead of writing them, and one
        // coroutine drains it. Writing inline was writing them under the walk's
        // own lock: a page arrived, its sixty books were mapped and every
        // hundredth triggered a transaction, and the fifteen other branches sat
        // waiting for the lock rather than asking for their next page. Measured
        // on a real library, the network was idle sixty-nine percent of the time
        // and never had more than two requests in flight. The two halves now
        // run at their own speed, and the sync costs the slower of them instead
        // of their sum.
        coroutineScope {
            val queue = Channel<OpdsShelf>(capacity = QUEUE_DEPTH)
            val writing = launch {
                for (shelf in queue) {
                    val mapped = mapper.map(shelf)
                    if (mapped.books.isEmpty()) continue
                    pending += mapped
                    shelf.entries.forEach { entry ->
                        entry.thumbnail?.href?.let { pendingCovers[mapper.bookId(entry)] = it }
                    }
                    if (pending.size >= BATCH) flush(shelf.title)
                }
                flush("")
            }

            walker.walkBooks(
                rootUrl = catalogueUrl,
                onProgress = { onProgress(OpdsSyncProgress.Walking(it.shelves, it.books, it.current)) },
            ) { shelf ->
                // Bounded, so a fast network cannot outrun the phone's disk into
                // an out-of-memory: once the queue is full the walk waits here,
                // which is the backpressure that used to be a lock.
                queue.send(shelf)
            }
            queue.close()
            writing.join()
        }

        // Series afterwards, and this is the slow half: one request per series,
        // thousands of them. Each shelf regroups books that are already in the
        // library — the book rows keep their identity and change parent — so a
        // grouping pass interrupted halfway leaves a library that is merely
        // less tidy, never one that is missing something.
        coroutineScope {
            val queue = Channel<OpdsShelf>(capacity = QUEUE_DEPTH)
            val grouping = launch {
                for (shelf in queue) {
                    val mapped = mapper.map(shelf)
                    if (mapped.books.isEmpty()) continue
                    pending += mapped
                    if (pending.size >= BATCH) {
                        currentCoroutineContext().ensureActive()
                        writer.regroup(pending)
                        kept += pending.map { it.series.id }
                        pending.clear()
                        onProgress(OpdsSyncProgress.Grouping(kept.size, books, shelf.title))
                    }
                }
                if (pending.isNotEmpty()) {
                    writer.regroup(pending)
                    kept += pending.map { it.series.id }
                    pending.clear()
                }
            }

            // What a stopped pass already achieved, read once. One request per
            // series and thousands of them is the whole cost of a sync, and a
            // resumed pass that re-bought them would be a button that does
            // nothing.
            val already = if (resume) writer.groupedSeries(libraryId) else emptySet()
            if (already.isNotEmpty()) {
                logger.info { "OPDS resuming: ${already.size} series already grouped" }
                kept += already
            }

            walker.walkSeries(
                rootUrl = catalogueUrl,
                onProgress = { onProgress(OpdsSyncProgress.Grouping(it.shelves, it.books, it.current)) },
                skip = { title -> mapper.seriesId(title) in already },
            ) { shelf -> queue.send(shelf) }
            queue.close()
            grouping.join()
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
