-- Indexes for the Books tab's filter panel.
--
-- The panel added three query shapes the mirror had never been asked for, and
-- two of them were full scans. Measured on the reference catalogue (10 713
-- books, 28 935 tag rows, 11 129 author rows), page 1 of 20 sorted by title,
-- median of 15 runs with an 8 MB page cache:
--
--   filter by tag        8.6 ms -> 2.1 ms   (SCAN BOOK_METADATA_TAG -> SEARCH)
--   filter by author     2.1 ms -> 0.1 ms   (SCAN BOOK_METADATA_AUTHOR -> SEARCH)
--   sort by release date 16.9 ms -> 0.02 ms (TEMP B-TREE -> covering index)
--                        max 215.9 ms -> 0.15 ms
--
-- The first two mirror V6, which did the same for the per-series aggregation
-- tables: the condition builder compares with equalsIgnoreCase, so the index
-- has to be on lower(), and the id is carried along to keep the subquery
-- covering.
--
-- The release-date index also serves the year filter, which was already cheap
-- (0.1 ms) because the LIMIT stops the title scan early.
--
-- Filtering by read status needs nothing: READ_PROGRESS holds two rows.
CREATE INDEX idx_book_tag_lower ON BOOK_METADATA_TAG (lower(tag), book_id);
CREATE INDEX idx_book_author_lower ON BOOK_METADATA_AUTHOR (lower(name), book_id);
CREATE INDEX idx_book_metadata_release_date ON BOOK_METADATA (release_date, book_id);
