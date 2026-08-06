package snd.komelia.ui.startup

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.Navigator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import snd.komelia.KomgaAuthenticationState
import snd.komelia.offline.api.OfflineLibraryApi
import snd.komelia.offline.settings.OfflineSettingsRepository
import snd.komelia.offline.user.model.OfflineUser
import snd.komelia.offline.user.repository.OfflineUserRepository
import snd.komelia.ui.MainScreen
import snd.komga.client.user.ROLE_ADMIN
import snd.komga.client.user.ROLE_FILE_DOWNLOAD

/**
 * Opens the app on its own library, with no server to sign in to.
 *
 * The mirror the whole UI reads is keyed by a user, because it was built to
 * hold what a Komga account had downloaded. Korabooks has one reader and no
 * account, so it uses the root identity the offline stack already reserves,
 * created once on first run.
 *
 * Offline mode is not a fallback here: it is the only mode. Switching it on is
 * what points every API at the mirror instead of at a server that does not
 * exist.
 */
class CatalogueStartViewModel(
    private val offlineSettingsRepository: OfflineSettingsRepository,
    private val userRepository: OfflineUserRepository,
    private val offlineLibraryApi: OfflineLibraryApi,
    private val komgaAuthState: KomgaAuthenticationState,
) : ScreenModel {

    val error = MutableStateFlow<String?>(null)

    suspend fun start(navigator: Navigator) {
        error.value = null
        try {
            val user = userRepository.find(OfflineUser.ROOT) ?: rootUser().also { userRepository.save(it) }
            offlineSettingsRepository.putUserId(user.id)
            offlineSettingsRepository.putOfflineMode(true)
            komgaAuthState.setStateValues(user.toKomgaUser(), offlineLibraryApi.getLibraries())
            navigator.replaceAll(MainScreen())
        } catch (e: Exception) {
            // Shown rather than swallowed: this runs before any screen exists,
            // so a notification would have nowhere to appear and the app would
            // sit on a spinner forever.
            error.value = e.message ?: e::class.simpleName ?: "démarrage impossible"
        }
    }

    fun retry(navigator: Navigator) {
        screenModelScope.launch { start(navigator) }
    }

    private fun rootUser() = OfflineUser(
        id = OfflineUser.ROOT,
        // The root identity belongs to no server, and the model enforces it.
        serverId = null,
        email = "korabooks",
        // Admin because the only reader here owns the library: the screens that
        // manage it are hidden from a non-admin, and there is nobody else to
        // hide them from.
        roles = setOf(ROLE_ADMIN, ROLE_FILE_DOWNLOAD),
        sharedAllLibraries = true,
        sharedLibrariesIds = emptySet(),
        labelsAllow = emptySet(),
        labelsExclude = emptySet(),
        ageRestriction = null,
    )
}
