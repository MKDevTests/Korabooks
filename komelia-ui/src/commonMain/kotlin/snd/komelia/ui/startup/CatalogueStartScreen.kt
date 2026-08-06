package snd.komelia.ui.startup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import snd.komelia.ui.LocalViewModelFactory
import snd.komelia.ui.platform.PlatformTitleBar

/**
 * The first screen, and normally the least seen one.
 *
 * Korabooks reads a catalogue, not a media server: there is nobody to log in
 * to. It opens straight onto the local mirror, and this screen exists only to
 * do that opening — and to have somewhere to say so when it fails.
 */
class CatalogueStartScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModelFactory = LocalViewModelFactory.current
        val vm = rememberScreenModel { viewModelFactory.getCatalogueStartViewModel() }
        val error = vm.error.collectAsState().value

        LaunchedEffect(Unit) { vm.start(navigator) }

        Column {
            PlatformTitleBar { }
            Box(
                modifier = Modifier.fillMaxSize().padding(30.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (error == null) {
                    CircularProgressIndicator()
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        TextButton(onClick = { vm.retry(navigator) }) { Text("Réessayer") }
                    }
                }
            }
        }
    }
}
