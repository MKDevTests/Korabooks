package snd.komelia.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import snd.komelia.AppNotifications
import snd.komelia.komga.api.KomgaReferentialApi
import snd.komelia.ui.LoadState
import snd.komga.client.common.KomgaPageRequest
import snd.komga.client.library.KomgaLibraryId

/**
 * Everyone who wrote something in the library, by name.
 *
 * A catalogue of ten thousand books is a catalogue of a few thousand authors,
 * and for a reader who thinks in authors rather than in titles that is the
 * shortest way in. Roles are collapsed away deliberately: a mirrored Calibre
 * library only ever records writers, and a list that said "writer" beside every
 * single name would be noise.
 */
class LibraryAuthorsTabState(
    private val referentialApi: KomgaReferentialApi,
    private val notifications: AppNotifications,
    private val libraryId: KomgaLibraryId?,
    private val screenModelScope: CoroutineScope,
    val cardWidth: StateFlow<Dp>,
) {
    private val mutableState = MutableStateFlow<LoadState<Unit>>(LoadState.Uninitialized)
    val state = mutableState.asStateFlow()

    var authors by mutableStateOf<List<String>>(emptyList())
        private set
    var totalAuthorsCount by mutableStateOf(0)
        private set
    var currentPage by mutableStateOf(1)
        private set
    var totalPages by mutableStateOf(1)
        private set
    var searchTerm by mutableStateOf("")
        private set

    val pageLoadSize = MutableStateFlow(50)

    suspend fun initialize() {
        if (state.value != LoadState.Uninitialized) return
        load(1)
    }

    suspend fun reload() = load(currentPage)

    fun onPageChange(page: Int) {
        screenModelScope.launch { load(page) }
    }

    fun onPageSizeChange(size: Int) {
        pageLoadSize.value = size
        screenModelScope.launch { load(1) }
    }

    fun onSearchChange(term: String) {
        searchTerm = term
        screenModelScope.launch { load(1) }
    }

    fun clearFilters() {
        searchTerm = ""
        screenModelScope.launch { load(1) }
    }

    val hasActiveFilter: Boolean
        get() = searchTerm.isNotBlank()

    private suspend fun load(page: Int) {
        notifications.runCatchingToNotifications {
            if (authors.isEmpty()) mutableState.value = LoadState.Loading

            val response = referentialApi.getAuthors(
                search = searchTerm.takeIf { it.isNotBlank() },
                libraryIds = libraryId?.let { listOf(it) } ?: emptyList(),
                pageRequest = KomgaPageRequest(
                    pageIndex = page - 1,
                    size = pageLoadSize.value,
                    unpaged = false,
                ),
            )

            // The query is distinct over (name, role), so one person credited
            // twice comes back twice. Collapsing here keeps the page count
            // honest at the cost of a page that is occasionally one row short.
            authors = response.content.map { it.name }.distinct()
            totalAuthorsCount = response.totalElements
            totalPages = response.totalPages
            currentPage = response.number + 1
            mutableState.value = LoadState.Success(Unit)
        }.onFailure { mutableState.value = LoadState.Error(it) }
    }
}
