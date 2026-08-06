package snd.komelia.komga.api

import snd.komelia.komga.api.model.KomeliaAuthorCount
import snd.komga.client.collection.KomgaCollectionId
import snd.komga.client.common.KomgaAuthor
import snd.komga.client.common.KomgaPageRequest
import snd.komga.client.common.Page
import snd.komga.client.library.KomgaLibraryId
import snd.komga.client.readlist.KomgaReadListId
import snd.komga.client.series.KomgaSeriesId

interface KomgaReferentialApi {
    suspend fun getAuthors(
        search: String? = null,
        role: String? = null,
        libraryIds: List<KomgaLibraryId> = emptyList(),
        collectionId: KomgaCollectionId? = null,
        seriesId: KomgaSeriesId? = null,
        readListId: KomgaReadListId? = null,
        pageRequest: KomgaPageRequest? = null,
    ): Page<KomgaAuthor>

    /**
     * The same authors, each with the size of their shelf.
     *
     * Served by the local mirror, which can group its own rows; the default
     * here answers with names and no counts so a server-backed implementation
     * never has to invent one.
     */
    suspend fun getAuthorCounts(
        search: String? = null,
        libraryIds: List<KomgaLibraryId> = emptyList(),
        pageRequest: KomgaPageRequest? = null,
    ): Page<KomeliaAuthorCount> {
        val page = getAuthors(search = search, libraryIds = libraryIds, pageRequest = pageRequest)
        return Page(
            content = page.content.map { KomeliaAuthorCount(it.name, null) }.distinctBy { it.name },
            pageable = page.pageable,
            totalPages = page.totalPages,
            totalElements = page.totalElements,
            last = page.last,
            first = page.first,
            size = page.size,
            number = page.number,
            sort = page.sort,
            numberOfElements = page.numberOfElements,
            empty = page.empty,
        )
    }

    suspend fun getAuthorsNames(search: String? = null): List<String>

    suspend fun getAuthorsRoles(): List<String>

    suspend fun getGenres(
        libraryIds: List<KomgaLibraryId> = emptyList(),
        collectionId: KomgaCollectionId? = null
    ): List<String>

    suspend fun getSharingLabels(
        libraryIds: List<KomgaLibraryId> = emptyList(),
        collectionId: KomgaCollectionId? = null
    ): List<String>

    suspend fun getTags(
        libraryIds: List<KomgaLibraryId> = emptyList(),
        collectionId: KomgaCollectionId? = null
    ): List<String>

    suspend fun getBookTags(
        seriesId: KomgaSeriesId? = null,
        readListId: KomgaReadListId? = null,
        libraryIds: List<KomgaLibraryId> = emptyList()
    ): List<String>

    suspend fun getSeriesTags(
        libraryId: KomgaLibraryId? = null,
        collectionId: KomgaCollectionId? = null
    ): List<String>

    suspend fun getLanguages(
        libraryIds: List<KomgaLibraryId> = emptyList(),
        collectionId: KomgaCollectionId? = null
    ): List<String>

    suspend fun getPublishers(
        libraryIds: List<KomgaLibraryId> = emptyList(),
        collectionId: KomgaCollectionId? = null
    ): List<String>

    suspend fun getAgeRatings(
        libraryIds: List<KomgaLibraryId> = emptyList(),
        collectionId: KomgaCollectionId? = null
    ): List<String>

    suspend fun getSeriesReleaseDates(
        libraryIds: List<KomgaLibraryId> = emptyList(),
        collectionId: KomgaCollectionId? = null
    ): List<String>
}