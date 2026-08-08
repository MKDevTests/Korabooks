package snd.komelia.db.offline.tables

import org.jetbrains.exposed.v1.core.Table

/**
 * COLLECTION and COLLECTION_SERIES have existed since `V1__offline_mode.sql`:
 * the whole Komga schema was ported over, collections included, and then never
 * used — [snd.komelia.offline.api.OfflineCollectionsApi] was a stub. No
 * migration was needed to start using them, which is the best kind of change.
 *
 * [seriesCount] is denormalised and NOT NULL with no default, so it has to be
 * written. Nothing reads it: the count the reader sees comes from joining
 * COLLECTION_SERIES on SERIES, so a series dropped by a resync stops counting
 * without anyone having to remember to update this column.
 */
object OfflineCollectionTable : Table("COLLECTION") {
    val id = text("id")
    val name = text("name")
    val ordered = bool("ordered")
    val seriesCount = integer("series_count")
    val createdDate = long("created_date")
    val lastModifiedDate = long("last_modified_date")

    override val primaryKey = PrimaryKey(id)
}
