package snd.komelia.offline.api

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import snd.komelia.komga.api.KomgaCollectionsApi
import snd.komelia.offline.api.repository.OfflineCollectionRepository
import snd.komelia.offline.api.repository.OfflineSeriesDtoRepository
import snd.komga.client.collection.KomgaCollection
import snd.komga.client.collection.KomgaCollectionCreateRequest
import snd.komga.client.collection.KomgaCollectionId
import snd.komga.client.collection.KomgaCollectionQuery
import snd.komga.client.collection.KomgaCollectionThumbnail
import snd.komga.client.collection.KomgaCollectionUpdateRequest
import snd.komga.client.common.KomgaPageRequest
import snd.komga.client.common.KomgaThumbnailId
import snd.komga.client.common.Page
import snd.komga.client.common.Page.Companion.page
import snd.komga.client.common.Pageable
import snd.komga.client.common.PatchValue
import snd.komga.client.common.Sort
import snd.komga.client.library.KomgaLibraryId
import snd.komga.client.search.KomgaSearchCondition
import snd.komga.client.search.KomgaSearchOperator
import snd.komga.client.series.KomgaSeries
import snd.komga.client.series.KomgaSeriesId
import snd.komga.client.series.KomgaSeriesSearch
import snd.komga.client.sse.KomgaEvent
import snd.komga.client.user.KomgaUserId
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Collections, served from the device instead of from Komga.
 *
 * This class used to be a stub: [getAll] answered an empty page and [addOne]
 * threw `NotImplementedError`, so the Collections tab was always empty and
 * "Add to collection" crashed the app. Everything it needs was already there —
 * the tab, the dialogs, the bulk actions, even a `RequiredJoin.Collection` — all
 * of it waiting on a store. [OfflineCollectionRepository] is that store.
 *
 * Thumbnails are **not** stored. A collection shows the cover of its first
 * series, and uploading a custom one is refused rather than silently dropped.
 */
