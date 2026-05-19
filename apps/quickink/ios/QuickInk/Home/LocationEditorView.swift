/*
 * LocationEditorView.swift
 *
 * Sheet for creating + editing places (Workspace home Places
 * section). Three affordances:
 *
 *   - Name input (required)
 *   - "Use current location" — gates on CoreLocation authorization,
 *     fetches a single fix via `LocationService.captureCurrent()` and
 *     fills lat/lng + the reverse-geocoded address.
 *   - Free-text address editor — for users who'd rather type the
 *     address by hand (no forward-geocode search inline yet; the
 *     coordinates stay nil for hand-typed addresses).
 *
 * Mirror of Android's `LocationEditorDialog`.
 */

import SwiftUI
import CoreLocation
import ReleafCoreDesignSystem

public enum LocationEditorMode: Identifiable {
    case create
    case edit(location: LocationEntity)

    public var id: String {
        switch self {
        case .create:            return "create"
        case .edit(let loc):     return "edit:\(loc.id)"
        }
    }
}

public struct LocationEditorView: View {

    public let mode: LocationEditorMode
    /// Called with name + address + optional coordinates. The
    /// coordinates carry only when the user successfully ran "Use
    /// current location"; a hand-typed address leaves them nil.
    public let onSubmit: (_ name: String, _ address: String?, _ latitude: Double?, _ longitude: Double?) -> Void
    public let onCancel: () -> Void

    @State private var name: String
    @State private var address: String
    @State private var latitude: Double?
    @State private var longitude: Double?

    @State private var isFetching = false
    @State private var statusMessage: String? = nil

    public init(
        mode: LocationEditorMode,
        onSubmit: @escaping (String, String?, Double?, Double?) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.mode = mode
        self.onSubmit = onSubmit
        self.onCancel = onCancel
        switch mode {
        case .create:
            _name      = State(initialValue: "")
            _address   = State(initialValue: "")
            _latitude  = State(initialValue: nil)
            _longitude = State(initialValue: nil)
        case .edit(let loc):
            _name      = State(initialValue: loc.name)
            _address   = State(initialValue: loc.address ?? "")
            _latitude  = State(initialValue: loc.latitude)
            _longitude = State(initialValue: loc.longitude)
        }
    }

    private var title: String {
        switch mode {
        case .create:        return "New place"
        case .edit:          return "Edit place"
        }
    }

    private var canSave: Bool {
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isFetching
    }

    private var hasCoordinates: Bool {
        latitude != nil && longitude != nil
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s4) {
            HStack {
                Text(title)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(QuickInkColors.ink)
                Spacer()
                Button("Cancel", action: onCancel)
                    .foregroundColor(QuickInkColors.muted)
            }

            VStack(alignment: .leading, spacing: 6) {
                Text("NAME")
                    .font(.system(size: 10.5, weight: .semibold))
                    .tracking(1.2)
                    .foregroundColor(QuickInkColors.muted)
                TextField("e.g. Home, Work", text: $name)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
                    .submitLabel(.done)
            }

            if hasCoordinates || !address.isEmpty {
                linkedLocationCard
            }

            Button(action: { Task { await useCurrentLocation() } }) {
                HStack(spacing: 8) {
                    if isFetching {
                        ProgressView()
                            .controlSize(.mini)
                            .tint(QuickInkColors.accent)
                    } else {
                        Image(systemName: "location.fill")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(QuickInkColors.inkSoft)
                    }
                    Text(isFetching ? "Finding…" : (hasCoordinates ? "Refresh current location" : "Use current location"))
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(QuickInkColors.ink)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background(QuickInkColors.borderSoft, in: RoundedRectangle(cornerRadius: 10))
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(QuickInkColors.border, lineWidth: 1)
                )
            }
            .buttonStyle(.plain)
            .disabled(isFetching)

            VStack(alignment: .leading, spacing: 6) {
                Text("ADDRESS")
                    .font(.system(size: 10.5, weight: .semibold))
                    .tracking(1.2)
                    .foregroundColor(QuickInkColors.muted)
                TextField("Street, city, country", text: $address, axis: .vertical)
                    .lineLimit(2, reservesSpace: true)
                    .textFieldStyle(.roundedBorder)
            }

            if let statusMessage = statusMessage {
                Text(statusMessage)
                    .font(.system(size: 12))
                    .foregroundColor(QuickInkColors.muted)
            }

            Button(action: {
                onSubmit(
                    name.trimmingCharacters(in: .whitespacesAndNewlines),
                    address.trimmingCharacters(in: .whitespacesAndNewlines),
                    latitude,
                    longitude
                )
            }) {
                Text("Save")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 13)
                    .background(canSave ? QuickInkColors.ink : QuickInkColors.muted,
                                in: RoundedRectangle(cornerRadius: 10))
            }
            .buttonStyle(.plain)
            .disabled(!canSave)
            Spacer()
        }
        .padding(AppSpacing.s4)
        .background(QuickInkColors.surface)
    }

    private var linkedLocationCard: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "mappin.and.ellipse")
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(QuickInkColors.accent)
                .padding(.top, 1)
            VStack(alignment: .leading, spacing: 2) {
                if !address.isEmpty {
                    Text(address)
                        .font(.system(size: 12.5))
                        .foregroundColor(QuickInkColors.ink)
                        .lineLimit(3)
                }
                if let lat = latitude, let lng = longitude {
                    Text(String(format: "%.5f, %.5f", lat, lng))
                        .font(.system(size: 10))
                        .foregroundColor(QuickInkColors.muted)
                }
            }
            Spacer()
            if hasCoordinates {
                Button("Clear") {
                    latitude = nil
                    longitude = nil
                }
                .font(.system(size: 10.5, weight: .semibold))
                .tracking(1)
                .foregroundColor(QuickInkColors.muted)
                .buttonStyle(.plain)
            }
        }
        .padding(AppSpacing.s3)
        .background(QuickInkColors.accentSoft.opacity(0.45),
                    in: RoundedRectangle(cornerRadius: 10))
    }

    /// Run the GPS + reverse-geocode pipeline through the existing
    /// `LocationService`. Permission denied / failed-fix paths leave
    /// the fields untouched and surface a status message.
    private func useCurrentLocation() async {
        statusMessage = nil
        isFetching = true
        defer { isFetching = false }

        let service = LocationService.shared
        let initial = service.authorizationStatus
        if initial == .notDetermined {
            _ = await service.requestAuthorization()
        }
        let status = service.authorizationStatus
        guard status == .authorizedWhenInUse || status == .authorizedAlways else {
            statusMessage = "Location permission denied."
            return
        }

        guard let captured = await service.captureCurrent() else {
            statusMessage = "Couldn't read current location."
            return
        }
        latitude  = captured.latitude
        longitude = captured.longitude
        let derivedAddress: String? = captured.address
            ?? [captured.subLocality, captured.locality]
                .compactMap { $0 }
                .joined(separator: ", ")
                .nonEmpty
        if let derived = derivedAddress {
            address = derived
        }
    }
}

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
