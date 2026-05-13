/*
 * SmartCollectionScreen.swift
 *
 * Workspace v1 Screen 3 — rule-based saved view. Header eyebrow +
 * name + "auto-curated rule" hero card + doc list driven by the
 * SmartCollectionRule evaluator. Editor UI is out of v1.
 *
 * Mirror of `SmartCollectionScreen.kt` in QuickInk's Android target.
 */

import SwiftUI
import Combine
import GRDB
import ReleafCoreDesignSystem

@MainActor
public struct SmartCollectionScreen: View {

    public let collectionId: String
    public let userId: String
    public let onBack: () -> Void
    public let onOpenCapture: (CaptureSummary) -> Void
    public let onOpenSearch: () -> Void
    public let onHome: () -> Void
    public let onWorkspace: () -> Void
    public let onScan: () -> Void
    public let onSettings: () -> Void

    @State private var collection: SmartCollectionEntity? = nil
    @State private var captures: [CaptureSummary] = []
    @State private var tagNamesById: [String: String] = [:]
    @State private var folderNamesById: [String: String] = [:]
    @State private var collectionCancellable: AnyCancellable? = nil
    @State private var capturesCancellable: AnyCancellable? = nil

    public init(
        collectionId: String,
        userId: String,
        onBack: @escaping () -> Void,
        onOpenCapture: @escaping (CaptureSummary) -> Void,
        onOpenSearch: @escaping () -> Void,
        onHome: @escaping () -> Void,
        onWorkspace: @escaping () -> Void,
        onScan: @escaping () -> Void,
        onSettings: @escaping () -> Void
    ) {
        self.collectionId = collectionId
        self.userId = userId
        self.onBack = onBack
        self.onOpenCapture = onOpenCapture
        self.onOpenSearch = onOpenSearch
        self.onHome = onHome
        self.onWorkspace = onWorkspace
        self.onScan = onScan
        self.onSettings = onSettings
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
                .padding(.horizontal, QuickInkSpacing.s2)
                .padding(.top, QuickInkSpacing.s2)

            if let coll = collection {
                ruleHero(coll)
                    .padding(.horizontal, QuickInkSpacing.s4)
                    .padding(.top, QuickInkSpacing.s2)
            }

            Text("Documents")
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(QuickInkColors.ink)
                .padding(.horizontal, QuickInkSpacing.s4)
                .padding(.top, QuickInkSpacing.s3)

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if captures.isEmpty, collection != nil {
                        Text("No captures match this rule yet.")
                            .font(.system(size: 12.5))
                            .foregroundColor(QuickInkColors.muted)
                            .padding(QuickInkSpacing.s4)
                    } else {
                        ForEach(captures) { cap in
                            docRow(cap)
                        }
                    }
                }
                .padding(.horizontal, QuickInkSpacing.s4)
            }
        }
        .background(QuickInkColors.bg)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            QuickInkBottomNavBar(
                activeTab:   .workspace,
                onHome:      onHome,
                onWorkspace: onWorkspace,
                onScan:      onScan,
                onSearch:    onOpenSearch,
                onSettings:  onSettings
            )
        }
        .task { await initialLoad() }
    }

    // MARK: - Pieces

    private var header: some View {
        HStack(spacing: QuickInkSpacing.s2) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(QuickInkColors.ink)
                    .frame(width: 36, height: 36)
            }
            ZStack {
                RoundedRectangle(cornerRadius: 7)
                    .fill(QuickInkColors.accentSoft)
                    .frame(width: 24, height: 24)
                Image(systemName: "sparkles")
                    .font(.system(size: 12))
                    .foregroundColor(QuickInkColors.accentDeep)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text("SMART COLLECTION")
                    .font(.system(size: 10, weight: .bold))
                    .tracking(1.2)
                    .foregroundColor(QuickInkColors.accentDeep)
                Text(collection?.name ?? "…")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(QuickInkColors.ink)
            }
            Spacer()
        }
    }

    private func ruleHero(_ collection: SmartCollectionEntity) -> some View {
        let clauses = SmartCollectionRule.decode(collection.ruleJson)
        return VStack(alignment: .leading, spacing: 0) {
            HStack {
                Image(systemName: "sparkles")
                    .font(.system(size: 12))
                    .foregroundColor(QuickInkColors.accent)
                Text("AUTO-CURATED RULE")
                    .font(.system(size: 10, weight: .bold))
                    .tracking(1.2)
                    .foregroundColor(QuickInkColors.accentDeep)
            }
            HStack(spacing: 4) {
                ForEach(Array(clauses.enumerated()), id: \.offset) { idx, clause in
                    if idx > 0 {
                        Text("·")
                            .font(.system(size: 11))
                            .foregroundColor(QuickInkColors.muted)
                    }
                    ruleChip(clause: clause)
                }
                if clauses.isEmpty {
                    Text("No clauses")
                        .font(.system(size: 12))
                        .italic()
                        .foregroundColor(QuickInkColors.muted)
                }
            }
            .padding(.top, QuickInkSpacing.s2)

            Divider().background(QuickInkColors.accentSoft)
                .padding(.top, QuickInkSpacing.s3)
                .padding(.bottom, QuickInkSpacing.s2)

            HStack {
                Text("\(captures.count)")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundColor(QuickInkColors.ink)
                Text("DOCUMENTS")
                    .font(.system(size: 10, weight: .bold))
                    .tracking(1.2)
                    .foregroundColor(QuickInkColors.muted)
                Spacer()
            }
        }
        .padding(QuickInkSpacing.s3)
        .background(QuickInkColors.accentSoft.opacity(0.4), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(QuickInkColors.accentSoft, lineWidth: 1))
    }

    private func ruleChip(clause: RuleClause) -> some View {
        let label: String
        switch clause {
        case .folderIs(let id):   label = "in: \(folderNamesById[id] ?? "folder")"
        case .tagIs(let id):      label = "#\(tagNamesById[id] ?? "tag")"
        case .tagIsNot(let id):   label = "not #\(tagNamesById[id] ?? "tag")"
        case .dateRange(_, let p):
            switch p {
            case "this_week":    label = "this week"
            case "this_month":   label = "this month"
            case "last_30_days": label = "last 30 days"
            case "this_quarter": label = "this quarter"
            default:             label = p
            }
        case .sourceIs(let v):       label = "source: \(v)"
        case .hasHandwriting(let v): label = v ? "handwritten" : "not handwritten"
        case .hasSignature(let v):   label = v ? "has signature" : "no signature"
        case .hasOcrText(let v):     label = v ? "has OCR text" : "no OCR text"
        }
        return Text(label)
            .font(.system(size: 11, weight: .semibold))
            .foregroundColor(QuickInkColors.accentDeep)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(QuickInkColors.surface, in: RoundedRectangle(cornerRadius: 4))
            .overlay(RoundedRectangle(cornerRadius: 4).stroke(QuickInkColors.accentSoft, lineWidth: 1))
    }

    private func docRow(_ cap: CaptureSummary) -> some View {
        let title = cap.title?.isEmpty == false ? cap.title! :
            (cap.category?.isEmpty == false ? cap.category! : "Untitled scan")
        return Button(action: { onOpenCapture(cap) }) {
            HStack(alignment: .top, spacing: QuickInkSpacing.s3) {
                RoundedRectangle(cornerRadius: 7)
                    .fill(QuickInkColors.borderSoft)
                    .frame(width: 44, height: 56)
                VStack(alignment: .leading, spacing: 5) {
                    Text(title)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(QuickInkColors.ink)
                        .lineLimit(1)
                    Text("\(cap.pageCount) \(cap.pageCount == 1 ? "page" : "pages") · \(String(cap.createdAt.prefix(10)))")
                        .font(.system(size: 11.5))
                        .foregroundColor(QuickInkColors.muted)
                }
                Spacer()
            }
            .padding(.vertical, 13)
            .overlay(
                Rectangle().fill(QuickInkColors.borderSoft).frame(height: 1),
                alignment: .bottom,
            )
        }
        .buttonStyle(.plain)
    }

    private func initialLoad() async {
        let repo = SmartCollectionRepository()
        let coll = (try? await repo.findById(collectionId)) ?? nil
        await MainActor.run { self.collection = coll }
        guard let coll else { return }

        capturesCancellable?.cancel()
        capturesCancellable = repo
            .observeMatchingCaptures(userId: userId, collection: coll)
            .replaceError(with: [])
            .receive(on: DispatchQueue.main)
            .sink { self.captures = $0 }

        // Resolve tag / folder ids → names for the chip labels.
        let dbQueue = QuickInkDatabase.shared.dbQueue
        let resolvedTagNames: [String: String]
            = (try? await dbQueue.read { db in
                try TagEntity.fetchAll(db).reduce(into: [String: String]()) { dict, t in
                    dict[t.id] = t.name
                }
            }) ?? [:]
        let resolvedFolderNames: [String: String]
            = (try? await dbQueue.read { db in
                try FolderEntity.fetchAll(db).reduce(into: [String: String]()) { dict, f in
                    dict[f.id] = f.name
                }
            }) ?? [:]
        await MainActor.run {
            self.tagNamesById    = resolvedTagNames
            self.folderNamesById = resolvedFolderNames
        }
    }
}
