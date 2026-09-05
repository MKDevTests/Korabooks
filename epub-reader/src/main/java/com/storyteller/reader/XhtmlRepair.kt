package com.storyteller.reader

import java.nio.charset.CharacterCodingException
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.TransformingResource

/**
 * Makes a chapter readable when its markup is not well-formed XML.
 *
 * An EPUB chapter is served to the WebView as `application/xhtml+xml`, which
 * means the strict XML parser. That parser has no tolerance: one attribute
 * written twice on one tag and the whole chapter is replaced by
 *
 *     This page contains the following errors:
 *     error on line 15 at column 67: Attribute style redefined
 *
 * A browser's HTML parser would have kept the first value and read on, and so
 * would every other reader the file has been through — which is why a file like
 * this can sit in a library for years without anyone noticing it is broken.
 *
 * Most of the time the duplicate is not in the file at all: it is created while
 * the chapter is served. Readium's `ReadiumCss.injectCssProperties` writes the
 * reader's settings as a `style` attribute on the root `<html>` element, and —
 * unlike the `dir` injection right next to it, which strips the existing value
 * first — it does not look for a `style` that is already there. A file Calibre
 * exported with `<html style="font-size:1.136rem;">` therefore reaches the
 * parser with two. Since that injection happens after this transform, the
 * `style` attribute is removed from `<html>` outright rather than deduplicated:
 * Readium is about to write its own, and the reader's own font size should win
 * over whatever the exporter froze into the file.
 *
 * The repair is otherwise deliberately narrow: it removes repeated attributes
 * from an opening tag and changes nothing else, so a file that was already
 * well-formed and has no `style` on `<html>` comes back byte for byte. It does
 * not attempt to fix unescaped ampersands, unclosed tags, or the other ways XML
 * can be refused — those would need a real HTML parser, and this one is a
 * scalpel for the defects actually seen.
 */
internal object XhtmlRepair {

    private val markupExtensions = setOf("xhtml", "html", "htm")

    /** Opening tags only: `<p …>`, never `</p>` or `<!-- … -->`. */
    private val openingTag = Regex("<[a-zA-Z][^<>]*>")

    /** `name=` at an attribute position, i.e. preceded by whitespace. */
    private val attribute = Regex("""\s([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*("[^"]*"|'[^']*')""")

    /** The one attribute Readium re-injects on `<html>` without a guard. */
    private const val INJECTED_ON_ROOT = "style"

    /** True for the resources the WebView will parse as XML. */
    fun handles(url: Url): Boolean = url.extension?.value?.lowercase() in markupExtensions

    /**
     * The same bytes, minus any attribute an opening tag repeats.
     *
     * Returns the original array when there is nothing to remove, so the common
     * case costs one scan and no allocation.
     */
    fun repair(bytes: ByteArray): ByteArray {
        // Rewriting means a decode and a re-encode, so anything that is not
        // UTF-8 — a UTF-16 chapter, a legacy encoding — is handed back
        // untouched. An unreadable chapter is a smaller loss than a corrupted
        // one, and EPUB 3 asks for UTF-8 anyway.
        val text = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            return bytes
        }
        var repaired: StringBuilder? = null
        var copiedUpTo = 0

        for (tag in openingTag.findAll(text)) {
            val seen = HashSet<String>()
            // Claiming the name up front makes the existing drop-a-repeat path
            // remove the file's own `style` on `<html>`, first one included.
            if (nameOf(tag.value) == "html") seen.add(INJECTED_ON_ROOT)
            var kept: StringBuilder? = null
            var tagCopiedUpTo = 0

            for (attr in attribute.findAll(tag.value)) {
                val name = attr.groupValues[1].lowercase()
                if (seen.add(name)) continue

                // A repeat. Drop it, copying everything before it the first time.
                val builder = kept ?: StringBuilder().also { kept = it }
                builder.append(tag.value, tagCopiedUpTo, attr.range.first)
                tagCopiedUpTo = attr.range.last + 1
            }

            val cleanTag = kept ?: continue
            cleanTag.append(tag.value, tagCopiedUpTo, tag.value.length)

            val out = repaired ?: StringBuilder(text.length).also { repaired = it }
            out.append(text, copiedUpTo, tag.range.first)
            out.append(cleanTag)
            copiedUpTo = tag.range.last + 1
        }

        val out = repaired ?: return bytes
        out.append(text, copiedUpTo, text.length)
        return out.toString().encodeToByteArray()
    }

    /** `html` for `<html xmlns="…">`, lowercased. */
    private fun nameOf(tag: String): String =
        tag.drop(1).takeWhile { it.isLetterOrDigit() }.lowercase()

    /** Wraps [resource] so the repair happens on the way to the WebView. */
    fun wrap(url: Url, resource: Resource): Resource =
        if (!handles(url)) resource
        else object : TransformingResource(resource) {
            override suspend fun transform(
                data: Try<ByteArray, ReadError>,
            ): Try<ByteArray, ReadError> = data.map { repair(it) }
        }
}
