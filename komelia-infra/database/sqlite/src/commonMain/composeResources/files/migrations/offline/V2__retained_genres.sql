-- The genres the reader chose to keep.
--
-- A Calibre library imported through OPDS brings back every genre its owner
-- ever typed, and the filter list becomes a wall of four hundred entries where
-- twenty are ever used. This table names the twenty.
--
-- It starts empty, and empty means "keep them all": an upgrade must not hide a
-- genre from someone who never asked for a list.
CREATE TABLE RETAINED_GENRE
(
    genre TEXT NOT NULL PRIMARY KEY
);
