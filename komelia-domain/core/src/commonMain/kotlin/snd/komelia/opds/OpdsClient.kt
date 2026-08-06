package snd.komelia.opds

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser.Companion.xmlParser
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Login for a catalogue that asks for one. Calibre-Web speaks HTTP Basic. */
data class OpdsCredentials(val username: String, val password: String)

class OpdsHttpException(val status: Int, val url: String) :
    Exception("OPDS request failed with $status: $url")

/**
 * Talks to one OPDS catalogue.
 *
 * The client never invents an address: every request goes to a URL that came
 * out of a feed, starting from the catalogue root the user typed. That is what
 * OPDS is for, and it is why Korabooks works against Calibre-Web, calibre's own
 * server or anything else that speaks the protocol, without a per-server
 * special case — the paths are discovered, not hardcoded.
 */
class OpdsClient(
    private val ktor: HttpClient,
    private val credentials: OpdsCredentials? = null,
    private val parser: OpdsFeedParser = OpdsFeedParser(),
) {

    suspend fun feed(url: String): OpdsFeed {
        val response = ktor.get(url) { authorize() }
        if (!response.status.isSuccess()) throw OpdsHttpException(response.status.value, url)
        return parser.parse(response.bodyAsText(), url)
    }

    /**
     * Walks a paginated feed to its end, following `rel="next"`.
     *
     * Capped, because a catalogue that links its first page as its next page is
     * a real failure mode and an endless one. Twenty thousand books is past any
     * home library and still finite.
     */
    suspend fun allPages(url: String, pageLimit: Int = 400): List<OpdsFeed> {
        val pages = mutableListOf<OpdsFeed>()
        val seen = mutableSetOf<String>()
        var next: String? = url
        while (next != null && pages.size < pageLimit && seen.add(next)) {
            val page = feed(next)
            pages += page
            next = page.nextPage
        }
        return pages
    }

    /**
     * Resolves the OpenSearch description a catalogue advertises into a
     * template, e.g. `https://host/opds/search/{searchTerms}`.
     *
     * Servers disagree on the shape of the search URL — path segment, query
     * parameter, sometimes both — so the template is asked for rather than
     * assumed.
     */
    suspend fun searchTemplate(descriptionUrl: String): String? {
        val response = ktor.get(descriptionUrl) { authorize() }
        if (!response.status.isSuccess()) return null
        val document = Ksoup.parse(response.bodyAsText(), xmlParser())
        return document.descendants()
            .filter { it.localName().equals("url", ignoreCase = true) }
            .firstOrNull { it.attr("type").startsWith(OpdsMediaType.ATOM) }
            ?.attr("template")
            ?.takeIf { it.isNotBlank() }
            ?.let { OpdsUrl.resolve(descriptionUrl, it) }
    }

    suspend fun search(template: String, query: String): OpdsFeed =
        feed(template.replace("{searchTerms}", query.encodeURLParameter()))

    /**
     * Streams a file — a cover, or a book of fifty megabytes.
     *
     * The response is handed to [block] rather than returned: it is only valid
     * inside the call, and reading a book into a ByteArray to then write it to
     * disk is how an app gets killed on a phone.
     */
    suspend fun <T> download(url: String, block: suspend (HttpResponse) -> T): T =
        ktor.prepareGet(url) { authorize() }.execute { response ->
            if (!response.status.isSuccess()) throw OpdsHttpException(response.status.value, url)
            block(response)
        }

    /**
     * A small file, whole: covers, and nothing else.
     *
     * Failure is an absence, not an exception. A catalogue with one broken
     * thumbnail should still sync — a missing cover costs a grey rectangle,
     * while a thrown error would cost the library.
     */
    suspend fun bytes(url: String): ByteArray? =
        runCatching { download(url) { it.body<ByteArray>() } }.getOrNull()

    @OptIn(ExperimentalEncodingApi::class)
    private fun io.ktor.client.request.HttpRequestBuilder.authorize() {
        val login = credentials ?: return
        val token = Base64.encode("${login.username}:${login.password}".encodeToByteArray())
        header(HttpHeaders.Authorization, "Basic $token")
    }
}
