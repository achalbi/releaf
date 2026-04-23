/*
 * TasksScreen.swift
 *
 * Workspace-level task list — iOS mirror of Android's TasksScreen.
 * Same three zones:
 *   - Header (breadcrumb back → Home, serif title, open-count summary)
 *   - Inline quick-add row (title field + priority chip + due-date
 *     pill + Add button)
 *   - Task list (open first, completed below, sectioned headers)
 *
 * Uses `@Environment(\.accentPalette)` so the screen re-tints with
 * the user's active accent, matching the rest of the app.
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct TasksScreen: View {
    @EnvironmentObject private var authStore: AuthStore

    public init() {}

    public var body: some View {
        Group {
            if let session = authStore.session {
                TasksContent(userId: session.userId)
            } else {
                EmptyView()
            }
        }
        .background(DotGridBackground().ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .hidesBottomBar()
    }
}

// MARK: - Content

private struct TasksContent: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accentPalette) private var accent
    @StateObject private var vm: TasksViewModel

    init(userId: String) {
        _vm = StateObject(wrappedValue: TasksViewModel(
            repository: TaskRepository(),
            userId: userId
        ))
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            quickAddRow
                .padding(.horizontal, AppSpacing.s4)
                .padding(.bottom, AppSpacing.s3)
            list
        }
        .task { await vm.bootstrap() }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s1) {
            Breadcrumbs([
                BreadcrumbSegment(label: "Home") { dismiss() },
                BreadcrumbSegment(label: "Tasks"),
            ])
            .padding(.horizontal, AppSpacing.s4)
            .padding(.top, AppSpacing.s3)

            VStack(alignment: .leading, spacing: AppSpacing.s1) {
                Text("Tasks")
                    .font(AppText.editorialTitle)
                    .foregroundStyle(AppColors.textPrimary)
                Text(summary)
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
            }
            .padding(.horizontal, AppSpacing.s4)
            .padding(.bottom, AppSpacing.s3)
        }
    }

    private var summary: String {
        if vm.isLoading { return "Loading…" }
        if vm.tasks.isEmpty { return "Nothing here yet" }
        let open = vm.openCount
        let done = vm.doneCount
        if open == 0 { return "All caught up — \(done) done" }
        if done == 0 { return "\(open) open" }
        return "\(open) open · \(done) done"
    }

    private var quickAddRow: some View {
        QuickAddRow { title, dueDate, priority in
            vm.addTask(title: title, dueDate: dueDate, priority: priority)
        }
    }

    @ViewBuilder private var list: some View {
        if vm.tasks.isEmpty && !vm.isLoading {
            emptyState
        } else {
            let open = vm.tasks.filter { !$0.completed }
            let done = vm.tasks.filter { $0.completed }
            ScrollView {
                LazyVStack(alignment: .leading, spacing: AppSpacing.s2) {
                    if !open.isEmpty {
                        sectionHeader("OPEN · \(open.count)")
                        ForEach(open) { task in
                            TaskRow(
                                task:     task,
                                onToggle: { vm.toggleCompleted(task) },
                                onDelete: { vm.deleteTask(task) }
                            )
                        }
                    }
                    if !done.isEmpty {
                        if !open.isEmpty { Color.clear.frame(height: AppSpacing.s3) }
                        sectionHeader("DONE · \(done.count)")
                        ForEach(done) { task in
                            TaskRow(
                                task:     task,
                                onToggle: { vm.toggleCompleted(task) },
                                onDelete: { vm.deleteTask(task) }
                            )
                        }
                    }
                    Color.clear.frame(height: AppSpacing.s10)
                }
                .padding(.horizontal, AppSpacing.s4)
            }
        }
    }

    private func sectionHeader(_ text: String) -> some View {
        Text(text)
            .font(AppText.eyebrow)
            .tracking(AppLetterSpacing.eyebrow)
            .foregroundStyle(AppColors.textSecondary)
            .padding(.vertical, AppSpacing.s2)
    }

    private var emptyState: some View {
        VStack(spacing: AppSpacing.s2) {
            Spacer()
            Text("No tasks yet")
                .font(AppText.sectionTitle)
                .foregroundStyle(AppColors.textPrimary)
            Text("Add a task above to start tracking work that doesn't fit in a notebook page.")
                .font(AppText.body)
                .foregroundStyle(AppColors.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, AppSpacing.s6)
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Quick-add

private struct QuickAddRow: View {
    @Environment(\.accentPalette) private var accent
    let onAdd: (String, String?, Int) -> Void

    @State private var title: String = ""
    @State private var dueDate: String? = nil
    @State private var priority: Int = 0
    @State private var showDatePicker: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            TextField("Add a task…", text: $title)
                .font(AppText.body)
                .foregroundStyle(AppColors.textPrimary)
                .tint(accent.primary)
                .submitLabel(.done)
                .onSubmit(commit)

            HStack(spacing: AppSpacing.s2) {
                priorityChip
                dueDatePill
                Spacer()
                addButton
            }
        }
        .padding(AppSpacing.s3)
        .background(AppColors.cardSolid)
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.md)
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: AppRadius.md))
        .sheet(isPresented: $showDatePicker) {
            DueDatePickerSheet(
                initial: dueDate,
                onPick: { iso in
                    dueDate = iso
                    showDatePicker = false
                },
                onClear: { dueDate = nil; showDatePicker = false }
            )
            .presentationDetents([.medium])
        }
    }

    private var priorityChip: some View {
        Button { priority = (priority + 1) % 4 } label: {
            Text(priorityLabel(priority))
                .font(AppText.meta.weight(.semibold))
                .foregroundStyle(priority == 0 ? AppColors.textSecondary : accent.primary)
                .padding(.horizontal, AppSpacing.s3)
                .padding(.vertical, 6)
                .background(AppColors.canvas)
                .overlay(Capsule().stroke(AppColors.borderDefault, lineWidth: 1))
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    private var dueDatePill: some View {
        Button {
            if dueDate != nil { dueDate = nil } else { showDatePicker = true }
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "calendar")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(dueDate == nil ? AppColors.textSecondary : accent.primary)
                Text(dueDate.map { formatShortDate($0) } ?? "Due date")
                    .font(AppText.meta.weight(.semibold))
                    .foregroundStyle(dueDate == nil ? AppColors.textSecondary : accent.primary)
            }
            .padding(.horizontal, AppSpacing.s3)
            .padding(.vertical, 6)
            .background(AppColors.canvas)
            .overlay(Capsule().stroke(AppColors.borderDefault, lineWidth: 1))
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    private var addButton: some View {
        let canAdd = !title.trimmingCharacters(in: .whitespaces).isEmpty
        return Button(action: commit) {
            HStack(spacing: 4) {
                Image(systemName: "plus")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(canAdd ? .white : AppColors.textSecondary)
                Text("Add")
                    .font(AppText.button)
                    .foregroundStyle(canAdd ? .white : AppColors.textSecondary)
            }
            .padding(.horizontal, AppSpacing.s4)
            .padding(.vertical, AppSpacing.s2)
            .background(canAdd ? accent.primary : AppColors.borderDefault)
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(!canAdd)
    }

    private func commit() {
        let trimmed = title.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        onAdd(trimmed, dueDate, priority)
        title = ""
        dueDate = nil
        priority = 0
    }
}

// MARK: - Row

private struct TaskRow: View {
    @Environment(\.accentPalette) private var accent
    let task: TaskRecord
    let onToggle: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: AppSpacing.s3) {
            Button(action: onToggle) {
                ZStack {
                    Circle()
                        .stroke(
                            task.completed ? accent.primary : AppColors.borderStrong,
                            lineWidth: 2
                        )
                        .frame(width: 22, height: 22)
                    if task.completed {
                        Circle().fill(accent.primary).frame(width: 22, height: 22)
                        Text("✓")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(.white)
                    }
                }
            }
            .buttonStyle(.plain)

            VStack(alignment: .leading, spacing: 2) {
                Text(task.title)
                    .font(AppText.body.weight(task.completed ? .regular : .semibold))
                    .foregroundStyle(task.completed ? AppColors.textSecondary : AppColors.textPrimary)
                    .strikethrough(task.completed)
                if let meta = rowMeta(task) {
                    Text(meta)
                        .font(AppText.meta)
                        .foregroundStyle(AppColors.textSecondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if task.priority > 0 {
                Circle()
                    .fill(accent.primary)
                    .frame(width: 8, height: 8)
            }

            Button(action: onDelete) {
                Image(systemName: "trash")
                    .font(.system(size: 16))
                    .foregroundStyle(AppColors.danger)
                    .frame(width: 32, height: 32)
            }
            .buttonStyle(.plain)
        }
        .padding(AppSpacing.s3)
        .background(AppColors.cardSolid)
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.md)
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: AppRadius.md))
    }
}

// MARK: - Due-date picker sheet

private struct DueDatePickerSheet: View {
    @Environment(\.dismiss) private var dismiss
    let initial: String?
    let onPick: (String) -> Void
    let onClear: () -> Void

    @State private var date: Date

    init(initial: String?, onPick: @escaping (String) -> Void, onClear: @escaping () -> Void) {
        self.initial = initial
        self.onPick = onPick
        self.onClear = onClear
        _date = State(initialValue: initial.flatMap { Self.parseIso($0) } ?? Date())
    }

    var body: some View {
        NavigationStack {
            VStack {
                DatePicker(
                    "Due date",
                    selection: $date,
                    displayedComponents: .date
                )
                .datePickerStyle(.graphical)
                .padding(AppSpacing.s4)
                Spacer()
            }
            .navigationTitle("Due date")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                if initial != nil {
                    ToolbarItem(placement: .destructiveAction) {
                        Button("Clear") { onClear() }
                            .tint(AppColors.danger)
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Set") { onPick(Self.toIso(date)) }
                }
            }
        }
    }

    private static let isoFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = .current
        return f
    }()

    static func parseIso(_ iso: String) -> Date? { isoFormatter.date(from: iso) }
    static func toIso(_ date: Date) -> String { isoFormatter.string(from: date) }
}

// MARK: - Helpers

private func rowMeta(_ task: TaskRecord) -> String? {
    var parts: [String] = []
    if let due = task.dueDate { parts.append("Due \(formatShortDate(due))") }
    if task.priority > 0 { parts.append("\(priorityLabel(task.priority)) priority") }
    return parts.isEmpty ? nil : parts.joinToString(" · ")
}

private func priorityLabel(_ p: Int) -> String {
    switch p {
    case 1: return "Low"
    case 2: return "Medium"
    case 3: return "High"
    default: return "No priority"
    }
}

private func formatShortDate(_ iso: String) -> String {
    let parser = DateFormatter()
    parser.dateFormat = "yyyy-MM-dd"
    parser.locale = Locale(identifier: "en_US_POSIX")
    parser.timeZone = .current
    guard let date = parser.date(from: iso) else { return iso }
    let display = DateFormatter()
    display.dateStyle = .medium
    display.timeZone = .current
    return display.string(from: date)
}

private extension Array where Element == String {
    func joinToString(_ separator: String) -> String { self.joined(separator: separator) }
}
