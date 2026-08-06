package snd.komelia.opds

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import snd.komga.client.library.KomgaLibraryId
import snd.komga.client.series.KomgaSeriesId

private val logger = KotlinLogging.logger { }

data class OpdsSyncResult(
    val libraryId: KomgaLibraryId,
    val shelves: Int,
    val books: Int,
    val covers: Int,
)

/** Where the sync is, for a screen that shows it. */
sealed interface OpdsSyncProgress {
    data class Walking(val shelves: Int, val books: Int, val current: String) : OpdsSyncProgress
    data class Writing(val done: Int, val total: Int, val current: String) : OpdsSyncProgress
}

/**
 * Reads a catalogue and writes it into the local mirror.
 *
 * The three steps are kept apart on purpose — walking is where a server's shape
 * is guessed at, mapping is where meaning is decided, writing is where rows are
 * made — and each is tested without the other two. This class is only the order
 * they happen in.
 *
 * Covers are fetched shelf by shelf rather than up front: a library of five
 * hundred books is five hundred small requests, and doing them alongside the
 * writes means the first shelves are already browsable while the rest arrive.
 */
class OpdsCatalogueSync(
    private val client: OpdsClient,
    private val writer: OpdsMirrorWriter,
) {

    suspend fun sync(
        catalogueUrl: String,
        catalogueName: String,
        onProgress: (OpdsSyncProgress) -> Unit = {},
    ): OpdsSyncResult {
        val libraryId = writer.library(catalogueUrl, catalogueName)
        val mapper = OpdsMapper(libraryId = libraryId, catalogueId = catalogueUrl)
        // Logged per request: when a sync looks stuck, the only useful question
        // is whether it is still asking the server for things, and logcat can
        // answer it without a screen.
        val walker = OpdsCatalogueWalker(fetch = { url ->
            logger.info { "OPDS fetch $url" }
            client.feed(url)
        })

        val kept = mutableSetOf<KomgaSeriesId>()
        var books = 0
        var covers = 0

        // Written as they are found, not collected first: twenty thousand books
        // take an hour to read whatever we do, and a library that appears only
        // at the end of that hour is a library nobody sees.
        walker.walk(
            rootUrl = catalogueUrl,
            onProgress = { onProgress(OpdsSyncProgress.Walking(it.shelves, it.books, it.current)) },
        ) { shelf ->
            currentCoroutineContext().ensureActive()
            val mapped = mapper.map(shelf)
            if (mapped.books.isNotEmpty()) {
                val coverUrls = shelf.entries.mapNotNull { entry ->
                    entry.thumbnail?.href?.let { mapper.bookId(entry) to it }
                }.toMap()

                writer.write(mapped, coverUrls)
                kept += mapped.series.id
                books += mapped.books.size
                covers += coverUrls.size
                onProgress(OpdsSyncProgress.Writing(kept.size, books, shelf.title))
            }
        }

        writer.prune(libraryId, kept)
        logger.info { "OPDS sync done: ${kept.size} shelves, $books books" }
        return OpdsSyncResult(libraryId, kept.size, books, covers)
    }

}
