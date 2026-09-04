package snd.komelia.ui.common

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/**
 * Movable type to five years out. Wide on purpose: this is here to catch
 * sentinels and typos, not to referee what counts as an old book.
 */
private val plausibleYears: IntRange by lazy {
    1450..(Clock.System.todayIn(TimeZone.currentSystemDefault()).year + 5)
}

/**
 * The year to put on screen, or null when the date is not really one.
 *
 * Calibre writes `0101-01-01` when it has no publication date — a sentinel that
 * arrived on the shelf as "(101)" on 1 898 of this library's books. The mirror
 * now drops those on the way in, but rows written before that still hold them,
 * and one book carries a genuine 9442 typed into Calibre by hand. Both are
 * cheaper to refuse here than to explain.
 */
fun LocalDate.displayYear(): Int? = year.takeIf { it in plausibleYears }
