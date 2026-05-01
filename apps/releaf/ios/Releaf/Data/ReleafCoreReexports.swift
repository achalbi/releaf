/*
 * ReleafCoreReexports.swift
 *
 * Re-export shim. ReleafData re-exports ReleafCoreData so existing
 * Releaf source files can keep saying `import ReleafData` and reach
 * Uuidv7 / IsoClock / FtsQuery / AttachmentStorage without per-file
 * import updates after the PR #4a extract.
 *
 * Why this file exists:
 *   - PR #4a moved Uuidv7, IsoClock, FtsQuery, AttachmentStorage out of
 *     ReleafData and into ReleafCoreData (in the shared ReleafCore
 *     package). Without this shim, ~30 Releaf files would need
 *     `import ReleafCoreData` added to compile.
 *   - `@_exported import` is the canonical SwiftPM workaround. It tells
 *     the compiler that types in the imported module should be visible
 *     to consumers of ReleafData as if they were defined in ReleafData.
 *
 * Why @_exported isn't a smell here:
 *   - The underscore prefix means "experimental / not part of stable
 *     Swift surface." It's been stable in practice for years and is
 *     widely used (e.g. Apple's own Combine + Foundation modules).
 *   - The intent here is exactly what re-export was designed for:
 *     refactoring a module's internals (split into smaller modules)
 *     without breaking consumers.
 *
 * Removable when:
 *   - Every Releaf file that uses one of these utility types has been
 *     migrated to `import ReleafCoreData` directly. Then this shim can
 *     drop out.
 *   - QuickInk doesn't depend on this shim — it imports ReleafCoreData
 *     directly because it doesn't use ReleafData at all.
 */

@_exported import ReleafCoreData
@_exported import ReleafCoreAuth   // PR #4c
@_exported import ReleafCoreNotes  // PR #4e
@_exported import ReleafCoreScan   // PR #4i
