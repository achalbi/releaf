/*
 * SyncStatusPill.swift
 *
 * Reusable status pill for QuickInk's sync state. Mockup brief:
 *
 *   - Synced       coral-soft green dot · "Synced — moments ago"
 *   - Pending      warning orange dot · "N pending"
 *   - Syncing now  pulsing accent dot · "Syncing now…"
 *   - Offline      muted gray dot · "Offline — changes saved locally"
 *   - Failed       danger red dot · "Sync failed · tap to retry"
 *
 * Today the Home screen renders this pill from `SyncStateStore`'s
 * `pendingCount` + `lastFullSyncAt` only. The offline + failed
 * states are surfaced once the shared `:shared:sync` package adds
 * a `lastError: String?` field and a network monitor publishes
 * online/offline transitions. Until then, the pill renders the
 * synced/pending paths automatically and the failed/offline
 * states wait for their producers.
 */

import SwiftUI

/// Status states the pill can render. Each carries the data
/// needed to render its message.
public enum SyncPillState: Equatable {
    /// All known rows have been pushed to Drive successfully.
    case synced(lastSyncAt: String?)
    /// One or more rows are dirty and queued for the next pass.
    case pending(count: Int)
    /// A sync pass is in flight right now.
    case syncing
    /// No network connectivity. Edits queue locally; the pill
    /// surfaces the local-only state so the user knows their
    /// changes haven't propagated yet.
    case offline
    /// The most recent pass failed. Tapping should request an
    /// immediate retry — the parent supplies the action.
    case failed(message: String?)
}

public struct SyncStatusPill: View {
    public let state: SyncPillState
    public let onRetry: (() -> Void)?

    public init(state: SyncPillState, onRetry: (() -> Void)? = nil) {
        self.state = state
        self.onRetry = onRetry
    }

    public var body: some View {
        let content = HStack(spacing: QuickInkSpacing.s2) {
            dot
            Text(message)
                .font(QuickInkText.meta)
                .foregroundStyle(QuickInkColors.inkSoft)
            Spacer()
            if case .failed = state, onRetry != nil {
                Text("Retry")
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.accent)
                    .padding(.horizontal, QuickInkSpacing.s2)
            }
        }
        .padding(.horizontal, QuickInkSpacing.s3)
        .padding(.vertical, QuickInkSpacing.s2)
        .background(QuickInkColors.surface)
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )

        if case .failed = state, let onRetry = onRetry {
            Button(action: onRetry) { content }
                .buttonStyle(.plain)
        } else {
            content
        }
    }

    @ViewBuilder
    private var dot: some View {
        Circle()
            .fill(dotColor)
            .frame(width: 6, height: 6)
    }

    private var dotColor: Color {
        switch state {
        case .synced:  return QuickInkColors.success
        case .pending: return QuickInkColors.warning
        case .syncing: return QuickInkColors.accent
        case .offline: return QuickInkColors.muted
        case .failed:  return QuickInkColors.danger
        }
    }

    private var message: String {
        switch state {
        case .synced(let last):
            if let last = last { return "Synced — \(last)" }
            return "Not yet synced"
        case .pending(let count):
            return "\(count) pending"
        case .syncing:
            return "Syncing now…"
        case .offline:
            return "Offline — changes saved locally"
        case .failed(let message):
            return message ?? "Sync failed"
        }
    }
}

#if DEBUG
struct SyncStatusPill_Previews: PreviewProvider {
    static var previews: some View {
        VStack(spacing: 8) {
            SyncStatusPill(state: .synced(lastSyncAt: "2m ago"))
            SyncStatusPill(state: .pending(count: 3))
            SyncStatusPill(state: .syncing)
            SyncStatusPill(state: .offline)
            SyncStatusPill(state: .failed(message: "Network error"), onRetry: {})
        }
        .padding()
        .background(QuickInkColors.bg)
    }
}
#endif
