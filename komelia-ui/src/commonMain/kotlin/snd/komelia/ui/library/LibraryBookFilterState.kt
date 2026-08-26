package snd.komelia.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.until
import snd.komelia.AppNotifications
import snd.komelia.komga.api.KomgaReferentialApi
import snd.komelia.ui.library.LibraryBookFilter.Companion.DEFAULT
import snd.komelia.ui.library.LibraryBooksTabState.Sort
import snd.komelia.ui.series.SeriesFilterState.TagExclusionMode
import snd.komelia.ui.series.SeriesFilterState.TagInclusionMode
import snd.komga.client.book.KomgaReadStatus
import snd.komga.client.common.KomgaAuthor
import snd.komga.client.library.KomgaLibraryId
import snd.komga.client.search.BookConditionBuilder
import snd.komga.client.search.KomgaSearchCondition

/**
 * Everything the Books tab asks the server for, as one value.
 *
 * The series tab has had a filter panel since Komelia; the books tab had a
 * search box, a letter bar and three sort chips. This is the same panel for
 * books, restricted to the fields a Calibre-Web mirror actually fills: on the
 * reference catalogue every one of the 10 713 books has an author, 10 428
 * carry tags and 9 168 a release date, while publisher, language and age
 * rating have no column at all on BOOK_METADATA. A control that can only ever
 * answer "nothing" is worse than a missing one, so those three are not here —
 * and neither is one-shot, which BookConditionBuilder cannot express.
 *
 * One value rather than a field per control, because every one of them is an
 * input to the same query: the tab reloads on any change to this object, and
 * on nothing else.
 */
data class LibraryBookFilter(
    val sortOrder: Sort = Sort.TITLE_ASC,
    val searchTerm: String = "",
    /** Single-letter prefix (A-Z, "#" for digit-starting titles, null = all). */
    val letterFilter: String? = null,
    /**
     * Set by the Authors tab: the books of one person.
     *
     * Kept apart from [authors] — that one is the panel's multi-select, and the
     * two would AND into an empty grid. Entering an author scope clears it.
     */
    val authorScope: String? = null,

    val readStatus: List<KomgaReadStatus> = emptyList(),
    val includeTags: List<String> = emptyList(),
    val excludeTags: List<String> = emptyList(),
    val inclusionMode: TagInclusionMode = TagInclusionMode.INCLUDE_IF_ALL_MATCH,
    val exclusionMode: TagExclusionMode = TagExclusionMode.EXCLUDE_IF_ANY_MATCH,
    val authors: List<KomgaAuthor> = emptyList(),
    val releaseDates: List<String> = emptyList(),
) {

    companion object {
        val DEFAULT = LibraryBookFilter()
    }

    /** True when the grid is showing a subset. A sort order alone is not one. */
    val narrowsResults: Boolean
        get() = this != DEFAULT.copy(sortOrder = sortOrder)

    fun addConditionTo(builder: BookConditionBuilder) {
        // Role left open: the mirror credits everyone as a writer, and pinning
        // the role would only make the filter brittle if that ever stops being
        // true.
        authorScope?.let { name ->
            builder.author { isEqualTo(KomgaSearchCondition.AuthorMatch(name, null)) }
        }

        // Titles are matched on their first letter, digits sharing the "#"
        // bucket the way every index in this app does.
        when (val letter = letterFilter) {
            null -> {}
            "#" -> builder.anyOf { ('0'..'9').forEach { d -> title { beginsWith(d.toString()) } } }
            else -> builder.title { beginsWith(letter) }
        }

        if (readStatus.isNotEmpty()) {
            builder.anyOf {
                readStatus.forEach { readStatus { isEqualTo(it) } }
            }
        }

        if (includeTags.isNotEmpty()) {
            when (inclusionMode) {
                TagInclusionMode.INCLUDE_IF_ALL_MATCH -> builder.allOf {
                    includeTags.forEach { tag { isEqualTo(it) } }
                }

                TagInclusionMode.INCLUDE_IF_ANY_MATCH -> builder.anyOf {
                    includeTags.forEach { tag { isEqualTo(it) } }
                }
            }
        }
        if (excludeTags.isNotEmpty()) {
            when (exclusionMode) {
                TagExclusionMode.EXCLUDE_IF_ANY_MATCH -> builder.allOf {
                    excludeTags.forEach { tag { isNotEqualTo(it) } }
                }

                TagExclusionMode.EXCLUDE_IF_ALL_MATCH -> builder.anyOf {
                    excludeTags.forEach { tag { isNotEqualTo(it) } }
                }
            }
        }

        // A year is a range, and the condition API only knows before/after: each
        // selected year becomes "after Dec 31 of the year before AND before Jan 1
        // of the year after". Same translation the series filter does.
        if (releaseDates.isNotEmpty()) {
            builder.anyOf {
                releaseDates.forEach {
                    allOf {
                        releaseDate {
                            isAfter(dateAtLastDayInYear(it.toInt() - 1).atStartOfDayIn(TimeZone.UTC))
                        }
                        releaseDate {
                            isBefore(LocalDate(it.toInt() + 1, 1, 1).atStartOfDayIn(TimeZone.UTC))
                        }
                    }
                }
            }
        }

        authors.forEach {
            builder.author { isEqualTo(KomgaSearchCondition.AuthorMatch(it.name, null)) }
        }
    }

    private fun dateAtLastDayInYear(year: Int): LocalDate {
        val start = LocalDate(year, 12, 1)
        val end = start.plus(1, DateTimeUnit.MONTH)
        val day = start.until(end, DateTimeUnit.DAY)
        return LocalDate(year, 12, day.toInt())
    }
}

/**
 * The [LibraryBookFilter] the user is editing, plus the option lists behind
 * each dropdown.
 */
