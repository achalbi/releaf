/*
 * StoryEditorScreen.swift
 *
 * Stories Phase 2 — the curated-narrative editor (§7.3 of the v3
 * mockup). Lets the user reorder items, edit captions / inline text,
 * change layout, set a cover, and remove items. Auto-saves on every
 * change with a "Saved just now" toast at the top.
 *
 * Add-flow: tap any "+ Add" inter-item button to open `StoryAddSheet`;
 * the sheet's italic subtitle anchors the insertion point — "after —
 * \"<preceding caption>\"" — and the sheet's options either insert
 * inline (text / handwritten / divider / pin) or kick off a capture
 * (voice clip, here; scan / photo / library picker stub TODOs that
 * land in the Phase 2 follow-up).
 *
 * Per-item ⋯ menu opens `StoryItemMenuSheet` with layout pills,
 * Set-as-cover, Move up/down, and a coral Remove action below a
 * divider.
 *
 * Mirror of Android `StoryEditorScreen.kt`.
 */

import SwiftUI

struct StoryEditorScreen: View {

    let storyId: String
    let userId: String
    var onBack: () -> Void
    /// Phase 3 hook — tap the bottom Preview button to push the
    /// reader. Phase 2 shipped this stubbed; QuickInkRoot now wires
    /// it to the `.storyReader(storyId:)` route.
    var onPreview: () -> Void = {}
    /// Launch the QuickCaptureScreen in the requested mode and call
    /// the closure once the capture pipeline finishes (with the
    /// newly-written `captures.id`). The editor inserts a story_item
    /// pointing at that id, so the new scan or photo lands inline.
    var onRequestCapture: (CaptureMode, @escaping (ScanFlowController.PassSummary) -> Void) -> Void = { _, _ in }

    @StateObject private var vm: StoryEditorViewModel

    @State private var titleDraft: String = ""
    @State private var subtitleDraft: String = ""
    /// Item the user is currently editing inline (text_block or
    /// handwritten_note). Keyed by item id so cancellation of one
    /// debounce doesn't leak into another's draft.
    @State private var itemTextDrafts: [String: String] = [:]
    @State private var itemCaptionDrafts: [String: String] = [:]

    /// Which item, if any, has the long-press lift active (visual
    /// "dragging" affordance + the drag gesture itself).
    @State private var draggingId: String? = nil
    @State private var dragOffset: CGFloat = 0
    /// Snapshot of the item order at gesture start so we can compute
    /// the in-progress new order without committing on every move.
    @State private var dragOrderSnapshot: [String] = []
    /// In-progress in-memory order during a drag — overrides
    /// `vm.items` for rendering only. Committed to GRDB on release.
    @State private var liveItemOrder: [String] = []

    @State private var addSheetPrecedingId: String? = nil
    @State private var showingAddSheet: Bool = false
    @State private var menuTargetId: String? = nil
    @State private var showingShareSheet: Bool = false

    init(
        storyId: String,
        userId: String,
        onBack: @escaping () -> Void,
        onPreview: @escaping () -> Void = {},
        onRequestCapture: @escaping (CaptureMode, @escaping (ScanFlowController.PassSummary) -> Void) -> Void = { _, _ in }
    ) {
        self.storyId          = storyId
        self.userId           = userId
        self.onBack           = onBack
        self.onPreview        = onPreview
        self.onRequestCapture = onRequestCapture
        _vm = StateObject(wrappedValue: StoryEditorViewModel(storyId: storyId, userId: userId))
    }

