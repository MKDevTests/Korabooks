package snd.komelia.ui.settings.catalogue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import snd.komelia.ui.LocalViewModelFactory
import snd.komelia.ui.settings.SettingsScreenContainer

/**
 * Where the library comes from: one address, one login, one button.
 *
 * This is the whole configuration of Korabooks. Everything else it shows is
 * read from the mirror this screen fills.
 */
class CatalogueSettingsScreen : Screen {

    @Composable
    override fun Content() {
        val viewModelFactory = LocalViewModelFactory.current
        val vm = rememberScreenModel { viewModelFactory.getCatalogueSettingsViewModel() }
        LaunchedEffect(Unit) { vm.initialize() }

        SettingsScreenContainer(title = "Catalogue") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "« Nouveautés » ne lit que ce que le catalogue a ajouté depuis la " +
                        "dernière fois, et s'arrête dès qu'il ne trouve rien de neuf. " +
                        "« Reprendre » continue une synchronisation interrompue sans " +
                        "relire les séries déjà regroupées. " +
                        "« Tout resynchroniser » relit tous les livres, et parmi les " +
                        "séries celles dont la taille a changé : le catalogue annonce " +
                        "combien de tomes chaque série tient, et une série de trois " +
                        "tomes qui en annonce toujours trois n'a rien à apprendre. " +
                        "La synchronisation continue si vous quittez cet écran.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The single biggest thing a reader can do about sync time, and
                // it is not in this app. A catalogue answers one page at a
                // time, so the whole cost is the number of pages asked for:
                // measured on a library of ten thousand books, sixty per page
                // is a hundred and seventy-six requests where two hundred is
                // fifty-three. Nothing that can be written here comes close.
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Astuce — la synchronisation dépend surtout de Calibre-Web. " +
                            "Dans Administration → Configuration de l'interface, montez " +
                            "« Livres par page » au maximum (200). Korabooks demande alors " +
                            "trois fois moins de pages, et la lecture du catalogue est " +
                            "d'autant plus rapide.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                Text(
                    "Adresse du flux OPDS de Calibre-Web, par exemple http://192.168.1.10:8083/opds",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = vm.url,
                    onValueChange = vm::onUrlChange,
                    label = { Text("Adresse") },
                    singleLine = true,
                    enabled = !vm.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = vm.username,
                    onValueChange = vm::onUsernameChange,
                    label = { Text("Identifiant (facultatif)") },
                    singleLine = true,
                    enabled = !vm.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = vm.password,
                    onValueChange = vm::onPasswordChange,
                    label = { Text("Mot de passe") },
                    singleLine = true,
                    enabled = !vm.busy,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = vm::test, enabled = !vm.busy && vm.url.isNotBlank()) {
                        Text("Tester")
                    }
                    TextButton(onClick = vm::save, enabled = !vm.busy && vm.url.isNotBlank()) {
                        Text("Enregistrer")
                    }
                    if (vm.syncing) {
                        Button(onClick = vm::cancelSync) { Text("Arrêter") }
                    } else {
                        Button(onClick = vm::syncRecent, enabled = !vm.busy && vm.url.isNotBlank()) {
                            Text("Nouveautés")
                        }
                        TextButton(onClick = vm::resumeSync, enabled = !vm.busy && vm.url.isNotBlank()) {
                            Text("Reprendre")
                        }
                        TextButton(onClick = vm::sync, enabled = !vm.busy && vm.url.isNotBlank()) {
                            Text("Tout resynchroniser")
                        }
                    }
                    if (vm.busy || vm.syncing) CircularProgressIndicator(Modifier.padding(start = 4.dp))
                }

                vm.status?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                vm.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
