package snd.komelia.offline

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import snd.komelia.offline.action.OfflineActions
import snd.komelia.offline.api.OfflineKomgaApi
import snd.komelia.offline.mediacontainer.BookContentExtractors
import snd.komelia.offline.sync.BookDownloadService
import snd.komelia.offline.sync.OfflineScannerService
import snd.komelia.offline.sync.model.DownloadEvent
import snd.komelia.offline.tasks.OfflineTaskEmitter
import snd.komga.client.sse.KomgaEvent

data class OfflineDependencies(
    val actions: OfflineActions,
    val taskEmitter: OfflineTaskEmitter,
    val komgaEvents: SharedFlow<KomgaEvent>,
    /**
     * The same stream, writable.
     *
     * Everything that fills the mirror from Komga sits inside this module and
     * emits through it. The OPDS sync fills the same tables from outside, and a
     * library that does not hear about its own new rows never redraws.
     */
    val komgaEventSink: MutableSharedFlow<KomgaEvent>,
    val bookDownloadEvents: MutableSharedFlow<DownloadEvent>,
    val downloadService: BookDownloadService,
    val offlineScannerService: OfflineScannerService,

    val repositories: OfflineRepositories,
    val fileService: BookContentExtractors,
    val komgaApi: OfflineKomgaApi,
)