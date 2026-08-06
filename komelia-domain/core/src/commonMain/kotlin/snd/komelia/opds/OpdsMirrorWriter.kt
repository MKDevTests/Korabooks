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
    suspend fun write(batch: List<MappedShelf>, covers: Map<KomgaBookId, String> = emptyMap()) {
        if (batch.isEmpty()) return
        repositories.transactionTemplate.execute {
            for (mapped in batch) writeShelf(mapped, covers)
        }
    }

    suspend fun write(mapped: MappedShelf, covers: Map<KomgaBookId, String> = emptyMap()) =
        write(listOf(mapped), covers)

    private suspend fun writeShelf(mapped: MappedShelf, covers: Map<KomgaBookId, String>) {
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
            repositories.bookMetadataAggregationRepository.save(
                OfflineBookMetadataAggregation(seriesId = series.id)
            )

            for (book in mapped.books) {
                writeBook(book, covers[book.id])
            }

            covers[mapped.books.firstOrNull()?.id]?.let { href ->
                repositories.thumbnailSeriesRepository.save(
                    OfflineThumbnailSeries(
                        id = KomgaThumbnailId(OpdsMapper.stableId(series.id.value, "cover")),
                        seriesId = series.id,
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
        repositories.bookMetadataRepository.save(book.metadata.toOfflineBookMetadata(book.id))
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
     * Drops the shelves the catalogue no longer offers.
     *
     * Deliberately narrow: it removes series, never books inside a kept series,
     * because a book missing from one sync is more often a server hiccup than a
     * deletion, and losing a downloaded file to a hiccup is unforgivable.
     */
    /**
     * Deletes the shelves left with no books, and says how many.
     *
     * Grouping moves a book from its own one-book shelf into a series, and the
     * shelf it came from stays behind as an empty husk. They are found by being
     * empty rather than predicted from what moved: a book can leave a shelf for
     * more than one reason, and only the count knows.
     */
    suspend fun pruneEmptySeries(libraryId: KomgaLibraryId): Int {
        val all = repositories.seriesRepository.findAllByLibraryId(libraryId).map { it.id }
        if (all.isEmpty()) return 0
        val withBooks = repositories.bookRepository.findAllBySeriesIds(all).map { it.seriesId }.toSet()
        val empty = all.filterNot { it in withBooks }
        if (empty.isEmpty()) return 0
        repositories.transactionTemplate.execute {
            repositories.seriesMetadataRepository.delete(empty)
            repositories.bookMetadataAggregationRepository.delete(empty)
            repositories.seriesRepository.delete(empty)
        }
        return empty.size
    }

    suspend fun prune(libraryId: KomgaLibraryId, kept: Set<KomgaSeriesId>) {
        val stale = repositories.seriesRepository.findAllByLibraryId(libraryId)
            .map { it.id }
            .filterNot { it in kept }
        if (stale.isEmpty()) return
        repositories.transactionTemplate.execute {
            repositories.seriesMetadataRepository.delete(stale)
            repositories.bookMetadataAggregationRepository.delete(stale)
            repositories.seriesRepository.delete(stale)
        }
    }
}
