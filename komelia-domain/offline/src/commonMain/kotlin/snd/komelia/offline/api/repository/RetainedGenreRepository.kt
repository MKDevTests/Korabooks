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
     * Every genre the library actually has, with how many series wear it.
     *
     * The count is the only thing that makes the choice possible: on four
     * hundred genres, the fifteen that matter are exactly the fifteen with more
     * than a handful of series behind them.
     */
    suspend fun findAllCounts(): List<RetainedGenreCount>

    /** Replaces the whole list — it is a set, not a log. */
    suspend fun replaceAll(genres: Collection<String>)
}

data class RetainedGenreCount(
    val genre: String,
    val seriesCount: Int,
)
