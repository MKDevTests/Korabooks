package snd.komelia.ui.settings.catalogue

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch
import snd.komelia.opds.OpdsCatalogueService
import snd.komelia.opds.OpdsSyncProgress

/**
 * The catalogue screen's state.
 *
 * Testing and syncing are kept apart because they answer different questions.
 * "Can I reach it" is one request and takes a second; "mirror it" is hundreds
 * and takes minutes. Being told the address is wrong after four minutes of
 * walking would be its own kind of insult.
 */
class CatalogueSettingsViewModel(
    private val catalogue: OpdsCatalogueService,
) : ScreenModel {

    var url by mutableStateOf("")
        private set
    var username by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set

    var status by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    suspend fun initialize() {
        val config = catalogue.current() ?: return
        url = config.url
        username = config.username
        password = config.password
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
            val result = catalogue.sync { progress ->
                status = when (progress) {
                    is OpdsSyncProgress.Walking ->
                        "Lecture du catalogue — ${progress.books} livres, ${progress.current}"
                    is OpdsSyncProgress.Grouping ->
                        "Regroupement des séries — ${progress.series}, ${progress.current}"
                    is OpdsSyncProgress.Writing ->
                        "Enregistrement ${progress.done}/${progress.total} — ${progress.current}"
                }
            }
            status = "${result.shelves} séries, ${result.books} livres, ${result.covers} couvertures"
        }
    }

    private fun guarded(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        status = null
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
}