class LibraryBookFilterState(
    private val libraryId: KomgaLibraryId?,
    private val referentialApi: KomgaReferentialApi,
    private val appNotifications: AppNotifications,
) {

    private val mutableFilterState = MutableStateFlow(LibraryBookFilter())
    val state = mutableFilterState.asStateFlow()

    var isChanged by mutableStateOf(false)
        private set
    var tagOptions by mutableStateOf<List<String>>(emptyList())
        private set
    var authorsOptions by mutableStateOf<List<KomgaAuthor>>(emptyList())
        private set
    var releaseDateOptions by mutableStateOf<List<String>>(emptyList())
        private set

    /**
     * Fills the dropdowns. Runs off the critical path — the grid must never wait
     * on data only the panel needs.
     */
    suspend fun initialize() {
        appNotifications.runCatchingToNotifications {
            val libraryIds = libraryId?.let { listOf(it) }.orEmpty()
            coroutineScope {
                val tags = async { referentialApi.getBookTags(libraryIds = libraryIds) }
                // The years come from the per-series aggregation rather than from
                // the books themselves, because that is the only release-date
                // lookup the referential API exposes. On the reference catalogue
                // it returns 119 of the 123 years present, and the four it misses
                // hold one book each — not worth a new endpoint.
                val releaseDates = async { referentialApi.getSeriesReleaseDates(libraryIds = libraryIds) }
                tagOptions = tags.await()
                releaseDateOptions = releaseDates.await()
            }
        }
    }

    /** Reinstates a persisted filter. The author scope is never restored. */
    fun restore(filter: LibraryBookFilter) {
        mutableFilterState.value = filter.copy(authorScope = null)
        checkIfAllDefault()
    }

    fun onSortOrderChange(sortOrder: Sort) {
        mutableFilterState.update { it.copy(sortOrder = sortOrder) }
        checkIfAllDefault()
    }

    fun onSearchTermChange(searchTerm: String) {
        mutableFilterState.update { it.copy(searchTerm = searchTerm) }
        checkIfAllDefault()
    }

    fun onLetterFilterChange(letter: String?) {
        mutableFilterState.update { it.copy(letterFilter = letter) }
        checkIfAllDefault()
    }

    /**
     * Shows one author's books, and drops the filters that would hide them.
     *
     * Arriving here from the Authors tab means asking a question about a person,
     * not about the letter or the search term left over from the last time the
     * grid was used.
     */
    fun onAuthorScopeChange(name: String?) {
        mutableFilterState.update {
            if (name == null) it.copy(authorScope = null)
            else it.copy(
                authorScope = name,
                letterFilter = null,
                searchTerm = "",
                authors = emptyList(),
            )
        }
        checkIfAllDefault()
    }

    fun onReadStatusSelect(readStatus: KomgaReadStatus) {
        mutableFilterState.update { current ->
            current.copy(
                readStatus = if (current.readStatus.contains(readStatus)) current.readStatus.minus(readStatus)
                else current.readStatus.plus(readStatus)
            )
        }
        checkIfAllDefault()
    }

    /** One tap includes the tag, the next excludes it, the third clears it. */
    fun onTagSelect(tag: String) {
        mutableFilterState.update { current ->
            if (current.includeTags.contains(tag)) {
                current.copy(
                    includeTags = current.includeTags.minus(tag),
                    excludeTags = current.excludeTags.plus(tag)
                )
            } else if (current.excludeTags.contains(tag)) {
                current.copy(excludeTags = current.excludeTags.minus(tag))
            } else current.copy(includeTags = current.includeTags.plus(tag))
        }
        checkIfAllDefault()
    }

    fun onInclusionModeChange(mode: TagInclusionMode) {
        mutableFilterState.update { it.copy(inclusionMode = mode) }
        checkIfAllDefault()
    }

    fun onExclusionModeChange(mode: TagExclusionMode) {
        mutableFilterState.update { it.copy(exclusionMode = mode) }
        checkIfAllDefault()
    }

    /**
     * Search-driven rather than a full list: the mirror holds 3 770 distinct
     * names, and a dropdown of 3 770 entries is not a control.
     */
    suspend fun onAuthorsSearch(search: String) {
        authorsOptions =
            if (search.isBlank()) emptyList()
            else referentialApi.getAuthors(
                search = search,
                libraryIds = libraryId?.let { listOf(it) }.orEmpty(),
            ).content
    }

    fun onAuthorSelect(author: KomgaAuthor) {
        val authorsByName = authorsOptions.filter { it.name == author.name }
        mutableFilterState.update { current ->
            current.copy(
                authors = if (current.authors.any { it.name == author.name })
                    current.authors.filter { it.name != author.name }
                else current.authors.plus(authorsByName)
            )
        }
        checkIfAllDefault()
    }

    fun onReleaseDateSelect(releaseDate: String) {
        mutableFilterState.update { current ->
            current.copy(
                releaseDates = if (current.releaseDates.contains(releaseDate))
                    current.releaseDates.minus(releaseDate)
                else current.releaseDates.plus(releaseDate)
            )
        }
        checkIfAllDefault()
    }

    fun resetTagFilters() {
        mutableFilterState.update {
            it.copy(
                includeTags = DEFAULT.includeTags,
                excludeTags = DEFAULT.excludeTags,
                inclusionMode = DEFAULT.inclusionMode,
                exclusionMode = DEFAULT.exclusionMode,
            )
        }
        checkIfAllDefault()
    }

    fun resetAuthors() {
        mutableFilterState.update { it.copy(authors = emptyList()) }
        checkIfAllDefault()
    }

    fun reset() {
        mutableFilterState.value = DEFAULT
        checkIfAllDefault()
    }

    private fun checkIfAllDefault() {
        isChanged = state.value != DEFAULT
    }
}
