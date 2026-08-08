package snd.komelia.opds

import kotlinx.datetime.LocalDate
import snd.komelia.komga.api.model.KomeliaBook
import snd.komga.client.book.KomgaBookId
import snd.komga.client.book.KomgaBookMetadata
import snd.komga.client.book.KomgaMediaStatus
import snd.komga.client.book.Media
import snd.komga.client.book.MediaProfile
import snd.komga.client.common.KomgaAuthor
import snd.komga.client.library.KomgaLibraryId
import snd.komga.client.series.KomgaSeries
import snd.komga.client.series.KomgaSeriesBookMetadata
import snd.komga.client.series.KomgaSeriesId
import snd.komga.client.series.KomgaSeriesMetadata
import snd.komga.client.series.KomgaSeriesStatus
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A group of catalogue entries that belong together: a Calibre series, or a
 * single book standing alone.
 *
 * OPDS has no notion of a series — the standard predates the idea and Calibre
 * never added it to its feeds. What both Calibre-Web and calibre's own server
 * do expose is a *series shelf*: a feed listing the books of one series, in
 * order. Grouping therefore happens by walking those shelves, not by reading a
 * field, and that walk is the sync's job. This type is what it hands over.
 */
data class OpdsShelf(
    val title: String,
    val entries: List<OpdsEntry>,
    /** A book with no series: it becomes a one-book series, like a Komga oneshot. */
    val standalone: Boolean = false,
)

/** One catalogue shelf, in the shape the rest of the app already understands. */
data class MappedShelf(val series: KomgaSeries, val books: List<KomeliaBook>)

/**
 * Turns catalogue entries into the model the library UI reads.
 *
 * Every screen in this app — the grids, the details, the genre chips, the
 * similarity index — is written against [KomgaSeries] and [KomeliaBook]. Rather
 * than teach them a second content model, an OPDS entry is translated into the
 * first one and written to the same local mirror the offline mode uses. The
 * whole UI then works with no knowledge that Komga is not on the other end.
 *
 * Identifiers are derived, never invented: the same entry maps to the same id on
 * every sync, or read progress would be lost and rows would double at each
 * refresh.
 */
