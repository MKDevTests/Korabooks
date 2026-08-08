package snd.komelia.opds

/**
 * The OPDS catalogue, as it actually arrives on the wire.
 *
 * This is a transport model, not a domain model: it keeps what Atom says and
 * nothing more. Turning an entry into a book — deciding that a Calibre tag is a
 * genre, that a `<category>` is worth indexing, that two entries belong to the
 * same series — happens later, against the local mirror. Keeping the two apart
 * means a server quirk can be fixed in the parser without touching the library.
 *
 * Every `href` here is absolute. Feeds routinely mix absolute and root-relative
 * links, sometimes within one document, and a caller that has to remember which
 * is which will eventually forget.
 */
data class OpdsFeed(
    val id: String?,
    val title: String?,
    val links: List<OpdsLink>,
    val entries: List<OpdsEntry>,
    /** OpenSearch counters, present on paginated feeds only. */
    val totalResults: Int? = null,
    val itemsPerPage: Int? = null,
    val startIndex: Int? = null,
) {
    /** The next page, or null when this is the last one. */
    val nextPage: String? get() = links.firstOrNull { it.rel == OpdsRel.NEXT }?.href

    val searchDescription: String?
        get() = links.firstOrNull { it.rel == OpdsRel.SEARCH }?.href
}

data class OpdsEntry(
    val id: String,
    val title: String,
    val authors: List<OpdsAuthor> = emptyList(),
    val summary: String? = null,
    /** Calibre tags. Free text, one per `<category>`. */
    val categories: List<String> = emptyList(),
    val language: String? = null,
    val publisher: String? = null,
    /** Kept as the raw ISO string: whose timezone it means is the caller's problem. */
    val published: String? = null,
    val updated: String? = null,
    val links: List<OpdsLink> = emptyList(),
) {
    /**
     * The downloadable files, one per format the server holds.
     *
     * A Calibre entry commonly carries several — epub, pdf, mobi of the same
     * book — which is why this is a list and not a single link.
     */
    val acquisitions: List<OpdsLink>
        get() = links.filter { it.rel != null && it.rel.startsWith(OpdsRel.ACQUISITION) }

    val cover: OpdsLink?
        get() = links.firstOrNull { it.rel == OpdsRel.IMAGE }
            ?: links.firstOrNull { it.rel == OpdsRel.THUMBNAIL }

    val thumbnail: OpdsLink?
        get() = links.firstOrNull { it.rel == OpdsRel.THUMBNAIL }
            ?: links.firstOrNull { it.rel == OpdsRel.IMAGE }

    /**
     * Where this entry leads when it is a shelf rather than a book.
     *
     * A navigation entry has no acquisition link; it points at another feed.
     * That is the whole difference between "Authors" and "Dune".
     */
    val navigation: OpdsLink?
        get() = if (acquisitions.isNotEmpty()) null
        else links.firstOrNull { it.type?.startsWith(OpdsMediaType.ATOM) == true }
            ?: links.firstOrNull { it.rel == null || it.rel == OpdsRel.SUBSECTION }

    val isBook: Boolean get() = acquisitions.isNotEmpty()

    /**
     * How many books hang behind this shelf, when the index bothers to say.
     *
     * This is the only number in OPDS that can save a request instead of costing
     * one: knowing that a series still holds three books is knowing there is
     * nothing to learn by opening it. The grouping pass is one request per series
     * and the server answers them one at a time, so every entry that answers here
     * is a second or so off the sync.
     *
     * Two places, because catalogues disagree about where it goes. Calibre-Web
     * has published it both as a `thr:count` attribute on the link and as a
     * `<content>` reading "3 Books" — translated, so the words are not matched,
     * only the number they start with. Neither is required by anything, hence the
     * null: an index that says nothing costs exactly what it costs today.
     */
    val shelfCount: Int?
        get() = navigation?.count
            ?: summary?.let { LEADING_COUNT.find(it)?.groupValues?.get(1)?.toIntOrNull() }
}

/** A count at the start of a shelf's description — "3 Books", "3 Bücher", "3 livres". */
private val LEADING_COUNT = Regex("^\\s*(\\d{1,6})\\b")

data class OpdsAuthor(val name: String, val uri: String? = null)

data class OpdsLink(
    /** Absolute, always: the parser resolves against the feed it came from. */
    val href: String,
    val rel: String? = null,
    val type: String? = null,
    val title: String? = null,
    /** `thr:count` — how many entries hide behind a navigation link. */
    val count: Int? = null,
    /** Size in bytes, when the server bothers to say. */
    val length: Long? = null,
)

object OpdsRel {
    const val ACQUISITION = "http://opds-spec.org/acquisition"
    const val IMAGE = "http://opds-spec.org/image"
    const val THUMBNAIL = "http://opds-spec.org/image/thumbnail"
    const val SUBSECTION = "subsection"
    const val SEARCH = "search"
    const val NEXT = "next"
    const val PREVIOUS = "previous"
    const val START = "start"
    const val SELF = "self"
}

object OpdsMediaType {
    const val ATOM = "application/atom+xml"
    const val NAVIGATION = "application/atom+xml;profile=opds-catalog;kind=navigation"
    const val ACQUISITION = "application/atom+xml;profile=opds-catalog;kind=acquisition"
    const val EPUB = "application/epub+zip"
    const val PDF = "application/pdf"
    const val CBZ = "application/x-cbz"
    const val MOBI = "application/x-mobipocket-ebook"
    const val OPEN_SEARCH = "application/opensearchdescription+xml"
}
