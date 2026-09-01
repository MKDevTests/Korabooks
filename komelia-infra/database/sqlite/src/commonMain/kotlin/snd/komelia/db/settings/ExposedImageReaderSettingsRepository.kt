package snd.komelia.db.settings

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import snd.komelia.db.ExposedRepository
import snd.komelia.db.ImageReaderSettings
import snd.komelia.db.defaultBookId
import snd.komelia.db.tables.ImageReaderSettingsTable
import snd.komelia.image.ReduceKernel
import snd.komelia.image.UpsamplingMode
import snd.komelia.settings.model.ContinuousReadingDirection
import snd.komelia.settings.model.LayoutScaleType
import snd.komelia.settings.model.OcrEngine
import snd.komelia.settings.model.OcrLanguage
import snd.komelia.settings.model.OcrSettings
import snd.komelia.settings.model.PageDisplayLayout
import snd.komelia.settings.model.PagedReadingDirection
import snd.komelia.settings.model.PanelsFullPageDisplayMode
import snd.komelia.settings.model.RapidOcrModel
import snd.komelia.settings.model.ReaderFlashColor
import snd.komelia.settings.model.ReaderTapNavigationMode
import snd.komelia.settings.model.ReaderType

class ExposedImageReaderSettingsRepository(database: Database) : ExposedRepository(database) {

    suspend fun get(): ImageReaderSettings? {
        return transaction {
            ImageReaderSettingsTable.selectAll()
                .where { ImageReaderSettingsTable.bookId.eq(defaultBookId) }
                .firstOrNull()
                ?.let {

                    ImageReaderSettings(
                        readerType = ReaderType.entries
                            .firstOrNull { type -> type.name == it[ImageReaderSettingsTable.readerType] }
                            ?: ReaderType.PAGED,
                        stretchToFit = it[ImageReaderSettingsTable.stretchToFit],
                        ocrSettings = OcrSettings(
                            enabled = it[ImageReaderSettingsTable.ocrEnabled],
                            selectedLanguage = OcrLanguage.valueOf(it[ImageReaderSettingsTable.ocrLanguage]),
                            engine = OcrEngine.valueOf(it[ImageReaderSettingsTable.ocrEngine]),
                            rapidOcrModel = RapidOcrModel.valueOf(it[ImageReaderSettingsTable.ocrRapidOcrModel]),
                            mergeBoxes = it[ImageReaderSettingsTable.ocrMergeBoxes],
                        ),
                        pagedScaleType = LayoutScaleType.valueOf(it[ImageReaderSettingsTable.pagedScaleType]),
                        pagedReadingDirection = PagedReadingDirection.valueOf(it[ImageReaderSettingsTable.pagedReadingDirection]),
                        pagedPageLayout = PageDisplayLayout.valueOf(it[ImageReaderSettingsTable.pagedPageLayout]),
                        continuousReadingDirection = ContinuousReadingDirection.valueOf(it[ImageReaderSettingsTable.continuousReadingDirection]),
                        continuousPadding = it[ImageReaderSettingsTable.continuousPadding],
                        continuousPageSpacing = it[ImageReaderSettingsTable.continuousPageSpacing],
                        cropBorders = it[ImageReaderSettingsTable.cropBorders],
                        flashOnPageChange = it[ImageReaderSettingsTable.flashOnPageChange],
                        flashDuration = it[ImageReaderSettingsTable.flashDuration],
                        flashEveryNPages = it[ImageReaderSettingsTable.flashEveryNPages],
                        flashWith = ReaderFlashColor.valueOf(it[ImageReaderSettingsTable.flashWith]),
                        downsamplingKernel = ReduceKernel.valueOf(it[ImageReaderSettingsTable.downsamplingKernel]),
                        linearLightDownsampling = it[ImageReaderSettingsTable.linearLightDownsampling],
                        upsamplingMode = UpsamplingMode.valueOf(it[ImageReaderSettingsTable.upsamplingMode]),
                        loadThumbnailPreviews = it[ImageReaderSettingsTable.loadThumbnailPreviews],
                        volumeKeysNavigation = it[ImageReaderSettingsTable.volumeKeysNavigation],
                        panelsFullPageDisplayMode = it[ImageReaderSettingsTable.panelsFullPageDisplayMode]
                            .let { mode -> PanelsFullPageDisplayMode.valueOf(mode) },
                        pagedReaderTapToZoom = it[ImageReaderSettingsTable.pagedReaderTapToZoom],
                        panelReaderTapToZoom = it[ImageReaderSettingsTable.panelReaderTapToZoom],
                        pagedReaderAdaptiveBackground = it[ImageReaderSettingsTable.pagedReaderAdaptiveBackground],
                        panelReaderAdaptiveBackground = it[ImageReaderSettingsTable.panelReaderAdaptiveBackground],
                        tapNavigationMode = it[ImageReaderSettingsTable.tapNavigationMode]
                            .let { mode -> ReaderTapNavigationMode.valueOf(mode) },
                        rapidOcrModelsUrl = it[ImageReaderSettingsTable.rapidOcrModelsUrl],
                        imageCacheSizeLimitMb = it[ImageReaderSettingsTable.imageCacheSizeLimitMb],
                        pagedSplitDoublePages = it[ImageReaderSettingsTable.pagedSplitDoublePages],
                        pagedReaderAutoDirection = it[ImageReaderSettingsTable.pagedReaderAutoDirection],
                        pagedAutoSkipBlankPages = it[ImageReaderSettingsTable.pagedAutoSkipBlankPages],
                        pagedAutoDetectWebtoon = it[ImageReaderSettingsTable.pagedAutoDetectWebtoon],
                        webtoonSmartScroll = it[ImageReaderSettingsTable.webtoonSmartScroll],
                        continuousReaderStopAtEnd = it[ImageReaderSettingsTable.continuousReaderStopAtEnd],
                        continuousReaderTapToZoom = it[ImageReaderSettingsTable.continuousReaderTapToZoom],
                        keepProgressBarVisibleWhileReading = it[ImageReaderSettingsTable.keepProgressBarVisibleWhileReading],
                    )
                }
        }
    }

