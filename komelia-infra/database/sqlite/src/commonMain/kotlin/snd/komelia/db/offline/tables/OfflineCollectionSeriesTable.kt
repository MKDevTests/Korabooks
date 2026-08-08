package snd.komelia.db.offline.tables

import org.jetbrains.exposed.v1.core.Table

/**
 * Membership, one row per (collection, series), ordered by [number].
 *
 * No primary key, matching the V1 schema — the writer replaces a collection's
 * rows wholesale and de-duplicates the ids first, so nothing needs the database
 * to refuse a repeat. See [OfflineCollectionTable] for why V1 is enough.
 */
object OfflineCollectionSeriesTable : Table("COLLECTION_SERIES") {
    val collectionId = text("collection_id")
    val seriesId = text("series_id")
    val number = integer("number")
}
