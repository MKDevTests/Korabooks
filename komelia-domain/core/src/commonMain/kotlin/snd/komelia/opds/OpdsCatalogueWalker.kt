package snd.komelia.opds

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        val lock = Mutex()
        for (batch in branches.chunked(PARALLELISM)) {
            coroutineScope {
                batch.map { branch ->
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
    ): (String) -> Unit {
        var visited = 0
        return { where ->
            visited++
            val (shelves, books) = counts()
            onProgress(OpdsWalkProgress(shelves, books, "$where ($visited)"))
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
        report: (String) -> Unit = {},
    ): List<Branch> {
        if (depth >= maxDepth) return emptyList()
        val index = allPages(url)
        val entries = index.flatMap { it.entries }
        if (entries.any { it.isBook }) return listOf(Branch(url, url, index.firstOrNull()))

        val found = mutableListOf<Branch>()
        for (entry in entries) {
            val href = entry.navigation?.href ?: continue
            report(entry.title)
            val peek = runCatching { fetch(href) }.getOrNull() ?: continue
            // A first page of books means this entry is a shelf; a first page
            // of shelves means it was only a letter, and the shelves are below.
            if (peek.entries.any { it.isBook } || peek.entries.isEmpty()) {
                found += Branch(entry.title, href, peek)
            } else {
                found += branchesUnder(href, depth + 1, report)
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
        var page = branch.first ?: runCatching { fetch(branch.href) }.getOrNull() ?: return
        val visited = mutableSetOf(branch.href)
        var count = 0
        while (count < PAGE_LIMIT) {
            block(page)
            count++
            val next = page.nextPage ?: return
            if (!visited.add(next)) return
            page = runCatching { fetch(next) }.getOrNull() ?: return
        }
    }

    /** An index and its continuations, guarding against a page that links to itself. */
    private suspend fun allPages(url: String): List<OpdsFeed> {
        val collected = mutableListOf<OpdsFeed>()
        val visited = mutableSetOf<String>()
        var next: String? = url
        while (next != null && collected.size < PAGE_LIMIT && visited.add(next)) {
            val page = runCatching { fetch(next) }.getOrNull() ?: break
            collected += page
            next = page.nextPage
        }
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