class OfflineCollectionsApi(
    private val collectionRepository: OfflineCollectionRepository,
    private val seriesDtoRepository: OfflineSeriesDtoRepository,
    private val offlineUserId: StateFlow<KomgaUserId>,
    /** [OfflineSeriesApi.getDefaultThumbnail], which knows about book covers. */
    private val seriesCover: suspend (KomgaSeriesId) -> ByteArray?,
    private val komgaEvents: MutableSharedFlow<KomgaEvent>,
) : KomgaCollectionsApi {

    override suspend fun getAll(
        search: String?,
        libraryIds: List<KomgaLibraryId>?,
        pageRequest: KomgaPageRequest?
    ): Page<KomgaCollection> {
        return collectionRepository.findAll(
            search = search,
            libraryIds = libraryIds,
            pageRequest = pageRequest ?: KomgaPageRequest(),
        )
    }

    override suspend fun getOne(id: KomgaCollectionId): KomgaCollection {
        return collectionRepository.find(id)
            ?: throw IllegalStateException("Collection $id is not found")
    }

    override suspend fun addOne(request: KomgaCollectionCreateRequest): KomgaCollection {
        val now = Clock.System.now()
        val collection = KomgaCollection(
            id = KomgaCollectionId(Uuid.generateV4().toHexDashString()),
            name = request.name,
            ordered = request.ordered,
            seriesIds = request.seriesIds.distinct(),
            createdDate = now,
            lastModifiedDate = now,
            filtered = false,
        )
        collectionRepository.save(collection)
        komgaEvents.emit(KomgaEvent.CollectionAdded(collection.id, collection.seriesIds))
        // Read it back: the repository drops members whose series the mirror no
        // longer has, and the caller displays what it is handed.
        return collectionRepository.find(collection.id) ?: collection
    }

    override suspend fun updateOne(
        id: KomgaCollectionId,
        request: KomgaCollectionUpdateRequest
    ) {
        val current = collectionRepository.find(id) ?: return
        val updated = current.copy(
            name = request.name.orKeep(current.name),
            ordered = request.ordered.orKeep(current.ordered),
            seriesIds = request.seriesIds.orKeep(current.seriesIds).distinct(),
            lastModifiedDate = Clock.System.now(),
        )
        collectionRepository.save(updated)
        komgaEvents.emit(KomgaEvent.CollectionChanged(id, updated.seriesIds))
    }

    override suspend fun deleteOne(id: KomgaCollectionId) {
        val seriesIds = collectionRepository.find(id)?.seriesIds ?: return
        collectionRepository.delete(id)
        komgaEvents.emit(KomgaEvent.CollectionDeleted(id, seriesIds))
    }

    /**
     * The collection's series, in the reader's order when the collection is
     * ordered and by sort title otherwise.
     *
     * [query] is ignored. On Komga it narrows a collection by status, genre,
     * read state and so on; here the only caller asks for the whole thing, and
     * answering a filter by pretending to have applied it would be worse than
     * not offering it.
     */
    override suspend fun getSeriesForCollection(
        id: KomgaCollectionId,
        query: KomgaCollectionQuery?,
        pageRequest: KomgaPageRequest?
    ): Page<KomgaSeries> {
        val collection = collectionRepository.find(id) ?: return Page.empty()
        val userId = offlineUserId.value

        // Two queries, not one per member. This used to call find() per series id,
        // and each of those is the full series DTO query — metadata, aggregation,
        // genres, tags. A collection of thirty series meant thirty of them, and
        // the Collections tab fired that once per visible card to draw a progress
        // bar. One search on collection membership replaces the lot.
        val members = seriesDtoRepository.findAll(
            search = KomgaSeriesSearch(
                condition = KomgaSearchCondition.CollectionId(KomgaSearchOperator.Is(id)),
            ),
            userId = userId,
            pageRequest = KomgaPageRequest(unpaged = true),
        ).content

        // Restore the reader's order, which SQL cannot express: it lives in the
        // membership row, not in anything the series search knows about.
        val byId = members.associateBy { it.id }
        val ordered =
            if (collection.ordered) collection.seriesIds.mapNotNull { byId[it] }
            else members.sortedBy { it.metadata.titleSort.lowercase() }

        val request = pageRequest ?: KomgaPageRequest()
        val size = request.size ?: 20
        val offset = (request.pageIndex ?: 0) * size
        val slice = if (request.unpaged == true) ordered else ordered.drop(offset).take(size)
        return page(slice, request.toPageable(ordered.size), ordered.size)
    }

    override suspend fun getDefaultThumbnail(collectionId: KomgaCollectionId): ByteArray? {
        val first = collectionRepository.find(collectionId)?.seriesIds?.firstOrNull() ?: return null
        return seriesCover(first)
    }

    /**
     * Never reached: [getThumbnails] returns nothing, so nothing can ask for a
     * thumbnail by id. It throws rather than returning empty bytes, which would
     * be cached as a valid, blank cover.
     */
    override suspend fun getThumbnail(
        collectionId: KomgaCollectionId,
        thumbnailId: KomgaThumbnailId
    ): ByteArray {
        throw IllegalStateException("Local collections have no stored thumbnails")
    }

    override suspend fun getThumbnails(collectionId: KomgaCollectionId): List<KomgaCollectionThumbnail> {
        return emptyList()
    }

    override suspend fun uploadThumbnail(
        collectionId: KomgaCollectionId,
        file: ByteArray,
        filename: String,
        selected: Boolean
    ): KomgaCollectionThumbnail {
        throw UnsupportedOperationException("A local collection shows the cover of its first series")
    }

    override suspend fun selectThumbnail(
        collectionId: KomgaCollectionId,
        thumbnailId: KomgaThumbnailId
    ) = Unit

    override suspend fun deleteThumbnail(
        collectionId: KomgaCollectionId,
        thumbnailId: KomgaThumbnailId
    ) = Unit

    /**
     * `None` means "set to null" on Komga. Nothing here is nullable — a
     * collection has a name, an ordering and a list — so it is treated like
     * `Unset` and leaves the value alone.
     */
    private fun <T> PatchValue<T>.orKeep(current: T): T =
        if (this is PatchValue.Some) value else current

    private fun KomgaPageRequest.toPageable(total: Int): Pageable {
        val sort = Sort(sorted = true, unsorted = false, empty = total == 0)
        val size = this.size ?: 20
        return if (unpaged == true) Pageable(
            sort,
            pageNumber = 0,
            pageSize = maxOf(total, 20),
            offset = 0,
            paged = false,
            unpaged = true,
        ) else Pageable(
            sort,
            pageNumber = pageIndex ?: 0,
            pageSize = size,
            offset = (pageIndex ?: 0) * size,
            paged = true,
            unpaged = false,
        )
    }
}