    var body: some View {
        ZStack(alignment: .top) {
            QuickInkColors.bg.ignoresSafeArea()

            VStack(spacing: 0) {
                topBar
                coverStrip
                itemsList
                bottomBar
            }

            if vm.savedJustNow {
                savedToast
                    .padding(.top, QuickInkSpacing.s2)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .task {
            vm.start()
        }
        .onChange(of: vm.story?.title) { newValue in
            // Keep the local draft in sync when the row first loads,
            // BUT don't clobber an in-flight edit — only seed when the
            // local draft is the previous DB value (or empty on first
            // load).
            if let dbTitle = newValue, titleDraft.isEmpty || titleDraft == (vm.story?.title ?? "") {
                titleDraft = dbTitle
            }
        }
        .onChange(of: vm.story?.subtitle) { newValue in
            let dbSub = newValue ?? ""
            if subtitleDraft.isEmpty || subtitleDraft == (vm.story?.subtitle ?? "") {
                subtitleDraft = dbSub
            }
        }
        .sheet(isPresented: $showingAddSheet) {
            StoryAddSheet(
                precedingItemCaption: precedingCaption(for: addSheetPrecedingId),
                userId:               userId,
                onPickInlineKind:     { kind in
                    let precedingId = addSheetPrecedingId
                    showingAddSheet = false
                    Task {
                        _ = await vm.insertItem(after: precedingId, kind: kind)
                    }
                },
                onPickVoiceClip:      { uri, durationMs in
                    let precedingId = addSheetPrecedingId
                    showingAddSheet = false
                    Task {
                        _ = await vm.insertVoiceClipItem(
                            after: precedingId,
                            audioUri: uri,
                            durationMs: durationMs
                        )
                    }
                },
                onPickCapture:        { captureId, kind in
                    let precedingId = addSheetPrecedingId
                    showingAddSheet = false
                    Task {
                        _ = await vm.insertCaptureItem(
                            after:     precedingId,
                            captureId: captureId,
                            kind:      kind
                        )
                    }
                },
                onPickCaptureMode:    { mode in
                    let precedingId = addSheetPrecedingId
                    showingAddSheet = false
                    onRequestCapture(mode) { summary in
                        let kind: StoryItem.Kind = summary.source == "photo" ? .photo : .document
                        Task {
                            _ = await vm.insertCaptureItem(
                                after:     precedingId,
                                captureId: summary.captureId,
                                kind:      kind
                            )
                        }
                    }
                },
                onPickStubbed:        {
                    showingAddSheet = false
                }
            )
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showingShareSheet) {
            StoryShareSheet(
                storyId:   storyId,
                userId:    userId,
                onDismiss: { showingShareSheet = false }
            )
            .presentationDetents([.medium, .large])
        }
        .sheet(item: Binding(
            get: { menuTargetId.flatMap { id in vm.items.first(where: { $0.id == id }) } },
            set: { newValue in menuTargetId = newValue?.id }
        )) { item in
            StoryItemMenuSheet(
                item:           item,
                isCoverItem:    vm.story?.coverItemId == item.id,
                onEditCaption:  {
                    menuTargetId = nil
                    // No dedicated caption-edit overlay yet — tapping
                    // the caption text in the editor list focuses
                    // inline. Close the sheet so the user can tap it.
                },
                onSetAsCover:   {
                    vm.setCover(itemId: item.id)
                    menuTargetId = nil
                },
                onLayoutChange: { layout in
                    vm.updateItemLayout(item.id, layout)
                },
                onMoveUp:       {
                    moveItem(item.id, by: -1)
                    menuTargetId = nil
                },
                onMoveDown:     {
                    moveItem(item.id, by: +1)
                    menuTargetId = nil
                },
                onRemove:       {
                    vm.removeItem(item.id)
                    menuTargetId = nil
                }
            )
            .presentationDetents([.medium])
        }
    }

    // MARK: - Top bar / saved toast

    private var topBar: some View {
        HStack {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(QuickInkColors.ink)
                    .padding(QuickInkSpacing.s2)
            }
            .buttonStyle(.plain)
            Spacer()
            HStack(spacing: QuickInkSpacing.s3) {
                Text("Preview · Share")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(QuickInkColors.inkSoft)
            }
            .padding(.trailing, QuickInkSpacing.s3)
        }
        .padding(.horizontal, QuickInkSpacing.s3)
        .padding(.top, QuickInkSpacing.s2)
    }

    private var savedToast: some View {
        Text("Saved just now")
            .font(.system(size: 11, weight: .medium))
            .foregroundStyle(QuickInkColors.inkSoft)
            .padding(.horizontal, 12)
            .padding(.vertical, 5)
            .background(
                Capsule().fill(QuickInkColors.surface)
            )
            .overlay(
                Capsule().strokeBorder(QuickInkColors.border, lineWidth: 0.5)
            )
            .shadow(color: QuickInkColors.ink.opacity(0.08), radius: 6, y: 2)
            .animation(.easeInOut(duration: 0.2), value: vm.savedJustNow)
    }

    // MARK: - Cover strip

    private var coverStrip: some View {
        HStack(spacing: QuickInkSpacing.s3) {
            RoundedRectangle(cornerRadius: 8)
                .fill(QuickInkColors.paper1)
                .frame(width: 44, height: 44)

            VStack(alignment: .leading, spacing: 2) {
                TextField("Title", text: $titleDraft)
                    .font(QuickInkText.editorial)
                    .foregroundStyle(QuickInkColors.ink)
                    .onChange(of: titleDraft) { vm.updateTitle($0) }
                TextField("a quiet line of context", text: $subtitleDraft)
                    .font(QuickInkText.bodyItalic)
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .onChange(of: subtitleDraft) { vm.updateSubtitle($0) }
            }

            Spacer(minLength: 0)
            Text("Cover")
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(QuickInkColors.accent)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .overlay(
                    Capsule().strokeBorder(QuickInkColors.accent.opacity(0.4), lineWidth: 1)
                )
        }
        .padding(.horizontal, QuickInkSpacing.s4)
        .padding(.vertical, QuickInkSpacing.s3)
        .background(QuickInkColors.surface)
        .overlay(alignment: .bottom) {
            Rectangle().fill(QuickInkColors.border).frame(height: 0.5)
        }
    }

    // MARK: - Items list (with +Add slots + drag-to-reorder)

    private var orderedItems: [StoryItem] {
        if let dragging = draggingId, !liveItemOrder.isEmpty {
            // Use the in-progress order while dragging.
            let byId = Dictionary(uniqueKeysWithValues: vm.items.map { ($0.id, $0) })
            return liveItemOrder.compactMap { byId[$0] }
        }
        return vm.items
    }

    private var itemsList: some View {
        ScrollView(showsIndicators: false) {
            VStack(spacing: QuickInkSpacing.s2) {
                ForEach(orderedItems, id: \.id) { item in
                    itemRow(item)
                        .opacity(draggingId == item.id ? 0.85 : 1.0)
                        .offset(y: draggingId == item.id ? dragOffset : 0)
                        .scaleEffect(draggingId == item.id ? 1.02 : 1.0)
                        .zIndex(draggingId == item.id ? 1 : 0)
                        .gesture(longPressDragGesture(for: item.id))
                    addSlot(precedingId: item.id)
                }
                if vm.items.isEmpty {
                    Text("Add the first item — a photo, a typed paragraph, or a voice clip.")
                        .font(QuickInkText.bodyItalic)
                        .foregroundStyle(QuickInkColors.inkSoft)
                        .padding(.vertical, QuickInkSpacing.s4)
                    addSlot(precedingId: nil)
                }
                Color.clear.frame(height: QuickInkSpacing.s8)
            }
            .padding(.horizontal, QuickInkSpacing.s4)
            .padding(.top, QuickInkSpacing.s3)
            .animation(.easeInOut(duration: 0.18), value: vm.items.map(\.id))
        }
    }

    @ViewBuilder
    private func itemRow(_ item: StoryItem) -> some View {
        switch item.kind {
        case StoryItem.Kind.textBlock.rawValue:
            textBlockRow(item)
        case StoryItem.Kind.handwrittenNote.rawValue:
            handwrittenRow(item)
        case StoryItem.Kind.dateDivider.rawValue:
            dividerRow(item)
        case StoryItem.Kind.placePin.rawValue:
            placePinRow(item)
        case StoryItem.Kind.voiceClip.rawValue:
            voiceClipRow(item)
        default:
            itemCardRow(item)
        }
    }

    /// Generic capture/photo/note item — thumbnail + caption + ⋯.
    private func itemCardRow(_ item: StoryItem) -> some View {
        HStack(spacing: QuickInkSpacing.s3) {
            RoundedRectangle(cornerRadius: 8)
                .fill(QuickInkColors.paper1)
                .frame(width: 56, height: 56)
            captionField(item)
            menuButton(item)
        }
        .padding(QuickInkSpacing.s2 + 2)
        .background(cardBackground)
    }

    private func textBlockRow(_ item: StoryItem) -> some View {
        let draft = Binding<String>(
            get: { itemTextDrafts[item.id] ?? item.text ?? "" },
            set: { itemTextDrafts[item.id] = $0; vm.updateItemText(item.id, $0) }
        )
        return HStack(alignment: .top, spacing: QuickInkSpacing.s2) {
            TextField("Write a paragraph…", text: draft, axis: .vertical)
                .font(QuickInkText.editorial)
                .foregroundStyle(QuickInkColors.ink)
                .lineLimit(2...8)
            menuButton(item)
                .alignmentGuide(.top) { d in d[.top] }
        }
        .padding(QuickInkSpacing.s3)
        .background(cardBackground)
    }

    private func handwrittenRow(_ item: StoryItem) -> some View {
        let draft = Binding<String>(
            get: { itemTextDrafts[item.id] ?? item.text ?? "" },
            set: { itemTextDrafts[item.id] = $0; vm.updateItemText(item.id, $0) }
        )
        return HStack(alignment: .top, spacing: QuickInkSpacing.s2) {
            TextField("Handwritten note…", text: draft, axis: .vertical)
                .font(QuickInkFont.handwritten(18))
                .foregroundStyle(QuickInkColors.ink)
                .lineLimit(2...6)
            menuButton(item)
        }
        .padding(QuickInkSpacing.s3)
        .background(cardBackground)
    }

    private func dividerRow(_ item: StoryItem) -> some View {
        HStack {
            Rectangle().fill(QuickInkColors.border).frame(height: 0.5)
            Text(item.text ?? "Date divider")
                .font(QuickInkFont.serif(13, weight: .medium))
                .foregroundStyle(QuickInkColors.inkSoft)
                .padding(.horizontal, QuickInkSpacing.s3)
            Rectangle().fill(QuickInkColors.border).frame(height: 0.5)
            menuButton(item).foregroundStyle(QuickInkColors.muted)
        }
        .padding(.vertical, QuickInkSpacing.s2)
    }

    private func placePinRow(_ item: StoryItem) -> some View {
        let draft = Binding<String>(
            get: { itemTextDrafts[item.id] ?? item.text ?? "" },
            set: { itemTextDrafts[item.id] = $0; vm.updateItemText(item.id, $0) }
        )
        return HStack(spacing: QuickInkSpacing.s2) {
            Image(systemName: "mappin.and.ellipse")
                .foregroundStyle(QuickInkColors.accent)
            TextField("Place name", text: draft)
                .font(QuickInkText.editorial)
                .foregroundStyle(QuickInkColors.ink)
            menuButton(item)
        }
        .padding(QuickInkSpacing.s3)
        .background(cardBackground)
    }

    private func voiceClipRow(_ item: StoryItem) -> some View {
        HStack(spacing: QuickInkSpacing.s3) {
            ZStack {
                Circle().fill(QuickInkColors.accentSoft).frame(width: 44, height: 44)
                Image(systemName: "waveform")
                    .foregroundStyle(QuickInkColors.accent)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text("Voice clip")
                    .font(QuickInkText.editorial)
                    .foregroundStyle(QuickInkColors.ink)
                Text("tap to play")
                    .font(QuickInkText.bodyItalic)
                    .foregroundStyle(QuickInkColors.inkSoft)
            }
            Spacer()
            menuButton(item)
        }
        .padding(QuickInkSpacing.s2 + 2)
        .background(cardBackground)
    }

    @ViewBuilder
    private func captionField(_ item: StoryItem) -> some View {
        let draft = Binding<String>(
            get: { itemCaptionDrafts[item.id] ?? item.caption ?? "" },
            set: { itemCaptionDrafts[item.id] = $0; vm.updateItemCaption(item.id, $0) }
        )
        TextField("Caption", text: draft, axis: .vertical)
            .font(QuickInkText.editorial)
            .foregroundStyle(QuickInkColors.ink)
            .lineLimit(1...3)
    }

    @ViewBuilder
    private func menuButton(_ item: StoryItem) -> some View {
        Button(action: { menuTargetId = item.id }) {
            Image(systemName: "ellipsis")
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(QuickInkColors.inkSoft)
                .padding(QuickInkSpacing.s2)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var cardBackground: some View {
        RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
            .fill(QuickInkColors.surface)
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                    .strokeBorder(QuickInkColors.border, lineWidth: 1)
            )
    }

    /// "+ Add" between-items button. Tap → opens the AddSheet anchored
    /// to the preceding item.
    private func addSlot(precedingId: String?) -> some View {
        Button(action: {
            addSheetPrecedingId = precedingId
            showingAddSheet = true
        }) {
            HStack {
                Spacer()
                Text("＋ Add")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(QuickInkColors.accent)
                Spacer()
            }
            .padding(.vertical, QuickInkSpacing.s2)
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                    .strokeBorder(QuickInkColors.accent.opacity(0.3), style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
            )
        }
        .buttonStyle(.plain)
    }

    // MARK: - Drag-to-reorder

    private func longPressDragGesture(for id: String) -> some Gesture {
        LongPressGesture(minimumDuration: 0.35)
            .sequenced(before: DragGesture())
            .onChanged { value in
                switch value {
                case .first(_):
                    break
                case .second(let pressed, let drag):
                    guard pressed else { return }
                    if draggingId != id {
                        draggingId = id
                        dragOrderSnapshot = vm.items.map(\.id)
                        liveItemOrder     = dragOrderSnapshot
                    }
                    if let drag = drag {
                        dragOffset = drag.translation.height
                        applyLiveReorder(for: id, offset: drag.translation.height)
                    }
                }
            }
            .onEnded { value in
                if case .second(let pressed, _) = value, pressed {
                    let final = liveItemOrder
                    draggingId = nil
                    dragOffset = 0
                    if final != dragOrderSnapshot {
                        vm.commitReorder(final)
                    }
                    liveItemOrder = []
                    dragOrderSnapshot = []
                } else {
                    draggingId = nil
                    dragOffset = 0
                    liveItemOrder = []
                    dragOrderSnapshot = []
                }
            }
    }

    private func applyLiveReorder(for id: String, offset: CGFloat) {
        // Rough card height for index math. Used only for the
        // in-progress reorder feedback; the real positions land on
        // release. ~76pt covers the typical row height + spacing.
        let stride: CGFloat = 76
        let indexShift = Int((offset / stride).rounded())
        guard indexShift != 0,
              let currentIdx = liveItemOrder.firstIndex(of: id) else { return }
        let targetIdx = max(0, min(liveItemOrder.count - 1, currentIdx + indexShift))
        if targetIdx == currentIdx { return }
        var next = liveItemOrder
        next.remove(at: currentIdx)
        next.insert(id, at: targetIdx)
        liveItemOrder = next
    }

    private func moveItem(_ id: String, by delta: Int) {
        guard let idx = vm.items.firstIndex(where: { $0.id == id }) else { return }
        let target = max(0, min(vm.items.count - 1, idx + delta))
        if target == idx { return }
        var ids = vm.items.map(\.id)
        ids.remove(at: idx)
        ids.insert(id, at: target)
        vm.commitReorder(ids)
    }

    // MARK: - Bottom action bar

    private var bottomBar: some View {
        HStack(spacing: QuickInkSpacing.s3) {
            Button("Preview") { onPreview() }
                .buttonStyle(.plain)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(QuickInkColors.ink)
                .padding(.horizontal, QuickInkSpacing.s4)
                .padding(.vertical, QuickInkSpacing.s2 + 2)
                .background(
                    Capsule().strokeBorder(QuickInkColors.border, lineWidth: 1)
                )

            Spacer()

            Button("Share") { showingShareSheet = true }
                .buttonStyle(.plain)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(QuickInkColors.textOnAccent)
                .padding(.horizontal, QuickInkSpacing.s5)
                .padding(.vertical, QuickInkSpacing.s2 + 2)
                .background(Capsule().fill(QuickInkColors.accent))
        }
        .padding(.horizontal, QuickInkSpacing.s4)
        .padding(.vertical, QuickInkSpacing.s3)
        .background(QuickInkColors.surface)
        .overlay(alignment: .top) {
            Rectangle().fill(QuickInkColors.border).frame(height: 0.5)
        }
    }

    // MARK: - Helpers

    private func precedingCaption(for id: String?) -> String? {
        guard let id = id, let item = vm.items.first(where: { $0.id == id }) else { return nil }
        return item.caption ?? item.text ?? item.kind.replacingOccurrences(of: "_", with: " ")
    }
}
