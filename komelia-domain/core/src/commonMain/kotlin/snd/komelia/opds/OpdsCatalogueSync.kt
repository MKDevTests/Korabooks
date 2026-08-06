package snd.komelia.opds

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import snd.komga.client.book.KomgaBookId
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
            logger.info { "OPDS wrote '${shelf.title}' (${mapped.books.size} books, ${fetched.size} covers)" }
            onProgress(OpdsSyncProgress.Writing(index + 1, shelves.size, shelf.title))
        }

        writer.prune(libraryId, kept)
        return OpdsSyncResult(libraryId, kept.size, books, covers)
    }

    /**
     * Six covers at a time.
     *
     * One request per book, and a library is hundreds of books: done one after
     * another this is the slowest part of a sync by a wide margin. Six is
     * enough to keep the connection busy without turning a home server into a
     * denial of service against itself.
     */
    private suspend fun coversOf(shelf: OpdsShelf, mapper: OpdsMapper): Map<KomgaBookId, ByteArray> =
        coroutineScope {
            val gate = Semaphore(6)
            shelf.entries
                .mapNotNull { entry -> entry.thumbnail?.href?.let { entry to it } }
                .map { (entry, href) ->
                    async { gate.withPermit { client.bytes(href) }?.let { mapper.bookId(entry) to it } }
                }
                .awaitAll()
                .filterNotNull()
                .toMap()
        }
}
