-- =============================================================
-- QuickInk — v3_capture_drive_binaries.sql
--
-- Adds two columns to `captures` so each capture can track the
-- Drive file id of its uploaded binary attachments:
--
--   pdf_drive_file_id      Drive file id of the PDF (one per capture)
--   preview_drive_file_id  Drive file id of the first-page JPEG
--
-- Both nullable. NULL = "not uploaded yet" (or "never been
-- uploaded because the local file no longer exists").
--
-- Why two columns instead of one JSON blob:
--   Each binary lives at its own Drive path. Tombstone-cascade and
--   re-upload paths need to address them individually. Plain
--   columns avoid the JSON-in-SQLite read-modify-write churn.
--
-- Why columns on `captures` rather than a join table:
--   Strict 1:1 relationship per capture — a capture has exactly
--   one PDF and exactly one preview. A side table would buy us
--   nothing here.
-- =============================================================

ALTER TABLE captures ADD COLUMN pdf_drive_file_id TEXT;
ALTER TABLE captures ADD COLUMN preview_drive_file_id TEXT;
