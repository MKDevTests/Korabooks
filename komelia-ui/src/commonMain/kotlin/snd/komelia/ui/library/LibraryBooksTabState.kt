package snd.komelia.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import snd.komelia.komga.api.KomgaBookApi
import snd.komelia.komga.api.KomgaReferentialApi
import snd.komelia.komga.api.model.KomeliaBook
import snd.komelia.offline.tasks.OfflineTaskEmitter
import snd.komelia.settings.CommonSettingsRepository
import snd.komelia.AppNotifications
import snd.komelia.ui.LoadState
import snd.komelia.ui.common.menus.BookMenuActions
import snd.komga.client.common.KomgaPageRequest
import snd.komga.client.common.KomgaSort
import snd.komga.client.library.KomgaLibraryId
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
 *
 * Every input to the query lives in [filterState]; this class only turns a new
 * filter into a page of books.
 */
class LibraryBooksTabState(
    private val bookApi: KomgaBookApi,
    referentialApi: KomgaReferentialApi,
    private val notifications: AppNotifications,
    private val komgaEvents: SharedFlow<KomgaEvent>,
    private val settingsRepository: CommonSettingsRepository,
    private val libraryId: KomgaLibraryId?,
    private val taskEmitter: OfflineTaskEmitter,
    private val screenModelScope: CoroutineScope,
    val cardWidth: StateFlow<Dp>,
) {
    /**
     * Sort orders the offline mirror can actually serve.
     *
     * Each one maps to a column the book repository knows how to sort on
     * (`metadata.title`, `createdDate`, `metadata.releaseDate`,
     * `readProgress.readDate`); a key it does not know is dropped silently,
     * which reads as "the button does nothing".
     */
    enum class Sort(val label: String, val sort: KomgaSort) {
        TITLE_ASC("Titre A→Z", KomgaSort.KomgaBooksSort.byTitle(KomgaSort.Direction.ASC)),
        TITLE_DESC("Titre Z→A", KomgaSort.KomgaBooksSort.byTitle(KomgaSort.Direction.DESC)),
        ADDED_DESC("Ajouts récents", KomgaSort.KomgaBooksSort.byCreatedDate(KomgaSort.Direction.DESC)),
        RELEASE_DATE_DESC("Parution récente", KomgaSort.KomgaBooksSort.byReleaseDate(KomgaSort.Direction.DESC)),
        RELEASE_DATE_ASC("Parution ancienne", KomgaSort.KomgaBooksSort.byReleaseDate(KomgaSort.Direction.ASC)),
        READ_DATE_DESC("Dernière lecture", KomgaSort.KomgaBooksSort.byReadDate(KomgaSort.Direction.DESC)),
    }

    private val mutableState = MutableStateFlow<LoadState<Unit>>(LoadState.Uninitialized)
    val state = mutableState.asStateFlow()

    val filterState = LibraryBookFilterState(
        libraryId = libraryId,
        referentialApi = referentialApi,
        appNotifications = notifications,
    )

    var books by mutableStateOf<List<KomeliaBook>>(emptyList())
        private set
    var totalBooksCount by mutableStateOf(0)
        private set
    var currentBooksPage by mutableStateOf(1)
        private set
    var totalBooksPages by mutableStateOf(1)
        private set

    val pageLoadSize = MutableStateFlow(20)

    private val reloadJobsFlow = MutableSharedFlow<Unit>(1, 0, DROP_OLDEST)

    @OptIn(FlowPreview::class)
    suspend fun initialize() {
        if (state.value != LoadState.Uninitialized) return
        pageLoadSize.value = settingsRepository.getBookPageLoadSize().first()
        load(1)
        // The grid must not wait on the filter panel's referential data: tags
        // and release years are only needed once the panel is opened.
        screenModelScope.launch { filterState.initialize() }

        // The one place a filter change turns into a query. Every control — the
        // search box, the letter bar, the sort chips, the panel — writes to the
        // filter and to nothing else, so there is exactly one load per change.
        //
        // Typing waits; everything else does not. The search field emits one
        // state per character, and each one costs a paged query over ten
        // thousand books. A letter chip or a sort toggle is a single deliberate
        // tap and must still feel instant, so only a changed search term earns
        // the delay. Emptying the box is a tap, not typing, so it goes through
        // at once — the same rule the series tab applies.
        var previousSearch = filterState.state.value.searchTerm
        filterState.state.drop(1).debounce { current ->
            val typing = current.searchTerm != previousSearch && current.searchTerm.isNotBlank()
            previousSearch = current.searchTerm
            if (typing) SEARCH_DEBOUNCE_MS else 0L
        }.onEach { load(1) }.launchIn(screenModelScope)

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

    /** Entry point from the Authors tab: the books of one person. */
    fun onAuthorFilterChange(name: String?) = filterState.onAuthorScopeChange(name)

    fun bookMenuActions() = BookMenuActions(bookApi, notifications, screenModelScope, taskEmitter)

    private suspend fun load(page: Int) {
        notifications.runCatchingToNotifications {
            if (books.isEmpty()) mutableState.value = LoadState.Loading

            val filter = filterState.state.value
            val response = bookApi.getBookList(
                conditionBuilder = allOfBooks {
                    libraryId?.let { id -> library { isEqualTo(id) } }
                    filter.addConditionTo(this)
                },
                fullTextSearch = filter.searchTerm.takeIf { it.isNotBlank() },
                pageRequest = KomgaPageRequest(
                    pageIndex = page - 1,
                    size = pageLoadSize.value,
                    sort = filter.sortOrder.sort,
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
