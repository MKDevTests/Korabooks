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
import kotlin.time.TimeSource

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

/**
 * Unopenable books named individually before the log settles for a total.
 *
 * Enough to tell a handful of MOBI-only oddities from a format preference that
 * is wrong for the whole library, without printing a line per book.
 */
private const val UNREADABLE_SAMPLE = 20

data class OpdsSyncResult(
    val libraryId: KomgaLibraryId,
    val shelves: Int,
    val books: Int,
    val covers: Int,
    /**
     * What the catalogue said it holds, against what was read.
     *
     * Null on a quick sync, which reads the newest books on purpose and would
     * be short by design, and on a catalogue that publishes no count.
     */
    val expectedBooks: Int? = null,
    /** Index pages the server never gave up, after every retry. */
    val lostPages: Int = 0,
    /**
     * Books that had their file on the device before this sync and not after.
     *
     * Always zero, and worth reporting precisely because of that: it went
     * wrong once, silently, and the reader found out days later. A number that
     * should be zero is only useful if somebody looks at it.
     */
    val downloadsLost: Int = 0,
) {
    /**
     * Whether the catalogue was read whole.
     *
     * The question the reader actually has after a sync, and the one the old
     * result could not answer: a run that lost half the index reported six
     * thousand books in the same words a six thousand book library would.
     */
    val complete: Boolean
        get() = lostPages == 0 && downloadsLost == 0 &&
            (expectedBooks == null || books >= expectedBooks)

    /** Books the catalogue announced and the walk never saw. */
    val missing: Int get() = expectedBooks?.let { (it - books).coerceAtLeast(0) } ?: 0
}

/** Where the sync is, for a screen that shows it. */
sealed interface OpdsSyncProgress {
    data class Walking(val shelves: Int, val books: Int, val current: String) : OpdsSyncProgress
    data class Writing(val done: Int, val total: Int, val current: String) : OpdsSyncProgress

    /** The second pass: the library is already usable while this runs. */
    data class Grouping(val series: Int, val books: Int, val current: String) : OpdsSyncProgress
}

/**
 * One sentence saying where a sync is, for a human to read.
 *
 * Here rather than in a screen because two places show it now — the catalogue
 * settings and the notification that keeps a multi-hour sync visible when the
 * app is not on screen — and two copies of this text would drift apart on the
 * first wording change.
 */
