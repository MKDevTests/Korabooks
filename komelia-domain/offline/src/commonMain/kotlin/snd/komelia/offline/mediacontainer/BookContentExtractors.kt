package snd.komelia.offline.mediacontainer

import io.github.oshai.kotlinlogging.KotlinLogging
import snd.komelia.offline.book.model.OfflineBook
import snd.komelia.offline.media.model.OfflineBookPage
import snd.komelia.offline.media.model.OfflineMedia
import snd.komga.client.book.KomgaMediaStatus
import snd.komga.client.book.MediaProfile

private val logger = KotlinLogging.logger { }

class BookContentExtractors(
    divinaExtractors: List<DivinaExtractor>,
    private val epubExtractor: EpubExtractor,
    private val pdfExtractor: PdfExtractor?,
) {

    val divinaExtractors = divinaExtractors
        .flatMap { e -> e.mediaTypes().map { it to e } }
        .toMap()

    fun getBookPage(
        book: OfflineBook,
        media: OfflineMedia,
        page: Int
    ): ByteArray {

        if (media.status != KomgaMediaStatus.READY) {
            logger.warn { "Book media is not ready, cannot get pages" }
            throw IllegalStateException("Media is not ready")
        }

        if (page > media.pageCount || page <= 0) {
            logger.error { "Page number #$page is out of bounds. Book has ${media.pageCount} pages" }
            throw IndexOutOfBoundsException("Page $page does not exist")
        }

        return when (media.mediaProfile) {
            MediaProfile.DIVINA -> getDivinaExtractorOrThrow(media)
                .getEntryBytes(book.fileDownloadPath, media.pages[page - 1].fileName)

            MediaProfile.EPUB -> {
                if (media.epubDivinaCompatible) {
                    epubExtractor.getEntryBytes(book.fileDownloadPath, media.pages[page - 1].fileName)
                } else throw IllegalStateException("Epub profile does not support getting page content")
            }

            MediaProfile.PDF -> {
                pdfExtractor?.getPage(book.fileDownloadPath, page)
                    ?: throw IllegalStateException("PDF extractor is not available on this platform")
            }

            null -> throw IllegalStateException("Media is not ready")
        }
    }

    /**
     * Reads the page list out of a file that is now on disk.
     *
     * A catalogue entry says nothing about what is inside the file, so the
     * mirror stores a page count of zero and no pages at all. For an epub that
     * is harmless — its reader paginates itself. For a PDF it is the whole bug:
     * the reader asks for the page list, gets an empty one, and shows a book
     * that is nothing but its cover.
     *
     * Returns null when there is nothing to add, so a caller can skip the write.
     */
    fun readPageList(book: OfflineBook, media: OfflineMedia): OfflineMedia? {
        if (media.mediaProfile != MediaProfile.PDF) return null
        if (media.pageCount > 0 && media.pages.isNotEmpty()) return null
        val extractor = pdfExtractor ?: return null

        val count = runCatching { extractor.getPageCount(book.fileDownloadPath) }
            .onFailure { logger.warn(it) { "could not count the pages of ${book.name}" } }
            .getOrNull()
            ?: return null
        if (count <= 0) return null

        // A PDF page is rendered on demand from its number — getBookPage never
        // looks at these names — so the entries only have to exist, be in order,
        // and be as many as the file has.
        return media.copy(
            pageCount = count,
            pages = (1..count).map { number ->
                OfflineBookPage(
                    bookId = book.id,
                    fileName = "$number",
                    mediaType = "image/jpeg",
                    width = null,
                    height = null,
                    fileSize = null,
                )
            },
        )
    }

    fun getFileContent(book: OfflineBook, media: OfflineMedia, filename: String): ByteArray {
        return when (media.mediaProfile) {
            MediaProfile.DIVINA -> getDivinaExtractorOrThrow(media)
                .getEntryBytes(book.fileDownloadPath, filename)

            MediaProfile.EPUB -> epubExtractor
                .getEntryBytes(book.fileDownloadPath, filename)

            MediaProfile.PDF -> throw IllegalStateException("PDFs are typically treated as single-file books, not containers for other files")
            null -> throw IllegalStateException("Extractor does not support extraction of files")
        }
    }

    private fun getDivinaExtractorOrThrow(media: OfflineMedia): DivinaExtractor {
        val type = checkNotNull(media.mediaType) { "Book media type is null" }
        return checkNotNull(divinaExtractors[type]) { "Unsupported book file format $type" }
    }
}