package snd.komelia.opds

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger { }

/** What the walk has found so far, for a screen that would rather not look frozen. */
data class OpdsWalkProgress(val shelves: Int, val books: Int, val current: String)

/**
 * Turns a catalogue into a list of shelves, by walking it.
 *
 * Two things have to be found in a tree whose shape nobody standardised:
 *
 *  - **every book**, which is why the walk goes through the author index: a
 *    book always has an author, "Unknown" if the library says nothing, so that
 *    index is the only one guaranteed to list the whole collection. "Recent"
 *    and "Hot" list a slice, and no server offers a plain "everything".
 *  - **which books form a series**, which is why the series index is walked
 *    first: OPDS has no series field, but a series *shelf* is a feed listing
 *    them in order, and that order is the only book numbering we will ever get.
 *
 * Books seen in a series keep it; the rest become one-book shelves.
 *
 * The two indexes are recognised by name, and this is the only place in
 * Korabooks where a server's vocabulary leaks into the code. It degrades on
 * purpose: without a series index every book stands alone, without an author
 * index the walk falls back to whatever acquisition feeds the root offers. A
 * library that shows up flat is a disappointment; one that shows up empty is a
 * bug, and the difference is worth the heuristic.
 */
class OpdsCatalogueWalker(
    private val fetch: suspend (String) -> OpdsFeed,
    private val maxDepth: Int = 4,
) {

    suspend fun walk(rootUrl: String, onProgress: (OpdsWalkProgress) -> Unit = {}): List<OpdsShelf> {
        val root = fetch(rootUrl)
        val shelves = mutableListOf<OpdsShelf>()
        val claimed = mutableSetOf<String>()

        // Reading an index is itself a few hundred requests, and it happens
        // before a single shelf exists to report. Without this the screen sits
        // on the same sentence for minutes and the sync looks hung — which is
        // exactly what it looked like the first time it ran for real.
        var visited = 0
        val report: (String) -> Unit = { where ->
            visited++
            onProgress(OpdsWalkProgress(shelves.size, claimed.size, "$where ($visited)"))
        }

        // The two recognitions are the whole risk of this class, so they are
        // said out loud: a walk that finds a suspiciously round number of books
        // has usually fallen back, and no other line would show it.
        logger.info {
            "OPDS root offers " + root.entries.joinToString { "${it.title} -> ${it.navigation?.href}" }
        }

        val seriesIndex = root.entries.firstOrNull { it.leadsTo(SERIES_SEGMENTS, SERIES_WORDS) }?.navigation?.href
        logger.info { "OPDS series index: ${seriesIndex ?: "not recognised"}" }
        if (seriesIndex != null) {
            for (shelf in navigationEntries(seriesIndex, report = report)) {
                val href = shelf.navigation?.href ?: continue
                val books = booksOf(href)
                if (books.isEmpty()) continue
                shelves += OpdsShelf(title = shelf.title, entries = books)
                claimed += books.map { it.id }
                onProgress(OpdsWalkProgress(shelves.size, claimed.size, shelf.title))
            }
        }

        logger.info { "OPDS series walk: ${shelves.size} shelves, ${claimed.size} books" }

        // An alphabetical index of every book, when the catalogue has one, is
        // both complete and cheap: twenty-six letters where the author index
        // costs one request per author. Calibre-Web calls it /opds/books.
        val bookIndex = root.entries.firstOrNull { it.leadsTo(ALL_BOOKS_SEGMENTS, emptyList()) }?.navigation?.href
            ?: root.entries.firstOrNull { it.leadsTo(AUTHOR_SEGMENTS, AUTHOR_WORDS) }?.navigation?.href
        logger.info { "OPDS book index: ${bookIndex ?: "not recognised — falling back to the root feeds"}" }

        val bookSources = bookIndex
            ?.let { index ->
                navigationEntries(index, report = report).mapNotNull { entry -> entry.navigation?.href }
            }
            ?: root.entries.mapNotNull { it.navigation?.href }
        logger.info { "OPDS ${bookSources.size} book sources to read" }

        for (source in bookSources) {
            for (book in booksOf(source)) {
                if (!claimed.add(book.id)) continue
                shelves += OpdsShelf(title = book.title, entries = listOf(book), standalone = true)
                onProgress(OpdsWalkProgress(shelves.size, claimed.size, book.title))
            }
        }

        return shelves
    }

    /**
     * Every navigation entry under a feed, descending through the letter
     * indexes catalogues use to break up long lists.
     *
     * A feed that mixes books and sub-shelves stops the descent: it is a shelf
     * itself, not an index.
     */
    private suspend fun navigationEntries(
        url: String,
        depth: Int = 0,
        report: (String) -> Unit = {},
    ): List<OpdsEntry> {
        if (depth >= maxDepth) return emptyList()
        val entries = pages(url).flatMap { it.entries }
        if (entries.any { it.isBook }) return entries.filter { !it.isBook }

        val direct = mutableListOf<OpdsEntry>()
        for (entry in entries) {
            val href = entry.navigation?.href ?: continue
            report(entry.title)
            val nested = pages(href).flatMap { page -> page.entries }
            // A sub-feed of books means this entry is a shelf; a sub-feed of
            // shelves means it was only a letter, and the shelves are below.
            if (nested.none { it.isBook } && nested.isNotEmpty()) {
                direct += navigationEntries(href, depth + 1, report)
            } else {
                direct += entry
            }
        }
        return direct
    }

    private suspend fun booksOf(url: String): List<OpdsEntry> =
        pages(url).flatMap { page -> page.entries.filter { it.isBook } }

    /**
     * A feed and its continuations, guarding against a page that links to itself.
     *
     * Memoised for the length of one walk: deciding whether an entry is a letter
     * or a shelf means looking behind it, and the walk then goes there again to
     * read the books. Over a home network that is twice the wait for nothing.
     */
    private suspend fun pages(url: String, limit: Int = 400): List<OpdsFeed> {
        cache[url]?.let { return it }
        val collected = mutableListOf<OpdsFeed>()
        val seen = mutableSetOf<String>()
        var next: String? = url
        while (next != null && collected.size < limit && seen.add(next)) {
            val page = runCatching { fetch(next) }.getOrNull() ?: break
            collected += page
            next = page.nextPage
        }
        cache[url] = collected
        return collected
    }

    private val cache = mutableMapOf<String, List<OpdsFeed>>()

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

        /**
         * Titles are the fallback, so they carry the languages a French reader
         * is likely to point this at. No such list for the book index: its
         * titles ("Livres alphabétiques", "Books by title") share their words
         * with half the other entries, and a wrong match there would quietly
         * mirror a slice of the library instead of the library.
         */
        private val SERIES_WORDS = listOf("série", "series", "serie", "reihe")
        private val AUTHOR_WORDS = listOf("auteur", "author", "autor", "verfasser")
    }
}
