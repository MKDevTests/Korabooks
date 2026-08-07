package snd.komelia.ui.settings.genres

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * list of four hundred entries is not a filter. This screen keeps the handful
 * that are: tick them, or paste a list you already have.
 */
class GenreSettingsScreen : Screen {

    @Composable
    override fun Content() {
        val viewModelFactory = LocalViewModelFactory.current
        val vm = rememberScreenModel { viewModelFactory.getGenreSettingsViewModel() }
        LaunchedEffect(Unit) { vm.initialize() }

        // Composed one row per genre inside a scrolling container, so a library
        // with four hundred genres pays for four hundred rows on every
        // recomposition. The filter is what keeps that number small in practice.
        var filter by remember { mutableStateOf("") }

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
                            if (vm.keepsEverything) "Aucun genre coché — les ${vm.counts.size} genres du catalogue restent proposés."
                            else "${vm.selected.size} genre(s) conservé(s) sur ${vm.counts.size}.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp),
                        )
                    }

                    OutlinedTextField(
                        value = vm.pasted,
                        onValueChange = vm::onPastedChange,
                        label = { Text("Coller une liste (un genre par ligne, ou séparés par des virgules)") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = vm::applyPasted, enabled = vm.pasted.isNotBlank()) {
                            Text("Ajouter la liste")
                        }
                        TextButton(onClick = vm::clear, enabled = vm.selected.isNotEmpty()) {
                            Text("Tout décocher")
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

                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        label = { Text("Chercher un genre") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    val shown = remember(vm.counts, filter) {
                        val term = filter.trim()
                        if (term.isEmpty()) vm.counts
                        else vm.counts.filter { it.genre.contains(term, ignoreCase = true) }
                    }

                    if (shown.isEmpty()) {
                        Text(
                            if (vm.counts.isEmpty()) "Le catalogue n'a encore aucun genre — synchronisez-le d'abord."
                            else "Aucun genre ne correspond.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        shown.forEach { count ->
                            CheckboxWithLabel(
                                checked = count.genre in vm.selected,
                                onCheckedChange = { vm.toggle(count.genre) },
                                label = {
                                    Text(
                                        "${count.genre}  ·  ${count.seriesCount}",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}
