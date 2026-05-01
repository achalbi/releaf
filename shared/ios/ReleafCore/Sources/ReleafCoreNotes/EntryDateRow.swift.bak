/*
 * EntryDateRow.swift
 *
 * Calendar chip + date picker sheet for the notepad editor. SwiftUI
 * parity with Android's `EntryDateRow` composable — shows "Today" /
 * "Yesterday" / "Apr 21, 2026", tapping opens a graphical DatePicker
 * in a medium-detent sheet so the user can back-date or move a
 * future-dated plan.
 *
 * Date round-trip is YYYY-MM-DD in a `Locale(identifier: "en_US_POSIX")`
 * formatter — matches the schema's CHECK-constrained format on disk.
 */

import SwiftUI
import ReleafDesignSystem

struct EntryDateRow: View {
    @Binding var entryDate: String
    @State private var showPicker = false

    var body: some View {
        Button(action: { showPicker = true }) {
            HStack(spacing: AppSpacing.s2) {
                Image(systemName: "calendar")
                    .font(.system(size: 14))
                    .foregroundStyle(AppColors.coral)
                Text(formattedLabel)
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
            }
            .padding(.vertical, AppSpacing.s1)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .sheet(isPresented: $showPicker) {
            EntryDatePickerSheet(
                initialDate: selectedDate,
                onConfirm: { newDate in
                    entryDate = Self.iso.string(from: newDate)
                    showPicker = false
                },
                onCancel: { showPicker = false }
            )
            .presentationDetents([.medium])
        }
    }

    private var selectedDate: Date {
        Self.iso.date(from: entryDate) ?? Date()
    }

    private var formattedLabel: String {
        guard let date = Self.iso.date(from: entryDate) else { return entryDate }
        let cal = Calendar.current
        if cal.isDateInToday(date)     { return "Today" }
        if cal.isDateInYesterday(date) { return "Yesterday" }
        return Self.display.string(from: date)
    }

    private static let iso: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale     = Locale(identifier: "en_US_POSIX")
        f.timeZone   = .current
        return f
    }()

    private static let display: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .medium
        return f
    }()
}

/// Graphical date picker wrapped in a NavigationStack so we get a Cancel
/// / Set toolbar pair. Takes a seed date and hands the chosen one back
/// via the confirm callback.
private struct EntryDatePickerSheet: View {
    @State private var date: Date
    let onConfirm: (Date) -> Void
    let onCancel: () -> Void

    init(initialDate: Date, onConfirm: @escaping (Date) -> Void, onCancel: @escaping () -> Void) {
        self._date = State(initialValue: initialDate)
        self.onConfirm = onConfirm
        self.onCancel = onCancel
    }

    var body: some View {
        NavigationStack {
            VStack {
                DatePicker(
                    "Entry date",
                    selection: $date,
                    displayedComponents: [.date]
                )
                .datePickerStyle(.graphical)
                .padding()

                Spacer()
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                        .foregroundStyle(AppColors.textSecondary)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Set") { onConfirm(date) }
                        .foregroundStyle(AppColors.coral)
                }
            }
            .navigationTitle("Entry date")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
