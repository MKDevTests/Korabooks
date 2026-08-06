package snd.komelia.opds

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The catalogues here are shaped like the real ones: a series index, an author
 * index sometimes broken up by letter, and paginated feeds.
 *
 * The two walks are tested apart because they are two promises. Reading every
 * book is the one a library depends on; finding which books form a series is
 * the one that can be kept later, or not at all.
 */
class OpdsCatalogueWalkerTest {

    private fun nav(title: String, href: String) = OpdsEntry(
        id = href,
        title = title,
        links = listOf(OpdsLink(href = href, type = OpdsMediaType.NAVIGATION)),
    )

    private fun book(id: String, title: String) = OpdsEntry(
        id = id,
        title = title,
        links = listOf(OpdsLink(href = "/get/$id.epub", rel = OpdsRel.ACQUISITION, type = OpdsMediaType.EPUB)),
    )

    private fun feed(entries: List<OpdsEntry>, next: String? = null) = OpdsFeed(
        id = null,
        title = null,
        links = next?.let { listOf(OpdsLink(href = it, rel = OpdsRel.NEXT)) } ?: emptyList(),
        entries = entries,
    )

    private fun walkerOver(catalogue: Map<String, OpdsFeed>) =
        OpdsCatalogueWalker(fetch = { url -> catalogue[url] ?: feed(emptyList()) })

    private suspend fun books(
        catalogue: Map<String, OpdsFeed>,
        onProgress: (OpdsWalkProgress) -> Unit = {},
    ) = buildList { walkerOver(catalogue).walkBooks("/opds", onProgress) { add(it) } }

    private suspend fun series(catalogue: Map<String, OpdsFeed>) =
        buildList { walkerOver(catalogue).walkSeries("/opds") { add(it) } }

    private val duneCatalogue = mapOf(
        "/opds" to feed(listOf(nav("Séries", "/opds/series"), nav("Auteurs", "/opds/author"))),
        "/opds/series" to feed(listOf(nav("Dune", "/opds/series/1"))),
        "/opds/series/1" to feed(listOf(book("b1", "Dune"), book("b2", "Le Messie de Dune"))),
        "/opds/author" to feed(listOf(nav("Frank Herbert", "/opds/author/1"))),
        "/opds/author/1" to feed(listOf(book("b1", "Dune"), book("b2", "Le Messie de Dune"), book("b9", "Seul"))),
    )

    @Test
    fun readsEveryBookOnItsOwnShelf() = runTest {
        val shelves = books(duneCatalogue)

        assertEquals(listOf("Dune", "Le Messie de Dune", "Seul"), shelves.map { it.title })
        assertTrue(shelves.all { it.standalone }, "grouping is not this walk's business")
        assertTrue(shelves.all { it.entries.size == 1 })
    }

    @Test
    fun readsASeriesWithItsBooksInFeedOrder() = runTest {
        val shelves = series(duneCatalogue)

        assertEquals(1, shelves.size)
        assertEquals("Dune", shelves.single().title)
        assertEquals(listOf("b1", "b2"), shelves.single().entries.map { it.id })
        assertTrue(!shelves.single().standalone)
    }

    @Test
    fun aBookIsNeverReadTwice() = runTest {
        val catalogue = mapOf(
            "/opds" to feed(listOf(nav("Auteurs", "/opds/author"))),
            "/opds/author" to feed(listOf(nav("Herbert", "/opds/author/1"), nav("Anonyme", "/opds/author/2"))),
            "/opds/author/1" to feed(listOf(book("b1", "Dune"))),
            // The same book, reached through a second author.
            "/opds/author/2" to feed(listOf(book("b1", "Dune"))),
        )

        assertEquals(listOf("Dune"), books(catalogue).map { it.title })
    }

    @Test
    fun descendsThroughTheLetterIndexes() = runTest {
        val catalogue = mapOf(
            "/opds" to feed(listOf(nav("Auteurs", "/opds/author"))),
            "/opds/author" to feed(listOf(nav("H", "/opds/author/h"))),
            "/opds/author/h" to feed(listOf(nav("Frank Herbert", "/opds/author/h/1"))),
            "/opds/author/h/1" to feed(listOf(book("b1", "Dune"))),
        )

        assertEquals(listOf("Dune"), books(catalogue).map { it.title })
    }

