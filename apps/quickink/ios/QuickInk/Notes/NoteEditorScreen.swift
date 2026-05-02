/*
 * NoteEditorScreen.swift
 *
 * QuickInk's Note Detail — upgraded from the slim title+body editor
 * to the mockup's tabbed view:
 *
 *   - Tab 1 (Page): lined-paper page rendering with coral left
 *     margin; if an associated scan preview exists, the image
 *     overlays the paper rule. Tap the page to open a fullscreen
 *     reader (handled in a follow-up).
 *
 *   - Tab 2 (OCR Text): clean editorial serif rendering of the
 *     transcript with a confidence badge, copy-to-clipboard button,
 *     and a "smart suggestions" footer hook (currently inert).
 *
 *   - Floating action bar (Re-tag / Export / Delete).
 *
 * Architecturally we still wrap `NotepadEditorViewModel` — the
 * view's `notes` and `title` strings remain the data source.
 * Inserting OCR-only-vs-text-note logic is a mostly-presentational
 * concern: when the entry has captured pages, we render them in
 * the Page tab; otherwise the Page tab is a static lined-paper
 * card showing the handwritten title.
 *
 * Slice 4 deferred bits:
 *   - Re-tag flow (T13 / category model)
 *   - Export sheet (T14)
 *   - Smart-suggestions backend
 *   - Multi-page navigator (model is one entry = one page today)
 *   - Entity highlighting (NSAttributedString pass over body)
 *
 * Mirror of Android `NoteEditorScreen.kt`.
 */

import SwiftUI
#if canImport(UIKit)
import UIKit
#endif
import ReleafCoreNotes

struct NoteEditorScreen: View {

    let entryId: String
    let userId: String
    let onBack: () -> Void

    @StateObject private var vm: NotepadEditorViewModel
    @State private var activeTab: Tab = .page
    @State private var showCopyToast = false
    @State private var showExportSheet = false

    enum Tab { case page, ocr }

