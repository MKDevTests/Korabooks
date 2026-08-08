package snd.komelia.opds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fixtures are shaped like what Calibre-Web actually returns: root-relative
 * links, a `dc:` prefix on language and publisher, several acquisition links per
 * book, and the OpenSearch counters on a paginated feed.
 */
class OpdsFeedParserTest {

    private val parser = OpdsFeedParser()
    private val root = "https://books.example/opds"

    /**
     * Copied down to the whitespace from what a real Calibre-Web returns on
     * /opds/new, because the thing being read is a *convention* rather than a
     * field: the series lives in a text line inside an xhtml `content`, beside
     * the tags, the genre block and the publisher's blurb. Anything invented
     * here would test the invention.
     */
    private val calibreWebNewFeed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dcterms="http://purl.org/dc/terms/">
          <title>Calibre-Web</title>
          <entry>
            <title>Missions stellaires</title>
            <id>urn:uuid:d71fc66c-65c4-4773-bbd3-f5c917300476</id>
            <updated>2026-06-23T18:25:13+00:00</updated>
            <author><name>Brandon Sanderson</name></author>
            <published>2023-09-20T05:35:21+00:00</published>
            <dcterms:language>fra</dcterms:language>
            <category scheme="http://www.bisg.org/standards/bisac_subject/index.html"
                      term="SF" label="SF"/>
            <category scheme="http://www.bisg.org/standards/bisac_subject/index.html"
                      term="SF.Space_Opera" label="SF.Space_Opera"/>
            <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml">
            TAGS: Jeunesse, SF, SF.Space_Opera<br/>
            SERIES: Skyward [2.50]<br/>
                            Genre:
                                        SF,
                                        SF.Space_Opera
                            <br/>
                <p>Alors que Spensa est coincée dans le nulle part.</p>
            </div></content>
            <link rel="http://opds-spec.org/acquisition" href="/opds/download/40810/epub/"
                  length="1196725" title="EPUB" type="application/epub+zip"/>
          </entry>
          <entry>
            <title>Les carnets du diable</title>
            <id>urn:uuid:9feb36fe-decd-45d3-807c-523150bbcf9e</id>
            <author><name>Anton LaVey</name></author>
            <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml">
                <p>Les Carnets du Diable rassemblent la sagesse du fondateur.</p>
            </div></content>
            <link rel="http://opds-spec.org/acquisition" href="/opds/download/40814/epub/"
                  length="551354" title="EPUB" type="application/epub+zip"/>
          </entry>
          <entry>
            <title>La frontière</title>
            <id>urn:uuid:5f79ea16-40ab-4171-8060-8b77ab5b2d89</id>
            <author><name>Don Winslow</name></author>
            <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml">
            SERIES: La Griffe du chien [3]<br/>
                <p>Art Keller, ancien agent de la DEA.</p>
            </div></content>
            <link rel="http://opds-spec.org/acquisition" href="/opds/download/40811/epub/"
                  length="964199" title="EPUB" type="application/epub+zip"/>
          </entry>
        </feed>
    """.trimIndent()

    /**
     * The line that saves a sync.
     *
     * OPDS has no series field, so membership normally costs one request per
     * series — 1729 of them against a server answering one at a time, measured at
     * forty minutes. Calibre-Web writes it into the book instead, and reading it
     * makes that pass unnecessary. Hence a test on the exact shape.
     */
    @Test
    fun readsTheSeriesAndVolumeCalibreWebWritesIntoTheDescription() {
        val entries = parser.parse(calibreWebNewFeed, root).entries

        assertEquals("Skyward", entries[0].seriesName)
        assertEquals(2.5, entries[0].seriesIndex)
        // A book with no series line belongs to none, and must not inherit one.
        assertNull(entries[1].seriesName)
        assertNull(entries[1].seriesIndex)
        // A name with spaces, and a whole number.
        assertEquals("La Griffe du chien", entries[2].seriesName)
        assertEquals(3.0, entries[2].seriesIndex)
    }

    /**
     * The description is the paragraphs, not the whole flattened document.
     *
     * Every book summary in the library used to read "TAGS: Jeunesse, SF SERIES:
     * Skyward [2.50] Genre: SF, SF.Space_Opera Alors que Spensa…", because the
     * metadata lines sit in the same `content` as the blurb.
     */
    @Test
    fun readsTheDescriptionWithoutTheMetadataLinesAroundIt() {
        val entries = parser.parse(calibreWebNewFeed, root).entries

        assertEquals("Alors que Spensa est coincée dans le nulle part.", entries[0].summary)
        assertFalse(entries[0].summary!!.contains("SERIES:"))
        assertFalse(entries[0].summary!!.contains("TAGS:"))
        assertFalse(entries[0].summary!!.contains("Genre:"))
    }

    private val navigationFeed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog">
          <id>urn:uuid:calibre-web</id>
          <title>Ma bibliothèque</title>
          <link rel="self" href="/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
          <link rel="search" href="/opds/osd" type="application/opensearchdescription+xml"/>
          <entry>
            <title>Auteurs</title>
            <id>urn:uuid:authors</id>
            <link href="/opds/author" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
          </entry>
          <entry>
            <title>Séries</title>
            <id>urn:uuid:series</id>
            <link href="/opds/series" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
          </entry>
          <entry>
            <title>Étiquettes</title>
            <id>urn:uuid:category</id>
            <link href="/opds/category" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
          </entry>
        </feed>
    """.trimIndent()

