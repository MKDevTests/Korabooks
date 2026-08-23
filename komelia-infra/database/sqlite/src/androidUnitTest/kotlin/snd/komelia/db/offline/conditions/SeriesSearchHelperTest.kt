package snd.komelia.db.offline.conditions

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import snd.komelia.db.offline.tables.OfflineSeriesMetadataGenreTable
import snd.komelia.db.offline.tables.OfflineSeriesTable
import snd.komga.client.search.SeriesConditionBuilder
import snd.komga.client.search.allOfSeries
import snd.komga.client.user.KomgaUserId
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The filters, against a real SQLite database.
 *
 * Written after a filter that filtered nothing: `anyOf` was folded from
 * `Op.TRUE`, and `TRUE OR <anything>` is true for every row, so choosing a genre
 * returned the entire library. The whole module had no test, which is how an
 * inverted neutral element survived — an assertion on the SQL string would not
 * have caught it either, since the SQL was valid. These count rows.
 */
class SeriesSearchHelperTest {

    private val helper = SeriesSearchHelper(KomgaUserId("user"))

    // A file rather than `:memory:`. Exposed opens a connection per
    // transaction, and an in-memory SQLite database is discarded when its last
    // connection closes — the schema created in setUp was gone by the time the
    // test queried it ("no such table: SERIES").
    private val dbFile: File = File.createTempFile("searchhelper", ".sqlite").apply { deleteOnExit() }

    @AfterTest
    fun tearDown() {
        dbFile.delete()
    }

    @BeforeTest
    fun setUp() {
        Database.connect("jdbc:sqlite:${dbFile.absolutePath}", "org.sqlite.JDBC")
        transaction {
            // LIBRARY is deliberately absent: SERIES declares a foreign key to
            // it, but SQLite only enforces foreign keys when asked to, and
            // filling thirty non-null library columns would say nothing about
            // the conditions under test.
            SchemaUtils.create(OfflineSeriesTable, OfflineSeriesMetadataGenreTable)

            series("fantasy-only", "Fantasy")
            series("sf-only", "SF")
            series("both", "Fantasy", "SF")
            series("no-genre")
        }
    }

    @Test
    fun `one genre in anyOf keeps only that genre`() {
        assertEquals(2, count { anyOf { genre { isEqualTo("Fantasy") } } })
    }

    /** The failure as reported: a filter that returns the whole library. */
    @Test
    fun `one genre in anyOf is not everything`() {
        val matching = count { anyOf { genre { isEqualTo("Fantasy") } } }
        assertTrue(matching < count { }, "the genre filter matched every series")
    }

    @Test
    fun `several genres in anyOf keep their union`() {
        assertEquals(
            3,
            count {
                anyOf {
                    genre { isEqualTo("Fantasy") }
                    genre { isEqualTo("SF") }
                }
            },
        )
    }

    @Test
    fun `several genres in allOf keep their intersection`() {
        assertEquals(
            1,
            count {
                allOf {
                    genre { isEqualTo("Fantasy") }
                    genre { isEqualTo("SF") }
                }
            },
        )
    }

    /** Case is the reader's business, not the catalogue's. */
    @Test
    fun `genre matching ignores case`() {
        assertEquals(2, count { anyOf { genre { isEqualTo("fAnTaSy") } } })
    }

    @Test
    fun `an unknown genre matches nothing`() {
        assertEquals(0, count { anyOf { genre { isEqualTo("Cuisine") } } })
    }

    /**
     * An empty `anyOf` is now false rather than true.
     *
     * That is what an empty disjunction means, and it is the change of
     * behaviour this fix carries: before, an empty group matched the library.
     * Every caller in the app guards its `anyOf` with a non-empty check, so
     * nothing constructs one on purpose.
     */
    @Test
    fun `an empty anyOf matches nothing`() {
        assertEquals(0, count { anyOf { } })
    }

    @Test
    fun `no condition at all keeps everything`() {
        assertEquals(4, count { })
    }

    // -- helpers --------------------------------------------------------------

    private fun count(condition: SeriesConditionBuilder.() -> Unit): Long = transaction {
        val (op, _) = helper.toCondition(allOfSeries(condition).toSeriesCondition())
        OfflineSeriesTable.selectAll().where { op }.count()
    }

    private fun series(id: String, vararg genres: String) {
        OfflineSeriesTable.insert {
            it[OfflineSeriesTable.id] = id
            it[libraryId] = "lib"
            it[name] = id
            it[url] = "/$id"
            it[booksCount] = 1
            it[deleted] = false
            it[oneshot] = false
            it[createdDate] = 0
            it[lastModifiedDate] = 0
            it[fileLastModifiedDate] = 0
        }
        genres.forEach { g ->
            OfflineSeriesMetadataGenreTable.insert {
                it[seriesId] = id
                it[genre] = g
            }
        }
    }
}
