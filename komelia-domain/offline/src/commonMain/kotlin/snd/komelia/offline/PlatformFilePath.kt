package snd.komelia.offline

import io.github.vinceglb.filekit.PlatformFile

expect fun PlatformFile.localFilePath(): String?

expect suspend fun PlatformFile.readChunked(chunkSize: Int, onChunk: suspend (ByteArray) -> Unit)

/**
 * The first [size] bytes, or fewer if the file is shorter.
 *
 * Separate from [readChunked] because that one reads to the end: telling an
 * epub from a login page costs four bytes, and reading twenty megabytes to look
 * at four of them is not a check anybody would leave enabled.
 */
expect suspend fun PlatformFile.readHeader(size: Int): ByteArray
