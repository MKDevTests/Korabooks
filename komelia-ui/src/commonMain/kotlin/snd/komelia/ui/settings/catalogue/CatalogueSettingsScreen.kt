package snd.komelia.ui.settings.catalogue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
                    Button(onClick = vm::sync, enabled = !vm.busy && vm.url.isNotBlank()) {
                        Text("Synchroniser")
                    }
                    if (vm.busy) CircularProgressIndicator(Modifier.padding(start = 4.dp))
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
