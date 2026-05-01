/*
 * Placeholder.swift
 *
 * Umbrella product. Once the other targets ship real contents, this
 * file is replaced by re-exports of the public types from each child
 * target, so consumers (Releaf) can `import ReleafCoreFeatures` and
 * get everything in one shot.
 *
 * QuickInk does NOT depend on this umbrella — it links the individual
 * products it needs explicitly, to keep its dependency graph honest
 * and surface unintentional coupling early.
 *
 * Delete + replace in the same PR that lands the umbrella re-exports.
 */

import Foundation

@available(*, unavailable, message: "ReleafCoreFeatures umbrella is in skeleton state — see PR #4")
internal enum _ReleafCoreFeaturesPlaceholder {}
