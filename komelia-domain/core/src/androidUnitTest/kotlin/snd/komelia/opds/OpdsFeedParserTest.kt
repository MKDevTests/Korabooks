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
