package snd.komelia.db.offline

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inSubQuery
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
 *
 * A list subquery, not a correlated `EXISTS`, and the difference is the whole
 * cost of the switch. Correlated, SQLite had to walk the series in sort order
 * and probe BOOK once per row until twenty matched — with one downloaded series
 * out of 6 825 on the reference mirror, that is a full scan of the catalogue for
 * every page: 42 ms, and an index only brought it to 26 ms because the scan
 * itself never went away. Uncorrelated, the subquery is evaluated once against
 * `idx_book_downloaded` (V6), which holds only the downloaded books, and the
 * result drops under the timer's resolution.
 *
 * Same rows either way: "some book of this series is downloaded" and "this
 * series is among those owning a downloaded book" are the same set.
 */
internal fun seriesHasDownloadedBook(seriesId: Column<String>): Op<Boolean> =
    seriesId.inSubQuery(
        OfflineBookTable
            .select(OfflineBookTable.seriesId)
            .where { bookIsDownloaded() }
    )
