package snd.komelia.image

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toSkiaRect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import snd.komelia.image.ReaderImage.PageId
import snd.komelia.image.SkiaBitmap.toSkiaBitmap
import snd.komelia.image.processing.ImageProcessingPipeline

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual typealias RenderImage = Image

class DesktopReaderImage(
    imageDecoder: KomeliaImageDecoder,
    imageSource: ImageSource,
    processingPipeline: ImageProcessingPipeline,
    stretchImages: StateFlow<Boolean>,
    pageId: PageId,
    upsamplingMode: StateFlow<UpsamplingMode>,
    downSamplingKernel: StateFlow<ReduceKernel>,
    linearLightDownSampling: StateFlow<Boolean>,
    private val showDebugGrid: StateFlow<Boolean>,
) : TilingReaderImage(
    imageDecoder = imageDecoder,
    imageSource = imageSource,
    processingPipeline = processingPipeline,
    stretchImages = stretchImages,
    upsamplingMode = upsamplingMode,
    downSamplingKernel = downSamplingKernel,
    linearLightDownSampling = linearLightDownSampling,
    pageId = pageId,
) {

    init {
        startImageLoading()
    }

    override fun closeTileBitmaps(tiles: List<ReaderImageTile>) {
        tiles.forEach { runCatching { it.renderImage?.close() } }
    }

    override fun createTilePainter(
        tiles: List<ReaderImageTile>,
        displaySize: IntSize,
        scaleFactor: Double
    ): TiledPainter {
        return SkiaTiledPainter(
            tiles = tiles,
            showDebugGrid = showDebugGrid.value,
            upsamplingMode = upsamplingMode.value,
            scaleFactor = scaleFactor,
            displaySize = displaySize
        )
    }

    override suspend fun resizeImage(
        image: KomeliaImage,
        scaleWidth: Int,
        scaleHeight: Int
    ): ReaderImageData {
        val downscaled = image.resize(
            scaleWidth = scaleWidth,
            scaleHeight = scaleHeight,
            linear = linearLightDownSampling.value,
            kernel = downSamplingKernel.value
        )
        val imageData = downscaled.toReaderImageData()
        downscaled.close()
        return imageData
    }

    override suspend fun getImageRegion(
        image: KomeliaImage,
        imageRegion: IntRect,
        scaleWidth: Int,
        scaleHeight: Int
    ): ReaderImageData {
        var region: KomeliaImage? = null
        var resized: KomeliaImage? = null
        try {
            region = image.extractArea(imageRegion.toImageRect())
            resized = region.resize(
                scaleWidth = scaleWidth,
                scaleHeight = scaleHeight,
                linear = linearLightDownSampling.value,
                kernel = downSamplingKernel.value
            )
            return resized.toReaderImageData()
        } finally {
            region?.close()
            resized?.close()
        }
    }

    private suspend fun KomeliaImage.toReaderImageData(): ReaderImageData {
        if (this.pagesLoaded == 1) {
            val skiaBitmap = this.toSkiaBitmap()
            val image = Image.makeFromBitmap(skiaBitmap)
            skiaBitmap.close()
            return ReaderImageData(width, height, listOf(image), null)
        }

        val frames = mutableListOf<RenderImage>()
        val delays = pageDelays?.let { mutableListOf<Long>() }
        for (i in 0 until this.pagesLoaded) {
            val skiaBitmap = this.extractArea(
                ImageRect(
                    left = 0,
                    right = width,
                    top = pageHeight * i,
                    bottom = pageHeight * (i + 1),
                )
            ).toSkiaBitmap()
            val image = Image.makeFromBitmap(skiaBitmap)
            skiaBitmap.close()

            frames.add(image)
            delays?.add(this.pageDelays?.getOrNull(i)?.toLong() ?: defaultFrameDelay)
        }
        return ReaderImageData(width, pageHeight, frames, delays)
    }

    private fun IntRect.toImageRect() = ImageRect(left = left, top = top, right = right, bottom = bottom)

    private class SkiaTiledPainter(
        private val tiles: List<ReaderImageTile>,
        private val upsamplingMode: UpsamplingMode,
        private val scaleFactor: Double,
        private val displaySize: IntSize,
        private val showDebugGrid: Boolean,
    ) : TiledPainter() {
        override val intrinsicSize: Size = displaySize.toSize()
        private val samplingMode = if (scaleFactor > 1.0) when (upsamplingMode) {
            UpsamplingMode.NEAREST -> SamplingMode.DEFAULT
            UpsamplingMode.BILINEAR -> SamplingMode.LINEAR
            UpsamplingMode.MITCHELL -> SamplingMode.MITCHELL
            UpsamplingMode.CATMULL_ROM -> SamplingMode.CATMULL_ROM
        } else SamplingMode.DEFAULT

        override fun DrawScope.onDraw() {
            tiles.forEach { tile ->
                if (tile.renderImage != null && !tile.renderImage.isClosed && tile.isVisible) {
                    val bitmap = tile.renderImage
                    drawContext.canvas.nativeCanvas.drawImageRect(
                        image = bitmap,
                        src = Rect.makeWH(
                            tile.size.width.toFloat(),
                            tile.size.height.toFloat()
                        ),
                        dst = tile.displayRegion.toSkiaRect(),
                        samplingMode = samplingMode,
                        paint = null,
                        strict = true
                    )

                    if (showDebugGrid) {
                        drawContext.canvas.drawRect(
                            tile.displayRegion,
                            Paint().apply {
                                style = PaintingStyle.Stroke
                                color = Color.Green

                            }
                        )
                    }
                }
            }
        }

        override fun withSamplingMode(upsamplingMode: UpsamplingMode): TiledPainter {
            return SkiaTiledPainter(
                tiles = tiles,
                upsamplingMode = upsamplingMode,
                scaleFactor = scaleFactor,
                displaySize = displaySize,
                showDebugGrid = showDebugGrid
            )
        }
    }

}