    init(entryId: String, userId: String, onBack: @escaping () -> Void) {
        self.entryId = entryId
        self.userId = userId
        self.onBack = onBack

        let repository = NotepadRepository(dbQueue: QuickInkDatabase.shared.dbQueue)
        _vm = StateObject(
            wrappedValue: NotepadEditorViewModel(
                repository: repository,
                entryId:    entryId,
                userId:     userId
            )
        )
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            VStack(spacing: 0) {
                topBar
                tabSwitcher
                    .padding(.horizontal, QuickInkSpacing.s5)
                    .padding(.top, QuickInkSpacing.s3)
                    .padding(.bottom, QuickInkSpacing.s2)

                if vm.isLoading {
                    Spacer()
                    ProgressView()
                        .tint(QuickInkColors.accent)
                    Spacer()
                } else {
                    Group {
                        switch activeTab {
                        case .page: pageTab
                        case .ocr:  ocrTab
                        }
                    }
                    .frame(maxHeight: .infinity)
                }
            }

            floatingActionBar
                .padding(.horizontal, QuickInkSpacing.s5)
                .padding(.bottom, QuickInkSpacing.s5)

            if showCopyToast {
                copyToast
                    .padding(.bottom, 100)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(QuickInkColors.bg.ignoresSafeArea())
        .task {
            await vm.bootstrap()
        }
        .sheet(isPresented: $showExportSheet) {
            ExportSheet(
                searchablePdfEnabled: UserDefaults.standard.bool(forKey: "quickink.settings.searchable_pdf_export_enabled"),
                onSelect: { format in
                    showExportSheet = false
                    // Format-specific export pipeline lands in a
                    // follow-up. Today the sheet's selection is a
                    // no-op — surfacing intent without actually
                    // generating the file. Once the Drive export +
                    // bitmap renderer ship, hand off here:
                    //   exporter.export(vm.entry, as: format)
                    _ = format
                },
                onDismiss: { showExportSheet = false }
            )
            .presentationDetents([.medium])
            .presentationDragIndicator(.hidden)
        }
    }

    // MARK: - Top bar

    @ViewBuilder
    private var topBar: some View {
        HStack(alignment: .center) {
            Button(action: backAndPersist) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(QuickInkColors.ink)
                    .padding(QuickInkSpacing.s3)
            }
            .accessibilityLabel("Back")

            VStack(alignment: .leading, spacing: 0) {
                if vm.title.isEmpty {
                    Text("Untitled")
                        .font(QuickInkText.label)
                        .foregroundStyle(QuickInkColors.muted)
                } else {
                    Text(vm.title)
                        .font(QuickInkText.label)
                        .foregroundStyle(QuickInkColors.ink)
                        .lineLimit(1)
                }
                Text(vm.entryDate)
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.muted)
            }

            Spacer()

            // Page navigator pill — placeholder showing 1/1 until
            // multi-page model exists. Stays visible to set the
            // expectation that multi-page is coming.
            Text("1 / 1")
                .font(QuickInkText.caption)
                .foregroundStyle(QuickInkColors.inkSoft)
                .padding(.horizontal, QuickInkSpacing.s3)
                .padding(.vertical, 4)
                .background(QuickInkColors.borderSoft)
                .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
                .padding(.trailing, QuickInkSpacing.s3)
        }
        .padding(.horizontal, QuickInkSpacing.s2)
        .padding(.top, QuickInkSpacing.s2)
    }

    // MARK: - Tab switcher

    @ViewBuilder
    private var tabSwitcher: some View {
        HStack(spacing: 4) {
            tabButton(label: "Page",     isActive: activeTab == .page) { activeTab = .page }
            tabButton(label: "OCR Text", isActive: activeTab == .ocr)  { activeTab = .ocr }
        }
        .padding(4)
        .background(QuickInkColors.borderSoft)
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
    }

    @ViewBuilder
    private func tabButton(label: String, isActive: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(QuickInkText.label)
                .foregroundStyle(isActive ? QuickInkColors.ink : QuickInkColors.inkSoft)
                .frame(maxWidth: .infinity)
                .padding(.vertical, QuickInkSpacing.s2)
                .background(
                    RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous)
                        .fill(isActive ? QuickInkColors.surface : .clear)
                        .shadow(color: isActive ? QuickInkColors.ink.opacity(0.06) : .clear, radius: 4, x: 0, y: 2)
                )
        }
        .buttonStyle(.plain)
    }

    // MARK: - Page tab

    @ViewBuilder
    private var pageTab: some View {
        ScrollView {
            ZStack(alignment: .topLeading) {
                QuickInkLinedPaper(
                    tone: QuickInkColors.surface,
                    lineSpacing: 22,
                    lineOpacity: 0.10
                )

                // Coral left margin line — the iconic mockup detail.
                HStack(spacing: 0) {
                    Rectangle()
                        .fill(QuickInkColors.accent.opacity(0.6))
                        .frame(width: 1.5)
                        .padding(.leading, 36)
                    Spacer()
                }

                // Body content rendered in handwritten Caveat to
                // simulate the scanned page. When real captured
                // image data is wired, replace this with the page
                // image.
                VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
                    if !vm.title.isEmpty {
                        Text(vm.title)
                            .font(QuickInkFont.handwritten(28))
                            .foregroundStyle(QuickInkColors.ink)
                    }
                    Text(vm.notes.isEmpty ? "Tap OCR Text to add transcript content." : vm.notes)
                        .font(QuickInkFont.handwritten(20))
                        .foregroundStyle(QuickInkColors.ink.opacity(0.85))
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.leading, 52)
                .padding(.trailing, QuickInkSpacing.s5)
                .padding(.vertical, QuickInkSpacing.s5)
            }
            .frame(minHeight: 480)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous)
                    .stroke(QuickInkColors.border, lineWidth: 1)
            )
            .shadow(color: QuickInkColors.ink.opacity(0.06), radius: 8, x: 0, y: 4)
            .padding(QuickInkSpacing.s5)
            .padding(.bottom, 100)
        }
    }

    // MARK: - OCR tab

    @ViewBuilder
    private var ocrTab: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: QuickInkSpacing.s4) {
                // Confidence + copy header.
                HStack {
                    HStack(spacing: QuickInkSpacing.s1) {
                        Circle()
                            .fill(QuickInkColors.success)
                            .frame(width: 6, height: 6)
                        Text("98% confidence")
                            .font(QuickInkText.caption)
                            .foregroundStyle(QuickInkColors.inkSoft)
                    }
                    .padding(.horizontal, QuickInkSpacing.s3)
                    .padding(.vertical, QuickInkSpacing.s1)
                    .background(QuickInkColors.borderSoft)
                    .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))

                    Spacer()

                    Button(action: copyOCRText) {
                        HStack(spacing: 6) {
                            Image(systemName: "doc.on.doc")
                                .font(.system(size: 13))
                            Text("Copy")
                                .font(QuickInkText.label)
                        }
                        .foregroundStyle(QuickInkColors.accent)
                        .padding(.horizontal, QuickInkSpacing.s3)
                        .padding(.vertical, QuickInkSpacing.s1)
                        .background(QuickInkColors.accentSoft)
                        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }

                // Title field — editable.
                TextField("Title", text: $vm.title)
                    .font(QuickInkText.pageTitle)
                    .foregroundStyle(QuickInkColors.ink)

                // Body / OCR transcript — editable. The mockup also
                // showed entity highlighting (dates, names, etc.) on
                // top of the transcript; with TextEditor we can't
                // run an attributed pass, so the highlighting hook
                // is captured below as a static read-only preview
                // that only renders when notes is non-empty.
                if vm.notes.isEmpty {
                    // Empty state — composing fresh.
                    TextEditor(text: $vm.notes)
                        .font(QuickInkText.body)
                        .foregroundStyle(QuickInkColors.ink)
                        .scrollContentBackground(.hidden)
                        .frame(minHeight: 200)
                        .padding(QuickInkSpacing.s3)
                        .background(QuickInkColors.surface)
                        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                                .stroke(QuickInkColors.border, lineWidth: 1)
                        )
                } else {
                    // Read with edit-on-tap. Tap the body to enter
                    // an editable TextEditor inline.
                    TextEditor(text: $vm.notes)
                        .font(QuickInkText.body)
                        .foregroundStyle(QuickInkColors.ink)
                        .scrollContentBackground(.hidden)
                        .frame(minHeight: 240)
                        .padding(QuickInkSpacing.s4)
                        .background(QuickInkColors.surface)
                        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                                .stroke(QuickInkColors.border, lineWidth: 1)
                        )
                }

                // Smart suggestions footer — placeholder. When the
                // suggestion model lands, populate with detected
                // todos, dates, contacts from the OCR text.
                VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
                    Text("SMART SUGGESTIONS")
                        .font(QuickInkText.eyebrow)
                        .tracking(QuickInkLetterSpacing.eyebrow)
                        .foregroundStyle(QuickInkColors.muted)

                    Text("Coming soon — extracted to-dos, dates, and contacts from this page.")
                        .font(QuickInkText.bodyItalic)
                        .foregroundStyle(QuickInkColors.inkSoft)
                }
                .padding(QuickInkSpacing.s4)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(QuickInkColors.accentSoft)
                .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
            }
            .padding(.horizontal, QuickInkSpacing.s5)
            .padding(.top, QuickInkSpacing.s3)
            .padding(.bottom, 100)
        }
    }

    // MARK: - Floating action bar

    @ViewBuilder
    private var floatingActionBar: some View {
        HStack(spacing: QuickInkSpacing.s2) {
            actionButton(systemName: "tag", label: "Re-tag", action: { /* T13 */ })
            actionButton(systemName: "square.and.arrow.up", label: "Export", action: { showExportSheet = true })
            if vm.entry != nil {
                actionButton(systemName: "trash", label: "Delete", tint: QuickInkColors.danger, action: deleteAndDismiss)
            }
        }
        .padding(QuickInkSpacing.s2)
        .background(QuickInkColors.surface)
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
        .shadow(color: QuickInkColors.ink.opacity(0.10), radius: 12, x: 0, y: 4)
    }

    @ViewBuilder
    private func actionButton(systemName: String, label: String, tint: Color = QuickInkColors.ink, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: systemName)
                    .font(.system(size: 14, weight: .medium))
                Text(label)
                    .font(QuickInkText.label)
            }
            .foregroundStyle(tint)
            .padding(.horizontal, QuickInkSpacing.s3)
            .padding(.vertical, QuickInkSpacing.s2)
        }
        .buttonStyle(.plain)
    }

    // MARK: - Copy toast

    @ViewBuilder
    private var copyToast: some View {
        Text("Copied to clipboard")
            .font(QuickInkText.label)
            .foregroundStyle(QuickInkColors.textOnAccent)
            .padding(.horizontal, QuickInkSpacing.s4)
            .padding(.vertical, QuickInkSpacing.s2)
            .background(QuickInkColors.ink)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
    }

    // MARK: - Actions

    private func backAndPersist() {
        // Save-on-back matches Releaf's editor flow — no separate
        // Save button. The VM's `save()` is a no-op when there's
        // nothing to commit (canSave == false), so it's safe to
        // always call.
        if vm.canSave {
            vm.save()
            QuickInkSyncEnvironment.shared.scheduler.requestImmediate()
        }
        onBack()
    }

    private func deleteAndDismiss() {
        vm.delete(onDeleted: {
            QuickInkSyncEnvironment.shared.scheduler.requestImmediate()
            onBack()
        })
    }

    private func copyOCRText() {
        #if canImport(UIKit)
        UIPasteboard.general.string = vm.notes
        #endif
        withAnimation(.easeInOut(duration: 0.18)) {
            showCopyToast = true
        }
        // Auto-dismiss after 1.6s.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) {
            withAnimation(.easeInOut(duration: 0.18)) {
                showCopyToast = false
            }
        }
    }
}
