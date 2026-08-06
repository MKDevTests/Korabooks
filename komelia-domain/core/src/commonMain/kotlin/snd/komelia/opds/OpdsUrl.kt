package snd.komelia.opds

/**
 * Resolves a link found in a feed against the address that feed was fetched
 * from — RFC 3986 reduced to the four shapes catalogues actually emit.
 *
 * There is no URL type in Kotlin common code, and pulling one in for this would
 * be a dependency for four `startsWith`. The cases below are exhaustive for
 * OPDS: Calibre-Web writes root-relative links, calibre's own server writes
 * absolute ones, and some proxies rewrite one into the other mid-document.
 */
internal object OpdsUrl {

    fun resolve(base: String, href: String): String? {
        val link = href.trim()
        if (link.isEmpty()) return null
        return when {
            link.startsWith("http://") || link.startsWith("https://") -> link
            // Protocol-relative: keep the scheme we are already speaking.
            link.startsWith("//") -> scheme(base) + ":" + link
            link.startsWith("/") -> origin(base) + link
            // A bare query belongs to the current document, not to its folder.
            link.startsWith("?") -> base.substringBefore('?').substringBefore('#') + link
            else -> directory(base) + link
        }
    }

    /** "https://books.example/opds/new?x=1" -> "https://books.example" */
    fun origin(url: String): String {
        val separator = url.indexOf("://")
        if (separator < 0) return url.substringBefore('/')
        val slash = url.indexOf('/', separator + 3)
        return if (slash < 0) url else url.substring(0, slash)
    }

    private fun scheme(url: String): String = url.substringBefore("://", missingDelimiterValue = "https")

    /** Everything up to and including the last slash of the path. */
    private fun directory(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val separator = path.indexOf("://")
        val firstSlash = if (separator < 0) path.indexOf('/') else path.indexOf('/', separator + 3)
        if (firstSlash < 0) return "$path/"
        val lastSlash = path.lastIndexOf('/')
        return if (lastSlash <= firstSlash) path.substring(0, firstSlash + 1)
        else path.substring(0, lastSlash + 1)
    }
}
