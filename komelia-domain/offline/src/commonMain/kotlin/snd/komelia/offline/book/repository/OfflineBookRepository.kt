package snd.komelia.offline.book.repository

import snd.komelia.offline.book.model.OfflineBook
import snd.komga.client.book.KomgaBookId
import snd.komga.client.library.KomgaLibraryId
import snd.komga.client.series.KomgaSeriesId
import snd.komga.client.user.KomgaUserId

interface OfflineBookRepository {
    suspend fun save(book: OfflineBook)
    suspend fun find(id: KomgaBookId): OfflineBook?
    suspend fun exists(id: KomgaBookId): Boolean
    suspend fun findIn(ids: Collection<KomgaBookId>): List<OfflineBook>


    suspend fun findFirstIdInSeriesOrNull(seriesId: KomgaSeriesId): KomgaBookId?

    suspend fun findLastIdInSeriesOrNull(seriesId: KomgaSeriesId): KomgaBookId?

    suspend fun findFirstUnreadIdInSeriesOrNull(
        seriesId: KomgaSeriesId,
        userId: KomgaUserId,
    ): KomgaBookId?

    suspend fun findAllBySeriesIds(seriesIds: List<KomgaSeriesId>): List<OfflineBook>

    /**
     * Of [seriesIds], the ones holding at least one book whose file is on disk.
     *
     * Distinct from "has a row": a mirrored catalogue has a row per book whether
     * or not anything was ever fetched, so presence in the table says nothing.
     * Only the local modification date does.
     */
    suspend fun findDownloadedSeriesIds(seriesIds: List<KomgaSeriesId>): Set<KomgaSeriesId>

    suspend fun findAllIdsBySeriesId(seriesId: KomgaSeriesId): List<KomgaBookId>
    suspend fun findAllIdsByLibraryId(libraryId: KomgaLibraryId): List<KomgaBookId>
    suspend fun get(id: KomgaBookId): OfflineBook
    suspend fun findAll(id: KomgaSeriesId): List<OfflineBook>
    suspend fun findAllNotDeleted(id: KomgaSeriesId): List<OfflineBook>
    suspend fun delete(id: KomgaBookId)

    suspend fun delete(ids: Collection<KomgaBookId>)
}