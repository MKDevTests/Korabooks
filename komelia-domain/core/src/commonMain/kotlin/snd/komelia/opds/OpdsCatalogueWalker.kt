package snd.komelia.opds

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger { }

/** Ends a walk early without unwinding it as a failure. */
private class StopWalk : Exception()

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
private const val ATTEMPTS = 2

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
    private suspend fun fetchOrNull(url: String): OpdsFeed? {
        repeat(ATTEMPTS) { attempt ->
            val result = runCatching { fetchLimited(url) }
            result.getOrNull()?.let { return it }
            val cause = result.exceptionOrNull()
            if (cause is CancellationException) throw cause
            logger.warn { "OPDS fetch failed (${attempt + 1}/$ATTEMPTS) $url: ${cause?.message}" }
        }
        logger.error { "OPDS giving up on $url — the mirror will be missing what was behind it" }
        return null
    }

    /**
     * Every book in the catalogue, one shelf each.
     *
     * The fast half, and the one worth waiting for: an alphabetical index costs
     * one request per page of sixty, so a twenty thousand book library is a few
     * hundred requests rather than a few thousand. Nothing here is grouped —
     * that is [walkSeries]' business, and it can happen later.
     */
    suspend fun walkBooks(
        rootUrl: String,
        onProgress: (OpdsWalkProgress) -> Unit = {},
        onShelf: suspend (OpdsShelf) -> Unit,
    ) {
        val root = fetch(rootUrl)
        var shelfCount = 0
        val seen = mutableSetOf<String>()
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
        coroutineScope {
            branches.map { branch ->
                async {
                    forEachPage(branch) { page ->
                        val found = page.entries.filter { it.isBook }
                        lock.withLock {
                            for (book in found) {
                                if (!seen.add(book.id)) continue
                                shelfCount++
                                onShelf(OpdsShelf(book.title, listOf(book), standalone = true))
                            }
                            onProgress(OpdsWalkProgress(shelfCount, seen.size, branch.title))
                        }
                    }
                }
            }.awaitAll()
        }
        logger.info { "OPDS books walk done: ${seen.size} books" }
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
        onShelf: suspend (OpdsShelf) -> Unit,
    ) {
        val root = fetch(rootUrl)
        var shelfCount = 0
        var bookCount = 0
        val report = reporter(onProgress) { shelfCount to bookCount }

        val seriesIndex = root.entries.firstOrNull { it.leadsTo(SERIES_SEGMENTS, SERIES_WORDS) }?.navigation?.href
        logger.info { "OPDS series index: ${seriesIndex ?: "not recognised"}" }
        if (seriesIndex == null) return

        // Read in batches so the network is busy while the phone parses, then
        // emitted in order — a shelf list that jumps around as it fills is
        // worse than one that takes a moment longer.
        val branches = branchesUnder(seriesIndex, report = report)
        logger.info { "OPDS ${branches.size} series to read" }
        for (batch in branches.chunked(PARALLELISM)) {
            val fetched = coroutineScope {
                batch.map { branch ->
                    async { branch.title to buildList { forEachPage(branch) { addAll(it.entries.filter { e -> e.isBook }) } } }
                }.awaitAll()
            }
            for ((title, books) in fetched) {
                if (books.isEmpty()) continue
                shelfCount++
                bookCount += books.size
                onShelf(OpdsShelf(title = title, entries = books))
                onProgress(OpdsWalkProgress(shelfCount, bookCount, title))
            }
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
        val entries = catchAll.ifEmpty { all }

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
                    found += branchesUnder(href, depth + 1, report)
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

        // Otherwise the catalogue only ever reveals the next address by handing
        // over the current page, and there is nothing to do but ask in turn.
        var page = first
        val visited = mutableSetOf(branch.href)
        var count = 1
        while (count < PAGE_LIMIT) {
            val next = page.nextPage ?: return
            if (!visited.add(next)) return
            page = fetchOrNull(next) ?: return
            block(page)
            count++
        }
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
