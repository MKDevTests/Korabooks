package snd.komelia.http

import io.ktor.client.plugins.cookies.*
import io.ktor.http.*
import kotlinx.coroutines.flow.StateFlow
import snd.komelia.settings.SecretsRepository

@Deprecated("changed to komga-remember-me since komga 1.21.0")
private const val deprecatedRememberMeCookie = "remember-me"
private const val rememberMeCookie = "komga-remember-me"
private const val sessionCookie = "KOMGA-SESSION"

class RememberMePersistingCookieStore(
    private val komgaUrl: StateFlow<Url>,
    private val secretsRepository: SecretsRepository,
) : CookiesStorage {
    private val delegate = AcceptAllCookiesStorage()

    /**
     * Restores the saved session, and only if what was saved is a session.
     *
     * [parseServerSetCookieHeader] parses anything: given a string with no `=` it
     * returns a cookie whose *name* is the whole string and whose value is empty.
     * One of those reached the store — the Android secrets repository used to keep
     * a single value for every key, so a catalogue login could land here — and
     * every request afterwards died inside Ktor while rendering the Cookie header,
     * because the name held a NUL. The store is keyed properly now; the shape is
     * checked anyway, since one bad cookie breaks every request the client makes
     * and the only cookie worth restoring is a named one.
     */
    suspend fun loadRememberMeCookie() {
        val url = komgaUrl.value
        secretsRepository.getCookie(url.toString())
            ?.let { parseServerSetCookieHeader(it) }
            ?.takeIf {
                (it.name == rememberMeCookie || it.name == deprecatedRememberMeCookie) &&
                        it.value.isNotBlank()
            }
            ?.let { delegate.addCookie(url, it) }
    }

    /**
     *
     * if cookie manually added as part of request then it'll be updated with request's path
     * SSE reconnection will reuse the request with cookie headers
     * which will in turn override cookie with new path, breaking all other requests
     * as a workaround, skip cookie if its path doesn't equal to '/'
     * see [io.ktor.client.plugins.cookies.HttpCookies.captureHeaderCookies]
     */
    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        if ((cookie.name == rememberMeCookie || cookie.name == sessionCookie) && cookie.path != komgaUrl.value.encodedPath.ifBlank { "/" }) {
            return
        }

        delegate.addCookie(requestUrl, cookie)
        if (
            (cookie.name == rememberMeCookie || cookie.name == deprecatedRememberMeCookie)
            && cookie.value.isNotBlank()
            && komgaUrl.value.host == requestUrl.host
        ) {
            secretsRepository.setCookie(komgaUrl.value.toString(), renderSetCookieHeader(cookie))
        }

    }

    override suspend fun get(requestUrl: Url): List<Cookie> {
        val cookies = delegate.get(requestUrl)
        return cookies
    }

    override fun close() {
        delegate.close()
    }
}
