package snd.komelia.kosync

/**
 * MD5, because KOSync speaks it and nothing here does.
 *
 * The protocol sends the password as an MD5 hex digest and keys every record by
 * a document hash, so a client cannot avoid it. There is no MD5 in the Kotlin
 * standard library, and `java.security.MessageDigest` is not reachable from
 * common code — the alternative was a cryptography dependency added to every
 * target for one sixty-line function that has not changed since 1992.
 *
 * Not for anything that needs to be secure: MD5 has been broken for collisions
 * for twenty years. Here it is an identifier and a wire format, chosen by the
 * protocol, not a defence.
 */
internal object Md5 {

    fun hex(input: String): String = hex(input.encodeToByteArray())

    fun hex(input: ByteArray): String {
        val digest = digest(input)
        return buildString(32) {
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                append(HEX[value shr 4])
                append(HEX[value and 0x0F])
            }
        }
    }

    private fun digest(message: ByteArray): ByteArray {
        // Padding: a single 1 bit, zeroes up to 56 bytes mod 64, then the
        // original length in bits as a little-endian 64-bit integer.
        val originalBits = message.size.toLong() * 8
        val padded = ByteArray(((message.size + 8) / 64 + 1) * 64)
        message.copyInto(padded)
        padded[message.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[padded.size - 8 + i] = (originalBits ushr (8 * i)).toByte()
        }

        var a0 = 0x67452301
        var b0 = 0xefcdab89.toInt()
        var c0 = 0x98badcfe.toInt()
        var d0 = 0x10325476

        val block = IntArray(16)
        var offset = 0
        while (offset < padded.size) {
            for (i in 0 until 16) {
                val base = offset + i * 4
                block[i] = (padded[base].toInt() and 0xFF) or
                    ((padded[base + 1].toInt() and 0xFF) shl 8) or
                    ((padded[base + 2].toInt() and 0xFF) shl 16) or
                    ((padded[base + 3].toInt() and 0xFF) shl 24)
            }

            var a = a0
            var b = b0
            var c = c0
            var d = d0

            for (i in 0 until 64) {
                val f: Int
                val g: Int
                when (i / 16) {
                    0 -> { f = (b and c) or (b.inv() and d); g = i }
                    1 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
                    2 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
                    else -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
                }
                val temp = d
                d = c
                c = b
                b = b + ((a + f + K[i] + block[g]).rotateLeft(S[i]))
                a = temp
            }

            a0 += a
            b0 += b
            c0 += c
            d0 += d
            offset += 64
        }

        // Little-endian, unlike every other digest on earth.
        return byteArrayOf(
            *a0.leBytes(), *b0.leBytes(), *c0.leBytes(), *d0.leBytes(),
        )
    }

    private fun Int.leBytes() = byteArrayOf(
        toByte(),
        (this ushr 8).toByte(),
        (this ushr 16).toByte(),
        (this ushr 24).toByte(),
    )

    private fun Int.rotateLeft(bits: Int) = (this shl bits) or (this ushr (32 - bits))

    private const val HEX = "0123456789abcdef"

    private val S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    /** floor(abs(sin(i + 1)) * 2^32), the constants from the RFC. */
    private val K = intArrayOf(
        0xd76aa478.toInt(), 0xe8c7b756.toInt(), 0x242070db, 0xc1bdceee.toInt(),
        0xf57c0faf.toInt(), 0x4787c62a, 0xa8304613.toInt(), 0xfd469501.toInt(),
        0x698098d8, 0x8b44f7af.toInt(), 0xffff5bb1.toInt(), 0x895cd7be.toInt(),
        0x6b901122, 0xfd987193.toInt(), 0xa679438e.toInt(), 0x49b40821,
        0xf61e2562.toInt(), 0xc040b340.toInt(), 0x265e5a51, 0xe9b6c7aa.toInt(),
        0xd62f105d.toInt(), 0x02441453, 0xd8a1e681.toInt(), 0xe7d3fbc8.toInt(),
        0x21e1cde6, 0xc33707d6.toInt(), 0xf4d50d87.toInt(), 0x455a14ed,
        0xa9e3e905.toInt(), 0xfcefa3f8.toInt(), 0x676f02d9, 0x8d2a4c8a.toInt(),
        0xfffa3942.toInt(), 0x8771f681.toInt(), 0x6d9d6122, 0xfde5380c.toInt(),
        0xa4beea44.toInt(), 0x4bdecfa9, 0xf6bb4b60.toInt(), 0xbebfbc70.toInt(),
        0x289b7ec6, 0xeaa127fa.toInt(), 0xd4ef3085.toInt(), 0x04881d05,
        0xd9d4d039.toInt(), 0xe6db99e5.toInt(), 0x1fa27cf8, 0xc4ac5665.toInt(),
        0xf4292244.toInt(), 0x432aff97, 0xab9423a7.toInt(), 0xfc93a039.toInt(),
        0x655b59c3, 0x8f0ccc92.toInt(), 0xffeff47d.toInt(), 0x85845dd1.toInt(),
        0x6fa87e4f, 0xfe2ce6e0.toInt(), 0xa3014314.toInt(), 0x4e0811a1,
        0xf7537e82.toInt(), 0xbd3af235.toInt(), 0x2ad7d2bb, 0xeb86d391.toInt(),
    )
}
