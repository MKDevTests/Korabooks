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
 * Every read drops series the mirror no longer has: a collection that claims
 * six series while showing four is worse than one that says four. That is a
 * belt over a brace — the offline database is opened with
 * `enforceForeignKeys(true)`, so a membership pointing nowhere cannot be
 * created in the first place; a sync deleting a shelf has to say what becomes
 * of the collections holding it, which is what [repointSeries] and
 * [deleteSeriesMemberships] are for.
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

    /**
     * Follows a series that changed identity, so the collections holding it do
     * not lose it.
     *
     * A shelf's id is derived, not stored, and a sync that changes how it is
     * derived moves every book onto a new shelf and leaves the old one to be
     * pruned. Deleting the membership would be silent curation loss — the
     * reader put that series in that collection by hand — so it is carried
     * over to where the books went.
     *
     * @param moves old shelf id to the shelf its books moved to
     */
    suspend fun repointSeries(moves: Map<KomgaSeriesId, KomgaSeriesId>)

    /**
     * Drops the memberships of shelves about to be deleted.
     *
     * Only for shelves whose books went nowhere — [repointSeries] has already
     * had its say. Without this the delete fails the foreign key and takes the
     * end of a twenty-minute sync with it.
     */
    suspend fun deleteSeriesMemberships(seriesIds: List<KomgaSeriesId>)

    suspend fun delete(collectionId: KomgaCollectionId)
}
