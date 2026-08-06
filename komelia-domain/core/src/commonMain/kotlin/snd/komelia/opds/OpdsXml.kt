package snd.komelia.opds

import com.fleeksoft.ksoup.nodes.Element

/**
 * Depth-first walk of everything below this element.
 *
 * Written out rather than taken from the parser: ksoup exposes no `allElements`,
 * and a four-line walk is cheaper than finding out which of its selector methods
 * survives the next version.
 */
internal fun Element.descendants(): Sequence<Element> = sequence {
    for (child in children()) {
        yield(child)
        yieldAll(child.descendants())
    }
}

/**
 * `opensearch:totalResults` -> `totalResults`.
 *
 * Case is left alone: half the names in Atom are camelCase, and folding them
 * here would mean folding every comparison against them too.
 */
internal fun Element.localName(): String = tagName().substringAfterLast(':')
