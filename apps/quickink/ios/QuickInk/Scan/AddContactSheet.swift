/*
 * AddContactSheet.swift
 *
 * SwiftUI wrapper around `CNContactViewController.forNewContact(_:)`
 * — presents the system "new contact" form pre-filled with the
 * supplied name + phone numbers. Used by `ScanDetailScreen`'s
 * "Add to contact" action when the capture is a Business Card.
 *
 * No Contacts permission is required for this path: the "new
 * contact" UI takes the user's input through the system sheet and
 * commits via Contacts.app, which handles the permission UX itself.
 * This means we don't need to ship `NSContactsUsageDescription` for
 * the feature.
 *
 * Mirror of Android's `Intent(ContactsContract.Intents.Insert.ACTION)`
 * launch path.
 */

import SwiftUI
import Contacts
import ContactsUI

struct AddContactSheet: UIViewControllerRepresentable {

    /// Best-guess full name from the parser. Split on the first
    /// whitespace into given / family — passable for typical
    /// "Firstname Lastname" cards. The user can edit either field
    /// in the system sheet before saving.
    let name: String?
    /// Normalised mobile numbers — first one is tagged as Mobile,
    /// any extras land as additional Mobile entries in the form.
    let phones: [String]
    /// Fired when the user dismisses the sheet via Done or Cancel.
    /// Lets the parent flip its presentation flag.
    let onDismiss: () -> Void

    func makeUIViewController(context: Context) -> UIViewController {
        let contact = CNMutableContact()
        if let name, !name.isEmpty {
            // Greedy first-token-as-given split. Multi-word last
            // names ("Van Der Berg") collapse into the family name.
            let parts = name
                .split(whereSeparator: { $0.isWhitespace })
                .map(String.init)
            contact.givenName = parts.first ?? ""
            if parts.count > 1 {
                contact.familyName = parts.dropFirst().joined(separator: " ")
            }
        }
        contact.phoneNumbers = phones.map { phone in
            CNLabeledValue(
                label: CNLabelPhoneNumberMobile,
                value: CNPhoneNumber(stringValue: phone)
            )
        }
        let vc = CNContactViewController(forNewContact: contact)
        vc.delegate = context.coordinator
        // Wrap in UINavigationController so the system Cancel / Done
        // bar buttons render — `forNewContact` requires a nav stack.
        let nav = UINavigationController(rootViewController: vc)
        return nav
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onDismiss: onDismiss) }

    final class Coordinator: NSObject, CNContactViewControllerDelegate {
        private let onDismiss: () -> Void
        init(onDismiss: @escaping () -> Void) { self.onDismiss = onDismiss }

        // Fired both on Cancel (contact == nil) and on Done
        // (contact != nil). We don't need to differentiate — either
        // way the sheet should close.
        func contactViewController(
            _ viewController: CNContactViewController,
            didCompleteWith contact: CNContact?
        ) {
            viewController.dismiss(animated: true) { [weak self] in
                self?.onDismiss()
            }
        }
    }
}
