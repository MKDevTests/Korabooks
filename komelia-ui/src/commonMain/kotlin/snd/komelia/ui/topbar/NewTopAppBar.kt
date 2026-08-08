package snd.komelia.ui.topbar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.HazeMaterials
import io.github.snd_r.komelia.ui.komelia_ui.generated.resources.NotoSerif_Bold
import io.github.snd_r.komelia.ui.komelia_ui.generated.resources.Res
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.Font
import snd.komelia.ui.LocalAccentColor
import snd.komelia.ui.LocalHazeState
import snd.komelia.ui.LocalKomgaState
import snd.komelia.ui.LocalMainScreenViewModel
import snd.komelia.ui.LocalOfflineMode
import snd.komelia.ui.LocalTheme
import snd.komelia.ui.Theme
import snd.komelia.ui.common.menus.LibraryActionsMenu
import snd.komelia.ui.common.menus.LibraryMenuActions
import snd.komga.client.library.KomgaLibrary
import snd.komelia.ui.LocalStrings

@Composable
fun NewTopAppBar(
    library: KomgaLibrary? = null,
    libraryActions: LibraryMenuActions? = null,
    modifier: Modifier = Modifier,
) {
    val theme = LocalTheme.current
    val hazeState = LocalHazeState.current
    val accentColor = LocalAccentColor.current
    val mainScreenVm = LocalMainScreenViewModel.current
    val coroutineScope = rememberCoroutineScope()
    var showOptionsMenu by remember { mutableStateOf(false) }

    val isAdmin = LocalKomgaState.current.authenticatedUser.collectAsState().value?.roleAdmin() ?: true
    val isOffline = LocalOfflineMode.current.collectAsState().value
    val showThreeDotsMenu = library != null && libraryActions != null && (isAdmin || isOffline)

    val iconColor = accentColor ?: theme.colorScheme.primary
    val notoSerif = FontFamily(Font(Res.font.NotoSerif_Bold, FontWeight.Bold))
    val hazeStyle = if (hazeState != null) HazeMaterials.thin(theme.colorScheme.surface) else null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (hazeState != null && hazeStyle != null)
                    Modifier.hazeEffect(hazeState) { style = hazeStyle }
                else
                    Modifier
            )
    ) {
        if (theme.transparentBars) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(45.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { coroutineScope.launch { mainScreenVm.toggleNavBar() } }) {
                Icon(Icons.Rounded.Menu, contentDescription = null, tint = iconColor)
            }

            Text(
                "Korabooks",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = notoSerif,
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic,
                    letterSpacing = (-0.5).sp,
                ),
                modifier = Modifier.weight(1f),
            )

            // The wi-fi toggle that used to sit here is gone. Korabooks has one
            // mode — see CatalogueStartViewModel: offline mode is not a fallback
            // here, it is the only mode — so the icon always read as offline, in
            // error red, and tapping it offered to leave for a Komga login screen
            // and a server that does not exist. It looked like "you are offline"
            // and acted as "take me online", which is how it came to be pressed.
            //
            // `goOnline` stays on the view model: the upstream project has two
            // modes, and the settings screen still exposes the switch for anyone
            // who means it.

            val toggleIcon = when (theme) {
                Theme.LIGHT, Theme.LIGHT_MODERN -> Icons.Rounded.DarkMode
                else -> Icons.Rounded.LightMode
            }
            IconButton(onClick = { mainScreenVm.toggleTheme(theme) }) {
                Icon(toggleIcon, contentDescription = LocalStrings.current.ui.toggleTheme, tint = iconColor)
            }

            if (showThreeDotsMenu && library != null && libraryActions != null) {
                Box {
                    IconButton(onClick = { showOptionsMenu = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = null, tint = iconColor)
                    }
                    LibraryActionsMenu(
                        library = library,
                        actions = libraryActions,
                        expanded = showOptionsMenu,
                        onDismissRequest = { showOptionsMenu = false }
                    )
                }
            }
        }
    }
}