fun OpdsSyncProgress.describe(): String = when (this) {
    is OpdsSyncProgress.Walking -> "Lecture du catalogue — $books livres, $current"
    is OpdsSyncProgress.Writing -> "$done livres enregistrés"

    // The grouping pass spends its first stretch reading the series index —
    // thousands of requests before a single series has been regrouped. Calling
    // that "Regroupement — 0 séries" is what made the sync look stuck exactly
    // where it was working hardest.
    is OpdsSyncProgress.Grouping ->
        if (series == 0) "Lecture des séries — $current"
        else "Regroupement — $series séries, $current"
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
            // One shelf per book, exactly as the books pass writes them —
            // including the grouping, when the catalogue names the series.
            //
            // It used to always write the standalone shelf and leave grouping
            // to a full sync, which on this catalogue is twenty minutes of a
            // server that renders two hundred books in twenty seconds and
            // renders them one request at a time. So every book added between
            // two full syncs sat outside its series, and the only way to put
            // it back was to re-read eleven thousand books to find the four
            // that moved. The series name is on the entry: the quick sync can
            // read it as cheaply as the slow one, and mostly removes the
            // reason to run the slow one at all.
            val shelves = page.entries.map { entry ->
                val series = entry.seriesName
                if (series != null) mapper.map(OpdsShelf(series, listOf(entry), standalone = false))
                else mapper.map(OpdsShelf(entry.title, listOf(entry), standalone = true))
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

        // A book joining a series rewrites that series' row from the one entry
        // it arrived on — one book, one book's tags. Harmless in a full sync,
        // which recounts everything at the end; this one has to ask for it, and
        // only when something actually landed. Reads the mirror, asks the
        // server nothing, and takes a second where the walk took twenty.
        val recounted = if (added > 0) writer.refreshSeriesAggregates(libraryId) else 0

        logger.info { "OPDS quick sync done: $added new books, $recounted counts corrected" }
        return OpdsSyncResult(libraryId, touched.size, added, covers)
    }

    /**
     * Reads a whole catalogue: every book, and the series not yet grouped.
     *
     * Grouping a shelf costs one request against a server that answers one at a
     * time, so a shelf already grouped is left alone — always, not on request.
     * There used to be a `resume` flag for that and a default that re-read all
     * of them; the default meant that adding one book to the catalogue cost
     * thirty minutes, which is not a choice anybody would make knowingly. What
     * `resume` did is now simply what this does, and an interrupted sync is
     * continued by running it again.
     */
    suspend fun sync(
        catalogueUrl: String,
        catalogueName: String,
        /**
         * Regroup every series, including the ones already grouped.
         *
         * The escape hatch for the two things this cannot otherwise see: a
         * grouping that was wrong when it was made, and a volume swapped for
         * another without the total moving. Costs one request per series, which
         * is the whole of a long sync — so it is asked for, never assumed.
         */
        force: Boolean = false,
        onProgress: (OpdsSyncProgress) -> Unit = {},
    ): OpdsSyncResult {
        val libraryId = writer.library(catalogueUrl, catalogueName)
        val mapper = OpdsMapper(libraryId = libraryId, catalogueId = catalogueUrl)
        // Logged per request, with what it cost: when a sync looks stuck, the
        // only useful questions are whether it is still asking the server for
        // things and whether the server is what it is waiting for. Twenty-one
        // seconds between two pages says nothing on its own — the walk also
        // waits on the writing queue behind it — and the two are told apart
        // only by timing the fetch itself.
        var fetchMillis = 0L
        val walker = OpdsCatalogueWalker(fetch = { url ->
            val started = TimeSource.Monotonic.markNow()
            val feed = client.feed(url)
            val took = started.elapsedNow()
            fetchMillis += took.inWholeMilliseconds
            logger.info { "OPDS fetch $url — ${took.inWholeMilliseconds} ms, ${feed.entries.size} entries" }
            feed
        })

        val kept = mutableSetOf<KomgaSeriesId>()
        var books = 0
        var covers = 0
        var writeMillis = 0L

        // What the mirror holds before we touch it, read in one pass. Two things
        // come out of it and both decide how long this sync takes: which series
        // are already grouped (so the books pass stops tearing them apart), and
        // how many books there were (so the grouping pass can be skipped when
        // the catalogue has not moved).
        // The one thing a sync must never cost the reader. Everything else it
        // touches can be rebuilt by running it again; a file it drops is a
        // download to do over, and on a phone that is not always possible.
        val downloadedBefore = writer.downloadedCount(libraryId)

        val before = writer.seriesBookCounts(libraryId)
        // Minus the shelves that hold two books because two books were merged
        // onto them. Those look grouped and are not, and sparing them would
        // make this sync preserve the very thing it is here to undo.
        val merged = writer.mergedShelves(libraryId, before)
        val grouped = before.filterValues { it > 1 }.keys - merged
        val booksBefore = before.values.sum()
        logger.info { "OPDS mirror holds $booksBefore books in ${before.size} shelves, ${grouped.size} grouped" }
        if (merged.isNotEmpty()) {
            logger.info { "OPDS ${merged.size} shelves are merged homonyms — their books will be split back out" }
        }

        // Buffered, because a transaction costs far more than the seven inserts
        // a shelf needs. One at a time the phone wrote twenty-five books a
        // minute while the network delivered twelve hundred.
        val pending = mutableListOf<MappedShelf>()
        val pendingCovers = mutableMapOf<KomgaBookId, String>()

        suspend fun flush(title: String) {
            if (pending.isEmpty()) return
            currentCoroutineContext().ensureActive()
            // The series whose books kept their parent are kept too: they were
            // never written this pass, and prune only spares what it is told.
            // The standalone shelves that were deliberately not created are the
            // one thing not claimed — they do not exist to be spared.
            val startedWrite = TimeSource.Monotonic.markNow()
            val written = writer.write(pending, pendingCovers, grouped)
            writeMillis += startedWrite.elapsedNow().inWholeMilliseconds
            kept += written.preserved
            kept += pending.map { it.series.id } - written.skipped
            books += pending.sumOf { it.books.size }
            covers += pendingCovers.size
            val last = pending.last().series
            pending.clear()
            pendingCovers.clear()
            events?.emit(KomgaEvent.SeriesAdded(last.id, last.libraryId))
            onProgress(OpdsSyncProgress.Writing(books, books, title))
        }

        // What the books pass managed to read: how many books, how many named
        // their own series (zero means the catalogue does not say, and the
        // grouping pass is the only way to find out), and how much of the index
        // the server never handed over.
        var walk = OpdsBooksWalk(books = 0, grouped = 0, expected = null, lostPages = 0)

        /** Books the catalogue holds in no format this app can open. */
        var unreadable = 0

        // Books first. This is the half that makes a library exist: it costs a
        // few hundred requests, and when it is done everything is there to browse
        // and to read — grouped too, on a catalogue that names its series.
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
                    // A shelf whose books offer no format this app can open —
                    // MOBI or AZW alone, typically. Dropping them is deliberate
                    // (see OpdsMapper.DEFAULT_FORMATS: offering a book we cannot
                    // open is a promise broken at the last tap), but dropping
                    // them silently meant a library that arrived thirteen books
                    // short with nothing anywhere to say which, or why.
                    if (mapped.books.isEmpty()) {
                        unreadable++
                        if (unreadable <= UNREADABLE_SAMPLE) {
                            logger.info {
                                "OPDS no readable format for \"${shelf.title}\" — " +
                                    shelf.entries.flatMap { it.acquisitions }
                                        .mapNotNull { it.type }
                                        .ifEmpty { listOf("no acquisition link") }
                                        .joinToString()
                            }
                        }
                        continue
                    }
                    pending += mapped
                    shelf.entries.forEach { entry ->
                        entry.thumbnail?.href?.let { pendingCovers[mapper.bookId(entry)] = it }
                    }
                    if (pending.size >= BATCH) flush(shelf.title)
                }
                flush("")
            }

            walk = walker.walkBooks(
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
        //
        // Skipped outright in two cases, and this is where a sync stops costing
        // forty-five minutes:
        //
        //  - **the catalogue named its series itself**, so the books pass already
        //    did the grouping and opening 1729 shelves would only confirm it. The
        //    single biggest win available, and it is free.
        //  - **nothing moved**: the catalogue holds exactly the books it held last
        //    time and the shelves are already grouped. Membership cannot have
        //    changed without a book appearing or disappearing, and the books pass
        //    no longer tears grouped series apart, so there is nothing to repair.
        //
        // [force] overrides both, for what neither can see: a grouping that was
        // wrong when it was made, or a volume swapped for another one-for-one.
        val named = walk.grouped
        val settledByCatalogue = named > 0
        val settledByCount = grouped.isNotEmpty() && books == booksBefore
        val settled = !force && (settledByCatalogue || settledByCount)
        if (settled) {
            logger.info {
                if (settledByCatalogue) "OPDS grouping skipped: the catalogue named $named books' series"
                else "OPDS grouping skipped: $books books, unchanged, ${grouped.size} shelves already grouped"
            }
            kept += before.keys
        }

        if (!settled) coroutineScope {
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

            // Read before the books pass, and still true: that pass no longer
            // moves a book out of a grouped series, so the counts it saw hold.
            val counts = before

            // Every shelf already grouped, left alone unless asked otherwise.
            // Two books never landed on one shelf by accident, so this is not a
            // guess: it is the record of a grouping pass that already happened.
            val already = if (force) emptySet() else grouped
            if (already.isNotEmpty()) {
                logger.info { "OPDS ${already.size} series already grouped — left alone" }
                kept += already
            }

            // Shelves the index says are unchanged, so they are neither read nor
            // pruned. Recorded as they are decided because the walk is what asks.
            val unchanged = mutableSetOf<KomgaSeriesId>()

            walker.walkSeries(
                rootUrl = catalogueUrl,
                onProgress = { onProgress(OpdsSyncProgress.Grouping(it.shelves, it.books, it.current)) },
                skip = { title, count ->
                    val id = mapper.seriesId(title)
                    when {
                        id in already -> true

                        // The count the catalogue publishes against the count we
                        // hold. Only above one: at one book a shelf is
                        // indistinguishable from the standalone shelf the books
                        // pass leaves behind, and a real single-volume series
                        // would then never be grouped at all. Costing one
                        // request per one-book series is the cheap half of that
                        // trade.
                        count != null && count > 1 && counts[id] == count -> {
                            unchanged += id
                            true
                        }

                        else -> false
                    }
                },
            ) { shelf -> queue.send(shelf) }

            queue.close()
            grouping.join()

            // Not read, but still real: prune deletes every shelf absent from
            // [kept], and skipping a series without saying so here would delete
            // the library we just decided was up to date. After the join, because
            // [kept] belongs to the grouping coroutine until it finishes.
            if (unchanged.isNotEmpty()) {
                logger.info { "OPDS ${unchanged.size} series unchanged by count — not re-read" }
                kept += unchanged
            }
        }

        writer.prune(libraryId, kept)
        // The one-book shelves whose book has just moved into a series. They
        // were real a minute ago, which is why they are found by being empty
        // rather than by being predicted.
        val emptied = writer.pruneEmptySeries(libraryId)
        // Last, because they describe what the pruning leaves behind.
        val recounted = writer.refreshSeriesAggregates(libraryId)
        // A book that changed shelf took its read progress with it — the
        // progress is the book's — but not the counts the *shelf* publishes,
        // which nothing recomputes on a move. Redone wholesale here, so a
        // series the reader finished is not reported unread by the sync that
        // regrouped it.
        writer.rebuildReadProgressAggregates()
        if (unreadable > 0) {
            logger.warn { "OPDS $unreadable books skipped: no format this app can open" }
        }
        // Checked, not assumed. The rewrite that lost these once looked
        // perfectly correct on the way in, and the loss surfaced days later as
        // a book the reader thought was on the device.
        val downloadedAfter = writer.downloadedCount(libraryId)
        if (downloadedAfter < downloadedBefore) {
            logger.error {
                "OPDS ${downloadedBefore - downloadedAfter} downloaded books lost their file " +
                    "during this sync ($downloadedBefore before, $downloadedAfter after) — " +
                    "the files are still on disk and nothing points at them any more"
            }
        }

        val result = OpdsSyncResult(
            libraryId = libraryId,
            shelves = kept.size - emptied,
            books = books,
            covers = covers,
            expectedBooks = walk.expected,
            lostPages = walk.lostPages,
            downloadsLost = (downloadedBefore - downloadedAfter).coerceAtLeast(0),
        )
        logger.info {
            "OPDS sync done: ${result.shelves} shelves, $books books, " +
                "$recounted counts corrected, $unreadable unreadable"
        }
        // The one number that decides what to make faster next. A walk that
        // spends its time in the network is a walk to parallelise; one that
        // spends it in SQLite is not, and no amount of concurrency upstream
        // would move it.
        logger.info { "OPDS time: ${fetchMillis / 1000}s asking the server, ${writeMillis / 1000}s writing" }
        // Loud, and returned, because the failure this guards against is the
        // quiet one: every earlier truncated run ended by announcing its own
        // short count as the catalogue.
        if (!result.complete) {
            logger.error {
                "OPDS sync INCOMPLETE: ${result.missing} of ${walk.expected} books never read, " +
                    "${result.lostPages} index pages lost — run it again"
            }
        }
        return result
    }

}
