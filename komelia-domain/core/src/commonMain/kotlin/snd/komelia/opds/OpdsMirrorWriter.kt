package snd.komelia.opds

import io.github.vinceglb.filekit.PlatformFile
import snd.komelia.komga.api.model.KomeliaBook
import snd.komelia.offline.OfflineRepositories
import snd.komelia.offline.book.model.OfflineBook
import snd.komelia.offline.book.model.OfflineThumbnailBook
import snd.komelia.offline.book.model.toOfflineBookMetadata
import snd.komelia.offline.library.model.OfflineLibrary
import snd.komelia.offline.media.model.OfflineMedia
import snd.komelia.offline.series.model.OfflineBookMetadataAggregation
import snd.komelia.offline.series.model.OfflineSeries
import snd.komelia.offline.series.model.OfflineSeriesMetadata
import snd.komelia.offline.series.model.OfflineThumbnailSeries
import snd.komelia.offline.server.model.OfflineMediaServer
import snd.komelia.offline.server.model.OfflineMediaServerId
import snd.komga.client.book.KomgaBookId
import snd.komga.client.common.KomgaThumbnailId
import snd.komga.client.library.KomgaLibraryId
import snd.komga.client.library.ScanInterval
import snd.komga.client.library.SeriesCover
import snd.komga.client.series.KomgaSeriesId
import kotlin.time.Instant

/**
 * Writes catalogue shelves into the local mirror.
 *
 * The mirror is the one the offline mode already fills from Komga, and that is
 * the entire point: thirteen API implementations read it, with the searching,
 * filtering, sorting and paging already written. A row put here is a row every
 * screen can already display.
 *
 * What differs from an offline download is that the file is not here. The
 * catalogue is mirrored whole — five hundred books browsable on a phone holding
 * none of them — and a book only gains a file when the reader asks for it.
 */
/**
 * What a batch of shelves actually did to the mirror.
 *
 * Both sets exist for pruning, which deletes every shelf a sync did not claim.
 * [preserved] must be claimed even though nothing was written to it, and
 * [skipped] must *not* be — claiming a standalone shelf that was deliberately
 * not created would report a library larger than the one on disk.
 */
data class MirrorWriteResult(
    /** Series that already held these books, and were left exactly as they were. */
    val preserved: Set<KomgaSeriesId> = emptySet(),
    /** Shelves not written, every one of their books having kept its series. */
    val skipped: Set<KomgaSeriesId> = emptySet(),
)

class OpdsMirrorWriter(private val repositories: OfflineRepositories) {

    /**
     * The library row standing for a catalogue, created once and found again on
     * every later sync through an id derived from its address.
     */
    suspend fun library(catalogueUrl: String, name: String): KomgaLibraryId {
        val server = repositories.mediaServerRepository.findByUrl(catalogueUrl)
            ?: OfflineMediaServer(
                id = OfflineMediaServerId(OpdsMapper.stableId(catalogueUrl, "server")),
                url = catalogueUrl,
            ).also { repositories.mediaServerRepository.save(it) }

        val id = KomgaLibraryId(OpdsMapper.stableId(catalogueUrl, "library"))
        val existing = repositories.libraryRepository.find(id)
        if (existing == null || existing.name != name) {
            repositories.libraryRepository.save(
                OfflineLibrary(
                    id = id,
                    mediaServerId = server.id,
                    name = name,
                    root = catalogueUrl,
                    importComicInfoBook = false,
                    importComicInfoSeries = false,
                    importComicInfoCollection = false,
                    importComicInfoReadList = false,
                    importComicInfoSeriesAppendVolume = false,
                    importEpubBook = false,
                    importEpubSeries = false,
                    importMylarSeries = false,
                    importLocalArtwork = false,
                    importBarcodeIsbn = false,
                    scanForceModifiedTime = false,
                    // Nothing here scans a folder: the catalogue is the source,
                    // and it is re-read on demand.
                    scanInterval = ScanInterval.DISABLED,
                    scanOnStartup = false,
                    scanCbx = true,
                    scanPdf = true,
                    scanEpub = true,
                    scanDirectoryExclusions = emptyList(),
                    repairExtensions = false,
                    convertToCbz = false,
                    emptyTrashAfterScan = false,
                    seriesCover = SeriesCover.FIRST,
                    hashFiles = false,
                    hashPages = false,
                    hashKoreader = false,
                    analyzeDimensions = false,
                    oneshotsDirectory = null,
                    unavailable = false,
                )
            )
        }
        return id
    }

