package snd.komelia.offline.api.repository

/**
 * The reader's short list of genres.
 *
 * An empty list is not "no genres" — it is "no opinion", and every genre the
 * library has is shown. That is the state the app ships in, and the state it
 * goes back to when the list is cleared.
 */
interface RetainedGenreRepository {

    /** The genres to keep; empty means keep them all. */
    suspend fun findAll(): Set<String>

    /**
     * Every genre the library actually has, with how many series wear it, and
     * the same figures aggregated per hierarchy root.
     *
     * The count is the only thing that makes the choice possible: on two
     * hundred genres, the ones that matter are the ones with series behind them.
     */
    suspend fun findAllCounts(): RetainedGenreCounts

    /** Replaces the whole list — it is a set, not a log. */
    suspend fun replaceAll(genres: Collection<String>)
}

data class RetainedGenreCount(
    val genre: String,
    val seriesCount: Int,
)

data class RetainedGenreCounts(
    /** One entry per genre as written. */
    val genres: List<RetainedGenreCount>,
    /**
     * One entry per hierarchy root, counting series **once** even when they
     * carry two children of that root. Summing the children would inflate
     * every family, and a count nobody can reproduce by hand is a count
     * nobody trusts.
     */
    val roots: List<RetainedGenreCount>,
)

/**
 * The family a genre belongs to.
 *
 * Calibre tags in this library are hierarchical by convention, written with
 * dots: `Fantasy`, `Fantasy.Dark_Fantasy`, `Fantasy.Historique`. Two hundred and
 * fourteen genres collapse to fifty-eight roots, which is the difference
 * between a list you tick and a list you abandon.
 */
fun genreRoot(genre: String): String = genre.substringBefore('.').trim()
