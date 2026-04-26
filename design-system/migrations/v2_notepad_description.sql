-- v2_notepad_description.sql
--
-- Adds an optional free-text `description` column to `notepad_entries`.
-- Surfaces under the entry title on list rows and in the editor header
-- — same role the column plays on `notebooks` (see Android migration
-- 3 → 4). Nullable, no default: existing rows round-trip as NULL,
-- which the UI treats as "no description yet".
--
-- Per design-system/migrations/README.md, this file is append-only:
-- once shipped, edits go in v3_<slug>.sql. Both platform adapters pick
-- it up automatically (iOS by name, Android by directory order).

ALTER TABLE notepad_entries ADD COLUMN description TEXT;