    /**
     * Many shelves, one transaction.
     *
     * A shelf costs seven inserts — series, its metadata, its aggregation, then
     * the book, its metadata, its media, its cover — and a transaction of its
     * own costs far more than the seven. Written one at a time a phone managed
     * twenty-five books a minute while the network was delivering twelve
     * hundred; batched, the writing stops being the thing you wait for.
     *
     * Still batches rather than one transaction for the catalogue: an
     * interrupted sync should leave a smaller library, never a broken one.
     */
    suspend fun write(
        batch: List<MappedShelf>,
        covers: Map<KomgaBookId, String> = emptyMap(),
        /**
         * Series the mirror already holds and that must survive this write —
         * pass [seriesBookCounts] filtered to more than one book. Empty means
         * the old behaviour: every book lands on the shelf it was mapped onto.
         */
        grouped: Set<KomgaSeriesId> = emptySet(),
    ): MirrorWriteResult {
        if (batch.isEmpty()) return MirrorWriteResult()

        // The books of this batch that already belong to a real series.
        //
        // This is the whole reason a full sync used to cost forty-five minutes,
        // and it was not a performance problem at all. The books pass maps every
        // book onto a shelf of its own, and writing that shelf wrote the book
        // with its standalone parent — unconditionally. So each full sync tore
        // the entire library back apart, and the grouping pass that followed,
        // one request per series against a server answering one at a time, was
        // spending forty minutes repairing damage done four minutes earlier.
        //
        // Keeping the existing parent costs one query per five hundred books and
        // makes the grouping pass what it always claimed to be: the optional
        // half.
        val parents: Map<KomgaBookId, KomgaSeriesId> =
            if (grouped.isEmpty()) emptyMap()
            else batch.flatMap { shelf -> shelf.books.map { it.id } }
                .chunked(IDS_PER_QUERY)
                .flatMap { repositories.bookRepository.findIn(it) }
                .mapNotNull { book ->
                    book.seriesId.takeIf { it in grouped }?.let { book.id to it }
                }
                .toMap()

        // Shelves nobody wrote, so the caller does not count them as library and
        // does not ask prune to spare something that is not there.
        val skipped = batch
            .filter { shelf -> shelf.books.isNotEmpty() && shelf.books.all { it.id in parents } }
            .map { it.series.id }
            .toSet()

        repositories.transactionTemplate.execute {
            for (mapped in batch) writeShelf(mapped, covers, parents)
            // Book metadata is the heaviest row a book has — a title and a
            // number, plus authors, tags and links in three tables of their
            // own. Written per book that is seven statements each; hoisted out
            // of the loop the whole batch costs seven. Written for every book,
            // including the ones that kept their series: the point of re-reading
            // a catalogue is to pick up metadata that changed.
            repositories.bookMetadataRepository.saveAll(
                batch.flatMap { it.books }.map { it.metadata.toOfflineBookMetadata(it.id) }
            )
        }
        return MirrorWriteResult(preserved = parents.values.toSet(), skipped = skipped)
    }

    suspend fun write(mapped: MappedShelf, covers: Map<KomgaBookId, String> = emptyMap()) {
        write(listOf(mapped), covers)
    }

