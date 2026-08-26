package snd.komelia.ui.library

/**
 * One criterion currently narrowing a list, in the form the chips row above the
 * grid shows it.
 *
 * This exists because a filtered library was indistinguishable from an empty
 * one. Tapping an author on a series opens the library with that author
 * applied, and the screen it lands on is titled with the library's name, says
 * "1 SERIES", and names the author nowhere — so a narrow filter reads as a
 * broken library. The only signal was a tinted filter icon, which said *that*
 * something was filtered and never *what*.
 *
 * Shared by both grids: the series tab builds these from a SeriesFilter, the
 * books tab from a [LibraryBookFilter], and one row composable renders either.
 *
 * [value] is already display-ready text; the label in front of it is chosen by
 * the composable, which is the layer that owns translations.
 *
 * Sort order is deliberately absent: it never hides anything, and both tabs
 * already say what it is — a dropdown in the series panel, a row of chips in
 * the books header.
 */
data class ActiveFilter(val kind: Kind, val value: String) {
    enum class Kind {
        SEARCH, LETTER, AUTHOR,
        GENRE, GENRE_EXCLUDED, TAG, TAG_EXCLUDED,
        PUBLISHER, LANGUAGE, AGE_RATING, RELEASE_DATE,
        READ_STATUS, PUBLICATION_STATUS, COMPLETION, FORMAT,
    }
}