class OpdsMapper(
    private val libraryId: KomgaLibraryId,
    /** Stable per configured catalogue, so two servers cannot collide. */
    private val catalogueId: String,
    /** Best format first — what the reader opens when several are offered. */
    private val formatPreference: List<String> = DEFAULT_FORMATS,
    private val now: () -> Instant = { Clock.System.now() },
) {

    fun map(shelf: OpdsShelf): MappedShelf {
        val seriesId = seriesId(shelf.title)
        val books = shelf.entries.mapIndexedNotNull { index, entry ->
            book(entry, seriesId, shelf, index)
        }
        return MappedShelf(series(shelf, seriesId, books), books)
    }

    fun seriesId(title: String): KomgaSeriesId =
        KomgaSeriesId(stableId(catalogueId, "series", title))

    fun bookId(entry: OpdsEntry): KomgaBookId =
        KomgaBookId(stableId(catalogueId, "book", entry.id))

    private fun series(shelf: OpdsShelf, id: KomgaSeriesId, books: List<KomeliaBook>): KomgaSeries {
        val first = shelf.entries.firstOrNull()
        val timestamp = now()
        return KomgaSeries(
            id = id,
            libraryId = libraryId,
            name = shelf.title,
            url = "",
            booksCount = books.size,
            booksReadCount = 0,
            booksUnreadCount = books.size,
            booksInProgressCount = 0,
            metadata = KomgaSeriesMetadata(
                // A published book is a finished thing. Komga's ONGOING would
                // put a "still running" badge on every shelf of the library.
                status = KomgaSeriesStatus.ENDED,
                statusLock = false,
                title = shelf.title,
                titleLock = false,
                alternateTitles = emptyList(),
                alternateTitlesLock = false,
                titleSort = shelf.title,
                titleSortLock = false,
                summary = if (shelf.standalone) first?.summary.orEmpty() else "",
                summaryLock = false,
                // Books are not manga: leaving this null keeps the reader from
                // opening right to left on a French novel.
                readingDirection = null,
                readingDirectionLock = false,
                publisher = first?.publisher.orEmpty(),
                publisherLock = false,
                ageRating = null,
                ageRatingLock = false,
                language = first?.language.orEmpty(),
                languageLock = false,
                // Calibre tags are what a reader thinks of as genres, and they
                // are the only classification the catalogue offers.
                genres = shelf.entries.flatMap { it.categories }.distinct(),
                genresLock = false,
                tags = emptyList(),
                tagsLock = false,
                totalBookCount = books.size,
                totalBookCountLock = false,
                sharingLabels = emptyList(),
                sharingLabelsLock = false,
                links = emptyList(),
                linksLock = false,
            ),
            deleted = false,
            oneshot = shelf.standalone && books.size == 1,
            booksMetadata = KomgaSeriesBookMetadata(
                authors = books.flatMap { it.metadata.authors }.distinct(),
                tags = shelf.entries.flatMap { it.categories }.distinct(),
                releaseDate = books.firstNotNullOfOrNull { it.metadata.releaseDate },
                summary = first?.summary.orEmpty(),
                summaryNumber = books.firstOrNull()?.metadata?.number.orEmpty(),
                created = timestamp,
                lastModified = timestamp,
            ),
            created = timestamp,
            lastModified = timestamp,
            fileLastModified = timestamp,
        )
    }

    private fun book(
        entry: OpdsEntry,
        seriesId: KomgaSeriesId,
        shelf: OpdsShelf,
        index: Int,
    ): KomeliaBook? {
        // No file, no book. A catalogue entry without an acquisition link is a
        // shelf, and one that only offers formats we cannot open would be a
        // cover the reader can never turn into a page.
        val file = preferredAcquisition(entry) ?: return null
        val timestamp = entry.updated?.let { parseInstant(it) } ?: now()

        // The catalogue's own volume number when it publishes one, and only then
        // the position in the shelf. Position was all a series shelf ever gave
        // us; a book that names "SERIES: Skyward [2.50]" arrives on its own,
        // scattered across the alphabetical index, and would otherwise be volume
        // one of its series along with every other volume of it.
        val declared = entry.seriesIndex
        val number = declared ?: (index + 1).toDouble()

        return KomeliaBook(
            id = bookId(entry),
            seriesId = seriesId,
            seriesTitle = shelf.title,
            libraryId = libraryId,
            name = entry.title,
            // The acquisition URL, kept so the downloader knows where to go
            // without walking the catalogue a second time.
            url = file.href,
            number = number.toInt(),
            created = timestamp,
            lastModified = timestamp,
            fileLastModified = timestamp,
            sizeBytes = file.length ?: 0,
            size = formatSize(file.length),
            media = media(file),
            metadata = KomgaBookMetadata(
                title = entry.title,
                summary = entry.summary.orEmpty(),
                // "2.5" for a novella, "3" for a volume: a trailing .0 on every
                // book in the library would read as a bug.
                number = if (number == number.toInt().toDouble()) number.toInt().toString()
                else number.toString(),
                numberSort = number.toFloat(),
                releaseDate = entry.published?.let { parseDate(it) },
                // OPDS says who wrote a book, never in what capacity. Calling
                // them writers is the honest guess, and it is the role the
                // credits list expects.
                authors = entry.authors.map { KomgaAuthor(it.name, "writer") },
                tags = entry.categories,
                isbn = "",
                links = emptyList(),
                titleLock = false,
                summaryLock = false,
                numberLock = false,
                numberSortLock = false,
                releaseDateLock = false,
                authorsLock = false,
                tagsLock = false,
                isbnLock = false,
                linksLock = false,
                created = timestamp,
                lastModified = timestamp,
            ),
            readProgress = null,
            deleted = false,
            fileHash = "",
            oneshot = shelf.standalone,
            downloaded = false,
            localFileLastModified = null,
            remoteFileUnavailable = false,
        )
    }

    /**
     * Picks the format to read among those the catalogue offers.
     *
     * A Calibre book commonly exists as epub, pdf and mobi at once. The order is
     * a preference, not a filter of the entry: a book we cannot open at all is
     * dropped by [book], and that is a different decision.
     */
    fun preferredAcquisition(entry: OpdsEntry): OpdsLink? {
        val acquisitions = entry.acquisitions
        for (format in formatPreference) {
            acquisitions.firstOrNull { it.type?.startsWith(format) == true }?.let { return it }
        }
        return null
    }

    private fun media(file: OpdsLink): Media {
        val type = file.type.orEmpty()
        val isKepub = file.href.contains(".kepub", ignoreCase = true)
        return Media(
            // The file is not here yet; the catalogue is still authoritative
            // about what it holds. Anything but READY would grey the book out.
            status = KomgaMediaStatus.READY,
            mediaType = type,
            pagesCount = 0,
            comment = "",
            epubDivinaCompatible = false,
            epubIsKepub = isKepub,
            mediaProfile = when {
                type.startsWith(OpdsMediaType.EPUB) -> MediaProfile.EPUB
                type.startsWith(OpdsMediaType.PDF) -> MediaProfile.PDF
                type.contains("cb") || type.contains("zip") || type.contains("rar") -> MediaProfile.DIVINA
                else -> null
            },
        )
    }

    private fun parseDate(raw: String): LocalDate? =
        runCatching { LocalDate.parse(raw.substringBefore('T')) }.getOrNull()

    private fun parseInstant(raw: String): Instant? =
        runCatching { Instant.parse(raw) }.getOrNull()

    private fun formatSize(bytes: Long?): String {
        if (bytes == null || bytes <= 0) return ""
        val mib = bytes.toDouble() / 1024 / 1024
        val rounded = (mib * 100).toLong() / 100.0
        return "$rounded MiB"
    }

    companion object {
        /**
         * EPUB before anything: it is the format this app reads best, and the
         * one a Calibre library holds most of. CBZ and PDF are opened by the
         * image and PDF paths inherited from Kora. MOBI is deliberately absent —
         * nothing here can open it, and offering it would be a promise broken
         * at the last tap.
         */
        val DEFAULT_FORMATS = listOf(
            OpdsMediaType.EPUB,
            OpdsMediaType.CBZ,
            "application/x-cbr",
            "application/zip",
            OpdsMediaType.PDF,
        )

        /**
         * FNV-1a over the parts, hex.
         *
         * Any stable function would do; what matters is that it depends only on
         * the catalogue and the entry, so a re-sync updates the same row and the
         * page you stopped on survives it.
         */
        fun stableId(vararg parts: String): String {
            var hash = -0x340d631b7bdddcdbL
            for (part in parts) {
                for (byte in part.encodeToByteArray()) {
                    hash = hash xor (byte.toLong() and 0xFF)
                    hash *= 0x100000001b3L
                }
                hash = hash xor 0x2FL
                hash *= 0x100000001b3L
            }
            return hash.toULong().toString(16).padStart(16, '0')
        }
    }
}
