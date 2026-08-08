package snd.komelia.opds

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.parser.Parser.Companion.xmlParser

/**
 * Reads an Atom/OPDS document into [OpdsFeed].
 *
 * Elements are matched on their local name, never on their prefix: the same
 * field arrives as `dc:language`, `dcterms:language` or plain `language`
 * depending on who wrote the server, and a selector like `dc|language` silently
 * returns nothing on the other two. Comparing the part after the colon costs
 * one `substringAfterLast` and stops the whole class of bug.
 */
/**
 * `SERIES: Le nom de la série [3]`, as Calibre-Web writes it into a book's
 * description. The number is fractional because Calibre files a novella at 2.5,
 * and the name is taken non-greedily so a title containing a bracket cannot eat
 * the index.
 */
private val SERIES_LINE = Regex("""SERIES:\s*(.+?)\s*\[\s*(\d+(?:[.,]\d+)?)\s*]""")

class OpdsFeedParser {

    fun parse(xml: String, feedUrl: String): OpdsFeed {
        val document = Ksoup.parse(xml, xmlParser())
        val feed = document.descendants().firstOrNull { it.localName().equals("feed", ignoreCase = true) }
            ?: throw OpdsParseException("no <feed> element; is $feedUrl an OPDS catalogue?")

        return OpdsFeed(
            id = feed.childText("id"),
            title = feed.childText("title"),
            links = feed.childrenNamed("link").mapNotNull { it.toLink(feedUrl) },
            entries = feed.childrenNamed("entry").map { it.toEntry(feedUrl) },
            totalResults = feed.childText("totalResults")?.toIntOrNull(),
            itemsPerPage = feed.childText("itemsPerPage")?.toIntOrNull(),
            startIndex = feed.childText("startIndex")?.toIntOrNull(),
        )
    }

    private fun Element.toEntry(feedUrl: String): OpdsEntry {
        val content = childrenNamed("content").firstOrNull()
        val series = content?.let { SERIES_LINE.find(it.text()) }
        return OpdsEntry(
        id = childText("id") ?: childText("title").orEmpty(),
        title = childText("title").orEmpty(),
        authors = childrenNamed("author").mapNotNull { author ->
            author.childText("name")?.let { OpdsAuthor(it, author.childText("uri")) }
        },
        // `summary` is the short form and `content` the long one; servers pick
        // one or the other, rarely both, and the reader wants whichever exists.
        //
        // Calibre-Web's `content` is not a description but a small document: a
        // "TAGS:" line, a "SERIES:" line, a "Genre:" block, and only then the
        // publisher's blurb in paragraphs of its own. Flattened to text it read
        // as "TAGS: SF, Jeunesse SERIES: Skyward [2] Genre: SF, … Spensa est…",
        // which is what every book summary in the library said. The paragraphs
        // are the description; the lines above them are metadata, and one of
        // them is worth forty minutes of sync (see [OpdsEntry.seriesName]).
        summary = childText("summary") ?: content?.let { description(it) },
        seriesName = series?.groupValues?.get(1)?.trim()?.ifBlank { null },
        seriesIndex = series?.groupValues?.get(2)?.replace(',', '.')?.toDoubleOrNull(),
        categories = childrenNamed("category")
            .mapNotNull { it.attr("label").ifBlank { it.attr("term") }.trim().ifBlank { null } }
            .distinct(),
        language = childText("language"),
        publisher = childText("publisher"),
        published = childText("published") ?: childText("issued") ?: childText("date"),
        updated = childText("updated"),
        links = childrenNamed("link").mapNotNull { it.toLink(feedUrl) },
        )
    }

    /**
     * The blurb out of a `content` that also carries metadata lines.
     *
     * The paragraphs, when there are any: Calibre-Web wraps the description in
     * `<p>` and leaves its "TAGS:" / "SERIES:" / "Genre:" lines as bare text
     * beside them, so the structure separates the two at no cost. A server that
     * puts a plain description in `content` has no paragraphs and gets its text
     * whole, which is the old behaviour.
     */
    private fun description(content: Element): String? {
        val paragraphs = content.select("p")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
        return if (paragraphs.isEmpty()) content.text().trim().ifBlank { null }
        else paragraphs.joinToString("\n\n")
    }

    private fun Element.toLink(feedUrl: String): OpdsLink? {
        val href = OpdsUrl.resolve(feedUrl, attr("href")) ?: return null
        return OpdsLink(
            href = href,
            rel = attr("rel").trim().ifBlank { null },
            type = attr("type").trim().ifBlank { null },
            title = attr("title").trim().ifBlank { null },
            count = (attr("thr:count").ifBlank { attr("count") }).toIntOrNull(),
            length = attr("length").toLongOrNull(),
        )
    }

    private fun Element.childrenNamed(name: String): List<Element> =
        children().filter { it.localName().equals(name, ignoreCase = true) }

    private fun Element.childText(name: String): String? =
        childrenNamed(name).firstOrNull()?.text()?.trim()?.ifBlank { null }
}

class OpdsParseException(message: String) : Exception(message)
