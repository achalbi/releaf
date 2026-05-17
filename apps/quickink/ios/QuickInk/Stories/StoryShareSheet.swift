/*
 * StoryShareSheet.swift
 *
 * Stories Phase 4 — the share sheet (§7.5 of the v3 mockup). Lives
 * inside an iOS `.sheet` presentation from the editor. Lays out:
 *
 *   ┌─────────────────────────────────┐
 *   │ —— handle ——                    │
 *   │ Share Tokyo, May 2026           │
 *   │ ┌────────────────────────────┐  │
 *   │ │ [thumb] Public link preview│  │
 *   │ │         15 items · ~3 min  │  │
 *   │ └────────────────────────────┘  │
 *   │ ┌──────┐ ┌──────┐               │
 *   │ │ PDF  │ │ Image│               │
 *   │ └──────┘ └──────┘               │
 *   │ ┌──────┐ ┌──────┐               │
 *   │ │ App  │ │ Link │ ← active when │
 *   │ └──────┘ └──────┘   share_mode  │
 *   │ ┌── dashed coral border ──┐    │   = publicLink
 *   │ │ quickink.app/s/xxxxx Copy│    │
 *   │ │ require a passcode  + add│    │
 *   │ └──────────────────────────┘    │
 *   └─────────────────────────────────┘
 *
 * Active actions in v3 Phase 4:
 *   - Save as PDF      → renders PDF, presents UIActivityViewController
 *   - Save as image    → renders PNG, presents UIActivityViewController
 *   - Share via app    → renders PDF, presents UIActivityViewController
 *   - Public link      → stub toast ("Phase 6 ships the backend")
 *
 * Mirror of Android `StoryShareSheet.kt`.
 */

import GRDB
import SwiftUI
import UIKit

struct StoryShareSheet: View {

    let storyId: String
    let userId: String
    var onDismiss: () -> Void

    @StateObject private var vm: StoryEditorViewModel
    @StateObject private var settings = SettingsState()
    @State private var presenting: URL? = nil
    @State private var rendering: Bool = false
    @State private var toast: String? = nil
    @State private var showingPublishConfirm: Bool = false
    @State private var showingUnpublishConfirm: Bool = false
    @State private var publishing: Bool = false

    private let repository = StoryRepository()

    init(storyId: String, userId: String, onDismiss: @escaping () -> Void) {
        self.storyId   = storyId
        self.userId    = userId
        self.onDismiss = onDismiss
        _vm = StateObject(wrappedValue: StoryEditorViewModel(storyId: storyId, userId: userId))
    }

