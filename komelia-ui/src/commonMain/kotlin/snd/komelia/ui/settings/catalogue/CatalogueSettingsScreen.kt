package snd.komelia.ui.settings.catalogue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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

    @OptIn(ExperimentalLayoutApi::class)
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
                    "« Nouveaux tomes » (quelques secondes) ajoute les tomes parus " +
                        "depuis la dernière fois et ne touche pas aux séries.\n" +
                        "« Tomes + séries » relit tous les tomes et range " +
                        "dans leur série ceux qui ne le sont pas encore. C'est celui " +
                        "à utiliser, y compris pour reprendre une synchro arrêtée.\n" +
                        "« Refaire toutes les séries » (~30 min) rouvre chaque série " +
                        "une par une. À garder pour un rangement visiblement faux : " +
                        "c'est le seul qui voie un tome remplacé par un autre.\n" +
                        "La synchronisation continue si vous quittez cet écran.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The whole of what is left to gain, and none of it is in this
                // app. Measured against Calibre-Web on a library of eleven
                // thousand books: a page of two hundred takes twenty seconds
                // to build, flat — as long at the start of the index as five
                // thousand books in — and two asked for at once take forty,
                // exactly. That is one worker, serving one request at a time,
                // spending a tenth of a second per book. Sixteen request slots
                // sit idle against it and no amount of concurrency here can
                // use them: the walk was made to measure how many the server
                // will take, and against this one the answer is one.
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Astuce — la durée est celle de Calibre-Web, pas celle de " +
                            "Korabooks : mesuré ici, une page de 200 livres lui prend " +
                            "20 secondes, et deux pages demandées ensemble lui en " +
                            "prennent 40. Il répond à une requête à la fois.\n" +
                            "Deux réglages de votre côté, dans cet ordre : donnez-lui " +
                            "plusieurs workers (Calibre-Web derrière gunicorn, option " +
                            "-w) — Korabooks sait alors lire 16 pages en parallèle et " +
                            "détecte tout seul ce que le serveur encaisse. Puis, dans " +
                            "Administration → Configuration de l'interface, montez " +
                            "« Livres par page » au maximum (200) pour réduire le " +
                            "nombre de requêtes.",
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

                // Five buttons in a plain Row ran straight off the side of a
                // phone. A Row neither wraps nor scrolls: it measures its
                // children at the width they ask for and draws them past the
                // edge, so "Tomes + séries" and "Refaire toutes les séries"
                // were simply not on screen — the two the paragraph above tells
                // you to use. FlowRow wraps onto as many lines as the width
                // needs, and still fits on one line where there is room.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = vm::test, enabled = !vm.busy && vm.url.isNotBlank()) {
                        Text("Tester")
                    }
                    TextButton(onClick = vm::save, enabled = !vm.busy && vm.url.isNotBlank()) {
                        Text("Enregistrer")
                    }
                }

                // Kept in their own row: these start work that runs for
                // minutes, and wrapping all five together would leave
                // "Enregistrer" and "Refaire toutes les séries" side by side
                // on the same line.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (vm.syncing) {
                        Button(onClick = vm::cancelSync) { Text("Arrêter") }
                    } else {
                        // Named after what they touch, in the order of what they
                        // cost. "Tout resynchroniser" / "Reprendre" said nothing
                        // about which of books and series a press would read,
                        // which is the only question a reader actually has.
                        Button(onClick = vm::syncRecent, enabled = !vm.busy && vm.url.isNotBlank()) {
                            Text("Nouveaux tomes")
                        }
                        TextButton(onClick = vm::sync, enabled = !vm.busy && vm.url.isNotBlank()) {
                            Text("Tomes + séries")
                        }
                        // Last, and deliberately dull: it is the one that costs
                        // half an hour, and nobody should reach for it by accident.
                        TextButton(onClick = vm::regroupAll, enabled = !vm.busy && vm.url.isNotBlank()) {
                            Text("Refaire toutes les séries")
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
