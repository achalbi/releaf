import SwiftUI
import ReleafDesignSystem

/// Horizontal scrollable strip of tag-filter chips. Selected = green800 / textOnDark.
struct TagChips: View {
    @Binding var selection: TagFilter

    private let filters: [TagFilter] = [
        .all,
        .tag(.home),
        .tag(.work),
        .tag(.recipes),
        .tag(.personal)
    ]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Array(filters.enumerated()), id: \.offset) { _, filter in
                    chip(for: filter)
                }
            }
            .padding(.horizontal, 2)
        }
    }

    @ViewBuilder
    private func chip(for filter: TagFilter) -> some View {
        let isSelected = filter == selection
        Button {
            selection = filter
        } label: {
            Text(filter.label)
                .font(.system(size: 13, weight: isSelected ? .medium : .regular))
                .foregroundColor(isSelected ? .textOnDark : .textGreen)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(
                    Capsule(style: .continuous)
                        .fill(isSelected ? Color.green800 : Color.bgChip)
                )
        }
        .buttonStyle(.plain)
    }
}

#if DEBUG
private struct TagChipsPreview: View {
    @State var sel: TagFilter = .all
    var body: some View {
        TagChips(selection: $sel)
            .padding()
            .background(Color.bgCanvas)
    }
}
#Preview { TagChipsPreview() }
#endif
