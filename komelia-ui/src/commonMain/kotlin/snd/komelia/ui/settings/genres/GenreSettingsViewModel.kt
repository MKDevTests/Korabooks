package snd.komelia.ui.settings.genres

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch
import snd.komelia.offline.api.repository.RetainedGenreCount
import snd.komelia.offline.api.repository.RetainedGenreRepository

/**
 * The genre short list, edited.
 *
 * The whole screen is one set of strings, so the state is that set plus the
 * catalogue's own genres to tick them off against. Nothing is written until
 * "Enregistrer": ticking forty boxes one save at a time would be forty
 * rewrites of the same table.
 */
class GenreSettingsViewModel(
    private val retainedGenres: RetainedGenreRepository,
) : ScreenModel {

    var counts by mutableStateOf<List<RetainedGenreCount>>(emptyList())
        private set

    /** The genres currently ticked. Empty means "keep them all". */
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set

    var pasted by mutableStateOf("")
        private set

    var loading by mutableStateOf(true)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /**
     * True when the list is untouched, so the screen can say what that means.
     *
     * Reading zero ticked boxes as "the library has no genres" is the mistake
     * this screen exists to prevent — an empty list shows everything, and that
     * has to be said out loud rather than inferred from an empty column.
     */
    val keepsEverything: Boolean get() = selected.isEmpty()

    suspend fun initialize() {
        try {
            counts = retainedGenres.findAllCounts()
            selected = retainedGenres.findAll()
        } catch (e: Exception) {
            error = e.message ?: e::class.simpleName ?: "échec"
        } finally {
            loading = false
        }
    }

    fun toggle(genre: String) {
        selected = if (genre in selected) selected - genre else selected + genre
        status = null
        error = null
    }

    fun onPastedChange(value: String) {
        pasted = value
        status = null
        error = null
    }

    /**
     * Adds a pasted list to the ticks instead of replacing them.
     *
     * The field is there for the reader who already keeps their list somewhere
     * else — a note, another app — and pasting it should not undo the boxes
     * they ticked here two minutes ago. Names that the library does not have are
     * dropped: a kept genre no series wears would just be a line that filters
     * nothing.
     */
    fun applyPasted() {
        val known = counts.associateBy { it.genre.lowercase() }
        val names = pasted.split('\n', ',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (names.isEmpty()) return

        val matched = names.mapNotNull { known[it.lowercase()]?.genre }
        val unknown = names.filter { known[it.lowercase()] == null }

        selected = selected + matched
        pasted = ""
        status = buildString {
            append("${matched.size} genre(s) ajouté(s)")
            if (unknown.isNotEmpty()) {
                append(" — introuvables dans le catalogue : ")
                append(unknown.take(5).joinToString(", "))
                if (unknown.size > 5) append("…")
            }
        }
    }

    fun clear() {
        selected = emptySet()
        status = null
        error = null
    }

    fun save() {
        screenModelScope.launch {
            error = null
            try {
                retainedGenres.replaceAll(selected)
                status = if (selected.isEmpty()) "Liste vidée — tous les genres réapparaissent"
                else "${selected.size} genre(s) conservé(s)"
            } catch (e: Exception) {
                error = e.message ?: e::class.simpleName ?: "échec"
                status = null
            }
        }
    }
}
