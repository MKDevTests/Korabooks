package snd.komelia.offline.readprogress

import snd.komelia.offline.server.model.OfflineMediaServerId
import snd.komga.client.book.KomgaBookId
import snd.komga.client.series.KomgaSeriesId
import snd.komga.client.user.KomgaUserId
import kotlin.time.Instant

interface OfflineReadProgressRepository {
    suspend fun save(readProgress: OfflineReadProgress)
    suspend fun saveAll(readProgress: List<OfflineReadProgress>)
    suspend fun find(bookId: KomgaBookId, userId: KomgaUserId): OfflineReadProgress?

    suspend fun findAllByBookIdsAndUserId(
        bookIds: List<KomgaBookId>,
        userId: KomgaUserId,
    ): List<OfflineReadProgress>

    suspend fun findAllModifiedAfter(
        timestamp: Instant,
        userId: KomgaUserId,
        serverId: OfflineMediaServerId,
    ): List<OfflineReadProgress>

    suspend fun findAllByServer(
        userId: KomgaUserId,
        serverId: OfflineMediaServerId
    ): List<OfflineReadProgress>

    suspend fun deleteByUserId(userId: KomgaUserId, )

    suspend fun deleteByBookIdsAndUserId(
        bookIds: List<KomgaBookId>,
        userId: KomgaUserId,
    )

    /**
     * Recomputes the per-series read counts from the per-book progress.
     *
     * READ_PROGRESS_SERIES is derived data — how many books of a shelf are
     * read, how many are in progress, when it was last touched — and it is
     * maintained one book at a time, whenever progress is saved. Nothing
     * maintains it when a *book changes shelf*, which a catalogue sync does by
     * the thousand: the old shelf keeps counts for books it no longer holds,
     * the new one has none, and a series the reader finished shows as unread.
     *
     * Cheap enough to simply redo: the source is one row per book actually
     * read, not one per book in the library.
     */
    suspend fun rebuildSeriesAggregates()

    suspend fun deleteBySeriesIds(seriesIds: List<KomgaSeriesId>)
    suspend fun deleteByBookIds(bookIds: List<KomgaBookId>)

    suspend fun delete(bookId: KomgaBookId, userId: KomgaUserId)
    suspend fun deleteAllBy(bookId: KomgaBookId)
}