package snd.komelia.offline.api.repository

import snd.komga.client.collection.KomgaCollection
import snd.komga.client.collection.KomgaCollectionId
import snd.komga.client.common.KomgaPageRequest
import snd.komga.client.common.Page
import snd.komga.client.library.KomgaLibraryId
import snd.komga.client.series.KomgaSeriesId

/**
 * The reader's own collections, stored on the device.
 *
 * A Komga collection comes from the server. OPDS has nothing of the kind, so
 * this is the whole store: created here, read here, and deliberately never
 * synchronised. What it holds is series ids, which the mirror keeps stable
 * across a resync — so a collection outlives the catalogue it points into.
 *
 * Every read drops series the mirror no longer has. Nothing enforces the
 * foreign key (SQLite needs `PRAGMA foreign_keys = ON`, which this app never
 * sets), and a collection that claims six series while showing four is worse
 * than one that says four.
 *
 * The tables are the ones `V1__offline_mode.sql` already shipped, unused. No
 * migration was needed.
 */
interface OfflineCollectionRepository {

    /**
     * @param search matched on the name, case-insensitively, as a substring
     * @param libraryIds keeps collections holding at least one series from
     *   these libraries. A collection with **no** series matches regardless:
     *   it belongs to no library, and hiding it would make a collection the
     *   reader just created unreachable.
     */
    suspend fun findAll(
        search: String?,
        libraryIds: List<KomgaLibraryId>?,
        pageRequest: KomgaPageRequest,
    ): Page<KomgaCollection>

    suspend fun find(collectionId: KomgaCollectionId): KomgaCollection?

    /** Every collection holding [seriesId], name-ordered. */
    suspend fun findAllBySeriesId(seriesId: KomgaSeriesId): List<KomgaCollection>

    /**
     * Inserts or replaces, membership included.
     *
     * The order of [KomgaCollection.seriesIds] is the order stored, whether or
     * not the collection is [KomgaCollection.ordered] — turning manual ordering
     * on must not scramble what the reader was already looking at.
     */
    suspend fun save(collection: KomgaCollection)

    suspend fun delete(collectionId: KomgaCollectionId)
}