    @Test
    fun followsPagination() = runTest {
        val catalogue = mapOf(
            "/opds" to feed(listOf(nav("Auteurs", "/opds/author"))),
            "/opds/author" to feed(listOf(nav("Anonyme", "/opds/author/1"))),
            "/opds/author/1" to feed(listOf(book("b1", "Un")), next = "/opds/author/1?page=2"),
            "/opds/author/1?page=2" to feed(listOf(book("b2", "Deux"))),
        )

        assertEquals(listOf("Un", "Deux"), books(catalogue).map { it.title })
    }

    @Test
    fun aPageThatLinksToItselfDoesNotLoopForever() = runTest {
        val catalogue = mapOf(
            "/opds" to feed(listOf(nav("Auteurs", "/opds/author"))),
            "/opds/author" to feed(listOf(nav("Anonyme", "/opds/author/1"))),
            "/opds/author/1" to feed(listOf(book("b1", "Un")), next = "/opds/author/1"),
        )

        assertEquals(listOf("Un"), books(catalogue).map { it.title })
    }

    @Test
    fun aCatalogueWithoutASeriesIndexStillYieldsEveryBook() = runTest {
        val catalogue = mapOf(
            "/opds" to feed(listOf(nav("Auteurs", "/opds/author"))),
            "/opds/author" to feed(listOf(nav("Anonyme", "/opds/author/1"))),
            "/opds/author/1" to feed(listOf(book("b1", "Un"), book("b2", "Deux"))),
        )

        assertEquals(2, books(catalogue).size, "flat, but complete")
        assertEquals(0, series(catalogue).size)
    }

    @Test
    fun prefersTheAlphabeticalIndexToTheAuthorIndex() = runTest {
        // Calibre-Web's own root, trimmed: an all-books index costs one request
        // per letter where the author index costs one per author.
        val catalogue = mapOf(
            "/opds" to feed(
                listOf(
                    nav("Livres alphabétiques", "/opds/books"),
                    nav("Livres lus", "/opds/readbooks"),
                    nav("Livres non-lus", "/opds/unreadbooks"),
                    nav("Auteurs", "/opds/author"),
                )
            ),
            "/opds/books" to feed(listOf(nav("D", "/opds/books/d"))),
            "/opds/books/d" to feed(listOf(book("b1", "Dune"))),
            "/opds/author" to feed(listOf(nav("Frank Herbert", "/opds/author/1"))),
            "/opds/author/1" to feed(listOf(book("b1", "Dune"))),
            // Slices of the library, and never the library: matching these
            // would mirror the read books and call it a catalogue.
            "/opds/readbooks" to feed(listOf(book("b7", "Déjà lu"))),
            "/opds/unreadbooks" to feed(listOf(book("b8", "Pas lu"))),
        )

        assertEquals(listOf("Dune"), books(catalogue).map { it.title })
    }

    @Test
    fun aCatalogueWithNeitherIndexFallsBackToWhateverTheRootOffers() = runTest {
        val catalogue = mapOf(
            "/opds" to feed(listOf(nav("Nouveautés", "/opds/new"))),
            "/opds/new" to feed(listOf(book("b1", "Un"))),
        )

        assertEquals(listOf("Un"), books(catalogue).map { it.title })
    }

    @Test
    fun reportsProgressAsItGoes() = runTest {
        val catalogue = mapOf(
            "/opds" to feed(listOf(nav("Auteurs", "/opds/author"))),
            "/opds/author" to feed(listOf(nav("Anonyme", "/opds/author/1"))),
            "/opds/author/1" to feed(listOf(book("b1", "Un")), next = "/opds/author/1?page=2"),
            "/opds/author/1?page=2" to feed(listOf(book("b2", "Deux"))),
        )
        val seen = mutableListOf<OpdsWalkProgress>()

        books(catalogue) { seen += it }

        // Reading the index is announced too, before a single book exists to
        // count — without it the screen sits silent through the longest part of
        // a real sync.
        assertTrue(seen.any { it.books == 0 }, "the index descent is announced")
        // One report per page, not per book: a page is what a request brings
        // back, and reporting each of sixty books would say the same thing
        // sixty times.
        assertEquals(listOf(1, 2), seen.map { it.books }.filter { it > 0 })
        assertEquals("Anonyme", seen.last().current)
    }

