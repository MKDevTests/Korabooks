package snd.komelia.db.offline

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.jdbc.select
import snd.komelia.db.offline.tables.OfflineBookTable
import snd.komelia.db.offline.tables.OfflineSettingsTable

/**
 * The "show only what is on disk" switch, and the conditions that honour it.
 *
 * Read straight from the settings table rather than passed in from the module
 * that builds these repositories. Those repositories take nothing but a
 * `Database`, and are constructed in the same expression as the settings
 * repository itself, so there is no flow to hand them at that point — and a
 * cached copy would be one more thing to keep in step with the switch.
 *
 * The cost is one primary-key read of a single-row table per list query, which
 * is not measurable next to the query it guards.
 */

/** True when the switch is on. Call inside a transaction. */
internal fun downloadedOnlyEnabled(): Boolean =
    OfflineSettingsTable
        .select(OfflineSettingsTable.downloadedOnly)
        .firstOrNull()
        ?.get(OfflineSettingsTable.downloadedOnly)
        ?: false

/**
 * Books whose file is on disk.
 *
 * The local modification date is the only honest signal: the mirror writes zero
 * there and an empty path beside it, so a row exists for every book the server
 * has, downloaded or not.
 */
internal fun bookIsDownloaded(): Op<Boolean> =
    OfflineBookTable.localFileModifiedDate.greater(0L)

/**
 * Series holding at least one downloaded book.
 *
 * [seriesId] is the series-side column of the query being narrowed, so this
 * works whether the caller is selecting from SERIES or from something joined to
 * it.
 */
internal fun seriesHasDownloadedBook(seriesId: Column<String>): Op<Boolean> =
    exists(
        OfflineBookTable
            .select(OfflineBookTable.id)
            .where { OfflineBookTable.seriesId.eq(seriesId) and bookIsDownloaded() }
    )