    var body: some View {
        VStack(spacing: 0) {
            handle
            header
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
                    previewRow
                    optionsGrid
                    if isPublicLinkActive {
                        linkBox
                    }
                    if rendering {
                        renderingIndicator
                    }
                }
                .padding(.horizontal, QuickInkSpacing.s4)
                .padding(.bottom, QuickInkSpacing.s6)
            }
            if let message = toast {
                Text(message)
                    .font(QuickInkText.bodyItalic)
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .padding(.horizontal, QuickInkSpacing.s4)
                    .padding(.vertical, QuickInkSpacing.s2)
            }
        }
        .background(QuickInkColors.surface)
        .task { vm.start() }
        .sheet(item: Binding(
            get: { presenting.map { ShareItem(url: $0) } },
            set: { newValue in presenting = newValue?.url }
        )) { item in
            ActivityView(activityItems: [item.url])
        }
        .alert("Publish this story?", isPresented: $showingPublishConfirm) {
            Button("Cancel", role: .cancel) {}
            Button("Publish") { doPublish() }
        } message: {
            Text("Anyone with the link will be able to read this story. You can stop sharing at any time.")
        }
        .alert("Stop sharing this story?", isPresented: $showingUnpublishConfirm) {
            Button("Cancel", role: .cancel) {}
            Button("Stop sharing", role: .destructive) { doUnpublish() }
        } message: {
            Text("The public link will go offline. People who saved the URL won't be able to open it anymore.")
        }
    }

    // MARK: - Pieces

    private var handle: some View {
        Capsule()
            .fill(QuickInkColors.border)
            .frame(width: 38, height: 4)
            .padding(.top, QuickInkSpacing.s2)
            .padding(.bottom, QuickInkSpacing.s3)
    }

    private var header: some View {
        Text("Share \(vm.story?.title ?? "story")")
            .font(QuickInkText.editorial)
            .foregroundStyle(QuickInkColors.ink)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, QuickInkSpacing.s4)
            .padding(.bottom, QuickInkSpacing.s2)
    }

    private var previewRow: some View {
        HStack(spacing: QuickInkSpacing.s3) {
            ZStack(alignment: .bottomLeading) {
                LinearGradient(
                    colors: [QuickInkColors.paper1, QuickInkColors.paper3],
                    startPoint: .topLeading,
                    endPoint:   .bottomTrailing
                )
                .frame(width: 48, height: 56)
                .clipShape(RoundedRectangle(cornerRadius: 4))
                Text((vm.story?.title.prefix(5)).map(String.init) ?? "Story")
                    .font(QuickInkFont.serif(6, weight: .medium))
                    .foregroundStyle(QuickInkColors.ink)
                    .padding(.horizontal, 4)
                    .padding(.bottom, 3)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text("Public link preview")
                    .font(QuickInkFont.serif(13, weight: .medium))
                    .foregroundStyle(QuickInkColors.ink)
                Text("\(vm.items.count) items · cream cover with title overlay · ~\(estimatedReadMinutes) min read")
                    .font(QuickInkText.bodyItalic)
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .lineLimit(2)
            }
            Spacer(minLength: 0)
            Text("Change")
                .font(.system(size: 10.5, weight: .medium))
                .foregroundStyle(QuickInkColors.accent)
        }
        .padding(QuickInkSpacing.s2 + 2)
        .background(QuickInkColors.bg)
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(QuickInkColors.border, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private var optionsGrid: some View {
        let cols = [GridItem(.flexible(), spacing: 9), GridItem(.flexible(), spacing: 9)]
        return LazyVGrid(columns: cols, spacing: 9) {
            optionCard(icon: "doc.fill", title: "Save as PDF", subtitle: "Editorial layout, ready to print.", active: false) {
                doExport(asPdf: true)
            }
            optionCard(icon: "photo.fill", title: "Save as image", subtitle: "A tall PNG for chats or stories.", active: false) {
                doExport(asPdf: false)
            }
            optionCard(icon: "arrow.up.right", title: "Share via app", subtitle: "WhatsApp, Messages, Mail.", active: false) {
                doExport(asPdf: true)
            }
            optionCard(icon: "link", title: "Public link", subtitle: isPublicLinkActive ? "A page anyone can open." : "Generate a public page.", active: isPublicLinkActive) {
                if isPublicLinkActive {
                    flashToast("Public link already live — see the box below.")
                } else if !settings.experimentalPublicLinksEnabled {
                    flashToast("Turn on Experimental → Public link sharing in Settings.")
                } else {
                    showingPublishConfirm = true
                }
            }
        }
    }

    private func optionCard(icon: String, title: String, subtitle: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 6) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(active ? QuickInkColors.accent : QuickInkColors.borderSoft)
                        .frame(width: 28, height: 28)
                    Image(systemName: icon)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(active ? QuickInkColors.textOnAccent : QuickInkColors.inkSoft)
                }
                Text(title)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(QuickInkColors.ink)
                Text(subtitle)
                    .font(.system(size: 10.5))
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(11)
            .background(QuickInkColors.bg)
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(active ? QuickInkColors.accent : QuickInkColors.border, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var linkBox: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(linkText)
                    .font(.system(size: 11))
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .lineLimit(1)
                Spacer()
                Button("Copy") {
                    UIPasteboard.general.string = linkText
                    flashToast("Link copied")
                }
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(QuickInkColors.accent)
                .buttonStyle(.plain)
            }
            Rectangle().fill(QuickInkColors.accent.opacity(0.4)).frame(height: 0.5)
            HStack {
                Text("require a passcode")
                    .font(QuickInkText.bodyItalic)
                    .foregroundStyle(QuickInkColors.inkSoft)
                Spacer()
                Button("+ add") { flashToast("Passcode protection ships in v1.1.") }
                    .font(.system(size: 10.5, weight: .medium))
                    .foregroundStyle(QuickInkColors.accent)
                    .buttonStyle(.plain)
            }
            HStack {
                Spacer()
                Button("Stop sharing") { showingUnpublishConfirm = true }
                    .font(.system(size: 10.5, weight: .medium))
                    .foregroundStyle(QuickInkColors.accent)
                    .buttonStyle(.plain)
            }
        }
        .padding(QuickInkSpacing.s2 + 2)
        .background(QuickInkColors.borderSoft)
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(QuickInkColors.accent, style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
        )
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .padding(.top, QuickInkSpacing.s2)
    }

    private var renderingIndicator: some View {
        HStack(spacing: 8) {
            ProgressView()
            Text("Rendering…")
                .font(QuickInkText.bodyItalic)
                .foregroundStyle(QuickInkColors.inkSoft)
        }
        .padding(.vertical, QuickInkSpacing.s2)
    }

    // MARK: - Action

    private func doExport(asPdf: Bool) {
        guard let story = vm.story else {
            flashToast("Story not ready yet — try again in a sec.")
            return
        }
        let items = vm.items
        rendering = true
        Task.detached(priority: .userInitiated) {
            // Resolve refId → preview_uri for each capture-backed
            // item so the exporters can embed real bitmaps instead
            // of cream-box placeholders.
            let previewUris = await Self.loadPreviewUris(forItems: items)
            let result: URL? = {
                do {
                    return asPdf
                        ? try StoryPdfExporter.export(story: story, items: items, previewUris: previewUris)
                        : try StoryImageExporter.export(story: story, items: items, previewUris: previewUris)
                } catch {
                    return nil
                }
            }()
            await MainActor.run {
                rendering = false
                if let url = result {
                    presenting = url
                } else {
                    flashToast("Couldn't render — please try again.")
                }
            }
        }
    }

    /// Look up `preview_uri` for every distinct `item.refId` that
    /// the exporter might need. One DB read regardless of item
    /// count; misses are silently absent from the returned map.
    private static func loadPreviewUris(forItems items: [StoryItem]) async -> [String: String] {
        let refIds = Set(items.compactMap { item -> String? in
            switch item.kind {
            case StoryItem.Kind.photo.rawValue,
                 StoryItem.Kind.document.rawValue,
                 StoryItem.Kind.note.rawValue:
                return item.refId
            default:
                return nil
            }
        })
        guard !refIds.isEmpty else { return [:] }
        return await Task.detached(priority: .userInitiated) {
            (try? await QuickInkDatabase.shared.dbQueue.read { db -> [String: String] in
                let placeholders = Array(repeating: "?", count: refIds.count).joined(separator: ",")
                let rows = try Row.fetchAll(
                    db,
                    sql: "SELECT id, preview_uri FROM captures WHERE id IN (\(placeholders)) AND deleted_at IS NULL",
                    arguments: StatementArguments(Array(refIds))
                )
                var out: [String: String] = [:]
                for row in rows {
                    if let uri = row["preview_uri"] as String?, !uri.isEmpty {
                        out[row["id"]] = uri
                    }
                }
                return out
            }) ?? [:]
        }.value
    }

    // MARK: - Publish

    private func doPublish() {
        guard let story = vm.story else {
            flashToast("Story not ready yet — try again in a sec.")
            return
        }
        let items = vm.items
        publishing = true
        Task {
            do {
                let result = try await StoryPublisher.publish(story: story, items: items)
                try await repository.markPublished(storyId: story.id, slug: result.slug)
                await MainActor.run {
                    publishing = false
                    flashToast("Link is live — anyone with it can read.")
                }
            } catch {
                await MainActor.run {
                    publishing = false
                    flashToast(error.localizedDescription)
                }
            }
        }
    }

    private func doUnpublish() {
        guard let story = vm.story else { return }
        publishing = true
        Task {
            do {
                try await StoryPublisher.unpublish(story: story)
                try await repository.markUnpublished(storyId: story.id)
                await MainActor.run {
                    publishing = false
                    flashToast("Public link removed.")
                }
            } catch {
                await MainActor.run {
                    publishing = false
                    flashToast(error.localizedDescription)
                }
            }
        }
    }

    private func flashToast(_ message: String) {
        toast = message
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_800_000_000)
            toast = nil
        }
    }

    // MARK: - Derived

    private var isPublicLinkActive: Bool {
        vm.story?.shareMode == Story.ShareMode.publicLink.rawValue
            && (vm.story?.shareSlug?.isEmpty == false)
    }

    private var linkText: String {
        "quickink.app/s/\(vm.story?.shareSlug ?? "…")"
    }

    /// Reading-time estimate — ~120 wpm reading speed for serif body
    /// is closer to ~180 wpm typical reading, but our items are
    /// mostly photos so we lean shorter. Floor at 1 minute.
    private var estimatedReadMinutes: Int {
        let words = vm.items.compactMap { $0.text ?? $0.caption }
            .reduce(0) { $0 + $1.split(whereSeparator: { $0.isWhitespace }).count }
        return max(1, Int(round(Double(words) / 180.0)) + (vm.items.count / 8))
    }
}

private struct ShareItem: Identifiable {
    let url: URL
    var id: URL { url }
}

private struct ActivityView: UIViewControllerRepresentable {
    let activityItems: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
