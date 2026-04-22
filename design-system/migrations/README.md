# Migrations — shared source of truth

Single `.sql` per schema version. Both iOS (GRDB) and Android (Room) read from this directory; neither platform owns a second copy.

---

## Why this exists

The v2 brief (`PROMPT.md` at repo root) mandates "shared migration numbering" — `v<N>` on iOS must be *exactly* `v<N>` on Android, forever. The proposal accepted in `docs/OPEN_QUESTIONS.md` §3 is: one canonical SQL file per version, consumed verbatim by thin platform adapters, enforced by a CI parity check.

Anything here is the schema. If a platform ships a table definition that isn't in this directory, that's a bug.

---

## File naming

```
vN_<slug>.sql
```

- `N` — monotonically increasing integer, starts at 1, no gaps.
- `<slug>` — short snake_case description. Free-form, for humans.
- One file per migration. Never edit a merged migration; add a new `v(N+1)_<slug>.sql` instead.

Examples:

```
v1_initial.sql            ← seed schema (this v1)
v2_add_capture_caption.sql
v3_add_project_archived.sql
```

---

## Authoring rules

1. **Append-only.** Once `vN_<slug>.sql` has shipped in a build that a user has installed, it is frozen. Changes go in `v(N+1)`.
2. **No platform forks.** Tokenizer SQL, triggers, defaults — everything SQLite understands lives in this file. Platform-specific bits (Kotlin/Swift code) stay in the adapter layer, not in SQL.
3. **One transaction-shaped unit per file.** The whole file is assumed to run inside the platform's migration transaction. Don't emit `BEGIN` / `COMMIT`.
4. **No destructive ops without a manifest major-version bump.** See `docs/OPEN_QUESTIONS.md` §5. `DROP TABLE`, `DROP COLUMN`, or column rename → major bump.
5. **Every new column that needs to be FTS-indexed must ship with its triggers.** The FTS5 virtual table definition + all three triggers (AFTER INSERT / UPDATE / DELETE) in the same file.
6. **Verify before PR.** Run the local check (below) — it must produce no output.

---

## Local verification

```bash
# Syntax check only — parses and creates every object in :memory:.
sqlite3 :memory: < design-system/migrations/v1_initial.sql
```

No output = clean parse. An error pinpoints the failing line.

For integration-level checks (CHECK constraints fire, FTS5 triggers populate the index, soft-delete removes rows from FTS), write a small scratch script or run the per-platform migration test suites (see "Platform adapters" below).

---

## What `v1_initial.sql` defines

Produced objects, as of this migration:

| Object kind              | Count | Examples                                                     |
| ------------------------ | ----- | ------------------------------------------------------------ |
| Base tables              | 20    | `projects`, `notebooks`, `captures`, `tasks`, `sync_state`   |
| FTS5 virtual tables      | 4     | `fts_notepad_notes`, `fts_capture_text`, `fts_page_notes`, `fts_task_title` |
| FTS5 sync triggers       | 12    | 3 per FTS-backed base table (AI / AU / AD)                   |
| User-defined indexes     | 37    | Per-parent position indexes, `dirty = 1` partial indexes, tombstone partials |

Encoded invariants (from `docs/OPEN_QUESTIONS.md`):

- **UUIDv7 PKs** as `TEXT` — app layer generates.
- **ISO-8601 UTC timestamps** as `TEXT`, default `strftime('%Y-%m-%dT%H:%M:%fZ', 'now')`.
- **Soft delete** via `deleted_at TEXT NULL`; queries filter `WHERE deleted_at IS NULL`.
- **Exactly-one-parent CHECK** on `captures`, `todo_lists`, `reference_links` (§1).
- **`user_id` scoping** only on `daily_logs`, `notepad_entries`, `user_settings` (everything else is scoped transitively through the parent).
- **Sparse integer positions** default `1024`, increments of `1024` (§11).
- **FTS5 tokenizer** uniform across all virtual tables: `unicode61 remove_diacritics 2`.
- **FTS sync triggers** guard on `deleted_at IS NULL AND <column> <> ''` — soft-deleted / empty rows never enter the index.

---

## Platform adapters

### iOS — GRDB

```swift
// Releaf/Persistence/Migrations.swift
import GRDB

enum Migrations {
    static func register(on migrator: inout DatabaseMigrator) {
        let dir = Bundle.main.resourceURL!.appendingPathComponent("migrations")
        for url in try! FileManager.default
            .contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
            .sorted(by: { $0.lastPathComponent < $1.lastPathComponent })
            .filter({ $0.pathExtension == "sql" })
        {
            let name = url.deletingPathExtension().lastPathComponent  // "v1_initial"
            let sql  = try! String(contentsOf: url, encoding: .utf8)
            migrator.registerMigration(name) { db in
                try db.execute(sql: sql)
            }
        }
    }
}
```

`design-system/migrations/*.sql` is copied into the app bundle's `migrations/` resource folder by a build-phase copy step.

### Android — Room

```kotlin
// app/src/main/java/app/releaf/mobile/db/Migrations.kt
package app.releaf.mobile.db

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

fun migrationsFromAssets(ctx: Context): Array<Migration> {
    val names = ctx.assets.list("migrations")!!
        .filter { it.endsWith(".sql") }
        .sorted()                                 // v1_..., v2_..., lexicographic matches numeric when zero-padded or single-digit within a single release
    return names.mapIndexed { idx, fname ->
        val from = idx                            // 0 → v1, 1 → v2, …
        val to   = idx + 1
        object : Migration(from, to) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ctx.assets.open("migrations/$fname").bufferedReader().use { r ->
                    // Room wraps this call in a transaction. Execute the whole
                    // file as a single script — SQL statements are separated
                    // by `;` at end-of-line. Room's execSQL takes one statement
                    // at a time, so split on `;\n` after stripping comments.
                    val script = r.readText()
                    splitSqlStatements(script).forEach { stmt -> db.execSQL(stmt) }
                }
            }
        }
    }.toTypedArray()
}
```

`design-system/migrations/*.sql` is mirrored into `app/src/main/assets/migrations/` by a Gradle `sync` task.

> **Note:** Room's `Migration(from, to)` uses integer versions starting at 1. The adapter maps file index `i` → `Migration(i, i+1)`. The `@Database(version = N)` annotation on the `RoomDatabase` subclass must equal the number of files here.

### CI parity check

`scripts/check-migrations.sh` (to be added in the next deliverable) enforces:

```
set(files in design-system/migrations/*.sql)  ==  set(migrations registered by iOS adapter)
set(files in design-system/migrations/*.sql)  ==  set(migrations registered by Android adapter)
```

Failure means someone added a migration on one platform and forgot the other, or edited an existing migration in place.

---

## When a migration lands

1. Author creates `v(N+1)_<slug>.sql` in this directory.
2. `sqlite3 :memory: < v(N+1)_<slug>.sql` succeeds locally.
3. Android adapter auto-picks it up (asset directory scan). Bump `@Database(version = N+1)`.
4. iOS adapter auto-picks it up (resource directory scan). No version constant to bump — GRDB tracks by migration name.
5. Integration tests on both platforms run against the new schema.
6. CI parity check passes.
7. If the change is user-visible via backup/restore, also bump `docs/DRIVE_SCHEMA.md` manifest version per §5.
