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
        val walker = OpdsCatalogueWalker(fetch = { client.feed(it) })

        val shelves = walker.walk(catalogueUrl) {
            onProgress(OpdsSyncProgress.Walking(it.shelves, it.books, it.current))
        }
        logger.info { "OPDS walk found ${shelves.size} shelves in $catalogueUrl" }

        val kept = mutableSetOf<KomgaSeriesId>()
        var books = 0
        var covers = 0

        shelves.forEachIndexed { index, shelf ->
            currentCoroutineContext().ensureActive()
            val mapped = mapper.map(shelf)
            if (mapped.books.isEmpty()) return@forEachIndexed

            val fetched = coversOf(shelf, mapper)
            writer.write(mapped, fetched)

            kept += mapped.series.id
            books += mapped.books.size
            covers += fetched.size
            onProgress(OpdsSyncProgress.Writing(index + 1, shelves.size, shelf.title))
        }

        writer.prune(libraryId, kept)
        return OpdsSyncResult(libraryId, kept.size, books, covers)
    }

    private suspend fun coversOf(shelf: OpdsShelf, mapper: OpdsMapper) = buildMap {
        for (entry in shelf.entries) {
            val href = entry.thumbnail?.href ?: continue
            val bytes = client.bytes(href) ?: continue
            put(mapper.bookId(entry), bytes)
        }
    }
}