    /** Whether the mirror already holds this book — the whole of the diff check. */
    suspend fun hasBook(id: KomgaBookId): Boolean = repositories.bookRepository.exists(id)

    /**
     * How many books each shelf of this library currently holds.
     *
     * Two questions are answered from this one pass, and both exist to stop the
     * grouping pass doing things per series:
     *
     *  - **which shelves have been grouped already**, for a resumed pass. Nothing
     *    is checkpointed anywhere; the mirror is its own record. Holding two books
     *    or more is something a shelf can only have become by being grouped — the
     *    first pass gives every book a shelf of its own, and a one-book shelf is
     *    precisely what it leaves behind. The cost of being wrong is lopsided on
     *    purpose: a real single-volume series looks ungrouped and is read again,
     *    wasting one request, while nothing is ever wrongly skipped, because two
     *    books never landed on one shelf by accident.
     *  - **whether a shelf has changed**, for a full one, by comparing against the
     *    count the catalogue's own index publishes. A series that held three books
     *    last week and says three books today has nothing to teach us, and opening
     *    it is a request against a server that answers one at a time.
     *
     * Counted rather than read from a column because there is no such column —
     * and counting it is one query per five hundred shelves, against thousands of
     * round trips saved.
     */
    suspend fun seriesBookCounts(libraryId: KomgaLibraryId): Map<KomgaSeriesId, Int> {
        val all = repositories.seriesRepository.findAllByLibraryId(libraryId).map { it.id }
        if (all.isEmpty()) return emptyMap()
        val counts = mutableMapOf<KomgaSeriesId, Int>()
        all.chunked(IDS_PER_QUERY).forEach { slice ->
            repositories.bookRepository.findAllBySeriesIds(slice).forEach { book ->
                counts[book.seriesId] = (counts[book.seriesId] ?: 0) + 1
            }
        }
        return counts
    }

    /**
     * Settles what a shelf can only know once all its volumes are in: how many
     * there are, and what they have in common.
     *
     * Needed because a shelf is now written once per book rather than once per
     * series. The books pass learns "SERIES: Skyward [2]" from the book itself,
     * and the volumes of one series are scattered across an alphabetical index,
     * so each arrives alone — each write therefore claimed the series held one
     * book, and stamped it with that one book's tags. A five volume series ended
     * up counting one and wearing whichever tags its last-written volume had.
     *
     * All of it is a property of the finished library rather than of any batch,
     * so it is computed here, once, from one pass over the shelves.
     */
    suspend fun refreshSeriesAggregates(libraryId: KomgaLibraryId): Int {
        val series = repositories.seriesRepository.findAllByLibraryId(libraryId)
        if (series.isEmpty()) return 0

        // Which books sit on which shelf, and their tags — read in slices for the
        // same reason everything else here is: a library holds ten thousand of them.
        val bookIdsBySeries = mutableMapOf<KomgaSeriesId, MutableList<KomgaBookId>>()
        series.map { it.id }.chunked(IDS_PER_QUERY).forEach { slice ->
            repositories.bookRepository.findAllBySeriesIds(slice).forEach { book ->
                bookIdsBySeries.getOrPut(book.seriesId) { mutableListOf() } += book.id
            }
        }
        val metadataByBook = bookIdsBySeries.values.flatten()
            .chunked(IDS_PER_QUERY)
            .flatMap { repositories.bookMetadataRepository.findAllByIds(it) }
            .associateBy { it.bookId }

        var changed = 0
        repositories.transactionTemplate.execute {
            for (shelf in series) {
                val bookIds = bookIdsBySeries[shelf.id] ?: emptyList()
                val metadata = bookIds.mapNotNull { metadataByBook[it] }
                // Sorted so the chips do not reshuffle between two syncs, and
                // distinct because a five volume series repeats its own tags five
                // times over.
                val tags = metadata.flatMap { it.tags }.distinct().sorted()
                val authors = metadata.flatMap { it.authors }.distinct()

                if (bookIds.size != shelf.bookCount) {
                    repositories.seriesRepository.save(shelf.copy(bookCount = bookIds.size))
                    changed++
                }
                repositories.seriesMetadataRepository.find(shelf.id)?.let { current ->
                    if (current.totalBookCount != bookIds.size || current.genres != tags) {
                        repositories.seriesMetadataRepository.save(
                            current.copy(totalBookCount = bookIds.size, genres = tags)
                        )
                    }
                }
                // The aggregation row is what the series page reads for the tag
                // chips and the credits; the mapper filled it from one volume.
                repositories.bookMetadataAggregationRepository.find(shelf.id)?.let { current ->
                    if (current.tags != tags.toSet() || current.authors != authors) {
                        repositories.bookMetadataAggregationRepository.save(
                            current.copy(tags = tags.toSet(), authors = authors)
                        )
                    }
                }
            }
        }
        return changed
    }

