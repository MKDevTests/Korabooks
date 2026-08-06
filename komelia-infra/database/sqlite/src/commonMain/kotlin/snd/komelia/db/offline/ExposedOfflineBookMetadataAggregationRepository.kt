package snd.komelia.db.offline

import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import snd.komelia.db.ExposedRepository
import snd.komelia.db.offline.tables.OfflineBookMetadataAggregationAuthorTable
import snd.komelia.db.offline.tables.OfflineBookMetadataAggregationTable
import snd.komelia.db.offline.tables.OfflineBookMetadataAggregationTagTable
import snd.komelia.offline.book.repository.OfflineBookMetadataAggregationRepository
import snd.komelia.offline.series.model.OfflineBookMetadataAggregation
import snd.komga.client.common.KomgaAuthor
import snd.komga.client.series.KomgaSeriesId
import kotlin.time.Instant

class ExposedOfflineBookMetadataAggregationRepository( database: Database) :
    OfflineBookMetadataAggregationRepository, ExposedRepository(database) {
    val aggregationTable = OfflineBookMetadataAggregationTable
    val aggregationTagTable = OfflineBookMetadataAggregationTagTable
    val aggregationAuthorTable = OfflineBookMetadataAggregationAuthorTable

    override suspend fun save(metadata: OfflineBookMetadataAggregation) {
        transaction {
            aggregationTable.upsert {
                it[seriesId] = metadata.seriesId.value
                it[releaseDate] = metadata.releaseDate?.toString()
                it[summary] = metadata.summary
                it[summaryNumber] = metadata.summaryNumber
                it[createdDate] = metadata.createdDate.epochSeconds
                it[lastModifiedDate] = metadata.lastModifiedDate.epochSeconds
            }

            aggregationTagTable.deleteWhere { aggregationTagTable.seriesId.eq(metadata.seriesId.value) }
            aggregationAuthorTable.deleteWhere { aggregationAuthorTable.seriesId.eq(metadata.seriesId.value) }

            aggregationTagTable.batchInsert(metadata.tags) {
                this[aggregationTagTable.seriesId] = metadata.seriesId.value
                this[aggregationTagTable.tag] = it
            }

            aggregationAuthorTable.batchInsert(metadata.authors) {
                this[aggregationAuthorTable.seriesId] = metadata.seriesId.value
                this[aggregationAuthorTable.name] = it.name
                this[aggregationAuthorTable.role] = it.role
            }

        }
    }


    /**
     * The only read here, and it used to run outside a transaction.
     *
     * Nothing asked for an aggregation while the mirror was empty, so the
     * missing transaction stayed invisible until a library had series in it —
     * at which point every screen that shows one failed with "no transaction in
     * context". The where clause named the tag table while selecting from the
     * aggregation one, which is the same kind of accident.
     */
    override suspend fun find(seriesId: KomgaSeriesId): OfflineBookMetadataAggregation? {
        return transaction {
            val rowResult = aggregationTable
                .selectAll()
                .where { aggregationTable.seriesId.eq(seriesId.value) }
                .firstOrNull() ?: return@transaction null

            rowResult.toBookMetaModel(
                authors = findAuthors(seriesId.value),
                tags = findTags(seriesId.value),
            )
        }
    }

    private fun findTags(seriesId: String): List<String> {
        return aggregationTagTable
            .select(aggregationTagTable.tag)
            .where { aggregationTagTable.seriesId.eq(seriesId) }
            .map { it[aggregationTagTable.tag] }
    }

    private fun findAuthors(seriesId: String): List<KomgaAuthor> {
        return aggregationAuthorTable
            .select(aggregationAuthorTable.name, aggregationAuthorTable.role)
            .where { aggregationAuthorTable.seriesId.eq(seriesId) }
            .map {
                KomgaAuthor(
                    name = it[aggregationAuthorTable.name],
                    role = it[aggregationAuthorTable.role],
                )
            }
    }

    override suspend fun get(seriesId: KomgaSeriesId): OfflineBookMetadataAggregation {
        return find(seriesId)
            ?: throw IllegalStateException("book metadata aggregation with seriesId $seriesId is not found")
    }

    override suspend fun delete(seriesId: KomgaSeriesId) {
        transaction {
            aggregationAuthorTable.deleteWhere { aggregationAuthorTable.seriesId.eq(seriesId.value) }
            aggregationTagTable.deleteWhere { aggregationTagTable.seriesId.eq(seriesId.value) }
            aggregationTable.deleteWhere { aggregationTable.seriesId.eq(seriesId.value) }
        }
    }

    override suspend fun delete(seriesIds: List<KomgaSeriesId>) {
        transaction {
            val ids = seriesIds.map { it.value }
            aggregationAuthorTable.deleteWhere { aggregationAuthorTable.seriesId.inList(ids) }
            aggregationTagTable.deleteWhere { aggregationTagTable.seriesId.inList(ids) }
            aggregationTable.deleteWhere { aggregationTable.seriesId.inList(ids) }
        }
    }

    private fun ResultRow.toBookMetaModel(
        authors: List<KomgaAuthor>,
        tags: List<String>
    ) = OfflineBookMetadataAggregation(
        seriesId = KomgaSeriesId(this[aggregationTable.seriesId]),
        releaseDate = this[aggregationTable.releaseDate]?.let { LocalDate.parse(it) },
        summary = this[aggregationTable.summary],
        summaryNumber = this[aggregationTable.summaryNumber],
        authors = authors,
        tags = tags.toSet(),
        createdDate = Instant.fromEpochSeconds(this[aggregationTable.createdDate]),
        lastModifiedDate = Instant.fromEpochSeconds(this[aggregationTable.lastModifiedDate])
    )
}