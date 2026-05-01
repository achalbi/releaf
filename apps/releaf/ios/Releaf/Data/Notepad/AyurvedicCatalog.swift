/*
 * AyurvedicCatalog.swift
 *
 * Mirror of `AyurvedicCatalog.kt` — same 90-plant pool, same
 * formatter contract, so a notepad entry seeded on Android renders
 * the same way on iOS once it round-trips through Drive sync.
 *
 * Repository-level rule (see `NotepadRepository.create`): both
 * `title` and `description` are auto-filled together as a pair from
 * the same plant — and ONLY when both were left blank by the
 * caller. If the caller supplied either, we keep their values
 * verbatim and skip the seed entirely; otherwise mixing an authored
 * title with an unrelated auto-description would produce mismatched
 * rows.
 *
 * Plant pool: the 90-entry `DailyPlants.all` list, generated from
 * `design-system/design-tokens.json` into the same `ReleafData`
 * module so this file can reach it without crossing layers. Selection
 * is **per-entry, deterministic on the entry id** (UUIDv7) — same id
 * always picks the same plant, and consecutive UUIDv7s differ in
 * their random tail so adjacent fresh entries land on different
 * plants. We use a custom djb2 hash because Swift's built-in
 * `String.hashValue` is salted per-process (SE-0206) — that's fine
 * for in-memory hash tables but would pick a different plant for
 * the same id on different launches if anyone re-derives the seed
 * later. Mirrors Android's `AyurvedicCatalog.forNewEntry(entryId:)`.
 */

import Foundation

public enum AyurvedicCatalog {

    /// Pick a plant deterministically from a stable id (UUIDv7). Same
    /// id → same plant; consecutive UUIDv7s differ in the random tail
    /// so adjacent fresh entries land on different rows without a
    /// global counter.
    public static func plant(forId id: String) -> DailyPlant {
        let pool = DailyPlants.all
        precondition(!pool.isEmpty, "DailyPlants.all is empty — check the token generator output")
        // Two-step modulo to handle the negative-hash case on
        // platforms where Int is signed (which it always is on Apple).
        let bucket = ((id.djb2Hash % pool.count) + pool.count) % pool.count
        return pool[bucket]
    }

    /// Render a plant as the seed description, paired with `name` in
    /// the row's `title` column. Format:
    /// `(<commonName>) <epithet> · <usedFor>`.
    ///
    /// Mirrors Android's `formatDescription` exactly so a seeded row
    /// reads identically on either platform.
    public static func description(for plant: DailyPlant) -> String {
        "(\(plant.commonName)) \(plant.epithet) · \(plant.usedFor)"
    }
}

private extension String {
    /// djb2 string hash. Used because Swift's built-in `hashValue`
    /// is salted per-process (SE-0206) and we want a stable bucket
    /// index across launches if anyone re-derives the seed.
    var djb2Hash: Int {
        var hash = 5381
        for byte in utf8 {
            hash = ((hash &<< 5) &+ hash) &+ Int(byte)
        }
        return hash
    }
}
