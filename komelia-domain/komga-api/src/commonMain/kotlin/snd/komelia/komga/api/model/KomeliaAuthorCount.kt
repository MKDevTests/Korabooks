package snd.komelia.komga.api.model

/**
 * An author, and how many books of theirs the library holds.
 *
 * The count is nullable because only the local mirror can answer it: Komga
 * publishes author names but never a tally, and a zero invented to fill the
 * field would read as "no books" rather than as "not known". A screen that
 * cannot get the number simply does not show one.
 */
data class KomeliaAuthorCount(
    val name: String,
    val bookCount: Int? = null,
)
