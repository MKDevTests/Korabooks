package snd.komelia.db.offline

import org.jetbrains.exposed.v1.core.JoinType
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
import snd.komelia.offline.api.repository.RetainedGenreCounts
import snd.komelia.offline.api.repository.RetainedGenreRepository
import snd.komelia.offline.api.repository.genreRoot

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
     *
     * Reads the distinct (genre, series) pairs and aggregates in Kotlin rather
     * than issuing two GROUP BYs. The per-root figure has to count a series
     * once even when it carries `Fantasy` *and* `Fantasy.Historique`, and
     * splitting on the dot in SQL would mean a hand-written `substr`/`instr`
     * expression for something the language already says plainly. The table is
     * a few thousand pairs on a real library — eight thousand on the reference
     * one — read once when the screen opens.
     */
    override suspend fun findAllCounts(): RetainedGenreCounts {
        val pairs = transaction {
            seriesMetaGenresTable
                .join(
                    otherTable = seriesTable,
                    joinType = JoinType.INNER,
                    onColumn = seriesMetaGenresTable.seriesId,
                    otherColumn = seriesTable.id,
                )
                .select(seriesMetaGenresTable.genre, seriesMetaGenresTable.seriesId)
                .withDistinct()
                .map { it[seriesMetaGenresTable.genre] to it[seriesMetaGenresTable.seriesId] }
        }

        return RetainedGenreCounts(
            genres = pairs.countBy { it.first },
            roots = pairs.countBy { genreRoot(it.first) },
        )
    }

    /**
     * Groups by [key] and counts the distinct series behind each group.
     *
     * Most-used first, because that is the order the choice is made in;
     * alphabetical within a count so the list stops shuffling between visits.
     */
    private fun List<Pair<String, String>>.countBy(key: (Pair<String, String>) -> String) =
        groupBy(key, { it.second })
            .map { (name, seriesIds) -> RetainedGenreCount(name, seriesIds.toSet().size) }
            .sortedWith(compareByDescending<RetainedGenreCount> { it.seriesCount }.thenBy { it.genre })

    override suspend fun replaceAll(genres: Collection<String>) {
        val distinct = genres.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        transaction {
            retainedTable.deleteAll()
            retainedTable.batchInsert(distinct) { genre -> this[retainedTable.genre] = genre }
        }
    }
}
