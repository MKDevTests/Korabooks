package snd.komelia.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import snd.komelia.ui.LocalStrings
import snd.komelia.ui.LocalWindowWidth
import snd.komelia.ui.common.components.FilterDialogMultiChoiceWithSearch
import snd.komelia.ui.common.components.FilterDropdownChoice
import snd.komelia.ui.common.components.FilterDropdownMultiChoice
import snd.komelia.ui.common.components.LabeledEntry
import snd.komelia.ui.common.components.LabeledEntry.Companion.stringEntry
import snd.komelia.ui.common.components.NoPaddingTextField
import snd.komelia.ui.common.components.TagFiltersDropdownMenu
import snd.komelia.ui.platform.WindowSizeClass.COMPACT
import snd.komelia.ui.platform.WindowSizeClass.EXPANDED
import snd.komelia.ui.platform.WindowSizeClass.FULL
import snd.komelia.ui.platform.WindowSizeClass.MEDIUM
import snd.komga.client.book.KomgaReadStatus

/**
 * The Books tab's filter panel, the counterpart of SeriesFilterContent.
 *
 * Same components, same layout, five controls instead of ten: the five the
 * mirrored catalogue can answer. See [LibraryBookFilter] for why the others are
 * absent.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryBookFilterContent(
    filterState: LibraryBookFilterState,
) {
    val strings = LocalStrings.current.seriesFilter
    val widthClass = LocalWindowWidth.current

    val spacing = remember(widthClass) {
        when (widthClass) {
            COMPACT, MEDIUM, EXPANDED -> 10.dp
            FULL -> 20.dp
        }
    }
    val width = remember(widthClass) {
        when (widthClass) {
            COMPACT -> 400.dp
            MEDIUM -> 220.dp
            else -> 250.dp
        }
    }
    val currentFilter = filterState.state.collectAsState().value

    Column(
        modifier = Modifier.widthIn(max = 1400.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing)
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.Center,
        ) {
            // Local state debounced by 200 ms, so holding the panel open while
            // typing does not fire a query per character. The tab debounces a
            // changed search term again on its side; both are cheap.
            var searchTerm by remember { mutableStateOf(currentFilter.searchTerm) }
            LaunchedEffect(searchTerm) {
                delay(200)
                filterState.onSearchTermChange(searchTerm)
            }
            NoPaddingTextField(
                text = searchTerm,
                placeholder = strings.search,
                onTextChange = { searchTerm = it },
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                trailingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().height(45.dp),
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            FilterDropdownChoice(
                selectedOption = LabeledEntry(currentFilter.sortOrder, currentFilter.sortOrder.label),
                options = LibraryBooksTabState.Sort.entries.map { LabeledEntry(it, it.label) },
                onOptionChange = { filterState.onSortOrderChange(it.value) },
                label = strings.sort,
                modifier = Modifier.width(width)
            )

            // Books carry tags, series carry genres — a mirrored catalogue puts
            // everything on the book, so the genre half of this control stays
            // empty and the menu renders as a plain tag picker.
            TagFiltersDropdownMenu(
                allTags = filterState.tagOptions,
                includeTags = currentFilter.includeTags,
                excludeTags = currentFilter.excludeTags,
                onTagSelect = filterState::onTagSelect,

                onReset = filterState::resetTagFilters,
                inclusionMode = currentFilter.inclusionMode,
                onInclusionModeChange = filterState::onInclusionModeChange,
                exclusionMode = currentFilter.exclusionMode,
                onExclusionModeChange = filterState::onExclusionModeChange,

                contentPadding = PaddingValues(5.dp),
                label = strings.filterTagsLabel,
                inputFieldColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .width(width)
                    .clip(RoundedCornerShape(5.dp)),
                inputFieldModifier = Modifier.fillMaxWidth()
            )

            FilterDropdownMultiChoice(
                selectedOptions = currentFilter.readStatus
                    .map { LabeledEntry(it, strings.forSeriesReadStatus(it)) },
                options = KomgaReadStatus.entries.map { LabeledEntry(it, strings.forSeriesReadStatus(it)) },
                onOptionSelect = { changed -> filterState.onReadStatusSelect(changed.value) },
                label = strings.readStatus,
                modifier = Modifier.width(width),
            )

            val authorsSelectedOptions = remember(currentFilter.authors) {
                currentFilter.authors.distinctBy { it.name }.map { LabeledEntry(it, it.name) }
            }
            val authorOptions = remember(filterState.authorsOptions) {
                filterState.authorsOptions.distinctBy { it.name }.map { LabeledEntry(it, it.name) }
            }
            FilterDialogMultiChoiceWithSearch(
                selectedOptions = authorsSelectedOptions,
                options = authorOptions,
                onOptionSelect = { author -> filterState.onAuthorSelect(author.value) },
                onSearch = filterState::onAuthorsSearch,
                label = strings.authors,
                modifier = Modifier.width(width),
                onClearAll = { filterState.resetAuthors() }
            )

            FilterDropdownMultiChoice(
                selectedOptions = currentFilter.releaseDates.map { stringEntry(it) },
                options = filterState.releaseDateOptions.map { stringEntry(it) },
                onOptionSelect = { changed -> filterState.onReleaseDateSelect(changed.value) },
                label = strings.releaseDate,
                modifier = Modifier.width(width),
            )
        }
    }
}
