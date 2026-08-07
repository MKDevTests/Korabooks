package snd.komelia.db.offline.tables

import org.jetbrains.exposed.v1.core.Table

object OfflineRetainedGenreTable : Table("RETAINED_GENRE") {
    val genre = text("genre")

    override val primaryKey = PrimaryKey(genre)
}
