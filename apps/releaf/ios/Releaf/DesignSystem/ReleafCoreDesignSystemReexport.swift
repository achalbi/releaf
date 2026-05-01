/*
 * ReleafCoreDesignSystemReexport.swift
 *
 * Re-export shim. ReleafDesignSystem re-exports ReleafCoreDesignSystem
 * so existing Releaf source files can keep saying
 * `import ReleafDesignSystem` and reach the moved design tokens
 * (AppColors, AppText, AppSpacing, AppRadius, AppShadow, AccentPalette,
 * AppMetrics, plus the shared component library: AppButton, Card,
 * DotGridBackground, Breadcrumbs, PageHeaderControls) without per-file
 * import updates after the PR #4g extract.
 *
 * Same pattern as Releaf/Data/ReleafCoreReexports.swift (PR #4a) — see
 * that file for the broader rationale.
 *
 * Removable once every Releaf file that uses the moved types has been
 * migrated to `import ReleafCoreDesignSystem` directly. QuickInk
 * doesn't depend on this shim.
 */

@_exported import ReleafCoreDesignSystem
