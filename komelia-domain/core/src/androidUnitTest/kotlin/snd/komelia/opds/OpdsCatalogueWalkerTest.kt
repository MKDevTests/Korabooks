package snd.komelia.opds

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The catalogues here are shaped like the real ones: a series index, an author
 * index sometimes broken up by letter, and paginated feeds.
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

    @Test
    fun booksOfASeriesStayTogetherAndKeepTheirOrder() = runTest {
        val catalogue = mapOf(
            "/opds" to feed(listOf(nav("Séries", "/opds/series"), nav("Auteurs", "/opds/author"))),
            "/opds/series" to feed(listOf(nav("Dune", "/opds/series/1"))),
            "/opds/series/1" to feed(listOf(book("b1", "Dune"), book("b2", "Le Messie de Dune"))),
            "/opds/author" to feed(listOf(nav("Frank Herbert", "/opds/author/1"))),
            "/opds/author/1" to feed(listOf(book("b1", "Dune"), book("b2", "Le Messie de Dune"))),
        )

        val shelves = walkerOver(catalogue).walk("/opds")

        assertEquals(1, shelves.size, "the two books belong to one shelf")
        assertEquals("Dune", shelves.single().title)
        assertEquals(listOf("b1", "b2"), shelves.single().entries.map { it.id })
        assertTrue(!shelves.single().standalone)
    }

    @Test
    fun aBookOutsideAnySeriesStandsAlone() = runTest {
        val catalogue = mapOf(
            "/opds" to feed(listOf(nav("Séries", "/opds/series"), nav("Auteurs", "/opds/author"))),
            "/opds/series" to feed(listOf(nav("Dune", "/opds/series/1"))),
            "/opds/series/1" to feed(listOf(book("b1", "Dune"))),
            "/opds/author" to feed(listOf(nav("Alain Damasio", "/opds/author/2"))),
            "/opds/author/2" to feed(listOf(book("b9", "La Horde du Contrevent"))),
        )

        val shelves = walkerOver(catalogue).walk("/opds")

        assertEquals(listOf("Dune", "La Horde du Contrevent"), shelves.map { it.title })
        assertEquals(listOf(false, true), shelves.map { it.standalone })
    }

    @Test
    fun descendsThroughTheLetterIndexes() = runTest {
        val catalogue = mapOf(
            "/opds" to feed(listOf(nav("Auteurs", "/opds/author"))),
            "/opds/author" to feed(listOf(nav("H", "/opds/author/h"))),
            "/opds/author/h" to feed(listOf(nav("Frank Herbert", "/opds/author/h/1"))),
            "/opds/author/h/1" to feed(listOf(book("b1", "Dune"))),
        )

        val shelves = walkerOver(catalogue).walk("/opds")

        assertEquals(listOf("Dune"), shelves.map { it.title })
    }

    @Test
    fun followsPagination() = runTest {
        val catalogue = mapOf(
            "/opds" to feed(listOf(nav("Auteurs", "/opds/author"))),
            "/opds/author" to feed(listOf(nav("Anonyme", "/opds/author/1"))),
            "/opds/author/1" to feed(listOf(book("b1", "Un")), next = "/opds/author/1?page=2"),
            "/opds/author/1?page=2" to feed(listOf(book("b2", "Deux"))),
        )

        val shelves = walkerOver(catalogue).walk("/opds")

        assertEquals(listOf("Un", "Deux"), shelves.map { it.title })
    }

    @Test
    fun aPageThatLinksToItselfDoesNotLoopForever() = runTest {
        val catalogue = mapOf(
            "/opds" to feed(listOf(nav("Auteurs", "/opds/author"))),
            "/opds/author" to feed(listOf(nav("Anonyme", "/opds/author/1"))),
            "/opds/author/1" to feed(listOf(book("b1", "Un")), next = "/opds/author/1"),
        )

        val shelves = walkerOver(catalogue).walk("/opds")

        assertEquals(listOf("Un"), shelves.map { it.title })
    }

    @Test
    fun aCatalogueWithoutASeriesIndexStillYieldsEveryBook() = runTest {
        val catalogue = mapOf(
            "/opds" to feed(listOf(nav("Auteurs", "/opds/author"))),
            "/opds/author" to feed(listOf(nav("Anonyme", "/opds/author/1"))),
            "/opds/author/1" to feed(listOf(book("b1", "Un"), book("b2", "Deux"))),
        )

        val shelves = walkerOver(catalogue).walk("/opds")

        assertEquals(2, shelves.size)
        assertTrue(shelves.all { it.standalone }, "flat, but complete")
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

        val shelves = walkerOver(catalogue).walk("/opds")

        assertEquals(listOf("Dune"), shelves.map { it.title })
    }

    @Test
    fun aCatalogueWithNeitherIndexFallsBackToWhateverTheRootOffers() = runTest {
        val catalogue = mapOf(
            "/opds" to feed(listOf(nav("Nouveautés", "/opds/new"))),
            "/opds/new" to feed(listOf(book("b1", "Un"))),
        )

        val shelves = walkerOver(catalogue).walk("/opds")

        assertEquals(listOf("Un"), shelves.map { it.title })
    }

    @Test
    fun reportsProgressAsItGoes() = runTest {
        val catalogue = mapOf(
            "/opds" to feed(listOf(nav("Auteurs", "/opds/author"))),
            "/opds/author" to feed(listOf(nav("Anonyme", "/opds/author/1"))),
            "/opds/author/1" to feed(listOf(book("b1", "Un"), book("b2", "Deux"))),
        )
        val seen = mutableListOf<OpdsWalkProgress>()

        walkerOver(catalogue).walk("/opds") { seen += it }

        // Reading the index is announced too, before a single book exists to
        // count — without it the screen sits silent through the longest part of
        // a real sync.
        assertTrue(seen.any { it.books == 0 }, "the index descent is announced")
        assertEquals(listOf(1, 2), seen.map { it.books }.filter { it > 0 })
        assertEquals("Deux", seen.last().current)
    }
}
