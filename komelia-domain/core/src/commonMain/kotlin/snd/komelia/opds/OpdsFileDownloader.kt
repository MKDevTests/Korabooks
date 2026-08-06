package snd.komelia.opds

import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import snd.komelia.offline.sync.CatalogueFileDownloader
import snd.komelia.settings.CommonSettingsRepository
import snd.komelia.settings.SecretsRepository
import kotlinx.io.Sink
import kotlinx.io.readByteArray

/**
 * Pours a book from the catalogue into a file, without holding it in memory.
 *
 * A book is not a cover: an epub is tens of megabytes and a comic archive can
 * be hundreds, and reading one into a ByteArray before writing it out is how an
 * app gets killed on a phone halfway through.
 */
class OpdsFileDownloader(
    private val ktor: HttpClient,
    settings: CommonSettingsRepository,
    secrets: SecretsRepository,
) : CatalogueFileDownloader {
    private val credentials = OpdsCredentialStore(settings, secrets)

    override suspend fun download(
        url: String,
        sink: Sink,
        onProgress: (read: Long, total: Long) -> Unit,
    ) {
        val config = credentials.current()
        OpdsClient(ktor, config?.credentials).download(url) { response ->
            val total = response.headers["Content-Length"]?.toLong() ?: 0L
            onProgress(0, total)
            val channel = response.bodyAsChannel().counted()
            while (!channel.isClosedForRead) {
                sink.writePacket(channel.readRemaining(DEFAULT_BUFFER_SIZE.toLong()))
                onProgress(channel.totalBytesRead, total)
            }
            sink.flush()
        }
    }

    override suspend fun stream(url: String, onChunk: suspend (ByteArray) -> Unit) {
        val config = credentials.current()
        OpdsClient(ktor, config?.credentials).download(url) { response ->
            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val packet = channel.readRemaining(DEFAULT_BUFFER_SIZE.toLong())
                if (packet.exhausted()) break
                onChunk(packet.readByteArray())
            }
        }
    }
}