    /**
     * Moves books into their series, and writes nothing else about them.
     *
     * The grouping pass revisits books the first pass already wrote in full.
     * Writing them again — metadata, authors, tags, links, media, cover, seven
     * statements a book — costs everything and changes one column. Here the
     * existing rows are read once per batch and saved back with a new parent.
     *
     * Books the first pass never saw are written whole: a series shelf may
     * offer a book the alphabetical index missed, and losing it to an
     * optimisation would be a poor trade.
     */
    suspend fun regroup(batch: List<MappedShelf>) {
        if (batch.isEmpty()) return
        val existing = batch.flatMap { it.books.map { book -> book.id } }
            .chunked(IDS_PER_QUERY)
            .flatMap { repositories.bookRepository.findIn(it) }
            .associateBy { it.id }

        repositories.transactionTemplate.execute {
            val unseen = mutableListOf<KomeliaBook>()
            for (mapped in batch) {
                writeSeries(mapped)
                for (book in mapped.books) {
                    val current = existing[book.id]
                    if (current == null) {
                        writeBook(book, null)
                        unseen += book
                    } else if (current.seriesId != mapped.series.id) {
                        repositories.bookRepository.save(current.copy(seriesId = mapped.series.id))
                    }
                }
            }
            // writeBook leaves metadata to its caller, so the books this pass
            // discovers have to be given theirs here — the ones it merely
            // reparents already have it.
            repositories.bookMetadataRepository.saveAll(
                unseen.map { it.metadata.toOfflineBookMetadata(it.id) }
            )
        }
    }

    private suspend fun writeShelf(
        mapped: MappedShelf,
        covers: Map<KomgaBookId, String>,
        parents: Map<KomgaBookId, KomgaSeriesId> = emptyMap(),
    ) {
        // Every book here already sits in a series: this shelf is the standalone
        // one the books pass would recreate, and writing it would both resurrect
        // a shelf that prune has to clean up again and pull the books back out.
        val allKept = mapped.books.isNotEmpty() && mapped.books.all { it.id in parents }
        if (!allKept) writeSeries(mapped)

        for (book in mapped.books) {
            val parent = parents[book.id]
            writeBook(if (parent != null) book.copy(seriesId = parent) else book, covers[book.id])
        }
        if (allKept) return

        covers[mapped.books.firstOrNull()?.id]?.let { href ->
            repositories.thumbnailSeriesRepository.save(
                OfflineThumbnailSeries(
                    id = KomgaThumbnailId(OpdsMapper.stableId(mapped.series.id.value, "cover")),
                    seriesId = mapped.series.id,
                    type = OfflineThumbnailSeries.Type.SIDECAR,
                    selected = true,
                    mediaType = "image/jpeg",
                    fileSize = 0,
                    // Unknown without decoding the image, and nothing reads
                    // them: Coil measures the bitmap it actually loads.
                    width = 0,
                    height = 0,
                    url = href,
                    thumbnail = null,
                )
            )
        }
    }

