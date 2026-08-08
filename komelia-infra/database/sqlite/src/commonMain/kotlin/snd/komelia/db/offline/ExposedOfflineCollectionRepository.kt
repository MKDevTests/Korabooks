package snd.komelia.db.offline

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import snd.komelia.db.ExposedRepository
import snd.komelia.db.offline.tables.OfflineCollectionSeriesTable
import snd.komelia.db.offline.tables.OfflineCollectionTable
import snd.komelia.db.offline.tables.OfflineSeriesTable
import snd.komelia.offline.api.repository.OfflineCollectionRepository
import snd.komga.client.collection.KomgaCollection
import snd.komga.client.collection.KomgaCollectionId
import snd.komga.client.common.KomgaPageRequest
import snd.komga.client.common.Page
import snd.komga.client.library.KomgaLibraryId
import snd.komga.client.series.KomgaSeriesId
import kotlin.time.Instant

/**
 * Collections read back through a join on SERIES, always.
 *
 * COLLECTION_SERIES declares its foreign keys, but SQLite only enforces them
 * under `PRAGMA foreign_keys = ON`, which this app never sets — so a catalogue
 * resync that drops a series leaves membership rows pointing nowhere. Every read
 * here goes through [members], which joins and therefore forgets them.
 *
 * Filtering and sorting happen in Kotlin rather than in SQL, on purpose. These
 * are the reader's hand-made collections — tens of rows, not thousands — and
 * `LIKE` on a SQLite text column ignores accents in a way a French library
 * notices ("Épopées" would not match "epopees" either way, but `contains(
 * ignoreCase = true)` at least behaves the same on every platform).
 */
class ExposedOfflineCollectionRepository(database: Database) :
    ExposedRepository(database), OfflineCollectionRepository {
    private val collectionTable = OfflineCollectionTable
    private val collectionSeriesTable = OfflineCollectionSeriesTable
    private val seriesTable = OfflineSeriesTable

    override suspend fun findAll(
        search: String?,
        libraryIds: List<KomgaLibraryId>?,
        pageRequest: KomgaPageRequest,
    ): Page<KomgaCollection> {
        return transaction {
            val named = collectionTable.selectAll()
                .toList()
                .filter { search.isNullOrBlank() || it[collectionTable.name].contains(search, ignoreCase = true) }

            val members = members(named.map { it[collectionTable.id] })
            val wanted = libraryIds?.map { it.value }?.toSet()

            val kept = named.filter { row ->
                if (wanted.isNullOrEmpty()) return@filter true
                val own = members[row[collectionTable.id]].orEmpty()
                // An empty collection belongs to no library; hiding it would
                // lose the one the reader just created, before they filled it.
                own.isEmpty() || own.any { it.libraryId in wanted }
            }.sortedBy { it[collectionTable.name].lowercase() }

            val slice =
                if (pageRequest.unpaged == true) kept
                else kept.drop(pageRequest.offset().toInt()).take(pageRequest.size ?: 20)

            page(
                result = slice.map { it.toModel(members) },
                pageRequest = pageRequest,
                count = kept.size.toLong(),
                sorted = true,
            )
        }
    }

    override suspend fun find(collectionId: KomgaCollectionId): KomgaCollection? {
        return transaction {
            val row = collectionTable.selectAll()
                .where { collectionTable.id.eq(collectionId.value) }
                .firstOrNull() ?: return@transaction null
            row.toModel(members(listOf(collectionId.value)))
        }
    }

    override suspend fun findAllBySeriesId(seriesId: KomgaSeriesId): List<KomgaCollection> {
        return transaction {
            val ids = collectionSeriesTable
                .select(collectionSeriesTable.collectionId)
                .where { collectionSeriesTable.seriesId.eq(seriesId.value) }
                .map { it[collectionSeriesTable.collectionId] }
            if (ids.isEmpty()) return@transaction emptyList()

            val members = members(ids)
            collectionTable.selectAll()
                .where { collectionTable.id.inList(ids) }
                .map { it.toModel(members) }
                .sortedBy { it.name.lowercase() }
        }
    }

    override suspend fun save(collection: KomgaCollection) {
        transaction {
            collectionTable.upsert {
                it[collectionTable.id] = collection.id.value
                it[collectionTable.name] = collection.name
                it[collectionTable.ordered] = collection.ordered
                it[collectionTable.seriesCount] = collection.seriesIds.distinct().size
                it[collectionTable.createdDate] = collection.createdDate.epochSeconds
                it[collectionTable.lastModifiedDate] = collection.lastModifiedDate.epochSeconds
            }
            collectionSeriesTable.deleteWhere { collectionSeriesTable.collectionId.eq(collection.id.value) }
            // distinct() and not a set: the position is the list order, and a
            // series listed twice would collide on the primary key.
            val ordered = collection.seriesIds.distinct()
            collectionSeriesTable.batchInsert(ordered.withIndex()) { (index, seriesId) ->
                this[collectionSeriesTable.collectionId] = collection.id.value
                this[collectionSeriesTable.seriesId] = seriesId.value
                this[collectionSeriesTable.number] = index
            }
        }
    }

    override suspend fun delete(collectionId: KomgaCollectionId) {
        transaction {
            collectionSeriesTable.deleteWhere { collectionSeriesTable.collectionId.eq(collectionId.value) }
            collectionTable.deleteWhere { collectionTable.id.eq(collectionId.value) }
        }
    }

    private data class Member(
        val seriesId: String,
        val libraryId: String,
    )

    /** Live members only, in the reader's order, per collection id. */
    private fun members(collectionIds: List<String>): Map<String, List<Member>> {
        if (collectionIds.isEmpty()) return emptyMap()
        return collectionSeriesTable
            .join(
                otherTable = seriesTable,
                joinType = JoinType.INNER,
                onColumn = collectionSeriesTable.seriesId,
                otherColumn = seriesTable.id,
            )
            .select(collectionSeriesTable.collectionId, collectionSeriesTable.seriesId, seriesTable.libraryId)
            .where { collectionSeriesTable.collectionId.inList(collectionIds) }
            .orderBy(collectionSeriesTable.number to SortOrder.ASC)
            .groupBy(
                { it[collectionSeriesTable.collectionId] },
                { Member(it[collectionSeriesTable.seriesId], it[seriesTable.libraryId]) },
            )
    }

    private fun ResultRow.toModel(members: Map<String, List<Member>>): KomgaCollection {
        val id = this[collectionTable.id]
        return KomgaCollection(
            id = KomgaCollectionId(id),
            name = this[collectionTable.name],
            ordered = this[collectionTable.ordered],
            seriesIds = members[id].orEmpty().map { KomgaSeriesId(it.seriesId) },
            createdDate = Instant.fromEpochSeconds(this[collectionTable.createdDate]),
            lastModifiedDate = Instant.fromEpochSeconds(this[collectionTable.lastModifiedDate]),
            // Komga sets this when the server hides series the user may not
            // read. There is one reader here and nothing is hidden from them.
            filtered = false,
        )
    }
}
