package snd.komelia.ui.settings.genres

import androidx.compose.foundation.clickable
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import snd.komelia.ui.LocalViewModelFactory
import snd.komelia.ui.common.components.CheckboxWithLabel
import snd.komelia.ui.settings.SettingsScreenContainer

/**
 * Which genres are worth having in a filter list.
 *
 * A Calibre library brings back every genre its owner ever typed, and a filter
 * list of hundreds of entries is not a filter. This screen keeps the handful
 * that are — by family, because the genres are hierarchical and ticking two
 * hundred boxes one at a time is a chore nobody finishes.
 */
class GenreSettingsScreen : Screen {

    @Composable
    override fun Content() {
        val viewModelFactory = LocalViewModelFactory.current
        val vm = rememberScreenModel { viewModelFactory.getGenreSettingsViewModel() }
        LaunchedEffect(Unit) { vm.initialize() }

        val scope = rememberCoroutineScope()
        val listPicker = rememberFilePickerLauncher(
            type = FileKitType.File(),
            mode = FileKitMode.Single,
        ) { file ->
            file?.let { picked -> scope.launch { vm.applyFile(picked.readBytes()) } }
        }

        var filter by remember { mutableStateOf("") }
        // Folded by default: the file picker is the gesture that works on the
        // device, and an always-open textarea would push the list down for a
        // field only the desktop can use.
        var pasteOpen by remember { mutableStateOf(false) }
        // Families start folded: fifty-eight rows fit on a screen, two hundred
        // and fourteen do not. Only what the reader opens is composed, which is
        // also what keeps a plain Column affordable inside a scrolling parent.
        var unfolded by remember { mutableStateOf<Set<String>>(emptySet()) }

        SettingsScreenContainer(title = "Genres") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Les genres cochés sont les seuls que Korabooks proposera pour " +
                        "filtrer et parcourir la bibliothèque. Rien de coché : tous " +
                        "les genres restent proposés, comme avant. Les livres ne sont " +
                        "jamais masqués — c'est la liste des genres qui est raccourcie.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (vm.loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Text("Lecture des genres…", modifier = Modifier.padding(start = 10.dp))
                    }
                } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (vm.keepsEverything)
                            "Aucun genre coché — les ${vm.allGenres.size} genres du miroir " +
                                "restent proposés, répartis en ${vm.groups.size} familles."
                        else buildString {
                            append("${vm.selected.size} genre(s) conservé(s) sur ${vm.allGenres.size} ")
                            append("connus du miroir, en ${vm.groups.size} familles.")
                            if (vm.awaited > 0) {
                                append(" Dont ${vm.awaited} que le miroir n'a pas encore : ")
                                append("ils s'appliqueront après « Tout resynchroniser ».")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }

                // Above the list, not below it. Below, it sat behind whatever
                // the list happens to be — and the list is the problem this
                // screen exists to solve: eleven hundred genres of a mirror
                // synced before the library was tidied. The one action that
                // fixes all of it in a single gesture cannot be the one you have
                // to scroll past the mess to reach.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(onClick = { listPicker.launch() }) {
                        Text("Importer une liste…")
                    }
                    TextButton(onClick = { pasteOpen = !pasteOpen }) {
                        Text(if (pasteOpen) "Masquer le collage" else "…ou coller")
                    }
                }
                Text(
                    "Un genre par ligne, ou séparés par des virgules. Les genres que le " +
                        "miroir ne connaît pas encore sont conservés et s'appliqueront " +
                        "après une synchronisation complète.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (pasteOpen) {
                    OutlinedTextField(
                        value = vm.pasted,
                        onValueChange = vm::onPastedChange,
                        label = { Text("Coller la liste ici") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = vm::applyPasted, enabled = vm.pasted.isNotBlank()) {
                        Text("Cocher les genres de la liste")
                    }
                }

                HorizontalDivider()

                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text("Chercher un genre") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                val term = filter.trim()
                val shown = remember(vm.groups, term) {
                    if (term.isEmpty()) vm.groups
                    else vm.groups.mapNotNull { group ->
                        when {
                            group.root.contains(term, ignoreCase = true) -> group
                            else -> group.entries
                                .filter { it.genre.contains(term, ignoreCase = true) }
                                .takeIf { it.isNotEmpty() }
                                ?.let { group.copy(entries = it) }
                        }
                    }
                }
                val shownGenres = remember(shown) { shown.flatMap { it.genres } }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The counterpart of the search field: narrow the list to
                    // "Fantasy", tick the family in one press. With an empty
                    // search it is "keep everything explicitly", which is a
                    // different thing from keeping nothing.
                    TextButton(
                        onClick = { vm.setAll(shownGenres, true) },
                        enabled = shownGenres.any { it !in vm.selected },
                    ) {
                        Text(
                            if (term.isEmpty()) "Tout cocher"
                            else "Cocher les ${shownGenres.size} affichés"
                        )
                    }
                    // With no search, "tout" has to mean the genres awaiting a
                    // resync too — they are invisible here, and a button that
                    // leaves rows behind is a button that lies.
                    TextButton(
                        onClick = { if (term.isEmpty()) vm.clear() else vm.setAll(shownGenres, false) },
                        enabled = if (term.isEmpty()) vm.selected.isNotEmpty()
                        else shownGenres.any { it in vm.selected },
                    ) {
                        Text(if (term.isEmpty()) "Tout décocher" else "Décocher les affichés")
                    }
                    Button(onClick = vm::save) { Text("Enregistrer") }
                }

                vm.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                vm.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                HorizontalDivider()

                if (shown.isEmpty()) {
                    Text(
                        if (vm.groups.isEmpty())
                            "Le catalogue n'a encore aucun genre — synchronisez-le d'abord."
                        else "Aucun genre ne correspond.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    shown.forEach { group ->
                        if (group.isLeaf) {
                            CheckboxWithLabel(
                                checked = group.root in vm.selected,
                                onCheckedChange = { vm.toggle(group.root) },
                                label = { GenreLabel(group.root, group.seriesCount) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            // Searching already told us which family the reader
                            // means, so it opens itself rather than asking for a
                            // second gesture.
                            val open = group.root in unfolded || term.isNotEmpty()
                            FamilyRow(
                                group = group,
                                selected = vm.selected,
                                open = open,
                                onToggle = { vm.toggleGroup(group) },
                                onUnfold = {
                                    unfolded =
                                        if (group.root in unfolded) unfolded - group.root
                                        else unfolded + group.root
                                },
                            )
                            if (open) {
                                group.entries.forEach { entry ->
                                    CheckboxWithLabel(
                                        checked = entry.genre in vm.selected,
                                        onCheckedChange = { vm.toggle(entry.genre) },
                                        label = {
                                            GenreLabel(
                                                entry.genre.removePrefix("${group.root}.")
                                                    .ifEmpty { group.root },
                                                entry.seriesCount,
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth().padding(start = 32.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                }
            }
        }
    }
}

@Composable
private fun FamilyRow(
    group: GenreGroup,
    selected: Set<String>,
    open: Boolean,
    onToggle: () -> Unit,
    onUnfold: () -> Unit,
) {
    val ticked = group.genres.count { it in selected }
    val state = when (ticked) {
        0 -> ToggleableState.Off
        group.genres.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
                .clickable(onClick = onToggle)
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(10.dp),
        ) {
            TriStateCheckbox(state = state, onClick = null)
            Spacer(Modifier.size(10.dp))
            Text(
                "${group.root}  ·  ${group.seriesCount}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "${group.genres.size} genres" + if (ticked in 1 until group.genres.size) ", $ticked cochés" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (open) "Replier ${group.root}" else "Déplier ${group.root}",
            modifier = Modifier.clickable(onClick = onUnfold)
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(10.dp),
        )
    }
}

@Composable
private fun GenreLabel(name: String, seriesCount: Int) {
    Text("$name  ·  $seriesCount", style = MaterialTheme.typography.bodyMedium)
}
