-- The four indexes the mirror was missing.
--
-- V1 ported the Komga schema with its foreign keys but almost none of its
-- indexes: 33 tables, 4 indexes, and not one on a join column. Most tables got
-- away with it because their lookup column leads their primary key, and SQLite
-- indexes a primary key for free. These four do not.
--
-- Measured on a rebuilt V1 schema holding the reference library — 7 000 series,
-- 10 561 books — with sqlite3:
--
--   books of a series, x2000 (what a scan does)     2.261 s -> 0.002 s
--   thumbnail of a book, x2000 (what a grid does)   0.929 s -> 0.002 s
--   book counts per series (once per sync)          0.008 s -> 0.002 s
--
-- EXPLAIN QUERY PLAN went from `SCAN b` to `SEARCH b USING COVERING INDEX`.
-- Displaying one cover cost two full scans of a 10 561-row table: the first
-- book of the series, then that book's thumbnail. That was the scrolling.
--
-- Deliberately NOT indexed: BOOK.library_id and SERIES.library_id. This app
-- serves one library, so `WHERE library_id = ?` matches every row and an index
-- on it can only slow the 10 561 inserts a full sync performs.
CREATE INDEX book__series_id_idx ON BOOK (series_id);
CREATE INDEX thumbnail_book__book_id_idx ON THUMBNAIL_BOOK (book_id);
CREATE INDEX thumbnail_series__series_id_idx ON THUMBNAIL_SERIES (series_id);

-- COLLECTION_SERIES has no primary key at all (V1 declared none), so both
-- directions needed one: reading a collection's members, and finding which
-- collections hold a series. Leading with collection_id makes the first index
-- covering for the membership read.
CREATE INDEX collection_series__collection_id_idx ON COLLECTION_SERIES (collection_id, series_id);
CREATE INDEX collection_series__series_id_idx ON COLLECTION_SERIES (series_id);
