package snd.komelia.db.offline

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.countDistinct
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import snd.komelia.db.ExposedRepository
import snd.komelia.db.offline.tables.OfflineRetainedGenreTable
import snd.komelia.db.offline.tables.OfflineSeriesMetadataGenreTable
import snd.komelia.db.offline.tables.OfflineSeriesTable
import snd.komelia.offline.api.repository.RetainedGenreCount
import snd.komelia.offline.api.repository.RetainedGenreRepository

class ExposedRetainedGenreRepository(database: Database) :
    RetainedGenreRepository, ExposedRepository(database) {
    private val retainedTable = OfflineRetainedGenreTable
    private val seriesMetaGenresTable = OfflineSeriesMetadataGenreTable
    private val seriesTable = OfflineSeriesTable

    override suspend fun findAll(): Set<String> {
        return transaction {
            retainedTable.selectAll()
                .map { it[retainedTable.genre] }
                .toSet()
        }
    }

    /**
     * Counted on series that still exist, and counted distinct.
     *
     * The genre table holds one row per genre *per series*, plus rows whose
     * series was deleted by a path that missed them. Counting rows would rank
     * the list by how much stale data a genre accumulated, which is the one
     * ordering nobody wants.
     */
    override suspend fun findAllCounts(): List<RetainedGenreCount> {
        return transaction {
            val genre = seriesMetaGenresTable.genre
            val series = seriesMetaGenresTable.seriesId.countDistinct()

            seriesMetaGenresTable
                .join(
                    otherTable = seriesTable,
                    joinType = JoinType.INNER,
                    onColumn = seriesMetaGenresTable.seriesId,
                    otherColumn = seriesTable.id,
                )
                .select(genre, series)
                .groupBy(genre)
                // Most-used first, because that is the order the choice is made
                // in; alphabetical within a count so the list stops shuffling
                // between two visits.
                .orderBy(series to SortOrder.DESC, genre to SortOrder.ASC)
                .map { RetainedGenreCount(genre = it[genre], seriesCount = it[series].toInt()) }
        }
    }

    override suspend fun replaceAll(genres: Collection<String>) {
        val distinct = genres.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        transaction {
            retainedTable.deleteAll()
            retainedTable.batchInsert(distinct) { genre -> this[retainedTable.genre] = genre }
        }
    }
}
