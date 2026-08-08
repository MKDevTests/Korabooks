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
     * letters beside it divide up. Reading both read the catalogue twice, and
     * reading only the letters lost the books this server files under none of
     * them — two thousand of ten and a half, measured. So the catch-all is the
     * half that is kept, and it is the letters that go unopened.
     */
    @Test
    fun readsTheCatchAllRatherThanTheLettersBesideIt() = runTest {
        val asked = mutableListOf<String>()
        val catalogue = mapOf(
            "/opds" to feed(listOf(nav("Auteurs", "/opds/author"))),
            "/opds/author" to feed(
                listOf(
                    nav("Tout", "/opds/author/letter/00"),
                    nav("D", "/opds/author/letter/D"),
                )
            ),
            // The catch-all holds a book no letter claims, which is the case
            // that made a real library arrive two thousand books short.
            "/opds/author/letter/00" to feed(listOf(book("b1", "Dune"), book("b2", "Les Furtifs"))),
            "/opds/author/letter/D" to feed(listOf(book("b1", "Dune"))),
        )
        val walker = OpdsCatalogueWalker(fetch = { url ->
            asked += url
            catalogue[url] ?: feed(emptyList())
        })

        val shelves = buildList { walker.walkBooks("/opds") { add(it) } }

        assertEquals(listOf("Dune", "Les Furtifs"), shelves.map { it.title })
        assertTrue("/opds/author/letter/D" !in asked, "the letters were never opened")
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

    /**
     * A navigation entry that says how many books are behind it.
     *
     * Shaped like the two forms a catalogue has been seen to use, because the
     * grouping pass now spends or saves a request on this one number.
     */
    private fun countedNav(title: String, href: String, count: Int?, asContent: Boolean = false) = OpdsEntry(
        id = href,
        title = title,
        summary = if (asContent && count != null) "$count Books" else null,
        links = listOf(
            OpdsLink(
                href = href,
                type = OpdsMediaType.NAVIGATION,
                count = if (asContent) null else count,
            )
        ),
    )

    /**
     * The series index, shaped like Calibre-Web's: a letter, and the series
     * underneath it. The letter matters — a series only reaches the skip
     * predicate below the top level, where the entries are shelves rather than
     * the letters an index divides itself by.
     */
    private fun seriesIndex(vararg entries: OpdsEntry) = mapOf(
        "/opds" to feed(listOf(nav("Séries", "/opds/series"))),
        "/opds/series" to feed(listOf(nav("D", "/opds/series/letter/D"))),
        "/opds/series/letter/D" to feed(entries.toList()),
    )

    /**
     * The whole point of the count: not asking.
     *
     * The grouping pass is one request per series against a server that answers
     * them one at a time — measured at forty minutes for this catalogue, against
     * four for every book in it. Nothing client-side fixes that, because the
     * requests were already made side by side. Only *fewer* requests do, and a
     * series whose size the index still reports as three is a series with nothing
     * to say.
     */
    @Test
    fun doesNotOpenASeriesTheIndexSaysIsUnchanged() = runTest {
        val asked = mutableListOf<String>()
        val catalogue = seriesIndex(
            countedNav("Dune", "/opds/series/1", count = 2),
            countedNav("Les Fourmis", "/opds/series/2", count = 3),
        ) + mapOf(
            "/opds/series/1" to feed(listOf(book("b1", "Dune"), book("b2", "Le Messie de Dune"))),
            "/opds/series/2" to feed(listOf(book("b3", "Les Fourmis"))),
        )
        val walker = OpdsCatalogueWalker(fetch = { url ->
            asked += url
            catalogue[url] ?: feed(emptyList())
        })

        // Dune is known to hold two books already; nothing is known of the other.
        val shelves = buildList {
            walker.walkSeries("/opds", skip = { _, count -> count == 2 }) { add(it) }
        }

        assertEquals(listOf("Les Fourmis"), shelves.map { it.title })
        assertTrue("/opds/series/1" !in asked, "the skipped series was never requested")
        assertTrue("/opds/series/2" in asked, "the other one still was")
    }

    /** A size that moved is the one case where the shelf has to be re-read. */
    @Test
    fun opensASeriesWhoseCountChanged() = runTest {
        val catalogue = seriesIndex(countedNav("Dune", "/opds/series/1", count = 3)) + mapOf(
            "/opds/series/1" to feed(
                listOf(book("b1", "Dune"), book("b2", "Le Messie de Dune"), book("b3", "Les Enfants de Dune"))
            ),
        )
        val walker = walkerOver(catalogue)

        // The mirror holds two; the index says three.
        val shelves = buildList {
            walker.walkSeries("/opds", skip = { _, count -> count == 2 }) { add(it) }
        }

        assertEquals(listOf("Dune"), shelves.map { it.title })
        assertEquals(3, shelves.single().entries.size)
    }

    /**
     * An index that publishes no count costs exactly what it costs today.
     *
     * Worth pinning down, because whether a server publishes one is not
     * something this code gets to decide, and a walk that silently skipped
     * everything on a null would mirror an empty library.
     */
    @Test
    fun readsEverySeriesWhenTheIndexPublishesNoCount() = runTest {
        val catalogue = seriesIndex(nav("Dune", "/opds/series/1")) + mapOf(
            "/opds/series/1" to feed(listOf(book("b1", "Dune"), book("b2", "Le Messie de Dune"))),
        )

        val shelves = buildList {
            walkerOver(catalogue).walkSeries("/opds", skip = { _, count -> count == 2 }) { add(it) }
        }

        assertEquals(listOf("Dune"), shelves.map { it.title })
    }

    /** Both places a catalogue has been seen to put the number. */
    @Test
    fun readsTheShelfCountFromEitherTheLinkOrTheDescription() {
        assertEquals(4, countedNav("Dune", "/opds/series/1", count = 4).shelfCount)
        assertEquals(4, countedNav("Dune", "/opds/series/1", count = 4, asContent = true).shelfCount)
        assertEquals(null, nav("Dune", "/opds/series/1").shelfCount)
        // A description that merely happens to start with something else.
        assertEquals(
            null,
            nav("Dune", "/opds/series/1").copy(summary = "Roman de Frank Herbert").shelfCount,
        )
    }

    /**
     * The books pass groups on its own when the catalogue says who belongs where.
     *
     * This is the whole of the sync's cost, moved: the grouping pass is one
     * request per series and 1729 of them against a server that answers one at a
     * time. A book that carries "SERIES: Skyward [2]" makes every one of those
     * requests unnecessary, and the count returned here is what tells the sync so.
     */
    @Test
    fun putsBooksOnTheirNamedSeriesShelf() = runTest {
        fun bookInSeries(id: String, title: String, series: String, index: Double) =
            book(id, title).copy(seriesName = series, seriesIndex = index)

        val catalogue = mapOf(
            "/opds" to feed(listOf(nav("Livres alphabétiques", "/opds/books"))),
            "/opds/books" to feed(listOf(nav("Tout", "/opds/books/letter/00"))),
            "/opds/books/letter/00" to feed(
                listOf(
                    bookInSeries("b1", "Vers les étoiles", "Skyward", 1.0),
                    book("b2", "Les carnets du diable"),
                    bookInSeries("b3", "Astrevise", "Skyward", 2.0),
                )
            ),
        )
        val shelves = mutableListOf<OpdsShelf>()

        val named = walkerOver(catalogue).walkBooks("/opds") { shelves += it }

        assertEquals(2, named, "two books named a series")
        // Volumes of one series arrive apart — the index is alphabetical — but
        // both shelves carry the series title, which is what gives them the same
        // identity once mapped.
        assertEquals(listOf("Skyward", "Les carnets du diable", "Skyward"), shelves.map { it.title })
        assertEquals(listOf(false, true, false), shelves.map { it.standalone })
    }

    /** A catalogue that says nothing still yields a flat library, as before. */
    @Test
    fun keepsOneShelfPerBookWhenNoSeriesIsNamed() = runTest {
        val named = books(duneCatalogue).let { shelves ->
            assertTrue(shelves.all { it.standalone })
            0
        }
        assertEquals(0, named)

        // And the count the walk itself reports is zero, which is the signal the
        // grouping pass is still needed.
        val reported = walkerOver(duneCatalogue).walkBooks("/opds") { }
        assertEquals(0, reported)
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
