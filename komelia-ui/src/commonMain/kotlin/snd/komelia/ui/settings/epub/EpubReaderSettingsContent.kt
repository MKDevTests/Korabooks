package snd.komelia.ui.settings.epub

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import snd.komelia.settings.model.EpubReaderType
import snd.komelia.settings.model.EpubReaderType.EPUB3_READER
import snd.komelia.settings.model.EpubReaderType.KOMGA_EPUB
import snd.komelia.settings.model.EpubReaderType.TTSU_EPUB
import snd.komelia.ui.LocalAccentColor
import snd.komelia.ui.LocalPlatform
import snd.komelia.ui.LocalStrings
import snd.komelia.ui.common.components.DropdownChoiceMenu
import snd.komelia.ui.common.components.LabeledEntry
import snd.komelia.ui.common.components.SwitchWithLabel
import snd.komelia.ui.platform.PlatformType
import snd.komelia.ui.platform.cursorForHand

@Composable
fun EpubReaderSettingsContent(
    readerType: EpubReaderType,
    onReaderChange: (EpubReaderType) -> Unit,

    epubCacheSizeLimitMb: Long,
    onEpubCacheSizeLimitMbChange: (Long) -> Unit,
    onClearEpubCache: () -> Unit,

    keepReaderScreenOn: Boolean,
    onKeepReaderScreenOnChange: (Boolean) -> Unit,
) {
    val strings = LocalStrings.current.settings
    val accentColor = LocalAccentColor.current
    val platform = LocalPlatform.current
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The same switch as the image reader's, showing the same value: one
        // setting, two places to find it. A phone is the only platform where
        // the screen turns itself off, so it is the only one that shows it.
        if (platform == PlatformType.MOBILE) {
            SwitchWithLabel(
                checked = keepReaderScreenOn,
                onCheckedChange = onKeepReaderScreenOnChange,
                label = { Text(LocalStrings.current.ui.keepScreenOnWhileReading) },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            DropdownChoiceMenu(
                selectedOption = remember(readerType) {
                    LabeledEntry(
                        readerType,
                        strings.forEpubReaderType(readerType)
                    )
                },
                options = remember(epub3ReaderAvailable) {
                    EpubReaderType.entries
                        .filter { it != EPUB3_READER || epub3ReaderAvailable }
                        .map { LabeledEntry(it, strings.forEpubReaderType(it)) }
                },
                onOptionChange = { onReaderChange(it.value) },
                label = { Text(LocalStrings.current.ui.readerType) },
                inputFieldModifier = Modifier.fillMaxWidth().animateContentSize(),
                modifier = Modifier.weight(1f),
            )

            AnimatedVisibility(readerType == TTSU_EPUB) {
                val uriHandler = LocalUriHandler.current
                ElevatedButton(
                    onClick = { uriHandler.openUri("https://github.com/ttu-ttu/ebook-reader") },
                    modifier = Modifier.cursorForHand().padding(start = 20.dp)
                ) {
                    Text(LocalStrings.current.ui.projectOnGithub)
                }
            }
        }


        when (readerType) {
            TTSU_EPUB -> Text(
                """
                    Loads entire book data at once. May cause long load times or performance issues
                    Adapted for use in Kora with storage/statistics features removed
                """.trimIndent()
            )

            KOMGA_EPUB -> Text(LocalStrings.current.ui.komgaWebuiEpubReaderAdapted)

            EPUB3_READER -> {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(LocalStrings.current.ui.nativeEpub3ReaderWith)

                    FilledTonalButton(
                        onClick = onClearEpubCache,
                        colors = accentColor?.let {
                            val contentColor = if (it.luminance() > 0.5f) Color.Black else Color.White
                            ButtonDefaults.filledTonalButtonColors(containerColor = it, contentColor = contentColor)
                        } ?: ButtonDefaults.filledTonalButtonColors()
                    ) { Text(LocalStrings.current.ui.clearEpubCache) }

                    Column {
                        Text(
                            LocalStrings.current.counts.maxEpubCacheSize(
                                "%.1f".format(epubCacheSizeLimitMb.toDouble() / 1024)
                            ),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Slider(
                            value = epubCacheSizeLimitMb.toFloat(),
                            onValueChange = { onEpubCacheSizeLimitMbChange(it.toLong()) },
                            valueRange = 1000f..10000f,
                            steps = 8, // (10000 - 1000) / 1000 - 1 = 8 steps for 1GB intervals
                            colors = accentColor?.let {
                                SliderDefaults.colors(
                                    thumbColor = it,
                                    activeTrackColor = it,
                                )
                            } ?: SliderDefaults.colors()
                        )
                    }
                }
            }
        }
    }
}
