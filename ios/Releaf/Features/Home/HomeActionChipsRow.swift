/*
 * HomeActionChipsRow.swift
 * Three compact chips on the Home tab — Tasks (open of total),
 * Reminders (up next · remaining time), Contacts (added count).
 * Sits above the existing full Tasks / Reminders / Contacts cards
 * as a quick-glance summary; each chip taps through to the same
 * destination handler the full card uses.
 *
 * Counts are stubbed for now — wire to real view-model state once
 * HomeDashboardViewModel exposes tasks/reminders/contacts summaries.
 */

import SwiftUI
import ReleafDesignSystem

public struct HomeActionChipsRow: View {
    let openTasks: Int
    let totalTasks: Int
    let nextReminderMinutes: Int?
    let contactsAdded: Int
    let onOpenTasks: () -> Void
    let onOpenReminders: () -> Void
    let onOpenContacts: () -> Void

    public init(
        openTasks: Int = 2,
        totalTasks: Int = 5,
        nextReminderMinutes: Int? = 56,
        contactsAdded: Int = 1,
        onOpenTasks: @escaping () -> Void,
        onOpenReminders: @escaping () -> Void,
        onOpenContacts: @escaping () -> Void
    ) {
        self.openTasks = openTasks
        self.totalTasks = totalTasks
        self.nextReminderMinutes = nextReminderMinutes
        self.contactsAdded = contactsAdded
        self.onOpenTasks = onOpenTasks
        self.onOpenReminders = onOpenReminders
        self.onOpenContacts = onOpenContacts
    }

    public var body: some View {
        HStack(spacing: AppSpacing.s3) {
            Chip(
                label: "TASKS",
                primary: "\(openTasks)",
                secondary: "of \(totalTasks) open",
                tint: .coral,
                action: onOpenTasks
            )
            Chip(
                label: "REMINDERS",
                primary: reminderPrimary,
                secondary: reminderSecondary,
                tint: .green,
                action: onOpenReminders
            )
            Chip(
                label: "CONTACTS",
                primary: "\(contactsAdded)",
                secondary: "added",
                tint: .neutral,
                action: onOpenContacts
            )
        }
    }

    private var reminderPrimary: String {
        guard let mins = nextReminderMinutes else { return "—" }
        if mins >= 60 { return "\(mins / 60)h" }
        return "\(mins)m"
    }

    private var reminderSecondary: String {
        nextReminderMinutes == nil ? "nothing queued" : "up next"
    }
}

// MARK: - Chip

private enum ChipTint { case coral, green, neutral }

private struct Chip: View {
    let label: String
    let primary: String
    let secondary: String
    let tint: ChipTint
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: AppSpacing.s2) {
                Text(label)
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.themeGreenDeep)
                    .lineLimit(1)
                Text(primary)
                    .font(.system(size: 22, design: .serif))
                    .foregroundStyle(primaryColor)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Text(secondary)
                    .font(AppText.tag)
                    .foregroundStyle(AppColors.textSecondary)
                    .lineLimit(1)
            }
            .padding(AppSpacing.s3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .fill(background)
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .stroke(border, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }

    private var background: Color {
        switch tint {
        case .coral:   return AppColors.coralSoft
        case .green:   return AppColors.greenSoft
        case .neutral: return AppColors.cardSolid
        }
    }

    private var border: Color {
        switch tint {
        case .coral:   return .clear
        case .green:   return AppColors.themeGreenBorderSoft
        case .neutral: return AppColors.borderDefault
        }
    }

    private var primaryColor: Color {
        switch tint {
        case .coral:   return AppColors.coralDeep
        case .green:   return AppColors.themeGreenDeep
        case .neutral: return AppColors.textPrimary
        }
    }
}
