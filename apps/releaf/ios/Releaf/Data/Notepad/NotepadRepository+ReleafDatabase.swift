/*
 * NotepadRepository+ReleafDatabase.swift  (app target)
 *
 * `NotepadRepository` lives in the shared ReleafCoreNotes module, which
 * only knows about GRDB's `DatabaseQueue` — it can't reach the
 * app-target `ReleafDatabase` wrapper, and (per
 * scripts/phase4e-ios-notes-extract.sh) `AyurvedicCatalog` /
 * `DailyPlants` are intentionally Releaf-specific and stayed in this
 * target. This extension re-attaches both pieces:
 *
 *   - the old `init(database: ReleafDatabase = .shared)` shape so
 *     existing Releaf call sites (`NotepadRepository()`,
 *     `NotepadRepository(database: ReleafDatabase(inMemory: true))`)
 *     keep compiling unchanged;
 *   - an `AyurvedicCatalog`-backed `NotepadEntrySeeder` so a freshly
 *     created entry still auto-fills (title, description) with a
 *     deterministic plant pair — the behavior the repository had
 *     before extraction.
 *
 * As a convenience init on a `final class`, this just forwards to the
 * shared module's designated init.
 */

import Foundation
import ReleafCoreNotes

extension NotepadRepository {
    /// Convenience init that pulls the underlying `DatabaseQueue` out
    /// of the app-side `ReleafDatabase` hub and wires Releaf's
    /// Ayurvedic-plant seeder. The default — the process-wide
    /// `.shared` instance — keeps every existing zero-arg call site
    /// (`NotepadRepository()`) working post-extract.
    public convenience init(database: ReleafDatabase = .shared) {
        self.init(
            dbQueue: database.dbQueue,
            seeder: NotepadRepository.ayurvedicSeeder
        )
    }

    /// `NotepadEntrySeeder` backed by Releaf's `AyurvedicCatalog`.
    /// Same contract as the inline seeding the repository had before
    /// extraction — pick a plant deterministically off the entry id,
    /// title gets `plant.name`, description gets the formatted line.
    /// Lives here (not in `ReleafCoreNotes`) because the catalog +
    /// `DailyPlants` data is Releaf-only.
    public static let ayurvedicSeeder: NotepadEntrySeeder = { entryId in
        let plant = AyurvedicCatalog.plant(forId: entryId)
        return (
            title: plant.name,
            description: AyurvedicCatalog.description(for: plant)
        )
    }
}
