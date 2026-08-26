package snd.komelia.ui.library

import kotlinx.serialization.Serializable
import snd.komelia.ui.series.SeriesFilterState
import snd.komga.client.book.KomgaReadStatus
import snd.komga.client.common.KomgaAuthor

/**
 * Serializable mirror of [LibraryBookFilter], persisted as a JSON blob so the
 * Books tab comes back the way it was left.
 *
 * Mirrors the source data class field by field, the same way [SeriesFilterDto]
 * does, so kotlinx.serialization stays off [LibraryBookFilter] itself.
 *
 * One field is deliberately absent: `authorScope`. It is a navigation intent
 * from the Authors tab, not a filter the reader set here — restoring it a week
 * later would open the Books tab on one stranger's shelf.
 */
@Serializable
data class LibraryBookFilterDto(
    val sortOrder: LibraryBooksTabState.Sort = LibraryBooksTabState.Sort.TITLE_ASC,
    val searchTerm: String = "",
    val letterFilter: String? = null,
    val readStatus: List<KomgaReadStatus> = emptyList(),
    val includeTags: List<String> = emptyList(),
    val excludeTags: List<String> = emptyList(),
    val inclusionMode: SeriesFilterState.TagInclusionMode =
        SeriesFilterState.TagInclusionMode.INCLUDE_IF_ALL_MATCH,
    val exclusionMode: SeriesFilterState.TagExclusionMode =
        SeriesFilterState.TagExclusionMode.EXCLUDE_IF_ANY_MATCH,
    val authors: List<KomgaAuthor> = emptyList(),
    val releaseDates: List<String> = emptyList(),
) {
    fun toDomain(): LibraryBookFilter = LibraryBookFilter(
        sortOrder = sortOrder,
        searchTerm = searchTerm,
        letterFilter = letterFilter,
        readStatus = readStatus,
        includeTags = includeTags,
        excludeTags = excludeTags,
        inclusionMode = inclusionMode,
        exclusionMode = exclusionMode,
        authors = authors,
        releaseDates = releaseDates,
    )

    companion object {
        fun from(filter: LibraryBookFilter): LibraryBookFilterDto = LibraryBookFilterDto(
            sortOrder = filter.sortOrder,
            searchTerm = filter.searchTerm,
            letterFilter = filter.letterFilter,
            readStatus = filter.readStatus,
            includeTags = filter.includeTags,
            excludeTags = filter.excludeTags,
            inclusionMode = filter.inclusionMode,
            exclusionMode = filter.exclusionMode,
            authors = filter.authors,
            releaseDates = filter.releaseDates,
        )
    }
}
