package snd.komelia.ui.oneshot

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted.Companion.Eagerly
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import snd.komelia.AppNotifications
import snd.komelia.komga.api.KomgaBookApi
import snd.komelia.komga.api.KomgaCollectionsApi
import snd.komelia.komga.api.KomgaReadListApi
import snd.komelia.komga.api.KomgaSeriesApi
import snd.komelia.komga.api.model.KomeliaBook
import snd.komelia.offline.tasks.OfflineTaskEmitter
import snd.komelia.settings.CommonSettingsRepository
import snd.komelia.ui.LoadState
import snd.komelia.ui.LoadState.Error
import snd.komelia.ui.LoadState.Loading
import snd.komelia.ui.LoadState.Success
import snd.komelia.ui.LoadState.Uninitialized
import snd.komelia.ui.collection.SeriesCollectionsState
import snd.komelia.ui.common.cards.defaultCardWidth
import snd.komelia.ui.common.menus.BookMenuActions
import snd.komelia.ui.readlist.BookReadListsState
import snd.komga.client.library.KomgaLibrary
import snd.komga.client.search.allOfBooks
import snd.komga.client.series.KomgaSeries
import snd.komga.client.series.KomgaSeriesId
import snd.komga.client.sse.KomgaEvent
import snd.komga.client.sse.KomgaEvent.BookAdded
import snd.komga.client.sse.KomgaEvent.BookChanged
import snd.komga.client.sse.KomgaEvent.ReadProgressChanged
import snd.komga.client.sse.KomgaEvent.ReadProgressDeleted
import snd.komga.client.sse.KomgaEvent.SeriesAdded
import snd.komga.client.sse.KomgaEvent.SeriesChanged