    private suspend fun writeSeries(mapped: MappedShelf) {
        val series = mapped.series
        run {
            repositories.seriesRepository.save(
                OfflineSeries(
                    id = series.id,
                    libraryId = series.libraryId,
                    name = series.name,
                    url = series.url,
                    oneshot = series.oneshot,
                    bookCount = series.booksCount,
                    deleted = false,
                    created = series.created,
                    lastModified = series.lastModified,
                    fileLastModified = series.fileLastModified,
                )
            )
            repositories.seriesMetadataRepository.save(
                OfflineSeriesMetadata(
                    seriesId = series.id,
                    status = series.metadata.status,
                    statusLock = false,
                    title = series.metadata.title,
                    alternateTitles = series.metadata.alternateTitles,
                    alternateTitlesLock = false,
                    titleLock = false,
                    titleSort = series.metadata.titleSort,
                    titleSortLock = false,
                    summary = series.metadata.summary,
                    summaryLock = false,
                    readingDirection = series.metadata.readingDirection,
                    readingDirectionLock = false,
                    publisher = series.metadata.publisher,
                    publisherLock = false,
                    ageRating = series.metadata.ageRating,
                    ageRatingLock = false,
                    language = series.metadata.language,
                    languageLock = false,
                    genres = series.metadata.genres,
                    genresLock = false,
                    tags = series.metadata.tags,
                    tagsLock = false,
                    totalBookCount = series.metadata.totalBookCount,
                    totalBookCountLock = false,
                    sharingLabels = series.metadata.sharingLabels,
                    sharingLabelsLock = false,
                    links = series.metadata.links,
                    linksLock = false,
                )
            )
            // The mapper already worked out what the shelf's books have in
            // common; saving an empty row instead threw it away, and every
            // screen reading a series' authors or tags — the series page, the
            // author and tag filters — was told the series had none.
            repositories.bookMetadataAggregationRepository.save(
                OfflineBookMetadataAggregation(
                    seriesId = series.id,
                    releaseDate = series.booksMetadata.releaseDate,
                    summary = series.booksMetadata.summary,
                    summaryNumber = series.booksMetadata.summaryNumber,
                    authors = series.booksMetadata.authors,
                    tags = series.booksMetadata.tags.toSet(),
                    createdDate = series.booksMetadata.created,
                    lastModifiedDate = series.booksMetadata.lastModified,
                )
            )
        }
    }

    private suspend fun writeBook(book: KomeliaBook, cover: String?) {
        repositories.bookRepository.save(
            OfflineBook(
                id = book.id,
                seriesId = book.seriesId,
                libraryId = book.libraryId,
                name = book.name,
                number = book.number,
                deleted = false,
                fileHash = book.fileHash,
                oneshot = book.oneshot,
                url = book.url,
                size = book.size,
                sizeBytes = book.sizeBytes,
                created = book.created,
                lastModified = book.lastModified,
                remoteFileLastModified = book.fileLastModified,
                // Epoch zero is what says "no file here yet". The download sets
                // a real date, and that is what the library reads to tell a
                // book on the shelf from a book on the server.
                localFileLastModified = Instant.fromEpochSeconds(0),
                remoteUnavailable = false,
                fileDownloadPath = PlatformFile(""),
            )
        )
        // Metadata is not written here: write() saves the whole batch of it in
        // one go, and regroup() only ever touches books it already wrote.
        repositories.mediaRepository.save(
            OfflineMedia(
                bookId = book.id,
                status = book.media.status,
                mediaType = book.media.mediaType,
                mediaProfile = book.media.mediaProfile,
                comment = book.media.comment,
                epubDivinaCompatible = book.media.epubDivinaCompatible,
                epubIsKepub = book.media.epubIsKepub,
                pageCount = book.media.pagesCount,
                // A page list needs the file open. It arrives with the download.
                pages = emptyList(),
                extension = null,
            )
        )
        // The address, not the image. Twenty thousand covers is twenty thousand
        // requests, and fetching them during a sync makes the whole library
        // wait on pictures nobody is looking at yet. The row is written so a
        // cover can be filled in later, when a screen actually asks for it.
        cover?.let { href ->
            repositories.thumbnailBookRepository.save(
                OfflineThumbnailBook(
                    id = KomgaThumbnailId(OpdsMapper.stableId(book.id.value, "cover")),
                    bookId = book.id,
                    type = OfflineThumbnailBook.Type.SIDECAR,
                    selected = true,
                    mediaType = "image/jpeg",
                    fileSize = 0,
                    width = 0,
                    height = 0,
                    url = href,
                    thumbnail = null,
                )
            )
        }
    }

