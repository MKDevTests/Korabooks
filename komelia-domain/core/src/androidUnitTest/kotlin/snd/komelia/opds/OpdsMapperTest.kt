package snd.komelia.opds

import snd.komga.client.book.MediaProfile
import snd.komga.client.library.KomgaLibraryId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpdsMapperTest {

    private val mapper = OpdsMapper(
        libraryId = KomgaLibraryId("calibre"),
        catalogueId = "https://books.example/opds",
    )

    private fun entry(
        id: String,
        title: String,
        formats: List<Pair<String, String>> = listOf("application/epub+zip" to "/get/$id.epub"),
        categories: List<String> = emptyList(),
        published: String? = null,
    ) = OpdsEntry(
        id = id,
        title = title,
        authors = listOf(OpdsAuthor("Alain Damasio")),
        summary = "Résumé de $title",
        categories = categories,
        language = "fra",
        publisher = "La Volte",
        published = published,
        links = formats.map { (type, href) ->
            OpdsLink(href = "https://books.example$href", rel = OpdsRel.ACQUISITION, type = type, length = 2_097_152)
        },
    )

    @Test
    fun aStandaloneBookBecomesAOneBookSeries() {
        val shelf = OpdsShelf(
            title = "La Horde du Contrevent",
            entries = listOf(entry("b1", "La Horde du Contrevent", categories = listOf("Science-fiction"), published = "2004-05-01T00:00:00+00:00")),
            standalone = true,
        )

        val mapped = mapper.map(shelf)

        assertTrue(mapped.series.oneshot, "a book without a series is a oneshot")
        assertEquals(1, mapped.series.booksCount)
        assertEquals(1, mapped.series.booksUnreadCount)
        assertEquals("La Horde du Contrevent", mapped.series.metadata.title)
        assertEquals(listOf("Science-fiction"), mapped.series.metadata.genres)
        assertEquals("La Volte", mapped.series.metadata.publisher)
        assertEquals("fra", mapped.series.metadata.language)
        assertEquals("Résumé de La Horde du Contrevent", mapped.series.metadata.summary)
        // A novel is not a manga: no reading direction rather than a wrong one.
        assertNull(mapped.series.metadata.readingDirection)

        val book = mapped.books.single()
        assertEquals("2004-05-01", book.metadata.releaseDate.toString())
        assertEquals(listOf("writer"), book.metadata.authors.map { it.role })
        assertEquals("2.0 MiB", book.size)
        assertEquals(2_097_152, book.sizeBytes)
        assertEquals(MediaProfile.EPUB, book.media.mediaProfile)
        assertTrue(book.url.endsWith("/get/b1.epub"), "the acquisition link is kept: ${book.url}")
        assertTrue(!book.downloaded, "nothing has been downloaded yet")
    }

    @Test
    fun aSeriesShelfKeepsTheOrderOfTheFeed() {
        val shelf = OpdsShelf(
            title = "Les Furtifs",
            entries = listOf(entry("b1", "Tome 1"), entry("b2", "Tome 2"), entry("b3", "Tome 3")),
        )

        val mapped = mapper.map(shelf)

        assertEquals(3, mapped.series.booksCount)
        assertTrue(!mapped.series.oneshot)
        assertEquals(listOf(1, 2, 3), mapped.books.map { it.number })
        assertEquals(listOf("1", "2", "3"), mapped.books.map { it.metadata.number })
        assertEquals(listOf("Les Furtifs"), mapped.books.map { it.seriesTitle }.distinct())
        assertEquals(listOf(mapped.series.id), mapped.books.map { it.seriesId }.distinct())
    }

    @Test
    fun epubWinsOverEverythingElse() {
        val book = mapper.map(
            OpdsShelf(
                "Dune",
                listOf(
                    entry(
                        "b9", "Dune",
                        formats = listOf(
                            "application/pdf" to "/get/9.pdf",
                            "application/epub+zip" to "/get/9.epub",
                        ),
                    )
                ),
            )
        ).books.single()

        assertTrue(book.url.endsWith(".epub"), "epub is preferred: ${book.url}")
        assertEquals(MediaProfile.EPUB, book.media.mediaProfile)
    }

    @Test
    fun aBookOfferedOnlyInAFormatWeCannotOpenIsDropped() {
        val mapped = mapper.map(
            OpdsShelf(
                "Vieux fichier",
                listOf(
                    entry("b7", "Un mobi", formats = listOf("application/x-mobipocket-ebook" to "/get/7.mobi")),
                    entry("b8", "Un epub"),
                ),
            )
        )

        assertEquals(listOf("Un epub"), mapped.books.map { it.name })
        assertEquals(1, mapped.series.booksCount, "the count follows what is readable")
    }

    @Test
    fun theSameEntryKeepsTheSameIdAcrossSyncs() {
        val once = mapper.map(OpdsShelf("Dune", listOf(entry("b1", "Dune")), standalone = true))
        val twice = mapper.map(OpdsShelf("Dune", listOf(entry("b1", "Dune")), standalone = true))

        assertEquals(once.books.single().id, twice.books.single().id)
        assertEquals(once.series.id, twice.series.id)
    }

    @Test
    fun twoCatalogueServersCannotCollide() {
        val elsewhere = OpdsMapper(
            libraryId = KomgaLibraryId("calibre"),
            catalogueId = "https://other.example/opds",
        )
        val shelf = OpdsShelf("Dune", listOf(entry("b1", "Dune")), standalone = true)

        assertNotEquals(mapper.map(shelf).books.single().id, elsewhere.map(shelf).books.single().id)
        assertNotEquals(mapper.map(shelf).series.id, elsewhere.map(shelf).series.id)
    }

    @Test
    fun aKepubIsFlaggedAsOne() {
        val book = mapper.map(
            OpdsShelf(
                "Kobo",
                listOf(entry("b5", "Un kepub", formats = listOf("application/epub+zip" to "/get/5.kepub.epub"))),
            )
        ).books.single()

        assertTrue(book.media.epubIsKepub)
    }
}