    /**
     * A counted feed has already told us every address it will ever have, so we
     * stop asking one at a time. That is the difference between a hundred and
     * seventy-five round trips in single file and eleven waves of sixteen.
     */
    @Test
    fun readsACountedFeedWithoutFollowingEveryNextLink() = runTest {
        val asked = mutableListOf<String>()
        fun counted(entries: List<OpdsEntry>, next: String?) = OpdsFeed(
            id = null,
            title = null,
            links = next?.let { listOf(OpdsLink(href = it, rel = OpdsRel.NEXT)) } ?: emptyList(),
            entries = entries,
            totalResults = 5,
            itemsPerPage = 2,
        )

        val catalogue = mapOf(
            "/opds" to feed(listOf(nav("Auteurs", "/opds/author"))),
            "/opds/author" to feed(listOf(nav("Anonyme", "/opds/author/1"))),
            "/opds/author/1" to counted(listOf(book("b1", "Un"), book("b2", "Deux")), "/opds/author/1?offset=2"),
            // Deliberately links back to the first page. Nothing should follow
            // it: the remaining addresses are computed from the count.
            "/opds/author/1?offset=2" to counted(
                listOf(book("b3", "Trois"), book("b4", "Quatre")),
                "/opds/author/1",
            ),
            "/opds/author/1?offset=4" to counted(listOf(book("b5", "Cinq")), null),
        )
        val walker = OpdsCatalogueWalker(fetch = { url ->
            asked += url
            catalogue[url] ?: feed(emptyList())
        })

        val shelves = buildList { walker.walkBooks("/opds") { add(it) } }

        assertEquals(listOf("Un", "Deux", "Trois", "Quatre", "Cinq"), shelves.map { it.title })
        assertTrue("/opds/author/1?offset=4" in asked, "the last page was worked out, not followed")
    }

    /**
     * Calibre-Web opens each index with "Tout", which holds everything the
     * letters beside it divide up. Reading both read the catalogue twice.
     */
    @Test
    fun ignoresTheCatchAllEntryWhenTheLettersAreThere() = runTest {
        val asked = mutableListOf<String>()
        val catalogue = mapOf(
            "/opds" to feed(listOf(nav("Auteurs", "/opds/author"))),
            "/opds/author" to feed(
                listOf(
                    nav("Tout", "/opds/author/letter/00"),
                    nav("D", "/opds/author/letter/D"),
                )
            ),
            "/opds/author/letter/00" to feed(listOf(book("b1", "Dune"), book("b2", "Les Furtifs"))),
            "/opds/author/letter/D" to feed(listOf(book("b1", "Dune"), book("b2", "Les Furtifs"))),
        )
        val walker = OpdsCatalogueWalker(fetch = { url ->
            asked += url
            catalogue[url] ?: feed(emptyList())
        })

        val shelves = buildList { walker.walkBooks("/opds") { add(it) } }

        assertEquals(listOf("Dune", "Les Furtifs"), shelves.map { it.title })
        assertTrue("/opds/author/letter/00" !in asked, "the catch-all was never opened")
    }

    /** On its own it is not a duplicate of anything, so it is read. */
    @Test
    fun readsTheCatchAllWhenItIsTheOnlyEntry() = runTest {
        val catalogue = mapOf(
            "/opds" to feed(listOf(nav("Auteurs", "/opds/author"))),
            "/opds/author" to feed(listOf(nav("Tout", "/opds/author/letter/00"))),
            "/opds/author/letter/00" to feed(listOf(book("b1", "Dune"))),
        )

        assertEquals(listOf("Dune"), books(catalogue).map { it.title })
    }

    /** Without a count there is nothing to compute from, so we ask page by page. */
    @Test
    fun stillFollowsNextLinksWhenTheFeedIsNotCounted() = runTest {
        val catalogue = mapOf(
            "/opds" to feed(listOf(nav("Auteurs", "/opds/author"))),
            "/opds/author" to feed(listOf(nav("Anonyme", "/opds/author/1"))),
            "/opds/author/1" to feed(listOf(book("b1", "Un")), next = "/opds/author/1?offset=1"),
            "/opds/author/1?offset=1" to feed(listOf(book("b2", "Deux"))),
        )

        assertEquals(listOf("Un", "Deux"), books(catalogue).map { it.title })
    }
}
