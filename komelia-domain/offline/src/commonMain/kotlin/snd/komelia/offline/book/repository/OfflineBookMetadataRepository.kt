package snd.komelia.offline.book.repository

import snd.komelia.offline.book.model.OfflineBookMetadata
import snd.komga.client.book.KomgaBookId

interface OfflineBookMetadataRepository {
    suspend fun save(metadata: OfflineBookMetadata)

    /**
     * Many at once, in as few statements as the store can manage.
     *
     * One by one, a book costs an upsert plus a delete and an insert for each
     * of authors, tags and links — seven statements, and a catalogue sync
     * writes ten thousand books. The default keeps other implementations
     * working without asking them to be clever.
     */
    suspend fun saveAll(metadata: List<OfflineBookMetadata>) {
        metadata.forEach { save(it) }
    }

    suspend fun find(id: KomgaBookId): OfflineBookMetadata?
    suspend fun findAllByIds(bookIds: List<KomgaBookId>): List<OfflineBookMetadata>
    suspend fun get(id: KomgaBookId): OfflineBookMetadata
    suspend fun delete(id: KomgaBookId)

    suspend fun delete(bookIds: List<KomgaBookId>)
}