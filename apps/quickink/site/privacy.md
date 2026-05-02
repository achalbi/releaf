---
title: Privacy Policy
permalink: /privacy/
---

# Privacy Policy for QuickInk

**Effective date:** 2 May 2026
**Last updated:** 2 May 2026

> ⚠️ This document is a starting template generated for the QuickInk Android app. It is **not legal advice**. Review with a qualified attorney before publishing — especially if you process data of users in the EU/UK (GDPR), California (CCPA/CPRA), or other regulated jurisdictions.

This Privacy Policy explains how the QuickInk mobile application ("QuickInk", "the app", "we", "our") handles your information.

QuickInk is operated by **thoughtbasics**, based in Bengaluru, Karnataka, India.

If you have questions about this policy or your data, contact us at: **admin@thoughtbasics.com**.

---

## 1. Summary

- QuickInk does **not** operate its own servers that store your notes or scans.
- Your notes and scans are stored on **your device** and synced to **your own Google Drive** account.
- We use **Google Sign-In** to authenticate you and **Google Drive** to sync your data, both with your consent.
- Document scanning runs **on your device** using Google ML Kit. Page images are not sent to our servers.
- We do not use analytics, advertising SDKs, or third-party trackers.

## 2. Information we access

### 2.1 Google account information

When you sign in with Google, the app receives your basic Google profile (name, email address, profile picture). We use this to identify you within the app and to authenticate requests to Google Drive on your behalf. We do not receive your Google password.

### 2.2 Content you create

The app stores notes, document scans, and related metadata (titles, timestamps, page order, search index) that you create. This content is stored on your device and, when sync is enabled, in a folder inside your own Google Drive.

### 2.3 Google Drive data

QuickInk requests the **`drive.file`** scope (or equivalent app-scoped Drive scope). Under this scope, the app can only read and write files **it itself created** in your Drive. The app cannot read your other Drive files. We use this scope solely to:
- Save copies of your QuickInk notes and scans to your Drive so they can sync across devices.
- Read those files back when you sign in on another device or reinstall the app.

We do **not** transfer your Drive data to any other party. We do **not** use your Drive data for advertising, model training, or any purpose other than providing app functionality.

### 2.4 Camera

Document scanning uses Google ML Kit's document scanner, which runs on your device and is invoked through a system-provided UI. The scanner asks for camera access at the moment you start a scan. Page images are processed locally; we do not upload them to our servers (because we do not have any).

### 2.5 Diagnostic information

The app may write basic logs to your device (for example, error traces) to help diagnose problems. These logs stay on your device. We do not collect crash reports or telemetry from your device unless explicitly stated in a future version of this policy.

## 3. Information we do not collect

- We do not collect or sell advertising identifiers.
- We do not use third-party analytics SDKs.
- We do not collect your contacts, location, microphone audio, SMS, or files outside QuickInk's own Drive folder.
- We do not access your Drive files outside the `drive.file` app-created scope.

## 4. How we use the information

We use the information described above **only** to:
1. Authenticate you and keep you signed in.
2. Store and sync your notes and scans across your devices via your own Google Drive.
3. Show your name or avatar inside the app for identification.
4. Provide app functionality such as search across your own notes.

We do not sell, rent, or trade your personal information to third parties.

## 5. Sharing with third parties

QuickInk relies on the following third-party services for core functionality:

| Provider | What it does | Data involved |
|---|---|---|
| Google Sign-In | Authentication | Your Google profile basics (name, email, avatar) |
| Google Drive | Cloud sync of notes and scans | Files QuickInk creates in your Drive (`drive.file` scope) |
| Google ML Kit (on-device) | Document scanning | Page images, processed on your device only |
| Google Play Services | App distribution and updates | Standard Play telemetry handled by Google |

Each of these is governed by Google's own Privacy Policy: https://policies.google.com/privacy

We do not share your data with any third parties beyond what is required to operate these services.

## 6. Data retention

- Data on your device is retained until you delete it or uninstall the app.
- Data in your Google Drive remains in your Drive until you delete it through QuickInk or via Drive directly.
- Because we do not run our own servers, **we do not retain copies of your notes or scans** anywhere outside your own device and your own Drive.

## 7. How to delete your data

- **In-app:** open Settings → sign out, then uninstall the app. This removes local data on your device.
- **Drive content:** open Google Drive and delete the QuickInk folder, or revoke QuickInk's access at https://myaccount.google.com/permissions.
- **Account-level deletion:** because we do not store an account record on our servers, there is nothing for us to delete. If you would still like a confirmation that no residual logs exist, email **admin@thoughtbasics.com** and we will respond within 30 days.

## 8. Children's privacy

QuickInk is not directed at children under 13 (or under 16 in regions where that is the applicable age). We do not knowingly collect information from children. If you believe a child has used the app without consent, contact us and we will assist.

## 9. International users

QuickInk processes data on your device and in your own Google account. Because Google operates globally, your data may transit Google's infrastructure in countries other than your own. Google publishes its data-transfer practices at https://policies.google.com/privacy.

## 10. Security

We use Google's authentication and Drive APIs over HTTPS. Locally, your data is stored in standard application-private storage on Android, which is sandboxed from other apps. No method of transmission or storage is 100% secure; you use the app at your own risk subject to Section 11 below.

## 11. Changes to this policy

We may update this policy from time to time. The "Last updated" date at the top reflects the most recent revision. If we make material changes, we will notify you via the app or via the same channel you signed in with.

## 12. Contact

Questions, corrections, or deletion requests:

**thoughtbasics**
Email: **admin@thoughtbasics.com**
Location: Bengaluru, Karnataka, India
