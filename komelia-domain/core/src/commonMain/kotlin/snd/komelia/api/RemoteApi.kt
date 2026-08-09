package snd.komelia.api

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import snd.komelia.komga.api.KomgaApi
import snd.komga.client.KomgaClientFactory
import snd.komga.client.sse.KomgaEvent
import snd.komga.client.sse.KomgaSSESession

data class RemoteApi(
    override val actuatorApi: RemoteActuatorApi,
    override val announcementsApi: RemoteAnnouncementsApi,
    override val bookApi: RemoteBookApi,
    override val collectionsApi: RemoteCollectionsApi,
    override val fileSystemApi: RemoteFileSystemApi,
    override val libraryApi: RemoteLibraryApi,
    override val readListApi: RemoteReadListApi,
    override val referentialApi: RemoteReferentialApi,
    override val seriesApi: RemoteSeriesApi,
    override val settingsApi: RemoteSettingsApi,
    override val tasksApi: RemoteTaskApi,
    override val userApi: RemoteUserApi,
    private val komgaClientFactory: KomgaClientFactory,
    private val offlineEvents: SharedFlow<KomgaEvent>
) : KomgaApi {
    override suspend fun createSSESession(): KomgaSSESession {
        return CombinedSSESession(komgaClientFactory, offlineEvents)
    }

    private class CombinedSSESession(
        private val komgaClientFactory: KomgaClientFactory,
        offlineEvents: SharedFlow<KomgaEvent>,
    ) : KomgaSSESession {
        override val incoming: MutableSharedFlow<KomgaEvent> = MutableSharedFlow()
        private val logger = KotlinLogging.logger { }
        private val coroutineScope = CoroutineScope(
            Dispatchers.Default + SupervisorJob() +
                    CoroutineExceptionHandler { _, exception -> logger.catching(exception) })


        init {
            // it might take a long time for the sse connection to be established
            // and for the server to respond with at least single event so that ktor could transform response body to sse session
            // launch the connection in separate coroutine to prevent blocking offline events
            coroutineScope.launch {
                val session = openSession() ?: return@launch
                session.incoming.collect { incoming.emit(it) }
            }

            coroutineScope.launch {
                offlineEvents.collect { incoming.emit(it) }
            }
        }

        /**
         * Opens the event stream, or gives up and says so.
         *
         * This used to retry every 10 seconds for as long as the process lived,
         * and only on [ClientRequestException] — so against a server with no
         * Komga event endpoint at all (Calibre-Web answers 404 there, and
         * `expectSuccess = true` turns that into a 4xx exception) it was an
         * attempt every 10 seconds, forever, waking the radio and writing a
         * stack trace each time, for a stream that could never open.
         *
         * A 4xx from a URL that exists is the server stating something stable:
         * repeating the same request cannot change the answer, so those stop
         * immediately. Everything else — a home server rebooting, wifi
         * dropping — is transient and worth a few tries, with the wait doubling
         * so a long outage costs a handful of attempts rather than one every ten
         * seconds. Live updates then stay off until the session is rebuilt,
         * which [snd.komelia.ManagedKomgaEvents] does on the next sign-in.
         */
        private suspend fun openSession(): KomgaSSESession? {
            var backoff = initialBackoff
            for (attempt in 1..maxAttempts) {
                try {
                    return komgaClientFactory.sseSession()
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (e is ClientRequestException) {
                        logger.info {
                            "no event stream here (${e.response.status}) — live updates stay off"
                        }
                        return null
                    }
                    if (attempt == maxAttempts) {
                        logger.warn(e) { "event stream unreachable after $attempt tries — live updates stay off" }
                        return null
                    }
                    logger.warn { "event stream unreachable (${e.message}), retrying in $backoff ms" }
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(maxBackoff)
                }
            }
            return null
        }

        override fun cancel() {
            // sse session should have this scope as its coroutine context
            coroutineScope.cancel()
        }

        private companion object {
            const val maxAttempts = 5
            const val initialBackoff = 10_000L
            const val maxBackoff = 120_000L
        }
    }
}
