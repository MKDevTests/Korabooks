package snd.komelia.kosync

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The vectors from RFC 1321, plus the two lengths where a hand-written MD5
 * breaks: a message that fills a block exactly, and one that leaves fewer than
 * eight bytes for the length field and so needs a second block of padding.
 */
class Md5Test {

    @Test
    fun `rfc 1321 vectors`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Md5.hex(""))
        assertEquals("0cc175b9c0f1b6a831c399e269772661", Md5.hex("a"))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Md5.hex("abc"))
        assertEquals("f96b697d7cb7938d525a2f31aaf161d0", Md5.hex("message digest"))
        assertEquals("c3fcd3d76192e4007dfb496cca67e13b", Md5.hex("abcdefghijklmnopqrstuvwxyz"))
        assertEquals(
            "d174ab98d277d9f5a5611c2c9f419d9f",
            Md5.hex("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"),
        )
        assertEquals(
            "57edf4a22be3c955ac49da2e2107b67a",
            Md5.hex("12345678901234567890123456789012345678901234567890123456789012345678901234567890"),
        )
    }

    @Test
    fun `block boundaries`() {
        // 55 bytes: the last length that still fits its own block.
        assertEquals("ef1772b6dff9a122358552954ad0df65", Md5.hex("a".repeat(55)))
        // 56 bytes: one byte too many, a whole extra block of padding.
        assertEquals("3b0c8ac703f828b04c6c197006d17218", Md5.hex("a".repeat(56)))
        // 64 bytes: exactly one block of message.
        assertEquals("014842d480b571495a4a0363793f7367", Md5.hex("a".repeat(64)))
    }

    @Test
    fun `non ascii is hashed as utf 8`() {
        assertEquals("8b1a9953c4611296a827abf8c47804d7", Md5.hex("Hello"))
        // Two bytes in UTF-8 (0xC3 0xA9), which is the point of the case.
        assertEquals("66ddcd97cfdeabb2f6fb8a999b4bc76f", Md5.hex("é"))
    }
}
