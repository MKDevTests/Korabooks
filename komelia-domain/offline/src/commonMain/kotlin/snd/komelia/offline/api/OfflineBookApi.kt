package snd.komelia.offline.api

import io.github.vinceglb.filekit.readBytes
import snd.komelia.offline.localFilePath
import snd.komelia.offline.readChunked
import kotlinx.coroutines.flow.StateFlow
import snd.komelia.komga.api.KomgaBookApi
import snd.komelia.komga.api.model.KomeliaBook
import snd.komelia.offline.action.OfflineActions
import snd.komelia.offline.api.repository.OfflineBookDtoRepository
import snd.komelia.offline.book.actions.BookAnalyzeAction
import snd.komelia.offline.book.actions.BookDeleteAction
import snd.komelia.offline.book.actions.BookMetadataRefreshAction
import snd.komelia.offline.book.actions.BookMetadataUpdateAction
import snd.komelia.offline.book.actions.BookThumbnailDeleteAction
import snd.komelia.offline.book.actions.BookThumbnailSelectAction
import snd.komelia.offline.book.actions.BookThumbnailUploadAction
import snd.komelia.offline.book.model.OfflineBook
import snd.komelia.offline.sync.CatalogueFileDownloader
import snd.komelia.offline.book.model.OfflineThumbnailBook
import snd.komelia.offline.book.repository.OfflineBookRepository
import snd.komelia.offline.book.repository.OfflineThumbnailBookRepository
import snd.komelia.offline.media.model.MediaExtensionEpub
import snd.komelia.offline.media.repository.OfflineMediaRepository
import snd.komelia.offline.mediacontainer.BookContentExtractors
import snd.komelia.offline.readprogress.OfflineReadProgressRepository
import snd.komelia.offline.readprogress.actions.ProgressCompleteForBookAction
import snd.komelia.offline.readprogress.actions.ProgressDeleteForBookAction
import snd.komelia.offline.readprogress.actions.ProgressMarkAction
import snd.komelia.offline.readprogress.actions.ProgressMarkProgressionAction
import snd.komga.client.book.KomgaBookId
import snd.komga.client.series.KomgaSeriesId
import snd.komga.client.book.KomgaBookMetadataUpdateRequest
import snd.komga.client.book.KomgaBookPage
import snd.komga.client.book.KomgaBookReadProgressUpdateRequest
import snd.komga.client.book.KomgaBookSearch
import snd.komga.client.book.KomgaBookThumbnail
import snd.komga.client.book.KomgaMediaStatus
import snd.komga.client.book.MediaProfile
import snd.komga.client.book.R2Device
import snd.komga.client.book.R2Locator
import snd.komga.client.book.R2Positions
import snd.komga.client.book.R2Progression
import snd.komga.client.book.WPPublication
import snd.komga.client.common.KomgaPageRequest
import snd.komga.client.common.KomgaSort
import snd.komga.client.common.KomgaThumbnailId
import snd.komga.client.common.Page
import snd.komga.client.library.KomgaLibraryId
import snd.komga.client.readlist.KomgaReadList
import snd.komga.client.search.BookConditionBuilder
import snd.komga.client.user.KomgaUserId

