package snd.komelia.offline.api

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import snd.komelia.offline.api.repository.OfflineCollectionRepository
import snd.komelia.offline.api.repository.OfflineSeriesDtoRepository
import snd.komga.client.collection.KomgaCollection
import snd.komga.client.collection.KomgaCollectionCreateRequest
import snd.komga.client.collection.KomgaCollectionId
import snd.komga.client.collection.KomgaCollectionUpdateRequest
import snd.komga.client.common.KomgaPageRequest
import snd.komga.client.common.Page
import snd.komga.client.common.Page.Companion.page
import snd.komga.client.common.Pageable
import snd.komga.client.common.PatchValue
import snd.komga.client.common.Sort
import snd.komga.client.library.KomgaLibraryId
import snd.komga.client.search.KomgaSearchCondition
import snd.komga.client.search.KomgaSearchOperator
import snd.komga.client.series.KomgaSeries
import snd.komga.client.series.KomgaSeriesBookMetadata
import snd.komga.client.series.KomgaSeriesId
import snd.komga.client.series.KomgaSeriesMetadata
import snd.komga.client.series.KomgaSeriesSearch
import snd.komga.client.series.KomgaSeriesStatus
import snd.komga.client.sse.KomgaEvent
import snd.komga.client.user.KomgaUserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class OfflineCollectionsApiTest {

    @Test
    fun keepsTheNameWhenOnlyTheMembersAreEdited() = runTest {
        val repository = FakeCollectionRepository()
        val api = api(repository)
        val created = api.addOne(KomgaCollectionCreateRequest("À lire cet été", false, listOf(id("a"))))

        api.updateOne(
            created.id,
            KomgaCollectionUpdateRequest(seriesIds = PatchValue.Some(listOf(id("a"), id("b")))),
        )

        val after = api.getOne(created.id)
        assertEquals("À lire cet été", after.name)
        assertEquals(listOf(id("a"), id("b")), after.seriesIds)
    }

    /**
     * `None` means "set to null" in Komga's patch protocol. Nothing on a
     * collection is nullable, so it must behave like `Unset` — an earlier
     * version would have written an empty name.
     */
    @Test
    fun treatsAnExplicitNullAsNoChange() = runTest {
        val repository = FakeCollectionRepository()
        val api = api(repository)
        val created = api.addOne(KomgaCollectionCreateRequest("Polars", true, listOf(id("a"))))

        api.updateOne(created.id, KomgaCollectionUpdateRequest(name = PatchValue.None))

        assertEquals("Polars", api.getOne(created.id).name)
        assertTrue(api.getOne(created.id).ordered)
    }

    @Test
    fun dropsASeriesListedTwice() = runTest {
        val repository = FakeCollectionRepository()
        val api = api(repository)

        val created = api.addOne(
            KomgaCollectionCreateRequest("Doublons", false, listOf(id("a"), id("b"), id("a"))),
        )

        assertEquals(listOf(id("a"), id("b")), created.seriesIds)
    }

    @Test
    fun tellsTheRestOfTheAppThatACollectionChanged() = runTest {
        val repository = FakeCollectionRepository()
        val events = MutableSharedFlow<KomgaEvent>(replay = 10, extraBufferCapacity = 10)
        val api = api(repository, events = events)

        val created = api.addOne(KomgaCollectionCreateRequest("SF", false, listOf(id("a"))))
        api.updateOne(created.id, KomgaCollectionUpdateRequest(name = PatchValue.Some("Science-fiction")))
        api.deleteOne(created.id)

        assertEquals(
            listOf(
                KomgaEvent.CollectionAdded::class,
                KomgaEvent.CollectionChanged::class,
                KomgaEvent.CollectionDeleted::class,
            ),
            events.replayCache.map { it::class },
        )
    }

    @Test
    fun saysNothingAboutACollectionThatIsAlreadyGone() = runTest {
        val repository = FakeCollectionRepository()
        val events = MutableSharedFlow<KomgaEvent>(replay = 10, extraBufferCapacity = 10)
        val api = api(repository, events = events)

        api.deleteOne(KomgaCollectionId("never-existed"))
        api.updateOne(KomgaCollectionId("never-existed"), KomgaCollectionUpdateRequest(name = PatchValue.Some("x")))

        assertTrue(events.replayCache.isEmpty())
    }

    @Test
    fun keepsTheReadersOrderWhenTheCollectionIsOrdered() = runTest {
        val repository = FakeCollectionRepository()
        val series = FakeSeriesDtoRepository(repository, listOf(series("c", "Cendres"), series("a", "Abîmes")))
        val api = api(repository, series)
        val created = api.addOne(KomgaCollectionCreateRequest("Ordonnée", true, listOf(id("c"), id("a"))))

        val page = api.getSeriesForCollection(created.id, pageRequest = KomgaPageRequest(unpaged = true))

        assertEquals(listOf("Cendres", "Abîmes"), page.content.map { it.metadata.title })
    }

    @Test
    fun sortsByTitleWhenTheCollectionIsNot() = runTest {
        val repository = FakeCollectionRepository()
        val series = FakeSeriesDtoRepository(repository, listOf(series("c", "Cendres"), series("a", "Abîmes")))
        val api = api(repository, series)
        val created = api.addOne(KomgaCollectionCreateRequest("Libre", false, listOf(id("c"), id("a"))))

        val page = api.getSeriesForCollection(created.id, pageRequest = KomgaPageRequest(unpaged = true))

        assertEquals(listOf("Abîmes", "Cendres"), page.content.map { it.metadata.title })
    }

    @Test
    fun forgetsASeriesTheMirrorNoLongerHas() = runTest {
        val repository = FakeCollectionRepository()
        val series = FakeSeriesDtoRepository(repository, listOf(series("a", "Abîmes")))
        val api = api(repository, series)
        val created = api.addOne(KomgaCollectionCreateRequest("Après resynchro", false, listOf(id("a"), id("gone"))))

        val page = api.getSeriesForCollection(created.id, pageRequest = KomgaPageRequest(unpaged = true))

        assertEquals(listOf("Abîmes"), page.content.map { it.metadata.title })
        assertEquals(1, page.totalElements)
    }

    @Test
    fun countsEveryMemberEvenWhenItHandsBackOnePage() = runTest {
        val repository = FakeCollectionRepository()
        val series = FakeSeriesDtoRepository(
            repository,
            listOf(series("a", "Un"), series("b", "Deux"), series("c", "Trois")),
        )
        val api = api(repository, series)
        val created = api.addOne(
            KomgaCollectionCreateRequest("Paginée", true, listOf(id("a"), id("b"), id("c"))),
        )

        val page = api.getSeriesForCollection(created.id, pageRequest = KomgaPageRequest(pageIndex = 1, size = 2))

        assertEquals(listOf("Trois"), page.content.map { it.metadata.title })
        assertEquals(3, page.totalElements)
    }

    @Test
    fun borrowsItsCoverFromItsFirstSeries() = runTest {
        val repository = FakeCollectionRepository()
        val api = api(repository, seriesCover = { seriesId -> seriesId.value.encodeToByteArray() })
        val created = api.addOne(KomgaCollectionCreateRequest("Couverture", true, listOf(id("c"), id("a"))))

        assertEquals("c", api.getDefaultThumbnail(created.id)?.decodeToString())
        assertNull(api.getDefaultThumbnail(KomgaCollectionId("never-existed")))
    }

    // ----- fixtures -----

    private fun api(
        repository: OfflineCollectionRepository,
        series: OfflineSeriesDtoRepository = FakeSeriesDtoRepository(),
        events: MutableSharedFlow<KomgaEvent> = MutableSharedFlow(replay = 10, extraBufferCapacity = 10),
        seriesCover: suspend (KomgaSeriesId) -> ByteArray? = { null },
    ) = OfflineCollectionsApi(
        collectionRepository = repository,
        seriesDtoRepository = series,
        offlineUserId = MutableStateFlow(KomgaUserId("reader")),
        seriesCover = seriesCover,
        komgaEvents = events,
    )

    private fun id(value: String) = KomgaSeriesId(value)

    /**
     * Stores exactly what it is given, and — like the real one — hands back only
     * the members whose series still exist, which here means the ones the
     * accompanying [FakeSeriesDtoRepository] knows about. Nothing filters here:
     * the api's own dropping of dead members is what the tests look at.
     */
    private class FakeCollectionRepository : OfflineCollectionRepository {
        private val stored = mutableMapOf<KomgaCollectionId, KomgaCollection>()

        override suspend fun findAll(
            search: String?,
            libraryIds: List<KomgaLibraryId>?,
            pageRequest: KomgaPageRequest,
        ): Page<KomgaCollection> = Page.empty()

        override suspend fun find(collectionId: KomgaCollectionId) = stored[collectionId]

        override suspend fun findAllBySeriesId(seriesId: KomgaSeriesId) =
            stored.values.filter { seriesId in it.seriesIds }

        override suspend fun save(collection: KomgaCollection) {
            stored[collection.id] = collection
        }

        override suspend fun delete(collectionId: KomgaCollectionId) {
            stored.remove(collectionId)
        }
    }

    /**
     * Answers a collection-membership search the way the real repository does:
     * the series it knows about that the collection lists, in no particular
     * order — SQL gives no promise there, and the api is what restores order.
     *
     * A fake that ignored the condition would let the ordering tests pass while
     * the real query returned the whole library, which is exactly the bug the
     * `Op.TRUE` stub had.
     */
    private class FakeSeriesDtoRepository(
        private val collections: OfflineCollectionRepository? = null,
        private val series: List<KomgaSeries> = emptyList(),
    ) : OfflineSeriesDtoRepository {
        private val known = series.associateBy { it.id }

        override suspend fun get(seriesId: KomgaSeriesId, userId: KomgaUserId) =
            known.getValue(seriesId)

        override suspend fun find(seriesId: KomgaSeriesId, userId: KomgaUserId) = known[seriesId]

        override suspend fun findAll(userId: KomgaUserId, pageRequest: KomgaPageRequest) =
            Page.empty<KomgaSeries>()

        override suspend fun findAll(
            search: KomgaSeriesSearch,
            userId: KomgaUserId,
            pageRequest: KomgaPageRequest,
        ): Page<KomgaSeries> {
            val condition = search.condition as? KomgaSearchCondition.CollectionId
                ?: return Page.empty()
            val operator = condition.operator as? KomgaSearchOperator.Is
                ?: return Page.empty()
            val members = collections?.find(operator.value)?.seriesIds.orEmpty()
            // Shuffled on purpose: nothing may rely on the order this returns.
            val content = members.mapNotNull { known[it] }.reversed()
            return page(
                content,
                Pageable(
                    Sort(sorted = false, unsorted = true, empty = content.isEmpty()),
                    pageNumber = 0,
                    pageSize = maxOf(content.size, 20),
                    offset = 0,
                    paged = false,
                    unpaged = true,
                ),
                content.size,
            )
        }

        override suspend fun findAllRecentlyUpdated(
            search: KomgaSeriesSearch,
            userId: KomgaUserId,
            pageRequest: KomgaPageRequest,
        ) = Page.empty<KomgaSeries>()
    }

    private fun series(id: String, title: String): KomgaSeries {
        val timestamp = Instant.fromEpochSeconds(0)
        return KomgaSeries(
            id = KomgaSeriesId(id),
            libraryId = KomgaLibraryId("library"),
            name = title,
            url = "",
            booksCount = 1,
            booksReadCount = 0,
            booksUnreadCount = 1,
            booksInProgressCount = 0,
            metadata = KomgaSeriesMetadata(
                status = KomgaSeriesStatus.ENDED,
                statusLock = false,
                title = title,
                titleLock = false,
                alternateTitles = emptyList(),
                alternateTitlesLock = false,
                titleSort = title,
                titleSortLock = false,
                summary = "",
                summaryLock = false,
                readingDirection = null,
                readingDirectionLock = false,
                publisher = "",
                publisherLock = false,
                ageRating = null,
                ageRatingLock = false,
                language = "",
                languageLock = false,
                genres = emptyList(),
                genresLock = false,
                tags = emptyList(),
                tagsLock = false,
                totalBookCount = 1,
                totalBookCountLock = false,
                sharingLabels = emptyList(),
                sharingLabelsLock = false,
                links = emptyList(),
                linksLock = false,
            ),
            deleted = false,
            oneshot = false,
            booksMetadata = KomgaSeriesBookMetadata(
                authors = emptyList(),
                tags = emptyList(),
                releaseDate = null,
                summary = "",
                summaryNumber = "",
                created = timestamp,
                lastModified = timestamp,
            ),
            created = timestamp,
            lastModified = timestamp,
            fileLastModified = timestamp,
        )
    }
}
