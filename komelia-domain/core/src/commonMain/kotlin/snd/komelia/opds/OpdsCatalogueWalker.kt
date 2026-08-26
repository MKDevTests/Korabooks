package snd.komelia.opds

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

private val logger = KotlinLogging.logger { }

/** Ends a walk early without unwinding it as a failure. */
private class StopWalk : Exception()

/**
 * What a walk of every book actually managed to read.
 *
 * The count alone was the answer for a long time, and it was the wrong answer:
 * a walk that lost half the catalogue to a timing-out server returned six
 * thousand two hundred books and nothing said that eleven thousand were on
 * offer. A caller that cannot tell a small library from a truncated read of a
 * large one cannot warn anybody.
 *
 * @param books distinct books actually read
 * @param grouped how many named their own series — whether the grouping pass
 *   has anything left to learn
 * @param expected what the catalogue said it holds, when it says so at all.
 *   Null means no branch published a count, not that nothing is missing.
 * @param lostPages addresses given up on after every retry, including the
 *   second pass
 */
data class OpdsBooksWalk(
    val books: Int,
    val grouped: Int,
    val expected: Int?,
    val lostPages: Int,
)

/**
 * Requests in flight at once.
 *
 * Sixteen keeps a home server's connection busy through the latency of the last
 * answer without behaving like a crawler against a machine that also has to
 * serve the reader's browser.
 *
 * It only means anything if the HTTP client allows as much: eight here against
 * OkHttp's default of five requests per host was five, and the grouping pass —
 * one request per series, two thousand of them — crawled at a series a second
 * because of it.
 */
private const val PARALLELISM = 16

/** Pages read from one feed before we decide it is lying about having more. */
private const val PAGE_LIMIT = 400

/**
 * Index entries peeked before the results are consumed and released.
 *
 * Only a memory bound: every page in a slice is held until the slice ends, and
 * a catalogue can list thousands of series under one index. Large enough that
 * the pause between slices costs nothing next to the slice itself.
 */
private const val PEEK_SLICE = 256

/** Tries at a failing address before a walk accepts losing what is behind it. */
private const val ATTEMPTS = 3

/**
 * Waited after a failed try, doubling.
 *
 * What this meets is not a refused connection but a server thinking: a
 * Calibre-Web page deep into an eleven thousand book index is a `LIMIT/OFFSET`
 * over a sorted query, and it gets slower the further in it goes. Asking again
 * the same instant asks the same busy server the same expensive question.
 */
private const val RETRY_BACKOFF_MS = 2_000L

/**
 * The most addresses of one feed asked for at once.
 *
 * A ceiling, not a setting: [OpdsCatalogueWalker.window] starts at one and only
 * climbs while the server keeps answering as fast as it did alone. Capped at
 * [PARALLELISM] because the gate underneath is what actually rations requests —
 * a larger window would queue up behind it, holding built pages in memory.
 */
private const val READ_AHEAD_MAX = PARALLELISM

/**
 * How much slower than alone a page may come back before the window shrinks.
 *
 * A server that handles requests one at a time returns two concurrent pages in
 * twice the time and gains nothing: same throughput, twice the latency, and at
 * sixteen deep every request is past its socket timeout — which is worse than
 * nothing, because the work is thrown away and asked for again. Measured on
 * Calibre-Web: a page of two hundred books takes twenty-two seconds alone, and
 * sixteen at once do not fit in sixty.
 *
 * A quarter over is noise. Anything more and the requests are queueing behind
 * each other rather than running beside each other.
 */
private const val WINDOW_GROWTH_CEILING = 1.25

/**
 * Pages lost in a row before a walk stops reading ahead into the gap.
 *
 * One lost page is a hiccup and the rest of the index is still worth reading —
 * that is the whole point of deriving the addresses rather than reading them
 * off the page that never arrived. Three in a row is a server that has stopped
 * answering, and marching four hundred addresses further into it would only add
 * minutes to a walk that has already failed.
 */
private const val LOST_PAGES_BEFORE_STOP = 3

/**
 * Windows to run at the current width before trying a wider one again.
 *
 * A server that answers one request at a time never stops looking like one, so
 * a window that widens on every healthy result and halves on every slow one
 * simply alternates — measured against Calibre-Web, one page in twenty-one
 * seconds and then two in forty-four, over and over. The throughput is the
 * same either way, but every probe costs a page of latency and makes progress
 * lurch.
 *
 * So a rejected widening backs off, doubling each time. The probe never stops
 * entirely: a server busy with something else when the walk started is worth
 * asking again later, and by then the wait between asks is long enough that
 * being wrong is free.
 */