class OneshotViewModel(
    series: KomgaSeries?,
    book: KomeliaBook?,
    private val seriesId: KomgaSeriesId,
    private val seriesApi: KomgaSeriesApi,
    private val bookApi: KomgaBookApi,
    private val events: SharedFlow<KomgaEvent>,
    private val notifications: AppNotifications,
    private val libraries: StateFlow<List<KomgaLibrary>>,
    private val taskEmitter: OfflineTaskEmitter,
    settingsRepository: CommonSettingsRepository,
    readListApi: KomgaReadListApi,
    collectionApi: KomgaCollectionsApi,
) : StateScreenModel<LoadState<Unit>>(Uninitialized) {

    private val reloadEventsEnabled = MutableStateFlow(true)
    private val reloadFlow = MutableSharedFlow<Unit>(1, 0, DROP_OLDEST)

    val series = MutableStateFlow(series)
    val library = MutableStateFlow<KomgaLibrary?>(null)
    val book = MutableStateFlow(book)
    var isExpanded by mutableStateOf(false)
    val bookMenuActions = BookMenuActions(bookApi, notifications, screenModelScope, taskEmitter)

    val cardWidth = settingsRepository.getCardWidth().map { it.dp }
        .stateIn(screenModelScope, Eagerly, defaultCardWidth.dp)

    val readListsState = BookReadListsState(
        book = this.book,
        bookApi = bookApi,
        readListApi = readListApi,
        notifications = notifications,
        komgaEvents = events,
        stateScope = screenModelScope,
    )
    val collectionsState = SeriesCollectionsState(
        series = this.series,
        notifications = notifications,
        seriesApi = seriesApi,
        collectionApi = collectionApi,
        events = events,
        screenModelScope = screenModelScope,
        cardWidth = cardWidth,
    )

    suspend fun initialize() {
        if (state.value != Uninitialized) return
        initState()
        book.filterNotNull().combine(libraries) { book, libraries ->
            // An empty list means "not loaded yet", not "your library is gone":
            // the flow starts empty and is filled once, at sign-in. Reading that
            // first emission as a failure is what left every one-book series on
            // an error screen -- and nothing here ever put the screen back to
            // Success afterwards, so a wait of a few hundred milliseconds looked
            // permanent. While the library is null the screen shows its loading
            // indicator, which is what a not-yet-loaded library should look like.
            if (libraries.isEmpty()) return@combine

            val newLibrary = libraries.firstOrNull { it.id == book.libraryId }
            library.value = newLibrary
            when {
                newLibrary == null ->
                    mutableState.value = Error(MissingLibraryException(book.metadata.title))
                // Recover, but only from this screen's own missing-library
                // error: clearing any error here would paper over a failed
                // book or series load that has nothing to do with libraries.
                state.value.let { it is Error && it.exception is MissingLibraryException } ->
                    mutableState.value = Success(Unit)
            }
        }.launchIn(screenModelScope)

        startKomgaEventListener()
        collectionsState.initialize()
        readListsState.initialize()

        reloadFlow.onEach {
            reloadEventsEnabled.first { it }
            loadSeries()
            loadBook()
        }.launchIn(screenModelScope)
    }

    private suspend fun initState() {
        notifications.runCatchingToNotifications {
            mutableState.value = Loading
            if (this.series.value == null) {
                this.series.value = seriesApi.getOneSeries(seriesId)
            }

            val currentBook = this.book.value
                ?: bookApi.getBookList(allOfBooks { seriesId { isEqualTo(seriesId) } })
                    .content.first()
                    .also { this.book.value = it }


            this.library.value = resolveLibrary(currentBook)
        }
            .onSuccess { mutableState.value = Success(Unit) }
            .onFailure { mutableState.value = Error(it) }
    }

    fun reload() {
        screenModelScope.launch {
            notifications.runCatchingToNotifications {
                mutableState.value = Loading
                val currentBook = book.value
                    ?: bookApi.getBookList(allOfBooks { seriesId { isEqualTo(seriesId) } })
                        .content.first()
                        .also { book.value = it }
                book.value = bookApi.getOne(currentBook.id)
                series.value = seriesApi.getOneSeries(seriesId)
                library.value = resolveLibrary(currentBook)
            }
                .onSuccess { mutableState.value = Success(Unit) }
                .onFailure { mutableState.value = Error(it) }
        }
    }

    fun onBookDownload() {
        screenModelScope.launch {
            book.value?.let { taskEmitter.downloadBook(it.id) }
        }
    }

    fun onBookDownloadDelete() {
        screenModelScope.launch {
            taskEmitter.deleteSeries(seriesId)
        }
    }

    private suspend fun loadBook() {
        notifications.runCatchingToNotifications {
            val currentBook = requireNotNull(book.value)
            this.book.value = bookApi.getOne(currentBook.id)
        }.onFailure { mutableState.value = Error(it) }
    }

    private suspend fun loadSeries() {
        notifications.runCatchingToNotifications {
            series.value = seriesApi.getOneSeries(seriesId)
        }.onFailure { mutableState.value = Error(it) }
    }

    /**
     * The book's library, or null while the list is still on its way.
     *
     * Only a *loaded* list that does not contain the book's library is an error
     * worth showing. An empty one is a race with sign-in, and used to be
     * reported as "Failed to find library for oneshot ...", which is both wrong
     * and unfixable by the reader.
     */
    private fun resolveLibrary(book: KomeliaBook): KomgaLibrary? {
        val loaded = this.libraries.value
        if (loaded.isEmpty()) return null
        return loaded.firstOrNull { it.id == book.libraryId }
            ?: throw MissingLibraryException(book.metadata.title)
    }

    fun stopKomgaEventHandler() {
        reloadEventsEnabled.value = false
        readListsState.stopKomgaEventHandler()
        collectionsState.stopKomgaEventHandler()
    }

    fun startKomgaEventHandler() {
        reloadEventsEnabled.value = true
        readListsState.startKomgaEventHandler()
        collectionsState.startKomgaEventHandler()
    }

    private fun startKomgaEventListener() {
        events.onEach { event ->
            when (event) {
                is SeriesChanged, is SeriesAdded ->
                    if (event.seriesId == seriesId) reloadFlow.tryEmit(Unit)

                is BookChanged, is BookAdded ->
                    if (event.bookId == book.value?.id) reloadFlow.tryEmit(Unit)

                is ReadProgressChanged, is ReadProgressDeleted ->
                    if (event.bookId == book.value?.id) reloadFlow.tryEmit(Unit)

                else -> {}
            }
        }.launchIn(screenModelScope)
    }
}

/**
 * Raised when the library list is loaded and the book's library is not in it.
 *
 * A named type rather than a bare [IllegalStateException] so the screen can
 * recognise its own error and clear it once the library shows up.
 */
private class MissingLibraryException(title: String) :
    IllegalStateException("Failed to find library for oneshot $title")