    /**
     * Deletes the shelves left with no books, and says how many.
     *
     * Grouping moves a book from its own one-book shelf into a series, and the
     * shelf it came from stays behind as an empty husk. They are found by being
     * empty rather than predicted from what moved: a book can leave a shelf for
     * more than one reason, and only the count knows.
     */
    suspend fun pruneEmptySeries(libraryId: KomgaLibraryId): Int {
        val empty = emptySeriesOf(libraryId)
        if (empty.isEmpty()) return 0
        deleteSeries(empty)
        return empty.size
    }

    /**
     * Drops the shelves the catalogue no longer offers — the empty ones.
     *
     * Emptiness is not a detail of the rule, it is the rule: a book row holds a
     * foreign key to its series, and deleting a shelf that still contains books
     * fails the constraint and takes the end of a twenty-minute sync with it.
     *
     * Deliberately narrow beyond that: it removes shelves, never the books
     * inside them, because a book missing from one sync is far more often a
     * server hiccup than a deletion — and losing a downloaded file to a hiccup
     * is unforgivable. A shelf that outlives its catalogue entry but still
     * holds books simply stays, and the next sync re-parents them.
     */
    suspend fun prune(libraryId: KomgaLibraryId, kept: Set<KomgaSeriesId>) {
        val stale = emptySeriesOf(libraryId).filterNot { it in kept }
        if (stale.isEmpty()) return
        deleteSeries(stale)
    }

    /** Shelves of [libraryId] that hold no book at all. */
    private suspend fun emptySeriesOf(libraryId: KomgaLibraryId): List<KomgaSeriesId> {
        val all = repositories.seriesRepository.findAllByLibraryId(libraryId).map { it.id }
        if (all.isEmpty()) return emptyList()
        val withBooks = mutableSetOf<KomgaSeriesId>()
        all.chunked(IDS_PER_QUERY).forEach { slice ->
            repositories.bookRepository.findAllBySeriesIds(slice)
                .mapTo(withBooks) { it.seriesId }
        }
        return all.filterNot { it in withBooks }
    }

    /**
     * Deletes in slices, because SQLite counts the ids.
     *
     * An `in (…)` clause becomes one bound parameter per id, and a catalogue of
     * twenty thousand books walks straight past the limit — a sync that spent
     * twenty minutes getting everything right would then fail on its last
     * statement.
     */
    private suspend fun deleteSeries(ids: List<KomgaSeriesId>) {
        ids.chunked(IDS_PER_QUERY).forEach { slice ->
            repositories.transactionTemplate.execute {
                // The cover row first: it points at the series, and a shelf
                // emptied by the grouping pass keeps the thumbnail it borrowed
                // from the book that left.
                repositories.thumbnailSeriesRepository.deleteBySeriesIds(slice)
                repositories.seriesMetadataRepository.delete(slice)
                repositories.bookMetadataAggregationRepository.delete(slice)
                repositories.seriesRepository.delete(slice)
            }
        }
    }

    private companion object {
        /** Comfortably under SQLite's bound-parameter limit. */
        const val IDS_PER_QUERY = 500
    }
}
