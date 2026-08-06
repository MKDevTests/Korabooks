package snd.komelia.opds

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

        val seriesIndex = root.entries.firstOrNull { it.looksLike(SERIES_WORDS) }?.navigation?.href
        if (seriesIndex != null) {
            for (shelf in navigationEntries(seriesIndex)) {
                val href = shelf.navigation?.href ?: continue
                val books = booksOf(href)
                if (books.isEmpty()) continue
                shelves += OpdsShelf(title = shelf.title, entries = books)
                claimed += books.map { it.id }
                onProgress(OpdsWalkProgress(shelves.size, claimed.size, shelf.title))
            }
        }

        val bookSources = root.entries.firstOrNull { it.looksLike(AUTHOR_WORDS) }?.navigation?.href
            ?.let { navigationEntries(it).mapNotNull { entry -> entry.navigation?.href } }
            ?: root.entries.mapNotNull { it.navigation?.href }

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
    private suspend fun navigationEntries(url: String, depth: Int = 0): List<OpdsEntry> {
        if (depth >= maxDepth) return emptyList()
        val entries = pages(url).flatMap { it.entries }
        if (entries.any { it.isBook }) return entries.filter { !it.isBook }

        val direct = mutableListOf<OpdsEntry>()
        for (entry in entries) {
            val href = entry.navigation?.href ?: continue
            val nested = pages(href).flatMap { page -> page.entries }
            // A sub-feed of books means this entry is a shelf; a sub-feed of
            // shelves means it was only a letter, and the shelves are below.
            if (nested.none { it.isBook } && nested.isNotEmpty()) {
                direct += navigationEntries(href, depth + 1)
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

    private fun OpdsEntry.looksLike(words: List<String>): Boolean {
        val target = navigation?.href ?: return false
        val haystack = (title + " " + target).lowercase()
        return words.any { haystack.contains(it) }
    }

    companion object {
        /**
         * Enough languages to cover the servers a French reader is likely to
         * point this at. The href is matched too, and that is usually what
         * saves the day: Calibre-Web serves /opds/series whatever its interface
         * language.
         */
        private val SERIES_WORDS = listOf("series", "série", "serie", "reihe", "serier")
        private val AUTHOR_WORDS = listOf("author", "auteur", "autor", "verfasser")
    }
}
