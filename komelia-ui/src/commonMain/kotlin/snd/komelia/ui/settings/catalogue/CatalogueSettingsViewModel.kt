package snd.komelia.ui.settings.catalogue

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch
import snd.komelia.opds.OpdsCatalogueService
import snd.komelia.opds.OpdsSyncProgress
import snd.komelia.opds.describe
import snd.komelia.opds.OpdsSyncState

/**
 * The catalogue screen's state.
 *
 * It watches the sync rather than running it. A sync of twenty thousand books
 * takes twenty minutes and belongs to the application: held in this screen's
 * scope, walking away from the settings killed it — and coming back offered to
 * start it again, as if nothing had been lost.
 *
 * Testing and syncing stay separate buttons because they answer different
 * questions. Reaching the catalogue is one request and takes a second; mirroring
 * it is thousands and takes minutes, and being told the address was wrong after
 * four minutes of walking would be its own kind of insult.
 */
class CatalogueSettingsViewModel(
    private val catalogue: OpdsCatalogueService,
) : ScreenModel {

    /**
     * Pre-filled with the shape of the answer, not with a guess.
     *
     * Nobody's Calibre-Web is on localhost from a phone, so this is never right
     * — it is there to be edited, and editing an address is quicker than
     * writing one from nothing when the port and the /opds suffix are the parts
     * people get wrong.
     */
    var url by mutableStateOf(DEFAULT_URL)
        private set
    var username by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set

    var status by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** True while a one-shot action of this screen runs — not while a sync does. */
    var busy by mutableStateOf(false)
        private set

    var syncing by mutableStateOf(false)
        private set

    suspend fun initialize() {
        catalogue.current()?.let { config ->
            url = config.url
            username = config.username
            password = config.password
        }
        screenModelScope.launch {
            catalogue.syncState.collect { state ->
                syncing = state is OpdsSyncState.Running
                when (state) {
                    is OpdsSyncState.Idle -> Unit
                    is OpdsSyncState.Running -> state.progress?.let { status = it.describe() }
                    is OpdsSyncState.Done -> status =
                        "${state.result.shelves} séries, ${state.result.books} livres"
                    is OpdsSyncState.Failed -> error = state.message
                }
            }
        }
    }

    fun onUrlChange(value: String) { url = value; status = null; error = null }
    fun onUsernameChange(value: String) { username = value; status = null; error = null }
    fun onPasswordChange(value: String) { password = value; status = null; error = null }

    fun test() {
        guarded {
            val title = catalogue.test(url, username, password)
            status = "Catalogue joint : $title"
        }
    }

    fun save() {
        guarded {
            catalogue.save(url, username, password)
            status = "Adresse enregistrée"
        }
    }

    /**
     * Saves before syncing, always.
     *
     * Typing an address and pressing Synchronise is one intention, and asking
     * the reader to press Save first would only be a way of punishing them for
     * not reading the screen in the order it was written.
     */
    fun sync() {
        guarded {
            catalogue.save(url, username, password)
            catalogue.startSync()
        }
    }

    /**
     * Reads only what the catalogue added since last time.
     *
     * The full sync is twenty minutes for a library that usually gained three
     * books, and nobody runs a twenty minute job to find out whether anything
     * happened. This one costs a single request when nothing did.
     */
    fun syncRecent() {
        guarded {
            catalogue.save(url, username, password)
            catalogue.startSync(recentOnly = true)
        }
    }

    /**
     * Opens every series shelf again, whatever the mirror already says.
     *
     * The long way round, and the only one left for a grouping that was wrong
     * when it was made: [sync] leaves grouped shelves alone and skips the
     * grouping pass entirely when the catalogue holds exactly the books it held
     * last time — right almost always, and blind to a volume swapped for
     * another. Half an hour on a real library, so it is asked for.
     *
     * `resumeSync` used to sit between the two and no longer needs to exist:
     * leaving grouped shelves alone is what [sync] does now, so an interrupted
     * sync is continued by pressing the same button again.
     */
    fun regroupAll() {
        guarded {
            catalogue.save(url, username, password)
            catalogue.startSync(force = true)
        }
    }

    fun cancelSync() = catalogue.cancelSync()

    // describe() moved next to OpdsSyncProgress: the notification says the same
    // sentence now, and two copies would have drifted apart.

    private fun guarded(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        screenModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                error = e.message ?: e::class.simpleName ?: "échec"
                status = null
            } finally {
                busy = false
            }
        }
    }

    private companion object {
        const val DEFAULT_URL = "http://localhost:8083/opds"
    }
}
