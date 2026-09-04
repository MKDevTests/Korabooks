package snd.komelia.offline

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import java.io.File

actual fun PlatformFile.localFilePath(): String? = this.path

actual suspend fun PlatformFile.readHeader(size: Int): ByteArray {
    return File(this.path).inputStream().use { stream ->
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = stream.read(buffer, read, size - read)
            if (n <= 0) break
            read += n
        }
        if (read < size) buffer.copyOf(read) else buffer
    }
}

actual suspend fun PlatformFile.readChunked(chunkSize: Int, onChunk: suspend (ByteArray) -> Unit) {
    File(this.path).inputStream().use { stream ->
        val buffer = ByteArray(chunkSize)
        var n: Int
        while (stream.read(buffer).also { n = it } != -1) {
            onChunk(if (n < chunkSize) buffer.copyOf(n) else buffer)
        }
    }
}