private const val PROBE_BACKOFF = 4

/** Windows between probes, once backing off has gone on long enough. */
private const val PROBE_BACKOFF_MAX = 64

/** A query parameter holding a plain number — a page offset, if it is one. */
private val NUMERIC_PARAM = Regex("([?&][A-Za-z_][A-Za-z_0-9]*=)(\\d+)")

/** What the walk has found so far, for a screen that would rather not look frozen. */
data class OpdsWalkProgress(val shelves: Int, val books: Int, val current: String)

/**
 * Reads a catalogue whose shape nobody standardised.
 *
 * Two things have to be found in it, and they cost wildly different amounts:
 *
 *  - **every book**, from an alphabetical index if the catalogue has one — one
 *    request per page of sixty — and from the author index otherwise, a book
 *    always having an author even if the library calls them Unknown. "Recent"
 *    and "Hot" list a slice and are never the library.
 *  - **which books form a series**, which OPDS cannot say: it has no series
 *    field, so membership is only learned by opening every series shelf, one
 *    request each. That feed's order is also the only book numbering we will
 *    ever get.
 *
 * Hence [walkBooks] and [walkSeries], run in that order and separable: the
 * first makes a library, the second tidies it.
 *
 * Indexes are recognised by name, and this is the only place in Korabooks where
 * a server's vocabulary leaks into the code. It degrades on purpose: without a
 * series index every book stands alone, without a book index the walk falls
 * back to whatever feeds the root offers. A library that shows up flat is a
 * disappointment; one that shows up empty is a bug, and the difference is worth
 * the heuristic.
 */
