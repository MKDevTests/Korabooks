package snd.komelia.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import snd.komelia.AppNotifications
import snd.komelia.komga.api.KomgaReferentialApi
import snd.komga.client.library.KomgaLibraryId

/**
 * One genre, and everything under it.
 *
 * Calibre writes hierarchies as dotted text — `Non_Fiction.Histoire.France` —
 * and declares which columns work that way in its own settings. Nothing in the
 * catalogue transmits the tree itself: it is entirely implied by the dots, so
 * it is rebuilt here from the flat list the library returns.
 */
data class GenreNode(
    /** The full dotted value, which is what a search matches on. */
    val path: String,
    /** The last segment: the part that is new at this level. */
    val label: String,
    val depth: Int,
    val children: List<GenreNode>,
) {
    /** This genre and every genre beneath it — what selecting a branch means. */
    val subtree: List<String>
        get() = listOf(path) + children.flatMap { it.subtree }
}

/**
 * The genre tree of a library, browsable and searchable.
 *
 * Two hundred genres in a flat list is a wall of text; the same two hundred as
 * a tree is a table of contents. A search is offered beside it because a reader
 * who knows they want "Napoleon" should not have to guess it lives under
 * History, then France.
 */
class LibraryGenresTabState(
    private val referentialApi: KomgaReferentialApi,
    private val notifications: AppNotifications,
    private val libraryId: KomgaLibraryId?,
    private val screenModelScope: CoroutineScope,
) {
    var roots by mutableStateOf<List<GenreNode>>(emptyList())
        private set
    var expanded by mutableStateOf<Set<String>>(emptySet())
        private set
    var searchTerm by mutableStateOf("")
        private set
    var loading by mutableStateOf(false)
        private set

    /** Every genre known, flat — the source the tree and the search both read. */
    private var all: List<String> = emptyList()

    suspend fun initialize() {
        if (all.isNotEmpty()) return
        reload()
    }

    suspend fun reload() {
        loading = true
        notifications.runCatchingToNotifications {
            all = referentialApi.getGenres(libraryIds = libraryId?.let { listOf(it) } ?: emptyList())
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
            roots = buildTree(all)
        }
        loading = false
    }

    fun onSearchChange(term: String) {
        searchTerm = term
        roots = buildTree(matching(term))
        // A search is only useful unfolded: its point is to show where a genre
        // lives, and a collapsed tree would answer with three closed branches.
        expanded = if (term.isBlank()) emptySet() else roots.flatMap { it.subtree }.toSet()
    }

    fun toggle(node: GenreNode) {
        expanded = if (node.path in expanded) expanded - node.path else expanded + node.path
    }

    fun collapseAll() {
        expanded = emptySet()
    }

    /** The rows to draw: roots, then the children of whatever is open. */
    fun visibleRows(): List<GenreNode> {
        val rows = mutableListOf<GenreNode>()
        fun walk(nodes: List<GenreNode>) {
            for (node in nodes) {
                rows += node
                if (node.path in expanded) walk(node.children)
            }
        }
        walk(roots)
        return rows
    }

    private fun matching(term: String): List<String> {
        if (term.isBlank()) return all
        val needle = term.trim().lowercase()
        // Ancestors come along: a match on Histoire.France is meaningless
        // hanging under nothing, and the path is how a reader recognises it.
        val kept = mutableSetOf<String>()
        for (value in all) {
            if (!value.lowercase().contains(needle)) continue
            val parts = value.split('.')
            for (i in 1..parts.size) kept += parts.take(i).joinToString(".")
        }
        return kept.sorted()
    }

    private fun buildTree(values: List<String>): List<GenreNode> {
        // Intermediate levels are implied, never listed: a library holding
        // "A.B.C" and nothing else still has an A and an A.B, and a tree that
        // omitted them would start at a leaf.
        val paths = sortedSetOf<String>()
        for (value in values) {
            val parts = value.split('.').map { it.trim() }.filter { it.isNotEmpty() }
            for (i in 1..parts.size) paths += parts.take(i).joinToString(".")
        }

        fun childrenOf(prefix: String?, depth: Int): List<GenreNode> {
            val level = paths.filter { path ->
                val parts = path.split('.')
                parts.size == depth + 1 && (prefix == null || path.startsWith("$prefix."))
            }
            return level.map { path ->
                GenreNode(
                    path = path,
                    label = path.substringAfterLast('.'),
                    depth = depth,
                    children = childrenOf(path, depth + 1),
                )
            }
        }
        return childrenOf(null, 0)
    }

    fun refresh() {
        screenModelScope.launch { reload() }
    }
}
