/*
 * PageDetailView.swift
 * One page, seven capture modes. Horizontal tab row; content switches with selection.
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct PageDetailView: View {
    @StateObject private var viewModel: PageDetailViewModel
    @State private var selected: CaptureMode = .overview

    public init(pageId: String) {
        _viewModel = StateObject(wrappedValue: PageDetailViewModel(pageId: pageId))
    }

    public var body: some View {
        ZStack {
            DotGridBackground().ignoresSafeArea()
            content
        }
        .navigationBarTitleDisplayMode(.inline)
        .hidesBottomBar()
        .task { await viewModel.load() }
    }

    @ViewBuilder private var content: some View {
        switch viewModel.state {
        case .loading:
            ProgressView().tint(AppColors.coral)

        case .failed(let message):
            VStack(spacing: AppSpacing.s3) {
                Text(message)
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textSecondary)
                AppButton("Try again", variant: .secondary) {
                    Task { await viewModel.load() }
                }
                .fixedSize(horizontal: true, vertical: false)
            }

        case .loaded(let page):
            Loaded(page: page, selected: $selected)
        }
    }
}

// MARK: - Loaded

private struct Loaded: View {
    let page: Page
    @Binding var selected: CaptureMode

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
                .padding(.horizontal, AppSpacing.s4)
                .padding(.top, AppSpacing.s4)
                .padding(.bottom, AppSpacing.s3)

            CaptureTabBar(selected: $selected)

            ScrollView {
                VStack(alignment: .leading, spacing: AppSpacing.s4) {
                    section
                    Spacer(minLength: AppSpacing.s10)
                }
                .padding(AppSpacing.s4)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            Text("PAGE")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.coral)

            Text(page.title)
                .font(AppText.pageTitle)
                .foregroundStyle(AppColors.textPrimary)

            if let capturedOn = page.capturedOn {
                Text(capturedOn)
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textTertiary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private var section: some View {
        switch selected {
        case .overview: OverviewSection(page: page)
        case .photos:   PhotosSection(photos: page.photos)
        case .voice:    VoiceSection(notes: page.voiceNotes)
        case .todo:     TodoSection(items: page.todoItems)
        case .scans:    ScansSection(scans: page.scannedDocuments)
        case .contacts: ContactsSection(contacts: page.contacts)
        case .location: LocationsSection(pins: page.locations)
        }
    }
}

// MARK: - Overview

private struct OverviewSection: View {
    let page: Page

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s4) {
            Text("AT A GLANCE")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.textSecondary)

            let c = page.counts
            StatGrid(items: [
                StatItem(label: "Photos", value: "\(c.photos)",     tone: .coral),
                StatItem(label: "Voice",  value: "\(c.voiceNotes)", tone: .neutral),
                StatItem(label: "To-do",  value: "\(c.todoItems)",  tone: .green),
            ])
            StatGrid(items: [
                StatItem(label: "Scans",    value: "\(c.scannedDocuments)", tone: .neutral),
                StatItem(label: "Contacts", value: "\(c.contacts)",         tone: .info),
                StatItem(label: "Places",   value: "\(c.locations)",        tone: .neutral),
            ])

            if page.notes.isEmpty {
                EmptyState(message: "Nothing written on this page yet.")
            } else {
                ForEach(page.notes) { NoteCard(note: $0) }
            }
        }
    }
}

private struct NoteCard: View {
    let note: Note
    var body: some View {
        Card {
            Text(note.body)
                .font(AppText.body)
                .foregroundStyle(AppColors.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

// MARK: - Photos

private struct PhotosSection: View {
    let photos: [Photo]
    var body: some View {
        if photos.isEmpty {
            EmptyState(message: "No photos on this page.")
        } else {
            VStack(spacing: AppSpacing.s3) {
                ForEach(photos) { PhotoTile(photo: $0) }
            }
        }
    }
}

private struct PhotoTile: View {
    let photo: Photo
    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: AppSpacing.s2) {
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .stroke(AppColors.borderDefault, lineWidth: 1)
                    .frame(height: 180)
                    .overlay(
                        Text(photo.caption ?? "Photo")
                            .font(AppText.meta)
                            .foregroundStyle(AppColors.textTertiary)
                    )
                if let caption = photo.caption {
                    Text(caption)
                        .font(AppText.body)
                        .foregroundStyle(AppColors.textPrimary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

// MARK: - Voice

private struct VoiceSection: View {
    let notes: [VoiceNote]
    var body: some View {
        if notes.isEmpty {
            EmptyState(message: "No voice notes on this page.")
        } else {
            VStack(spacing: AppSpacing.s3) {
                ForEach(notes) { VoiceCard(note: $0) }
            }
        }
    }
}

private struct VoiceCard: View {
    let note: VoiceNote
    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: AppSpacing.s2) {
                HStack {
                    Text("Voice note · \(formatDuration(note.durationMs))")
                        .font(AppText.sectionTitle)
                        .foregroundStyle(AppColors.textPrimary)
                    Spacer()
                    Text("▶︎ Play")
                        .font(AppText.button)
                        .foregroundStyle(AppColors.coral)
                }
                if let transcription = note.transcription {
                    Text("\u{201C}\(transcription)\u{201D}")
                        .font(AppText.body.italic())
                        .foregroundStyle(AppColors.textSecondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

private func formatDuration(_ ms: Int) -> String {
    let totalSeconds = ms / 1000
    let minutes = totalSeconds / 60
    let seconds = totalSeconds % 60
    return String(format: "%d:%02d", minutes, seconds)
}

// MARK: - Todo

private struct TodoSection: View {
    let items: [TodoItem]
    var body: some View {
        if items.isEmpty {
            EmptyState(message: "Nothing on the to-do list.")
        } else {
            Card {
                VStack(alignment: .leading, spacing: AppSpacing.s3) {
                    ForEach(items.sorted(by: { $0.position < $1.position })) { TodoRow(item: $0) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
}

private struct TodoRow: View {
    let item: TodoItem
    var body: some View {
        HStack(alignment: .top, spacing: AppSpacing.s2) {
            Image(systemName: item.done ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(item.done ? AppColors.coral : AppColors.textTertiary)
            Text(item.body)
                .font(AppText.body)
                .strikethrough(item.done)
                .foregroundStyle(item.done ? AppColors.textTertiary : AppColors.textPrimary)
        }
    }
}

// MARK: - Scans

private struct ScansSection: View {
    let scans: [ScannedDocument]
    var body: some View {
        if scans.isEmpty {
            EmptyState(message: "No scanned documents.")
        } else {
            VStack(spacing: AppSpacing.s3) {
                ForEach(scans) { ScanRow(scan: $0) }
            }
        }
    }
}

private struct ScanRow: View {
    let scan: ScannedDocument
    var body: some View {
        Card {
            HStack(spacing: AppSpacing.s3) {
                Image(systemName: "doc.text")
                    .font(.system(size: 24))
                    .foregroundStyle(AppColors.coral)
                VStack(alignment: .leading, spacing: 2) {
                    Text(scan.title)
                        .font(AppText.sectionTitle)
                        .foregroundStyle(AppColors.textPrimary)
                    Text("\(scan.pageCount) page\(scan.pageCount == 1 ? "" : "s")")
                        .font(AppText.meta)
                        .foregroundStyle(AppColors.textSecondary)
                }
                Spacer()
            }
        }
    }
}

// MARK: - Contacts

private struct ContactsSection: View {
    let contacts: [Contact]
    var body: some View {
        if contacts.isEmpty {
            EmptyState(message: "No contacts pinned to this page.")
        } else {
            VStack(spacing: AppSpacing.s3) {
                ForEach(contacts) { ContactCard(contact: $0) }
            }
        }
    }
}

private struct ContactCard: View {
    let contact: Contact
    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: AppSpacing.s1) {
                Text(contact.name)
                    .font(AppText.sectionTitle)
                    .foregroundStyle(AppColors.textPrimary)
                if let phone = contact.phone {
                    Text(phone).font(AppText.body).foregroundStyle(AppColors.textSecondary)
                }
                if let email = contact.email {
                    Text(email).font(AppText.body).foregroundStyle(AppColors.textSecondary)
                }
                if let notes = contact.notes {
                    Text(notes).font(AppText.meta).foregroundStyle(AppColors.textTertiary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

// MARK: - Locations

private struct LocationsSection: View {
    let pins: [LocationPin]
    var body: some View {
        if pins.isEmpty {
            EmptyState(message: "No places on this page.")
        } else {
            VStack(spacing: AppSpacing.s3) {
                ForEach(pins) { LocationCard(pin: $0) }
            }
        }
    }
}

private struct LocationCard: View {
    let pin: LocationPin
    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: AppSpacing.s1) {
                Text(pin.name)
                    .font(AppText.sectionTitle)
                    .foregroundStyle(AppColors.textPrimary)
                Text(String(format: "%.4f, %.4f", pin.latitude, pin.longitude))
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
                if let notes = pin.notes {
                    Text(notes).font(AppText.body).foregroundStyle(AppColors.textPrimary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

// MARK: - Empty state

private struct EmptyState: View {
    let message: String
    var body: some View {
        HStack {
            Spacer()
            Text(message)
                .font(AppText.body)
                .foregroundStyle(AppColors.textTertiary)
                .padding(.vertical, AppSpacing.s6)
            Spacer()
        }
    }
}

#Preview {
    NavigationStack {
        PageDetailView(pageId: "pg-1")
    }
}
