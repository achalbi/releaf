// Moved to `ios/Releaf/Data/Notepad/DailyPlants.generated.swift`.
//
// The DailyPlant type and the DailyPlants enum are now emitted from
// `design-system/design-tokens.json` by the token generator into
// `ReleafData` so the notepad seeding flow (which lives in the
// data layer) can pick from the same pool. Features that previously
// imported `ReleafDesignSystem` to read the type still resolve the
// symbol — they also import `ReleafData`, and Swift looks across
// every imported module when resolving names.
//
// This stub is kept on disk because the build sandbox in some
// environments doesn't allow deletion of source files. It contributes
// no compiled code. Safe to delete from a developer machine when
// convenient.