    suspend fun save(settings: ImageReaderSettings) {
        transaction {
            ImageReaderSettingsTable.upsert {
                it[bookId] = defaultBookId
                it[readerType] = settings.readerType.name
                it[stretchToFit] = settings.stretchToFit


                it[ocrEnabled] = settings.ocrSettings.enabled
                it[ocrLanguage] = settings.ocrSettings.selectedLanguage.name
                it[ocrEngine] = settings.ocrSettings.engine.name
                it[ocrRapidOcrModel] = settings.ocrSettings.rapidOcrModel.name
                it[ocrMergeBoxes] = settings.ocrSettings.mergeBoxes

                it[pagedScaleType] = settings.pagedScaleType.name
                it[pagedReadingDirection] = settings.pagedReadingDirection.name
                it[pagedPageLayout] = settings.pagedPageLayout.name
                it[continuousReadingDirection] = settings.continuousReadingDirection.name
                it[continuousPadding] = settings.continuousPadding
                it[continuousPageSpacing] = settings.continuousPageSpacing
                it[cropBorders] = settings.cropBorders
                it[flashOnPageChange] = settings.flashOnPageChange
                it[flashDuration] = settings.flashDuration
                it[flashEveryNPages] = settings.flashEveryNPages
                it[flashWith] = settings.flashWith.name
                it[downsamplingKernel] = settings.downsamplingKernel.name
                it[linearLightDownsampling] = settings.linearLightDownsampling
                it[loadThumbnailPreviews] = settings.loadThumbnailPreviews
                it[volumeKeysNavigation] = settings.volumeKeysNavigation
                it[upsamplingMode] = settings.upsamplingMode.name
                it[panelsFullPageDisplayMode] = settings.panelsFullPageDisplayMode.name
                it[pagedReaderTapToZoom] = settings.pagedReaderTapToZoom
                it[panelReaderTapToZoom] = settings.panelReaderTapToZoom
                it[pagedReaderAdaptiveBackground] = settings.pagedReaderAdaptiveBackground
                it[panelReaderAdaptiveBackground] = settings.panelReaderAdaptiveBackground
                it[tapNavigationMode] = settings.tapNavigationMode.name
                it[rapidOcrModelsUrl] = settings.rapidOcrModelsUrl
                it[imageCacheSizeLimitMb] = settings.imageCacheSizeLimitMb
                it[pagedSplitDoublePages] = settings.pagedSplitDoublePages
                it[pagedReaderAutoDirection] = settings.pagedReaderAutoDirection
                it[pagedAutoSkipBlankPages] = settings.pagedAutoSkipBlankPages
                it[pagedAutoDetectWebtoon] = settings.pagedAutoDetectWebtoon
                it[webtoonSmartScroll] = settings.webtoonSmartScroll
                it[continuousReaderStopAtEnd] = settings.continuousReaderStopAtEnd
                it[continuousReaderTapToZoom] = settings.continuousReaderTapToZoom
                it[keepProgressBarVisibleWhileReading] = settings.keepProgressBarVisibleWhileReading
            }
        }
    }
}