    private val acquisitionFeed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom"
              xmlns:dc="http://purl.org/dc/terms/"
              xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/">
          <id>urn:uuid:new</id>
          <title>Nouveautés</title>
          <opensearch:totalResults>120</opensearch:totalResults>
          <opensearch:itemsPerPage>60</opensearch:itemsPerPage>
          <opensearch:startIndex>1</opensearch:startIndex>
          <link rel="next" href="/opds/new?offset=60" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
          <entry>
            <title>La Horde du Contrevent</title>
            <id>urn:uuid:book-42</id>
            <author><name>Alain Damasio</name></author>
            <dc:language>fra</dc:language>
            <dc:publisher>La Volte</dc:publisher>
            <published>2004-05-01T00:00:00+00:00</published>
            <summary>Vingt-trois hordiers remontent le vent.</summary>
            <category label="Science-fiction" term="science-fiction"/>
            <category term="aventure"/>
            <link rel="http://opds-spec.org/image" href="/opds/cover/42" type="image/jpeg"/>
            <link rel="http://opds-spec.org/image/thumbnail" href="/opds/cover/42/thumb" type="image/jpeg"/>
            <link rel="http://opds-spec.org/acquisition" href="/opds/download/42/epub/" type="application/epub+zip"/>
            <link rel="http://opds-spec.org/acquisition" href="/opds/download/42/pdf/" type="application/pdf"/>
          </entry>
          <entry>
            <title>Les Furtifs</title>
            <id>urn:uuid:book-43</id>
            <author><name>Alain Damasio</name></author>
            <link rel="http://opds-spec.org/acquisition" href="https://cdn.example/files/43.epub" type="application/epub+zip"/>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun readsANavigationFeed() {
        val feed = parser.parse(navigationFeed, root)

        assertEquals("Ma bibliothèque", feed.title)
        assertEquals(3, feed.entries.size)
        assertEquals(listOf("Auteurs", "Séries", "Étiquettes"), feed.entries.map { it.title })
        assertTrue(feed.entries.none { it.isBook }, "a navigation entry is not a book")
        assertEquals("https://books.example/opds/author", feed.entries[0].navigation?.href)
        assertEquals("https://books.example/opds/osd", feed.searchDescription)
        assertNull(feed.nextPage, "a navigation feed has no next page")
    }

    @Test
    fun readsABookWithItsFormats() {
        val book = parser.parse(acquisitionFeed, "$root/new").entries.first()

        assertTrue(book.isBook)
        assertEquals("La Horde du Contrevent", book.title)
        assertEquals(listOf("Alain Damasio"), book.authors.map { it.name })
        assertEquals("fra", book.language)
        assertEquals("La Volte", book.publisher)
        assertEquals("2004-05-01T00:00:00+00:00", book.published)
        assertEquals("Vingt-trois hordiers remontent le vent.", book.summary)
        // The label is what a reader should see; the term is the slug behind it.
        assertEquals(listOf("Science-fiction", "aventure"), book.categories)
        assertEquals(2, book.acquisitions.size)
        assertEquals(
            listOf("application/epub+zip", "application/pdf"),
            book.acquisitions.map { it.type },
        )
        assertEquals("https://books.example/opds/cover/42", book.cover?.href)
        assertEquals("https://books.example/opds/cover/42/thumb", book.thumbnail?.href)
        assertNull(book.navigation, "a book leads to its file, not to another feed")
    }

    @Test
    fun keepsPaginationAndAbsoluteLinksIntact() {
        val feed = parser.parse(acquisitionFeed, "$root/new")

        assertEquals(120, feed.totalResults)
        assertEquals(60, feed.itemsPerPage)
        assertEquals("https://books.example/opds/new?offset=60", feed.nextPage)
        // Already absolute, and on another host: left alone.
        assertEquals(
            "https://cdn.example/files/43.epub",
            feed.entries[1].acquisitions.single().href,
        )
    }

    @Test
    fun refusesADocumentThatIsNotACatalogue() {
        val thrown = runCatching { parser.parse("<html><body>login</body></html>", root) }
        assertTrue(thrown.exceptionOrNull() is OpdsParseException, "got ${thrown.exceptionOrNull()}")
    }

    @Test
    fun resolvesTheFourShapesOfLink() {
        val page = "https://books.example/opds/new?offset=60"
        assertEquals("https://other.example/x", OpdsUrl.resolve(page, "https://other.example/x"))
        assertEquals("https://books.example/opds/cover/1", OpdsUrl.resolve(page, "/opds/cover/1"))
        assertEquals("https://books.example/opds/cover/1", OpdsUrl.resolve(page, "cover/1"))
        assertEquals("https://books.example/opds/new?offset=120", OpdsUrl.resolve(page, "?offset=120"))
        assertEquals("https://books.example/x", OpdsUrl.resolve(page, "//books.example/x"))
        assertNull(OpdsUrl.resolve(page, "  "))
    }

    @Test
    fun toleratesAFeedWithoutTheUsualPrefixes() {
        val bare = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Sans préfixe</title>
              <entry>
                <title>Un livre</title>
                <id>1</id>
                <language>eng</language>
                <publisher>Self</publisher>
                <link rel="http://opds-spec.org/acquisition/open-access" href="/get/1.epub" type="application/epub+zip"/>
              </entry>
            </feed>
        """.trimIndent()

        val book = parser.parse(bare, root).entries.single()

        assertEquals("eng", book.language)
        assertEquals("Self", book.publisher)
        // open-access is an acquisition too: the rel is a prefix, not a value.
        assertTrue(book.isBook)
        assertFalse(book.categories.isNotEmpty())
    }
}