class OfflineBookApi(
    private val mediaRepository: OfflineMediaRepository,
    private val komeliaBookRepository: OfflineBookDtoRepository,
    private val bookRepository: OfflineBookRepository,
    private val thumbnailBookRepository: OfflineThumbnailBookRepository,
    private val readProgressRepository: OfflineReadProgressRepository,
    private val actions: OfflineActions,
    private val fileContentExtractors: BookContentExtractors,

    private val offlineUserId: StateFlow<KomgaUserId>,

    /**
     * Fetches a cover the mirror only knows the address of.
     *
     * Null everywhere the mirror holds real bytes — a Komga library downloaded
     * for offline reading carries its own thumbnails. A catalogue mirrored over
     * OPDS does not: twenty thousand covers are not worth six hundred megabytes
     * of database, so the sync stores the address and the picture arrives when
     * a grid asks for it.
     */
    private val coverLoader: (suspend (String) -> ByteArray?)? = null,

    /**
     * Reads a book that was mirrored but never downloaded.
     *
     * Opening one used to fail with a file-not-found on an empty path, which is
     * what a reader gets when it is handed a row describing a file nobody
     * fetched. Streaming straight from the catalogue means a book opens on the
     * first tap; downloading it explicitly is still what makes it stay.
     */
    private val catalogueDownloader: CatalogueFileDownloader? = null,
) : KomgaBookApi {

    /**
     * A downloaded book is one with a local modification date.
     *
     * The mirror writes zero there and an empty path beside it — the path alone
     * cannot be trusted to say whether anything is on disk.
     */
    private val OfflineBook.hasLocalCopy: Boolean
        get() = localFileLastModified.epochSeconds > 0

    private val userId
        get() = offlineUserId.value

    override suspend fun getOne(bookId: KomgaBookId): KomeliaBook {
        return komeliaBookRepository.get(bookId, userId)
    }

    override suspend fun getBookList(
        conditionBuilder: BookConditionBuilder,
        fullTextSearch: String?,
        pageRequest: KomgaPageRequest?
    ): Page<KomeliaBook> {
        return getBookList(
            search = KomgaBookSearch(
                condition = conditionBuilder.toBookCondition(),
                fullTextSearch = fullTextSearch
            ),
            pageRequest = pageRequest
        )
    }

    override suspend fun getBookList(
        search: KomgaBookSearch,
        pageRequest: KomgaPageRequest?
    ): Page<KomeliaBook> {

        return komeliaBookRepository.findAll(
            userId = userId,
            search = search,
            pageRequest = pageRequest ?: KomgaPageRequest(unpaged = true),
        )
    }

    override suspend fun getLatestBooks(pageRequest: KomgaPageRequest?): Page<KomeliaBook> {
        val sort = KomgaSort.KomgaBooksSort.byLastModifiedDateDesc()
        val page = (pageRequest ?: KomgaPageRequest()).copy(sort = sort)

        return komeliaBookRepository.findAll(userId, page)
    }

    override suspend fun getBooksOnDeck(
        libraryIds: List<KomgaLibraryId>?,
        pageRequest: KomgaPageRequest?
    ): Page<KomeliaBook> {
        return komeliaBookRepository.findAllOnDeck(
            userId = userId,
            filterOnLibraryIds = libraryIds,
            pageRequest = pageRequest ?: KomgaPageRequest()
        )
    }

    override suspend fun getDuplicateBooks(pageRequest: KomgaPageRequest?): Page<KomeliaBook> {
        return Page.empty()
    }

    override suspend fun getBookSiblingPrevious(bookId: KomgaBookId): KomeliaBook? {
        return komeliaBookRepository.findPreviousInSeriesOrNull(bookId, userId)
    }

    override suspend fun getBookSiblingNext(bookId: KomgaBookId): KomeliaBook? {
        return komeliaBookRepository.findNextInSeriesOrNull(bookId, userId)
    }

    override suspend fun updateMetadata(
        bookId: KomgaBookId,
        request: KomgaBookMetadataUpdateRequest
    ) {
        actions.get<BookMetadataUpdateAction>()
            .run(bookId, request)
    }

    override suspend fun getBookPages(bookId: KomgaBookId): List<KomgaBookPage> {
        val media = mediaRepository.get(bookId)
        return when (media.status) {
            KomgaMediaStatus.UNKNOWN -> error("Book has not been analyzed yet")
            KomgaMediaStatus.OUTDATED -> error("Book is outdated and must be re-analyzed")
            KomgaMediaStatus.ERROR -> error("Book analysis failed")
            KomgaMediaStatus.UNSUPPORTED -> error("Book format is not supported")
            KomgaMediaStatus.READY -> {
                media.pages.mapIndexed { index, bookPage ->
                    KomgaBookPage(
                        number = index + 1,
                        fileName = bookPage.fileName,
                        mediaType = bookPage.mediaType,
                        width = bookPage.width,
                        height = bookPage.height,
                        sizeBytes = bookPage.fileSize,
                        size = bookPage.fileSize
                            ?.let { "${(it.toDouble() / 1024 / 1024)}MiB" }
                            ?: ""
                    )

                }
            }
        }

    }

    override suspend fun analyze(bookId: KomgaBookId) {
        actions.get<BookAnalyzeAction>()
            .run(bookId)
    }

    override suspend fun refreshMetadata(bookId: KomgaBookId) {
        actions.get<BookMetadataRefreshAction>()
            .run(bookId)
    }

    override suspend fun markReadProgress(
        bookId: KomgaBookId,
        request: KomgaBookReadProgressUpdateRequest
    ) {
        if (request.completed == true) {
            actions.get<ProgressCompleteForBookAction>().run(
                bookId = bookId,
                userId = userId,
            )
        } else {
            actions.get<ProgressMarkAction>().run(
                bookId = bookId,
                userId = userId,
                page = requireNotNull(request.page)
            )
        }
    }

    override suspend fun deleteReadProgress(bookId: KomgaBookId) {
        actions.get<ProgressDeleteForBookAction>().run(
            bookId = bookId,
            userId = userId
        )
    }

    override suspend fun deleteBook(bookId: KomgaBookId) {
        actions.get<BookDeleteAction>()
            .execute(bookId = bookId)
    }

    override suspend fun regenerateThumbnails(forBiggerResultOnly: Boolean) {
    }

    override suspend fun getDefaultThumbnail(bookId: KomgaBookId): ByteArray? {
        val thumbnail = thumbnailBookRepository.findSelectedByBookId(bookId) ?: return null
        thumbnail.thumbnail?.let { return it }
        val url = thumbnail.url?.takeIf { it.isNotBlank() } ?: return null
        return coverLoader?.invoke(url)
    }

    override suspend fun getThumbnail(
        bookId: KomgaBookId,
        thumbnailId: KomgaThumbnailId
    ): ByteArray {
        TODO("Not yet implemented")
    }

    override suspend fun getThumbnails(bookId: KomgaBookId): List<KomgaBookThumbnail> {
        return thumbnailBookRepository.findAllByBookId(bookId).map { it.toKomgaBookThumbnail() }
    }

    override suspend fun uploadThumbnail(
        bookId: KomgaBookId,
        file: ByteArray,
        filename: String,
        selected: Boolean
    ): KomgaBookThumbnail {
        val thumbnail = actions.get<BookThumbnailUploadAction>()
            .run(
                bookId = bookId,
                file = file,
                selected = selected
            )
        return thumbnail.toKomgaBookThumbnail()
    }

    override suspend fun selectBookThumbnail(
        bookId: KomgaBookId,
        thumbnailId: KomgaThumbnailId
    ) {
        actions.get<BookThumbnailSelectAction>()
            .run(
                bookId = bookId,
                thumbnailId = thumbnailId
            )
    }

    override suspend fun deleteBookThumbnail(
        bookId: KomgaBookId,
        thumbnailId: KomgaThumbnailId
    ) {
        actions.get<BookThumbnailDeleteAction>()
            .run(
                bookId = bookId,
                thumbnailId = thumbnailId
            )
    }

    override suspend fun getAllReadListsByBook(bookId: KomgaBookId): List<KomgaReadList> {
        return emptyList()
    }

    override suspend fun getPage(bookId: KomgaBookId, page: Int): ByteArray {
        val book = bookRepository.get(bookId)
        val media = mediaRepository.get(bookId)

        return fileContentExtractors.getBookPage(book, media, page)
    }

    // avoid double decode/encode and get raw image, resize is handled in client
    override suspend fun getPageThumbnail(
        bookId: KomgaBookId,
        page: Int
    ): ByteArray {
        val book = bookRepository.get(bookId)
        val media = mediaRepository.get(bookId)

        return fileContentExtractors.getBookPage(book, media, page)
    }

    override suspend fun getReadiumProgression(bookId: KomgaBookId): R2Progression? {
        return readProgressRepository.find(bookId, userId)?.let {
            R2Progression(
                modified = it.readDate,
                device = R2Device(it.deviceId, it.deviceName),
                locator = it.locator ?: R2Locator("", "")
            )
        }
    }

    override suspend fun updateReadiumProgression(
        bookId: KomgaBookId,
        progression: R2Progression
    ) {
        actions.get<ProgressMarkProgressionAction>().run(
            bookId = bookId,
            userId = userId,
            newProgression = progression
        )
    }

    override suspend fun getReadiumPositions(bookId: KomgaBookId): R2Positions {
        val media = mediaRepository.get(bookId)
        check(media.extension is MediaExtensionEpub)
        return R2Positions(media.extension.positions.size, media.extension.positions)
    }

    override suspend fun getWebPubManifest(bookId: KomgaBookId): WPPublication {
        val media = mediaRepository.get(bookId)
        return when (val extension = media.extension) {
            is MediaExtensionEpub -> extension.manifest
            null -> throw IllegalStateException("Unsupported book type")
        }
    }

    override suspend fun getBookEpubResource(
        bookId: KomgaBookId,
        resourceName: String
    ): ByteArray {
        val book = bookRepository.get(bookId)
        val media = mediaRepository.get(bookId)
        return when (media.mediaProfile) {
            MediaProfile.EPUB -> fileContentExtractors.getFileContent(book, media, resourceName)
            else -> throw IllegalStateException("Unsupported media profile ${media.mediaProfile}")
        }
    }

    override suspend fun getBookRawFile(bookId: KomgaBookId): ByteArray {
        val book = bookRepository.get(bookId)
        if (book.hasLocalCopy) return book.fileDownloadPath.readBytes()

        val downloader = catalogueDownloader ?: return book.fileDownloadPath.readBytes()
        val chunks = mutableListOf<ByteArray>()
        downloader.stream(book.url) { chunks.add(it) }
        val size = chunks.sumOf { it.size }
        val out = ByteArray(size)
        var offset = 0
        chunks.forEach { it.copyInto(out, offset); offset += it.size }
        return out
    }

    override suspend fun downloadBookRawFile(bookId: KomgaBookId, onChunk: suspend (ByteArray) -> Unit) {
        val book = bookRepository.get(bookId)
        if (!book.hasLocalCopy && catalogueDownloader != null) {
            catalogueDownloader.stream(book.url, onChunk)
            return
        }
        book.fileDownloadPath.readChunked(64 * 1024, onChunk)
    }

    override suspend fun getDownloadedSeriesIds(seriesIds: List<KomgaSeriesId>): Set<KomgaSeriesId> {
        return seriesIds.toSet()
    }

    override suspend fun getBookLocalFilePath(bookId: KomgaBookId): String? {
        return runCatching {
            val book = bookRepository.get(bookId)
            if (!book.hasLocalCopy) null
            else book.fileDownloadPath.localFilePath()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    override suspend fun hasLocalFile(bookId: KomgaBookId): Boolean =
        runCatching { bookRepository.get(bookId).hasLocalCopy }.getOrDefault(false)

    private fun OfflineThumbnailBook.toKomgaBookThumbnail() = KomgaBookThumbnail(
        id = this.id,
        bookId = this.bookId,
        type = this.type.name,
        selected = this.selected,
        mediaType = this.mediaType,
        fileSize = this.fileSize,
        width = this.width,
        height = this.height
    )
}