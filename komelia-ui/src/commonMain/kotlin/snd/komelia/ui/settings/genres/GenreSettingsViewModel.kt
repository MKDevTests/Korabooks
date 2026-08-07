package snd.komelia.ui.settings.genres

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch
import snd.komelia.offline.api.repository.RetainedGenreCount
import snd.komelia.offline.api.repository.RetainedGenreRepository
import snd.komelia.offline.api.repository.genreRoot

/**
 * A family of genres: `Fantasy` and everything written `Fantasy.*`.
 *
 * The group is what makes the screen usable. Two hundred and fourteen genres
 * ticked one at a time is a chore nobody finishes; fifty-eight families with a
 * single tick each is a decision.
 */
data class GenreGroup(
    val root: String,
    val entries: List<RetainedGenreCount>,
    /** Series carrying any genre of this family, counted once. */
    val seriesCount: Int,
) {
    /** A family of one, whose only member is the root itself: no point unfolding it. */
    val isLeaf: Boolean get() = entries.size == 1 && entries.first().genre == root

    val genres: List<String> get() = entries.map { it.genre }
}

/**
 * The genre short list, edited.
 *
 * Nothing is written until "Enregistrer": ticking forty boxes one save at a
 * time would be forty rewrites of the same table.
 */
class GenreSettingsViewModel(
    private val retainedGenres: RetainedGenreRepository,
) : ScreenModel {

    var groups by mutableStateOf<List<GenreGroup>>(emptyList())
        private set

    /** Every genre the library has, flat — what the paste field matches against. */
    var allGenres by mutableStateOf<List<String>>(emptyList())
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
            val counts = retainedGenres.findAllCounts()
            val byRoot = counts.genres.groupBy { genreRoot(it.genre) }
            groups = counts.roots.map { root ->
                GenreGroup(
                    root = root.genre,
                    // Inside a family, alphabetical: the root first, then its
                    // children in a stable order. Ranking by count here would
                    // scatter `Fantasy.*` around its own parent.
                    entries = byRoot[root.genre].orEmpty().sortedBy { it.genre },
                    seriesCount = root.seriesCount,
                )
            }
            allGenres = counts.genres.map { it.genre }
            selected = retainedGenres.findAll()
        } catch (e: Exception) {
            error = e.message ?: e::class.simpleName ?: "échec"
        } finally {
            loading = false
        }
    }

    fun toggle(genre: String) = setAll(listOf(genre), genre !in selected)

    /**
     * Ticks or unticks a whole family in one gesture.
     *
     * Partially ticked counts as unticked here, so the first press on a mixed
     * family completes it rather than emptying it — the reader who ticked three
     * of twelve was heading towards the family, not away from it.
     */
    fun toggleGroup(group: GenreGroup) = setAll(group.genres, !group.genres.all { it in selected })

    fun setAll(genres: Collection<String>, checked: Boolean) {
        selected = if (checked) selected + genres else selected - genres.toSet()
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
     * else — a note, a Calibre column — and pasting it should not undo the boxes
     * they ticked here two minutes ago.
     *
     * Names the mirror has never seen are **kept**, not dropped. They used to be
     * discarded on the grounds that a genre no series wears filters nothing, and
     * that was wrong: a mirror synced before the library was tidied is missing
     * genuine genres — seventy-five of two hundred and fourteen, measured — and
     * dropping them meant the reader could not write their real list until a
     * full resync had run for hours. Kept, they cost one row, they filter
     * nothing until the genre arrives, and they start working the moment it
     * does. Still counted out loud, because a name that matches nothing is also
     * how a typo looks.
     */
    fun applyPasted() {
        applyList(pasted)
        pasted = ""
    }

    /**
     * The same list, read from a file instead of a text field.
     *
     * Because pasting is not a thing you can do on a phone. The list lives on a
     * computer — exported from Calibre, kept in a note — and getting it into a
     * text field on a tablet means either retyping two hundred lines or getting
     * it into the tablet's clipboard, which nothing does. Picking a file is one
     * gesture, and `adb push` puts the file there in one command.
     */
    fun applyFile(bytes: ByteArray) = applyList(bytes.decodeToString())

    private fun applyList(text: String) {
        val known = allGenres.associateBy { it.lowercase() }
        val names = text.split('\n', ',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        if (names.isEmpty()) return

        // Prefer the mirror's own spelling when it has one, so ticking a box and
        // pasting the same genre don't end up as two rows.
        val (present, awaited) = names.map { known[it.lowercase()] ?: it }
            .partition { known.containsKey(it.lowercase()) }

        setAll(present + awaited, true)
        status = buildString {
            append("${present.size} genre(s) coché(s)")
            if (awaited.isNotEmpty()) {
                append(", ${awaited.size} en attente — pas encore dans le miroir, ")
                append("ils s'appliqueront après une synchronisation complète : ")
                append(awaited.take(5).joinToString(", "))
                if (awaited.size > 5) append("…")
            }
        }
    }

    /**
     * Retained genres the mirror does not have yet.
     *
     * Worth its own figure on screen: without it, "142 conservés" over a list
     * showing 139 boxes reads like an arithmetic bug rather than a list waiting
     * on a resync.
     */
    val awaited: Int get() = (selected - allGenres.toSet()).size

    /** Empties the list outright — including genres the mirror has never seen. */
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
