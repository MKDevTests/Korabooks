package snd.komelia.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import snd.komelia.komga.api.KomgaBookApi
import snd.komelia.komga.api.model.KomeliaBook
import snd.komelia.offline.tasks.OfflineTaskEmitter
import snd.komelia.settings.CommonSettingsRepository
import snd.komelia.AppNotifications
import snd.komelia.ui.LoadState
import snd.komelia.ui.common.menus.BookMenuActions
import snd.komga.client.common.KomgaPageRequest
import snd.komga.client.common.KomgaSort
import snd.komga.client.library.KomgaLibraryId
import snd.komga.client.search.KomgaSearchCondition
import snd.komga.client.search.allOfBooks
import snd.komga.client.sse.KomgaEvent
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger { }

/**
 * Every book of a library, series or not.
 *
 * A catalogue mirrored from Calibre-Web is mostly standalone books, and the
 * series view answers for them with thousands of one-book shelves — technically
 * right and useless to browse. This is the same library seen the other way
 * round, and switching between the two is one tap.
 */
class LibraryBooksTabState(
    private val bookApi: KomgaBookApi,
    private val notifications: AppNotifications,
    private val komgaEvents: SharedFlow<KomgaEvent>,
    private val settingsRepository: CommonSettingsRepository,
    private val libraryId: KomgaLibraryId?,
    private val taskEmitter: OfflineTaskEmitter,
    private val screenModelScope: CoroutineScope,
    val cardWidth: StateFlow<Dp>,
) {
    enum class Sort(val label: String, val sort: KomgaSort) {
        TITLE_ASC("Titre A→Z", KomgaSort.KomgaBooksSort.byTitle(KomgaSort.Direction.ASC)),
        TITLE_DESC("Titre Z→A", KomgaSort.KomgaBooksSort.byTitle(KomgaSort.Direction.DESC)),
        ADDED_DESC("Ajouts récents", KomgaSort.KomgaBooksSort.byCreatedDate(KomgaSort.Direction.DESC)),
    }

    private val mutableState = MutableStateFlow<LoadState<Unit>>(LoadState.Uninitialized)
    val state = mutableState.asStateFlow()

    var books by mutableStateOf<List<KomeliaBook>>(emptyList())
        private set
    var totalBooksCount by mutableStateOf(0)
        private set
    var currentBooksPage by mutableStateOf(1)
        private set
    var totalBooksPages by mutableStateOf(1)
        private set
    var sortOrder by mutableStateOf(Sort.TITLE_ASC)
        private set

    /** Jump to a letter, the only way twenty thousand titles are navigable. */
    var letterFilter by mutableStateOf<String?>(null)
        private set
    var searchTerm by mutableStateOf("")
        private set

    /** Set by the Authors tab: the books of one person, shown as a normal grid. */
    var authorFilter by mutableStateOf<String?>(null)
        private set

    val pageLoadSize = MutableStateFlow(20)

    private val reloadJobsFlow = MutableSharedFlow<Unit>(1, 0, DROP_OLDEST)

    suspend fun initialize() {
        if (state.value != LoadState.Uninitialized) return
        pageLoadSize.value = settingsRepository.getBookPageLoadSize().first()
        load(1)

        // One redraw per second at most: a running sync emits an event per
        // batch, and a grid that reloads on each of them spends the sync
        // reloading.
        reloadJobsFlow.onEach {
            load(currentBooksPage)
            delay(1000)
        }.launchIn(screenModelScope)

        komgaEvents.onEach { event ->
            when (event) {
                is KomgaEvent.BookEvent, is KomgaEvent.SeriesEvent -> reloadJobsFlow.tryEmit(Unit)
                else -> Unit
            }
        }.launchIn(screenModelScope)
    }

    suspend fun reload() = load(currentBooksPage)

    fun onPageChange(page: Int) {
        screenModelScope.launch { load(page) }
    }

    fun onPageSizeChange(size: Int) {
        screenModelScope.launch {
            settingsRepository.putBookPageLoadSize(size)
            pageLoadSize.value = size
            load(1)
        }
    }

    fun onSortChange(sort: Sort) {
        sortOrder = sort
        screenModelScope.launch { load(1) }
    }

    fun onLetterFilterChange(letter: String?) {
        letterFilter = letter
        screenModelScope.launch { load(1) }
    }

    fun onSearchChange(term: String) {
        searchTerm = term
        screenModelScope.launch { load(1) }
    }

    /**
     * Shows one author's books, and drops the filters that would hide them.
     *
     * Arriving here from the Authors tab means asking a question about a
     * person, not about the letter or the search term left over from the last
     * time the grid was used.
     */
    fun onAuthorFilterChange(name: String?) {
        authorFilter = name
        if (name != null) {
            letterFilter = null
            searchTerm = ""
        }
        screenModelScope.launch { load(1) }
    }

    fun clearFilters() {
        letterFilter = null
        searchTerm = ""
        authorFilter = null
        sortOrder = Sort.TITLE_ASC
        screenModelScope.launch { load(1) }
    }

    val hasActiveFilter: Boolean
        get() = letterFilter != null || searchTerm.isNotBlank() || authorFilter != null

    fun bookMenuActions() = BookMenuActions(bookApi, notifications, screenModelScope, taskEmitter)

    private suspend fun load(page: Int) {
        notifications.runCatchingToNotifications {
            if (books.isEmpty()) mutableState.value = LoadState.Loading

            val response = bookApi.getBookList(
                conditionBuilder = allOfBooks {
                    libraryId?.let { id -> library { isEqualTo(id) } }
                    // Role left open: the mirror credits everyone as a writer,
                    // and pinning the role would only make the filter brittle
                    // if that ever stops being true.
                    authorFilter?.let { name ->
                        author { isEqualTo(KomgaSearchCondition.AuthorMatch(name, null)) }
                    }
                    // Titles are matched on their first letter, digits sharing
                    // the "#" bucket the way every index in this app does.
                    when (val letter = letterFilter) {
                        null -> {}
                        "#" -> anyOf { ('0'..'9').forEach { d -> title { beginsWith(d.toString()) } } }
                        else -> title { beginsWith(letter) }
                    }
                },
                fullTextSearch = searchTerm.takeIf { it.isNotBlank() },
                pageRequest = KomgaPageRequest(
                    pageIndex = page - 1,
                    size = pageLoadSize.value,
                    sort = sortOrder.sort,
                    // Explicit, because the offline repository only applies a
                    // LIMIT when it reads exactly false here — a null would
                    // silently fetch the whole library as one page.
                    unpaged = false,
                ),
            )

            logger.info {
                "books tab: page $page of ${response.totalPages}, " +
                    "${response.content.size} of ${response.totalElements} books"
            }
            books = response.content
            totalBooksCount = response.totalElements.toInt()
            totalBooksPages = response.totalPages
            currentBooksPage = response.number + 1
            mutableState.value = LoadState.Success(Unit)
        }.onFailure { mutableState.value = LoadState.Error(it) }
    }
}
