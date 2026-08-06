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
class OpdsFeedParser {

    fun parse(xml: String, feedUrl: String): OpdsFeed {
        val document = Ksoup.parse(xml, xmlParser())
        val feed = document.descendants("feed").firstOrNull()
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

    private fun Element.toEntry(feedUrl: String) = OpdsEntry(
        id = childText("id") ?: childText("title").orEmpty(),
        title = childText("title").orEmpty(),
        authors = childrenNamed("author").mapNotNull { author ->
            author.childText("name")?.let { OpdsAuthor(it, author.childText("uri")) }
        },
        // `summary` is the short form and `content` the long one; servers pick
        // one or the other, rarely both, and the reader wants whichever exists.
        summary = childText("summary") ?: childText("content"),
        categories = childrenNamed("category")
            .mapNotNull { it.attr("label").ifBlank { it.attr("term") }.trim().ifBlank { null } }
            .distinct(),
        language = childText("language"),
        publisher = childText("publisher"),
        published = childText("published") ?: childText("issued") ?: childText("date"),
        updated = childText("updated"),
        links = childrenNamed("link").mapNotNull { it.toLink(feedUrl) },
    )

    private fun Element.toLink(feedUrl: String): OpdsLink? {
        val href = OpdsUrl.resolve(feedUrl, attr("href")) ?: return null
        return OpdsLink(
            href = href,
            rel = attr("rel").trim().ifBlank { null },
            type = attr("type").trim().ifBlank { null },
            title = attr("title").trim().ifBlank { null },
            count = (attr("thr:count").ifBlank { attr("count") }).toIntOrNull(),
        )
    }

    private fun Element.childrenNamed(name: String): List<Element> =
        children().filter { it.localName().equals(name, ignoreCase = true) }

    private fun Element.childText(name: String): String? =
        childrenNamed(name).firstOrNull()?.text()?.trim()?.ifBlank { null }

    /** `opensearch:totalResults` -> `totalResults`. Case is left alone: half the
     *  names in Atom are camelCase and folding them would need folding the
     *  comparison too, which is exactly the bug this is meant to avoid. */
    private fun Element.localName(): String = tagName().substringAfterLast(':')

    private fun com.fleeksoft.ksoup.nodes.Document.descendants(name: String): List<Element> =
        allElements.filter { it.localName().equals(name, ignoreCase = true) }
}

class OpdsParseException(message: String) : Exception(message)
