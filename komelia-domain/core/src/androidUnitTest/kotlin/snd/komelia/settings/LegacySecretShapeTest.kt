package snd.komelia.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The migration off the single shared `rememberMe` field.
 *
 * Getting the two shapes the wrong way round would hand a catalogue login to the
 * cookie parser again, which is the bug this was written for: a cookie whose name
 * held a NUL, and every request after it refused by Ktor. Covered by shape rather
 * than by hoping, since the field itself says nothing about who wrote it.
 */
class LegacySecretShapeTest {

    private val serverUrl = "http://localhost:8083/opds"
    private val opdsKey = "opds:$serverUrl"

    private val opdsLogin = "admin${NUL}some-password"
    private val setCookieHeader =
        "komga-remember-me=abc123; Path=/; Max-Age=1209600; Expires=Sat, 23 Aug 2026 00:00:00 GMT; HttpOnly"

    @Test
    fun `a catalogue login is never read as a cookie`() {
        assertFalse(legacyValueBelongsTo(serverUrl, opdsLogin))
    }

    @Test
    fun `a catalogue login is read for the catalogue key`() {
        assertTrue(legacyValueBelongsTo(opdsKey, opdsLogin))
    }

    @Test
    fun `a cookie is never read as a catalogue login`() {
        assertFalse(legacyValueBelongsTo(opdsKey, setCookieHeader))
    }

    @Test
    fun `a cookie is read for the server key`() {
        assertTrue(legacyValueBelongsTo(serverUrl, setCookieHeader))
    }

    @Test
    fun `a password containing no separator still counts as a login`() {
        // save() always writes the separator, even for an empty password.
        assertTrue(legacyValueBelongsTo(opdsKey, "admin$NUL"))
    }

    @Test
    fun `nothing claims a blank field`() {
        for (key in listOf(serverUrl, opdsKey)) {
            assertFalse(legacyValueBelongsTo(key, ""), key)
            assertFalse(legacyValueBelongsTo(key, "   "), key)
        }
    }

    @Test
    fun `a value of neither shape is refused`() {
        // Whatever this is, guessing would be worse than asking again.
        for (key in listOf(serverUrl, opdsKey)) {
            assertFalse(legacyValueBelongsTo(key, "garbage"), key)
        }
    }
}
