package snd.komelia.ui.reader.image

import androidx.compose.ui.unit.IntSize
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.Navigator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import snd.komelia.AppNotifications
import snd.komelia.ManagedKomgaEvents
import snd.komelia.color.repository.BookColorCorrectionRepository
import snd.komelia.image.BookImageLoader
import snd.komelia.image.ReaderImageFactory
import snd.komelia.komga.api.KomgaBookApi
import snd.komelia.komga.api.KomgaReadListApi
import snd.komelia.komga.api.model.KomeliaBook
import snd.komelia.komga.api.KomgaSeriesApi
import snd.komelia.reader.SeriesReaderOverridesRepository
import snd.komelia.settings.CommonSettingsRepository
import snd.komelia.settings.ImageReaderSettingsRepository
import snd.komelia.image.ReadingDirection
import snd.komelia.settings.model.ContinuousReadingDirection
import snd.komelia.settings.model.PagedReadingDirection
import snd.komelia.settings.model.OcrSettings
import snd.komelia.settings.model.ReaderType.CONTINUOUS
import snd.komelia.settings.model.ReaderType.PAGED
import snd.komelia.ui.BookSiblingsContext
import snd.komelia.ui.LoadState
import snd.komelia.ui.reader.image.continuous.ContinuousReaderState
import snd.komelia.ui.reader.image.paged.PagedReaderState
import snd.komelia.ui.strings.AppStrings
import snd.komga.client.book.KomgaBookId

private val cleanupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
private val logger = KotlinLogging.logger { }

class ReaderViewModel(
    bookApi: KomgaBookApi,
    seriesApi: KomgaSeriesApi,
    readListApi: KomgaReadListApi,
    navigator: Navigator,
    appNotifications: AppNotifications,
    readerSettingsRepository: ImageReaderSettingsRepository,
    private val commonSettingsRepository: CommonSettingsRepository,
    imageLoader: BookImageLoader,
    appStrings: Flow<AppStrings>,
    readerImageFactory: ReaderImageFactory,
    markReadProgress: Boolean,
    currentBookId: MutableStateFlow<KomgaBookId?>,
    bookSiblingsContext: BookSiblingsContext,
    colorCorrectionRepository: BookColorCorrectionRepository,
    private val bookAnnotationRepository: snd.komelia.annotations.BookAnnotationRepository,
    private val epubBookmarkRepository: snd.komelia.bookmarks.EpubBookmarkRepository,
    private val readerSyncService: snd.komelia.sync.ReaderSyncService,
    private val komgaEvents: ManagedKomgaEvents,
    private val ocrService: snd.komelia.image.OcrService,
    val colorCorrectionIsActive: Flow<Boolean>,
    onBookChange: () -> Unit = {},
    private val seriesReaderOverridesRepository: SeriesReaderOverridesRepository,
    private val blankPageDetector: snd.komelia.image.processing.BlankPageDetector,
) : ScreenModel {
    val screenScaleState = ScreenScaleState()
    private val pageChangeFlow = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val readerState: ReaderState = ReaderState(
        imageLoader = imageLoader,
        bookApi = bookApi,
        seriesApi = seriesApi,
        readListApi = readListApi,
        navigator = navigator,
        appNotifications = appNotifications,
        readerSettingsRepository = readerSettingsRepository,
        commonSettingsRepository = commonSettingsRepository,
        currentBookId = currentBookId,
        markReadProgress = markReadProgress,
        stateScope = screenModelScope,
        bookSiblingsContext = bookSiblingsContext,
        colorCorrectionRepository = colorCorrectionRepository,
        bookAnnotationRepository = bookAnnotationRepository,
        epubBookmarkRepository = epubBookmarkRepository,
        readerSyncService = readerSyncService,
        komgaEvents = komgaEvents,
        pageChangeFlow = pageChangeFlow,
        ocrService = ocrService,
    )

    val pagedReaderState = PagedReaderState(
        cleanupScope = cleanupScope,
        readerState = readerState,
        appNotifications = appNotifications,
        settingsRepository = readerSettingsRepository,
        imageLoader = imageLoader,
        appStrings = appStrings,
        pageChangeFlow = pageChangeFlow,
        screenScaleState = screenScaleState,
        onBookChange = onBookChange,
        seriesReaderOverridesRepository = seriesReaderOverridesRepository,
        blankPageDetector = blankPageDetector,
    )
    val continuousReaderState = ContinuousReaderState(
        cleanupScope = cleanupScope,
        readerState = readerState,
        imageLoader = imageLoader,
        settingsRepository = readerSettingsRepository,
        notifications = appNotifications,
        appStrings = appStrings,
        readerImageFactory = readerImageFactory,
        pageChangeFlow = pageChangeFlow,
        screenScaleState = screenScaleState,
    )

    init {
        combine(
            readerState.readerType,
            pagedReaderState.readingDirection,
            continuousReaderState.readingDirection
        ) { type, pagedDir, continuousDir ->
            when (type) {
                PAGED -> if (pagedDir == PagedReadingDirection.RIGHT_TO_LEFT) ReadingDirection.RTL else ReadingDirection.LTR
                CONTINUOUS -> if (continuousDir == ContinuousReadingDirection.RIGHT_TO_LEFT) ReadingDirection.RTL else ReadingDirection.LTR
            }
        }.onEach { readerState.readingDirection.value = it }
            .launchIn(screenModelScope)

        readerState.ocrSettings
            .flatMapLatest { ocrSettings ->
                if (ocrSettings.enabled) {
                    readerState.readerType.flatMapLatest { readerType ->
                        when (readerType) {
                            PAGED -> pagedReaderState.currentSpread.map { it.pages.firstOrNull()?.imageResult?.image }
                            CONTINUOUS -> flowOf(null) // TODO
                        }
                    }.debounce(200)
                } else {
                    readerState.ocrResults.value = emptyList()
                    readerState.ocrPageId.value = null
                    flowOf(null)
                }
            }
            .onEach { image ->
                image?.let { readerState.scanCurrentPageForText(it) }
            }.launchIn(screenModelScope)
    }

    suspend fun initialize(bookId: KomgaBookId, seedBook: KomeliaBook? = null) {
        val currentState = readerState.state.value
        if (currentState is LoadState.Success || currentState == LoadState.Loading) return

        readerState.initialize(bookId, seedBook)
        screenScaleState.areaSize.takeWhile { it == IntSize.Zero }.collect()

        readerState.readerType.onEach {
            stopAllReaderModeStates()
            when (it) {
                PAGED -> pagedReaderState.initialize()
                CONTINUOUS -> continuousReaderState.initialize()
            }
        }.launchIn(screenModelScope)
    }

    private fun stopAllReaderModeStates() {
        pagedReaderState.stop()
        continuousReaderState.stop()
    }

    override fun onDispose() {
        stopAllReaderModeStates()
        readerState.onDispose()
    }
}