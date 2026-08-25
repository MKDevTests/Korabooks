package snd.komelia.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import snd.komelia.db.migrations.AppMigrations
import snd.komelia.db.migrations.MigrationResourcesProvider
import snd.komelia.db.migrations.OfflineMigrations
import javax.sql.DataSource


class KomeliaDatabase(databaseDir: String, serverId: Long? = null) {
    val app: Database
    val offline: Database
    val offlineReadOnly: Database

    private val appDatasource: HikariDataSource
    private val offlineWriteDatasource: HikariDataSource
    private val offlineReadDatasource: HikariDataSource

    init {
        val appFileName = if (serverId != null) "server_${serverId}_komelia.sqlite" else "komelia.sqlite"
        val offlineFileName = if (serverId != null) "server_${serverId}_offline.sqlite" else "offline.sqlite"

        val appUrl = "jdbc:sqlite:${databaseDir}/$appFileName"
        val appConfig = SQLiteConfig().apply {
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            enforceForeignKeys(true)
            busyTimeout = 5_000
        }
        appConfig.newConnectionConfig()
        appDatasource = HikariDataSource(
            HikariConfig().apply {
                dataSource = SQLiteDataSource(appConfig).apply { url = appUrl }
                poolName = "DB app pool"
                maximumPoolSize = 1
            }
        )

        val offlineUrl = "jdbc:sqlite:${databaseDir}/$offlineFileName"
        val offlineWriteConfig = SQLiteConfig().apply {
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            enforceForeignKeys(true)
            transactionMode= SQLiteConfig.TransactionMode.IMMEDIATE
            busyTimeout = 5_000
        }
        offlineWriteDatasource = HikariDataSource(
            HikariConfig().apply {
                dataSource = SQLiteDataSource(offlineWriteConfig)
                    .apply { url = offlineUrl }
                poolName = "DB offline pool"
                maximumPoolSize = 1
            }
        )

        flywayMigrate(appDatasource, AppMigrations())
        flywayMigrate(offlineWriteDatasource, OfflineMigrations())

        // Built AFTER the migrations, and it has to be. Hikari opens
        // `minimumIdle` connections while its constructor runs, and a read-only
        // SQLite connection cannot create the file it is pointed at: on a first
        // launch, before the write pool has made the mirror,
        // `[SQLITE_CANTOPEN] Unable to open the database file` would come out of
        // this constructor and take the whole app down. Migrating first also
        // means the first read already sees the current schema.
        // Reads get their own connections, and that is the whole point of this
        // pool. WAL lets any number of readers run alongside the single writer,
        // but only if they are not the same connection: with everything on the
        // write pool (maximumPoolSize = 1, and IMMEDIATE on top), a list query
        // issued while a catalogue sync writes waits for the write transaction
        // to commit. Measured on a copy of the reference mirror, reads taken
        // while a writer was busy:
        //
        //   same connection as the writer   p50  56.9 ms   p95 182.8 ms
        //   its own connection (WAL)        p50   0.8 ms   p95   5.2 ms
        //   nobody writing                  p50   0.4 ms   p95   2.9 ms
        //
        // And it makes the Home screen's parallelism real rather than nominal:
        // its six shelves are launched with async/awaitAll and were re-serialised
        // on the one connection — 42.1 ms against 23.3 ms across four.
        //
        // maximumPoolSize is 3 and minimumIdle 1 on purpose. Each SQLite
        // connection owns its page cache, so the cache_size below is paid per
        // open connection; letting Hikari retire the extra ones after a minute
        // means the steady state is one connection and only a burst (Home, a
        // filter change) opens more.
        val offlineReadOnlyConfig = SQLiteConfig().apply {
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            enforceForeignKeys(true)
            setReadOnly(true)
            busyTimeout = 5_000
            // 8 MiB instead of SQLite's 2 MiB default. The mirror is a 75 MB
            // file and the queries that still walk it — a genre filter, a deep
            // OFFSET — were re-reading btree pages they had just evicted.
            // Measured after the V6 indexes, median of 15 runs:
            //
            //   genre filter, page      2 MiB 15.2 ms -> 8 MiB  4.1 ms
            //   Books tab, offset 9000  2 MiB 40.4 ms -> 8 MiB 22.5 ms
            //   library page 1          unchanged (the index already answers it)
            //
            // 16 MiB would take the second one to 7 ms, and 8 was chosen over it
            // because this is per connection: the win is worth 8 MB of the app's
            // ~95 MB, not 48. `temp_store = MEMORY` was measured too and changed
            // nothing, so it is not set.
            setCacheSize(-8_000)
        }
        offlineReadDatasource = HikariDataSource(
            HikariConfig().apply {
                dataSource = SQLiteDataSource(offlineReadOnlyConfig).apply { url = offlineUrl }
                poolName = "DB offline read pool"
                maximumPoolSize = 3
                minimumIdle = 1
                idleTimeout = 60_000
                // Not decoration: without it the pool dies in its constructor
                // and takes the app with it. Hikari stamps its own default onto
                // every connection it opens, and its default is read-write —
                // `PoolBase.setupConnection` calls `setReadOnly(false)` on a
                // connection xerial opened read-only, which xerial refuses with
                // "Cannot change read-only flag after establishing a
                // connection". Observed on the device, then reproduced against
                // HikariCP 2.4.13 and fixed by this line: the requested value
                // now matches the connection's, so the driver's no-op path is
                // taken. Writes stay refused by SQLite itself
                // ([SQLITE_READONLY]), which is checked, not assumed.
                isReadOnly = true
            }
        )

        app = Database.connect(appDatasource)
        offline = Database.connect(offlineWriteDatasource)
        offlineReadOnly = Database.connect(
            datasource = offlineReadDatasource,
            databaseConfig = DatabaseConfig { defaultReadOnly = true }
        )

        TransactionManager.defaultDatabase = app
    }

    fun close() {
        appDatasource.close()
        offlineWriteDatasource.close()
        offlineReadDatasource.close()
    }

    private fun flywayMigrate(datasource: DataSource, resourcesProvider: MigrationResourcesProvider) {
        Flyway(
            Flyway.configure()
                .loggers("slf4j")
                .dataSource(datasource)
                .resourceProvider(resourcesProvider)
                .javaMigrationClassProvider(resourcesProvider)
        ).migrate()
    }
}
