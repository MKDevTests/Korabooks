package snd.komelia.ui.settings.imagereader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import snd.komelia.ui.LocalAccentColor
import snd.komelia.ui.LocalPlatform
import snd.komelia.ui.common.components.SwitchWithLabel
import snd.komelia.ui.platform.PlatformType
import snd.komelia.ui.settings.imagereader.rapidocr.RapidOcrSettingsContent
import snd.komelia.ui.settings.imagereader.rapidocr.RapidOcrSettingsState
import snd.komelia.ui.settings.imagereader.rapidocr.isRapidOcrSupported
import snd.komelia.ui.LocalStrings

/**
 * Whether the reader's manga tooling is offered at all.
 *
 A Calibre library is
 * novels and essays, and every one of these settings asks its reader to have
 * an opinion about something that will never happen to them.
 *
 * A constant rather than a deletion: the screens behind it are large, they
 * work, and Korabooks may yet grow a comics shelf. Flipping this back on is
 * the whole cost of changing our mind.
 */
private const val MANGA_TOOLS_VISIBLE = false

@Composable
fun ImageReaderSettingsContent(
    loadThumbnailPreviews: Boolean,
    onLoadThumbnailPreviewsChange: (Boolean) -> Unit,

    volumeKeysNavigation: Boolean,
    onVolumeKeysNavigationChange: (Boolean) -> Unit,

    keepReaderScreenOn: Boolean,
    onKeepReaderScreenOnChange: (Boolean) -> Unit,

    imageCacheSizeLimitMb: Long,
    onImageCacheSizeLimitMbChange: (Long) -> Unit,

    pagedReaderAutoDirection: Boolean,
    onPagedReaderAutoDirectionChange: (Boolean) -> Unit,

    pagedAutoSkipBlankPages: Boolean,
    onPagedAutoSkipBlankPagesChange: (Boolean) -> Unit,

    pagedAutoDetectWebtoon: Boolean,
    onPagedAutoDetectWebtoonChange: (Boolean) -> Unit,

    webtoonSmartScroll: Boolean,
    onWebtoonSmartScrollChange: (Boolean) -> Unit,


    continuousReaderStopAtEnd: Boolean,
    onContinuousReaderStopAtEndChange: (Boolean) -> Unit,

    onCacheClear: () -> Unit,
    rapidOcrSettingsState: RapidOcrSettingsState,
) {
    val accentColor = LocalAccentColor.current

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val platform = LocalPlatform.current
        SwitchWithLabel(
            checked = loadThumbnailPreviews,
            onCheckedChange = onLoadThumbnailPreviewsChange,
            label = { Text(LocalStrings.current.ui.loadSmallPreviewsWhenDragging) },
            supportingText = { Text(LocalStrings.current.ui.canBeSlowForHigh) },
        )

        if (platform == PlatformType.MOBILE) {
            SwitchWithLabel(
                checked = volumeKeysNavigation,
                onCheckedChange = onVolumeKeysNavigationChange,
                label = { Text(LocalStrings.current.ui.volumeKeysNavigation) },
            )
            SwitchWithLabel(
                checked = keepReaderScreenOn,
                onCheckedChange = onKeepReaderScreenOnChange,
                label = { Text(LocalStrings.current.ui.keepScreenOnWhileReading) },
            )
        }

        SwitchWithLabel(
            checked = pagedReaderAutoDirection,
            onCheckedChange = onPagedReaderAutoDirectionChange,
            label = { Text(LocalStrings.current.ui.autoDetectReadingDirection) },
            supportingText = { Text(LocalStrings.current.ui.useSeriesMetadataManualFlips) },
        )

        SwitchWithLabel(
            checked = pagedAutoSkipBlankPages,
            onCheckedChange = onPagedAutoSkipBlankPagesChange,
            label = { Text(LocalStrings.current.ui.autoSkipBlankPages) },
            supportingText = { Text(LocalStrings.current.ui.whenCropBordersIsOn) },
        )

        if (MANGA_TOOLS_VISIBLE) {
            SwitchWithLabel(
                checked = pagedAutoDetectWebtoon,
                onCheckedChange = onPagedAutoDetectWebtoonChange,
                label = { Text(LocalStrings.current.ui.autoDetectWebtoon) },
                supportingText = { Text(LocalStrings.current.ui.ifTheFirst3Pages) },
            )

            SwitchWithLabel(
                checked = webtoonSmartScroll,
                onCheckedChange = onWebtoonSmartScrollChange,
                label = { Text(LocalStrings.current.ui.webtoonSmartScroll) },
                supportingText = { Text(LocalStrings.current.ui.inTheContinuousReaderA) },
            )
        }

        SwitchWithLabel(
            checked = continuousReaderStopAtEnd,
            onCheckedChange = onContinuousReaderStopAtEndChange,
            label = { Text(LocalStrings.current.ui.stopAtEndOfBook) },
            supportingText = { Text(LocalStrings.current.ui.pauseAtTheLastPage) },
        )


        FilledTonalButton(
            onClick = onCacheClear,
            colors = accentColor?.let {
                val contentColor = if (it.luminance() > 0.5f) Color.Black else Color.White
                ButtonDefaults.filledTonalButtonColors(containerColor = it, contentColor = contentColor)
            } ?: ButtonDefaults.filledTonalButtonColors()
        ) { Text(LocalStrings.current.ui.clearImageCache) }

        Column {
            Text(
                "Max Image Cache Size: ${"%.1f".format(imageCacheSizeLimitMb.toDouble() / 1024)} GB",
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = imageCacheSizeLimitMb.toFloat(),
                onValueChange = { onImageCacheSizeLimitMbChange(it.toLong()) },
                valueRange = 500f..5000f,
                steps = 44, // (5000 - 500) / 100 - 1 = 44 steps for 100MB intervals
                colors = accentColor?.let {
                    SliderDefaults.colors(
                        thumbColor = it,
                        activeTrackColor = it,
                    )
                } ?: SliderDefaults.colors()
            )
            Text(
                LocalStrings.current.ui.requiresAppRestartToTake,
                style = MaterialTheme.typography.labelSmall
            )
        }

        if (MANGA_TOOLS_VISIBLE && isRapidOcrSupported()) {
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            RapidOcrSettingsContent(
                isDownloaded = rapidOcrSettingsState.isDownloaded.collectAsState().value,
                rapidOcrModelsUrl = rapidOcrSettingsState.rapidOcrModelsUrl.collectAsState().value,
                onRapidOcrModelsUrlChange = rapidOcrSettingsState::onRapidOcrModelsUrlChange,
                downloadFlow = rapidOcrSettingsState::downloadFlow
            )
        }
    }
}
