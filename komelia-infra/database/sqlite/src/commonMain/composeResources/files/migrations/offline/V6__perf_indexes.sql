-- The indexes the filters and the Books tab were missing.
--
-- V3 indexed the join columns and V5 the default series sort. What neither
-- covered: the columns the *filters* compare, and the column the Books tab
-- orders on. Two shapes were paying for it.
--
-- 1. Filters compare a lowercased column. `equalsIgnoreCase` compiles to
--    `LOWER(col) = ?` (SearchOperatorUtils.kt), and a plain index on `col`
--    cannot answer that — SQLite needs an index on the *expression*. Hence
--    `lower(...)` in the index definitions below. `series_id` trails so the
--    subquery reads the index alone and never touches the table.
--
-- 2. The Books tab sorts on BOOK_METADATA.title (LibraryBooksTabState.Sort
--    .TITLE_ASC, the default) and on BOOK.created_date for "Ajouts récents".
--    Neither had an index, so every page scanned 10 713 rows into a temporary
--    B-tree — the same fault V5 fixed on the series side.
--
-- Measured with sqlite3 on a byte-exact copy of the reference mirror
-- (75 112 448 bytes, 6 825 series, 10 713 books, 19 398 genre rows):
--
--   filter by genre (count + page, so paid twice)   19 ms -> 4 ms
--   Books tab, title A->Z, page 1                   23 ms -> 2 ms
--   Books tab, "Ajouts récents"                      7 ms -> 1 ms
--   filter by author                                 6 ms -> 2 ms
--   filter by tag                                   10 ms -> 2 ms
--   "downloaded only", page 1                       42 ms -> under the timer
--                                                   (with the rewrite below)
--
-- EXPLAIN QUERY PLAN goes from `SCAN <table>` to
-- `SEARCH <table> USING INDEX ... (<expr>=?)` in every case, and the plan was
-- checked against the exact SQL Exposed emits — qualified names, uppercase
-- LOWER — because an expression index only applies when the two expressions
-- match.
CREATE INDEX idx_series_genre_lower ON SERIES_METADATA_GENRE (lower(genre), series_id);
CREATE INDEX idx_book_agg_author_lower ON BOOK_METADATA_AGGREGATION_AUTHOR (lower(name), series_id);
CREATE INDEX idx_book_agg_tag_lower ON BOOK_METADATA_AGGREGATION_TAG (lower(tag), series_id);

CREATE INDEX idx_book_metadata_title ON BOOK_METADATA (title);
CREATE INDEX idx_book_created_date ON BOOK (created_date);

-- "Show only what is on disk", which was the worst query in the app: a
-- correlated EXISTS per series meant scanning all 6 825 of them until twenty
-- matched, and on this mirror exactly one series has a downloaded book — so it
-- scanned the whole catalogue, every page. Partial, because that is the whole
-- point: it indexes the handful of downloaded books and ignores the other ten
-- thousand, which also keeps a full sync's inserts cheap.
--
-- The index alone only halved it (42 ms -> 26 ms); the scan direction had to go
-- too. DownloadedOnly.kt now asks `series.id IN (SELECT series_id FROM BOOK
-- WHERE ...)`, which reads this index and nothing else.
CREATE INDEX idx_book_downloaded ON BOOK (series_id) WHERE local_file_modified_date > 0;

-- Deliberately NOT indexed: SERIES_METADATA_TAG. Calibre-Web publishes no
-- series-level tags over OPDS, so the table is empty on the reference mirror
-- and an index on it cannot make anything faster. The tag filter's cost was in
-- BOOK_METADATA_AGGREGATION_TAG, which is indexed above.