class OpdsCatalogueWalker(
    private val fetch: suspend (String) -> OpdsFeed,
    private val maxDepth: Int = 4,
) {
    /**
     * The one budget of requests in flight, shared by every phase.
     *
     * Held around a single fetch and never around a branch: a walk that takes a
     * permit for a whole letter and then asks for its pages would wait on
     * permits it is itself holding. Bounding the fetch instead means coroutines
     * can be created freely — only the network is rationed.
     */
    private val gate = Semaphore(PARALLELISM)

    private suspend fun fetchLimited(url: String): OpdsFeed = gate.withPermit { fetch(url) }

    /**
     * Addresses this walker asked for and never got.
     *
     * Kept rather than counted so they can be asked for again once the walk is
     * over: by then the server is no longer answering fifteen other requests,
     * and the page that timed out under load usually arrives on its own.
     */
    private val lost = mutableSetOf<String>()
    private val lostLock = Mutex()

    /**
     * Addresses asked for at once, found by asking.
     *
     * Servers differ by an order of magnitude in what they will take, and
     * nothing in a feed says which kind is on the other end. Komga answers
     * sixteen concurrent pages in about the time it answers one; Calibre-Web
     * answers them one after another and times the rest out. So the window is
     * measured rather than configured: it starts at one, doubles while pages
     * keep coming back at close to their solo speed, and halves the moment they
     * do not.
     */
    private var window = 1

    /** What one page costs with nothing else in flight — the thing to beat. */
    private var soloMillis = Long.MAX_VALUE

    /** Windows still to run before the next attempt at a wider one. */
    private var probeIn = 0

    /** How long the wait will be next time a widening is turned down. */
    private var probeBackoff = PROBE_BACKOFF

    private val paceLock = Mutex()

    private suspend fun pace(failed: Boolean, slowestMillis: Long) = paceLock.withLock {
        val was = window
        val tooSlow = soloMillis != Long.MAX_VALUE &&
            slowestMillis > soloMillis * WINDOW_GROWTH_CEILING
        when {
            failed || tooSlow -> {
                window = (window / 2).coerceAtLeast(1)
                probeIn = probeBackoff
                probeBackoff = (probeBackoff * 2).coerceAtMost(PROBE_BACKOFF_MAX)
            }

            soloMillis == Long.MAX_VALUE -> Unit
            probeIn > 0 -> probeIn--
            else -> {
                window = (window * 2).coerceAtMost(READ_AHEAD_MAX)
                // A widening that holds is evidence the last refusal was the
                // server being busy, not the server being serial: start over.
                probeBackoff = PROBE_BACKOFF
            }
        }
        if (window != was) {
            logger.info {
                "OPDS reading $window pages at once (was $was, slowest $slowestMillis ms of $soloMillis alone)"
            }
        }
    }

    private suspend fun takeLost(): Set<String> = lostLock.withLock {
        val taken = lost.toSet()
        lost.clear()
        taken
    }

    /**
     * A fetch a walk can survive losing — but not silently.
     *
     * Every failure here costs whole shelves: a dropped index entry takes its
     * subtree with it, and the sync ends by reporting a smaller library as if
     * that were the answer. Two real runs of the same catalogue found forty
     * eight and fifty sources, and nothing anywhere said why. Retried once,
     * because the failure this meets in practice is a home server briefly
     * refusing a connection, and logged always, because a walk that quietly
     * returns less than the catalogue holds is worse than one that fails.
     */
    /** [fetchOrNull], with what the whole of it cost — retries included. */
    private suspend fun fetchTimed(url: String): Pair<OpdsFeed?, Long> {
        val started = TimeSource.Monotonic.markNow()
        val feed = fetchOrNull(url)
        return feed to started.elapsedNow().inWholeMilliseconds
    }

    private suspend fun fetchOrNull(url: String): OpdsFeed? {
        repeat(ATTEMPTS) { attempt ->
            val result = runCatching { fetchLimited(url) }
            result.getOrNull()?.let { return it }
            val cause = result.exceptionOrNull()
            if (cause is CancellationException) throw cause
            logger.warn { "OPDS fetch failed (${attempt + 1}/$ATTEMPTS) $url: ${cause?.message}" }
            if (attempt < ATTEMPTS - 1) delay(RETRY_BACKOFF_MS shl attempt)
        }
        logger.error { "OPDS giving up on $url" }
        lostLock.withLock { lost.add(url) }
        return null
    }

    /**
     * Every book in the catalogue, one shelf each.
     *
     * The fast half, and the one worth waiting for: an alphabetical index costs
     * one request per page of sixty, so a twenty thousand book library is a few
     * hundred requests rather than a few thousand.
     *
     * Grouped here too, when the catalogue lets us — a book that names its own
     * series is put straight onto that series' shelf. Returns how many did, which
     * is what tells the caller whether [walkSeries] has anything left to learn:
     * on a catalogue that names series, it has nothing, and that is thousands of
     * requests not made.
     */
    suspend fun walkBooks(
        rootUrl: String,
        onProgress: (OpdsWalkProgress) -> Unit = {},
        onShelf: suspend (OpdsShelf) -> Unit,
    ): OpdsBooksWalk {
        val root = fetch(rootUrl)
        var shelfCount = 0
        var grouped = 0
        val seen = mutableSetOf<String>()
        // Per branch, so a catalogue that counts some of its letters and not
        // others yields no total rather than a total that is wrong.
        val counts = mutableListOf<Int?>()
        val report = reporter(onProgress) { shelfCount to seen.size }

        logger.info {
            "OPDS root offers " + root.entries.joinToString { "${it.title} -> ${it.navigation?.href}" }
        }

        // An alphabetical index of every book, when the catalogue has one, is
        // both complete and cheap: twenty-six letters where the author index
        // costs one request per author. Calibre-Web calls it /opds/books.
        val bookIndex = root.entries.firstOrNull { it.leadsTo(ALL_BOOKS_SEGMENTS, emptyList()) }?.navigation?.href
            ?: root.entries.firstOrNull { it.leadsTo(AUTHOR_SEGMENTS, AUTHOR_WORDS) }?.navigation?.href
        logger.info { "OPDS book index: ${bookIndex ?: "not recognised — falling back to the root feeds"}" }

        val branches = bookIndex
            ?.let { branchesUnder(it, report = report) }
            ?: root.entries.mapNotNull { entry -> entry.navigation?.href?.let { Branch(entry.title, it, null) } }
        logger.info { "OPDS ${branches.size} book sources to read" }

        // Emitted page by page, and pages arrive every sixty books. Collecting
        // a whole letter first meant nothing appeared for minutes on a letter
        // holding two thousand books — which is what the first real run did.
        // All branches at once, the gate rationing the fetches underneath them.
        // Batches of sixteen were worse than useless here: the branches are
        // wildly uneven — one letter of this catalogue holds ten thousand books
        // and a hundred and seventy pages — so a batch lasted as long as its
        // largest member with every other slot idle.
        val lock = Mutex()

        suspend fun emit(page: OpdsFeed, branchTitle: String) {
            val found = page.entries.filter { it.isBook }
            lock.withLock {
                for (book in found) {
                    if (!seen.add(book.id)) continue
                    shelfCount++
                    // A book that names its own series goes straight
                    // onto that shelf. One book per shelf still —
                    // shelves are emitted as pages arrive and the
                    // volumes of one series are scattered across the
                    // alphabet — but the shelf's *identity* is the
                    // series name, so they all land on the same row.
                    // This is what makes the grouping pass optional:
                    // see [OpdsEntry.seriesName].
                    val series = book.seriesName
                    if (series != null) {
                        grouped++
                        onShelf(OpdsShelf(series, listOf(book), standalone = false))
                    } else {
                        onShelf(OpdsShelf(book.title, listOf(book), standalone = true))
                    }
                }
                onProgress(OpdsWalkProgress(shelfCount, seen.size, branchTitle))
            }
        }

        coroutineScope {
            branches.map { branch ->
                async {
                    var counted = false
                    forEachPage(branch) { page ->
                        // The catalogue's own figure, read off the branch's
                        // first page: what the walk is later held against.
                        if (!counted) {
                            counted = true
                            lock.withLock { counts.add(page.totalResults) }
                        }
                        emit(page, branch.title)
                    }
                }
            }.awaitAll()
        }

        // Second pass over what the server would not give up, one address at a
        // time. Under load a Calibre-Web index page past offset six thousand
        // takes longer than the socket will wait; asked again with the walk
        // finished and nothing else in flight, it usually answers.
        val retryable = takeLost()
        if (retryable.isNotEmpty()) {
            logger.warn { "OPDS asking again for ${retryable.size} pages the server dropped" }
            for (url in retryable) emit(fetchOrNull(url) ?: continue, "reprise")
        }

        val expected = if (counts.isEmpty() || counts.any { it == null }) null else counts.sumOf { it!! }
        val stillLost = takeLost().size
        // Said out loud because it decides whether the next pass runs at all, and
        // whether a catalogue names its series is a property of the server.
        logger.info {
            "OPDS books walk done: ${seen.size} books" +
                (expected?.let { " of $it announced" } ?: " (the catalogue publishes no count)") +
                ", $grouped named a series, $stillLost pages lost"
        }
        return OpdsBooksWalk(books = seen.size, grouped = grouped, expected = expected, lostPages = stillLost)
    }

    /**
     * Only what the catalogue added recently.
     *
     * A full walk of a real library is twenty minutes and a few hundred
     * requests, and almost all of it re-reads books that have not moved since
     * the last time. Calibre-Web publishes its additions newest-first at
     * /opds/new, so a catalogue that gained nothing costs exactly one request.
     *
     * [stopWhenKnown] is asked about each page: it answers true once the page
     * holds nothing new, and the walk stops there rather than paging back
     * through the whole history.
     */
    suspend fun walkRecent(
        rootUrl: String,
        onProgress: (OpdsWalkProgress) -> Unit = {},
        stopWhenKnown: suspend (OpdsShelf) -> Boolean,
    ) {
        val root = fetch(rootUrl)
        val recentIndex = root.entries.firstOrNull { it.leadsTo(RECENT_SEGMENTS, RECENT_WORDS) }?.navigation?.href
        logger.info { "OPDS recent index: ${recentIndex ?: "not recognised"}" }
        if (recentIndex == null) return

        var books = 0
        try {
            forEachPage(Branch("", recentIndex, null)) { page ->
                val found = page.entries.filter { it.isBook }
                if (found.isEmpty()) return@forEachPage
                books += found.size
                onProgress(OpdsWalkProgress(0, books, page.title ?: "nouveautés"))
                // Returning true means the page was entirely known, and pages
                // are newest-first: everything after it is older still.
                if (stopWhenKnown(OpdsShelf(title = page.title ?: "", entries = found))) {
                    logger.info { "OPDS recent walk stopped: nothing new on this page" }
                    throw StopWalk()
                }
            }
        } catch (_: StopWalk) {
        }
    }

    /**
     * The series, and which books belong to them.
     *
     * The slow half, and the optional one: OPDS has no series field, so
     * membership is only known by opening every series shelf — one request per
     * series, thousands of them in a real library. Run after [walkBooks], it
     * regroups a library that is already there to read.
     */
    suspend fun walkSeries(
        rootUrl: String,
        onProgress: (OpdsWalkProgress) -> Unit = {},
        /**
         * Whether a series can be left alone, given its title and whatever count
         * the index published for it (null when it published none).
         *
         * Asked before the request rather than after: the request *is* the cost
         * here, and answering "we had that one" once it has been paid for would
         * save nothing at all. This is the only lever that shortens the grouping
         * pass — see [OpdsEntry.shelfCount].
         */
        skip: (title: String, count: Int?) -> Boolean = { _, _ -> false },
        onShelf: suspend (OpdsShelf) -> Unit,
    ) {
        val root = fetch(rootUrl)
        var shelfCount = 0
        var bookCount = 0
        val report = reporter(onProgress) { shelfCount to bookCount }

        val seriesIndex = root.entries.firstOrNull { it.leadsTo(SERIES_SEGMENTS, SERIES_WORDS) }?.navigation?.href
        logger.info { "OPDS series index: ${seriesIndex ?: "not recognised"}" }
        if (seriesIndex == null) return

        val branches = branchesUnder(seriesIndex, report = report, skip = skip)
        logger.info { "OPDS ${branches.size} series to read" }

        // All series at once, the gate rationing whatever fetches remain — the
        // same shape as [walkBooks], and for a much smaller reason.
        //
        // Worth being exact about, because this loop looks like where the time
        // goes and is not: [branchesUnder] has already fetched the first page of
        // every series in order to tell a series from a letter, and hands it over
        // in [Branch.first]. So most of what runs here fetches nothing at all.
        // Only a series long enough to paginate — forty volumes, say — asks for
        // anything, and those are a handful.
        //
        // Batching sixteen and waiting for the batch was still wrong for that
        // handful, and worse, a batch also waited on the slowest `onShelf`; all
        // at once costs nothing to write and leaves no slot idle. It is not the
        // half hour, and it is not claimed to be — the half hour is one request
        // per series against a server that answers one at a time, and the only
        // cure for that is [skip].
        //
        // What it costs: shelves arrive as they finish rather than in index
        // order, so the title on the progress line jumps around.
        //
        // [inFlight] bounds how many finished-but-unsent shelves pile up. Without
        // it, thousands of coroutines would each hold a built list while waiting
        // to hand it over, and the queue they wait on holds six hundred.
        val inFlight = Semaphore(PARALLELISM * 4)
        val lock = Mutex()
        coroutineScope {
            branches.map { branch ->
                async {
                    inFlight.withPermit {
                        val books = buildList {
                            forEachPage(branch) { addAll(it.entries.filter { e -> e.isBook }) }
                        }
                        if (books.isNotEmpty()) {
                            // Counters and emission under one lock: two shelves
                            // finishing together must not both read shelfCount.
                            lock.withLock {
                                shelfCount++
                                bookCount += books.size
                                onShelf(OpdsShelf(title = branch.title, entries = books))
                                onProgress(OpdsWalkProgress(shelfCount, bookCount, branch.title))
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        logger.info { "OPDS series walk done: $shelfCount series, $bookCount books" }
    }

    /**
     * Reading an index is itself hundreds of requests, and all of them happen
     * before a single shelf exists to report. Without this the screen sits on
     * the same sentence for minutes and the sync looks hung — which is exactly
     * what it looked like the first time it ran for real.
     */
    private fun reporter(
        onProgress: (OpdsWalkProgress) -> Unit,
        counts: () -> Pair<Int, Int>,
    ): (String, Int) -> Unit {
        var visited = 0
        return { where, total ->
            visited++
            val (shelves, books) = counts()
            // Out of how many, because "1 234" alone says nothing about whether
            // this ends in a minute or in an hour — and this phase is the one
            // that looks frozen.
            val position = if (total > 0) "$visited/$total" else "$visited"
            onProgress(OpdsWalkProgress(shelves, books, "$where ($position)"))
        }
    }

    /**
     * A shelf of books hanging under an index, with the page that proved it is
     * one.
     *
     * Telling a letter from a shelf means looking behind it, and that look
     * already fetched the answer. Keeping it is the whole difference between
     * one request per shelf and two — on a catalogue of two thousand series,
     * two thousand round trips saved.
     */
    private data class Branch(val title: String, val href: String, val first: OpdsFeed?)

    /**
     * The shelves under an index, descending through the letter indexes
     * catalogues use to break up long lists.
     *
     * Only the first page of a candidate is read. Paginating it here is what
     * made the first real sync look dead: deciding that /opds/books/letter/00
     * is a shelf walked all two thousand of its books, before a single one had
     * been handed over.
     */
    private suspend fun branchesUnder(
        url: String,
        depth: Int = 0,
        report: (String, Int) -> Unit = { _, _ -> },
        skip: (title: String, count: Int?) -> Boolean = { _, _ -> false },
    ): List<Branch> {
        if (depth >= maxDepth) return emptyList()
        val index = allPages(url)
        val all = index.flatMap { it.entries }
        if (all.any { it.isBook }) return listOf(Branch(url, url, index.firstOrNull()))

        // Calibre-Web opens every index with an entry titled "Tout", holding
        // the whole library, and follows it with the letters that divide the
        // same library up. Reading both means reading the catalogue twice: the
        // books were deduplicated afterwards, but the requests were made — a
        // hundred and seventy-six pages of them, measured, for nothing.
        //
        // The catch-all is the half that is kept, and the letters dropped. It
        // was the other way round for one release, on the theory that separate
        // letters could be read side by side — and a real library came back
        // with eight thousand five hundred books out of ten thousand five
        // hundred and sixty-one. The letters are not a partition: a title the
        // server files under no letter is reachable only through the catch-all.
        // Reading side by side also bought nothing here, the server answering
        // one request at a time whatever we asked of it.
        val catchAll = if (all.size > 1) all.filter { it.isCatchAll } else emptyList()
        val chosen = catchAll.ifEmpty { all }

        // Below the top level only. At the top these are the letters an index
        // divides itself by, and a library holding a book called "A" would
        // otherwise skip the whole of A on a resumed pass — the one book that
        // is its own shelf and the letter above it share a title, and by then
        // a title is all we have to go on.
        val entries = if (depth > 0) chosen.filterNot { skip(it.title, it.shelfCount) } else chosen
        val avoided = chosen.size - entries.size
        if (avoided > 0) logger.info { "OPDS skipping $avoided already-known shelves under $url" }

        // Whether the index publishes a count decides whether a sync of an
        // unchanged catalogue costs one request per series or none, and it is a
        // property of the server, not of the code — so it gets said out loud once
        // per index rather than guessed at from a stopwatch.
        if (depth > 0 && chosen.isNotEmpty()) {
            val counted = chosen.count { it.shelfCount != null }
            logger.info { "OPDS $counted/${chosen.size} shelves under $url publish a count" }
        }

        // The peeks run together, and this is the whole cost of a sync.
        //
        // Sequentially, a catalogue of two thousand eight hundred series was
        // two thousand eight hundred round trips taken one at a time: measured
        // on a real library, one request a second for forty-five minutes, with
        // the connection idle nearly half of it. The parallelism below used to
        // live in walkSeries, which by then had every page already in hand and
        // fetched nothing — the fast phase was the parallel one.
        //
        // A permit rather than a batch: batching sixteen and waiting for all
        // sixteen leaves the slowest answer holding the other fifteen slots.
        // Sliced only to bound memory, since every peeked page is kept until
        // the slice is done.
        val found = mutableListOf<Branch>()
        for (slice in entries.chunked(PEEK_SLICE)) {
            val peeked = coroutineScope {
                slice.map { entry ->
                    async {
                        val href = entry.navigation?.href
                        val page = href?.let {
                            report(entry.title, entries.size)
                            fetchOrNull(it)
                        }
                        Triple(entry, href, page)
                    }
                }.awaitAll()
            }

            for ((entry, href, peek) in peeked) {
                if (href == null || peek == null) continue
                // A first page of books means this entry is a shelf; a first
                // page of shelves means it was only a letter, and the shelves
                // are below.
                if (peek.entries.any { it.isBook } || peek.entries.isEmpty()) {
                    found += Branch(entry.title, href, peek)
                } else {
                    found += branchesUnder(href, depth + 1, report, skip)
                }
            }
        }
        return found
    }

    /**
     * Every page of a branch, starting from the one already fetched.
     *
     * Handed over as they arrive rather than returned as a list: a letter can
     * hold thousands of books, and the caller wants to show the first sixty
     * long before the last.
     */
    private suspend fun forEachPage(branch: Branch, block: suspend (OpdsFeed) -> Unit) {
        val first = branch.first ?: fetchOrNull(branch.href) ?: return
        block(first)

        // A feed that says how many results it has, and links its second page by
        // an offset, has already told us the address of every page it will ever
        // have. Following rel="next" one answer at a time turned that into a
        // hundred and seventy-five round trips taken in single file — measured
        // at three seconds each, nine minutes for one letter, with fifteen of
        // the sixteen slots idle throughout.
        val known = pageUrlsAfter(first)
        if (known != null) {
            for (slice in known.chunked(PEEK_SLICE)) {
                val pages = coroutineScope {
                    slice.map { url -> async { fetchOrNull(url) } }.awaitAll()
                }
                for (page in pages) block(page ?: continue)
            }
            return
        }

        val visited = mutableSetOf(branch.href)
        val pageSize = first.entries.size

        // Otherwise the catalogue only reveals the next address by handing over
        // the current page — but after two pages it has revealed the *shape* of
        // every address after them, and that is enough to stop asking in single
        // file.
        //
        // Asking in turn cost this catalogue twenty minutes. Measured over
        // three runs, a page of two hundred books took twenty-one seconds flat
        // — as true at offset two hundred as at five thousand six hundred, so
        // not a server getting slower as it goes — and fifty-six of them one
        // after another is the whole of the sync, with fifteen of the sixteen
        // request slots idle throughout.
        //
        // The shape is confirmed, never guessed. [advancedBy] moves the one
        // number in the address by a page, and the second page's own `next`
        // link has to agree with what that predicts before a single address is
        // derived. Guessing was tried and it is wrong: `?page=2` advanced by a
        // page of two is `?page=4`, and a walk that believed itself would have
        // read every other page of the catalogue and reported it as the whole.
        var page = first
        var next = page.nextPage
        var read = 1
        var confirmed: String? = null
        while (read < PAGE_LIMIT && next != null) {
            if (!visited.add(next)) return
            val predicted = next.advancedBy(pageSize)
            val (fetched, millis) = fetchTimed(next)
            // Asked for on its own, so this is what one page costs with the
            // server to itself: the yardstick the window is judged against.
            if (fetched != null) paceLock.withLock { soloMillis = minOf(soloMillis, millis) }
            if (fetched == null) {
                logger.error { "OPDS stopping at $next — the mirror will be missing what was behind it" }
                return
            }
            page = fetched
            block(page)
            read++
            val serverNext = page.nextPage ?: return
            if (predicted != null && serverNext == predicted && page.entries.size == pageSize) {
                confirmed = serverNext
                break
            }
            next = serverNext
        }
        val readAheadFrom = confirmed ?: return

        // Reading past the end is what marks the end: a page shorter than the
        // first is the last one, and an address beyond it answers with nothing.
        var ahead: String? = readAheadFrom
        var lostInARow = 0
        while (ahead != null && read < PAGE_LIMIT) {
            val room = paceLock.withLock { window }
            val batchUrls = buildList {
                var url: String? = ahead
                while (size < room && read + size < PAGE_LIMIT && url != null && visited.add(url)) {
                    add(url)
                    url = url.advancedBy(pageSize)
                }
            }
            if (batchUrls.isEmpty()) return
            val results = coroutineScope {
                batchUrls.map { url -> async { fetchTimed(url) } }.awaitAll()
            }
            // Judged before the pages are handed on, so the next window is
            // already the right size whatever the caller does with these.
            pace(failed = results.any { it.first == null }, slowestMillis = results.maxOf { it.second })
            for ((fetched, _) in results) {
                // A lost page no longer breaks the chain: the addresses after
                // it were derived, not read off it. It is already recorded for
                // the second pass.
                if (fetched == null) {
                    lostInARow++
                    continue
                }
                lostInARow = 0
                // Nothing on it means the window asked past the end, which is
                // how the end is found without a total. Not handed on: an empty
                // page is not a page of the catalogue.
                if (fetched.entries.isEmpty()) return
                block(fetched)
                read++
                if (fetched.entries.size < pageSize) return
            }
            if (lostInARow >= LOST_PAGES_BEFORE_STOP) {
                logger.error {
                    "OPDS stopping at ${batchUrls.last()} — $lostInARow pages in a row unanswered"
                }
                return
            }
            ahead = batchUrls.last().advancedBy(pageSize)
        }
    }

    /**
     * The same address, one page further in.
     *
     * Held to the same rule as [pageUrlsAfter]: exactly one numeric query
     * parameter, or we decline. An offset is the only thing that can safely be
     * moved, and an address that was invented rather than derived would report
     * whatever it happened to hit as the catalogue.
     */
    private fun String.advancedBy(step: Int): String? {
        val matches = NUMERIC_PARAM.findAll(this).toList()
        val only = matches.singleOrNull() ?: return null
        val value = only.groupValues[2].toIntOrNull() ?: return null
        return replaceRange(only.groups[2]!!.range, (value + step).toString())
    }

    /**
     * Every page after the first, when they can be worked out rather than asked
     * for.
     *
     * Deliberately narrow. The second page's address must differ from the
     * first's by exactly one numeric parameter whose value is the page size —
     * that is a record offset and nothing else, so multiplying it is safe. Two
     * candidates, or a value that is not the page size, and we decline: an
     * invented address is worse than a slow walk.
     */
    private fun pageUrlsAfter(first: OpdsFeed): List<String>? {
        val total = first.totalResults ?: return null
        val perPage = first.itemsPerPage?.takeIf { it > 0 } ?: return null
        val next = first.nextPage ?: return null
        if (total <= perPage) return null

        val offsets = NUMERIC_PARAM.findAll(next)
            .filter { it.groupValues[2].toIntOrNull() == perPage }
            .toList()
        if (offsets.size != 1) return null
        val value = offsets.single().groups[2]?.range ?: return null

        val pages = (total + perPage - 1) / perPage
        if (pages > PAGE_LIMIT) return null
        return (1 until pages).map { index ->
            next.replaceRange(value, (index * perPage).toString())
        }
    }

    /** An index and its continuations, guarding against a page that links to itself. */
    private suspend fun allPages(url: String): List<OpdsFeed> {
        val collected = mutableListOf<OpdsFeed>()
        forEachPage(Branch(url, url, null)) { collected += it }
        return collected
    }

    /**
     * Recognises an index by the last segment of its address, and only then by
     * the words in its title.
     *
     * The segment, because a path is a server's own vocabulary and it does not
     * translate: Calibre-Web serves /opds/series whatever language its
     * interface speaks. The last segment specifically, because a substring
     * match on "books" also matches /opds/readbooks and /opds/unreadbooks,
     * which are two slices of a library rather than a library.
     */
    /**
     * An index entry that repeats everything its siblings divide up.
     *
     * Recognised by its title, in the languages a catalogue is likely to speak,
     * and only when it also leads somewhere shaped like a sibling. A title is a
     * weak signal on its own — a series really called "Tout" would be a fair
     * name for a book — so the address has to agree: Calibre-Web files the
     * catch-all under the same `letter/` path as the letters, with `00` where
     * the letter goes.
     */
    private val OpdsEntry.isCatchAll: Boolean
        get() {
            val target = navigation?.href ?: return false
            val segment = target.substringBefore('?').trimEnd('/').substringAfterLast('/')
            if (segment != CATCH_ALL_SEGMENT) return false
            return title.trim().lowercase() in CATCH_ALL_WORDS
        }

    private fun OpdsEntry.leadsTo(segments: List<String>, words: List<String>): Boolean {
        val target = navigation?.href ?: return false
        val segment = target.substringBefore('?').substringBefore('#')
            .trimEnd('/')
            .substringAfterLast('/')
            .lowercase()
        if (segment in segments) return true
        return words.isNotEmpty() && words.any { title.lowercase().contains(it) }
    }

    companion object {
        private val ALL_BOOKS_SEGMENTS = listOf("books", "letter", "alphabetical", "title", "titles")
        private val SERIES_SEGMENTS = listOf("series", "serie", "reihen")
        private val AUTHOR_SEGMENTS = listOf("author", "authors", "autor", "auteur")
        private val RECENT_SEGMENTS = listOf("new", "recent", "latest", "neu")

        /** Where a letter would be, in the address of the entry that holds them all. */
        private const val CATCH_ALL_SEGMENT = "00"
        private val CATCH_ALL_WORDS = setOf(
            "tout", "tous", "all", "alle", "todo", "todos", "tutti", "tudo", "全部",
        )

        /**
         * Titles are the fallback, so they carry the languages a French reader
         * is likely to point this at. No such list for the book index: its
         * titles ("Livres alphabétiques", "Books by title") share their words
         * with half the other entries, and a wrong match there would quietly
         * mirror a slice of the library instead of the library.
         */
        private val SERIES_WORDS = listOf("série", "series", "serie", "reihe")
        private val RECENT_WORDS = listOf("nouveau", "nouveauté", "recent", "récent", "new", "latest")
        private val AUTHOR_WORDS = listOf("auteur", "author", "autor", "verfasser")
    }
}

