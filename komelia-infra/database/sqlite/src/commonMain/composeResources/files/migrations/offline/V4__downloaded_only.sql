-- "Show only what is on disk", for a mirrored catalogue.
--
-- A catalogue mirror holds a row per book on the server, so the library looks
-- identical online and offline — twenty thousand covers, of which a handful
-- open without a connection. This switch narrows every list to the books whose
-- file is actually there.
--
-- Off by default: it is a view, not a mode, and turning it on for someone who
-- has downloaded nothing would show them an empty library.
--
-- SETTINGS is the offline settings table (one row, primary key `version`).
ALTER TABLE SETTINGS
    ADD COLUMN downloaded_only BOOLEAN NOT NULL DEFAULT 0;
