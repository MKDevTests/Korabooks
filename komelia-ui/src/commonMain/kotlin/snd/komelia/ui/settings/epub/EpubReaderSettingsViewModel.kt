package snd.komelia.ui.settings.epub

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import snd.komelia.settings.CommonSettingsRepository
import snd.komelia.settings.EpubReaderSettingsRepository
import snd.komelia.settings.model.EpubReaderType
import snd.komelia.settings.model.EpubReaderType.TTSU_EPUB
import snd.komelia.ui.LoadState

class EpubReaderSettingsViewModel(
    private val settingsRepository: EpubReaderSettingsRepository,
    private val commonSettingsRepository: CommonSettingsRepository,
    private val onEpubCacheClear: () -> Unit,
) : StateScreenModel<LoadState<Unit>>(LoadState.Uninitialized) {
    val selectedEpubReader = MutableStateFlow(TTSU_EPUB)
    val epubCacheSizeLimitMb = MutableStateFlow(2048L)

    /**
     * The same setting the image reader shows, deliberately.
     *
     * It has always governed both readers — EpubReaderViewModel reads it on
     * open — but its only switch lived under "Image reader", where nobody
     * looking for it while reading a book would think to go.
     */
    val keepReaderScreenOn = MutableStateFlow(false)

    suspend fun initialize() {
        if (state.value !is LoadState.Uninitialized) return
        selectedEpubReader.value = settingsRepository.getReaderType().first()
        epubCacheSizeLimitMb.value = settingsRepository.getEpubCacheSizeLimitMb().first()
        keepReaderScreenOn.value = commonSettingsRepository.getKeepReaderScreenOn().first()
        mutableState.value = LoadState.Success(Unit)
    }

    fun onKeepReaderScreenOnChange(enabled: Boolean) {
        keepReaderScreenOn.value = enabled
        screenModelScope.launch { commonSettingsRepository.putKeepReaderScreenOn(enabled) }
    }

    fun onSelectedTypeChange(type: EpubReaderType) {
        selectedEpubReader.value = type
        screenModelScope.launch { settingsRepository.putReaderType(type) }
    }

    fun onEpubCacheSizeLimitMbChange(size: Long) {
        epubCacheSizeLimitMb.value = size
        screenModelScope.launch { settingsRepository.putEpubCacheSizeLimitMb(size) }
    }

    fun onClearEpubCache() {
        onEpubCacheClear()
    }
}
