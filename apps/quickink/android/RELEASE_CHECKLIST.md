# QuickInk Android — release checklist

Pre-flight before pushing an AAB to Play Console. Items grouped
by who owns them: **Code** (changes in this repo), **Console**
(work in Play Console / Google Cloud), **Assets** (graphics +
copy you author).

---

## Code — every release

- [ ] **Bump `versionCode`** in `apps/quickink/android/app/build.gradle.kts`.
      Must strictly increase across every Play upload.
- [ ] **Bump `versionName`** if user-facing. Format: `MAJOR.MINOR.PATCH`.
- [ ] **Run release build locally** and install the AAB or its
      universal APK on a real device. R8 may strip something the
      debug build doesn't catch.
      ```bash
      cd shared/android && ./gradlew :apps:quickink:bundleRelease
      ```
- [ ] **Smoke test the install build:**
      - Sign in with Google succeeds (real OAuth client, not stub)
      - Document scanner launches and writes a capture
      - Note shows in Recent rail with preview thumbnail
      - Sync pill flips Pending → Synced
      - Settings → sign out + sign back in works
- [ ] **Lint + unit tests pass:**
      ```bash
      ./gradlew :apps:quickink:lint :apps:quickink:test
      ```
- [ ] No new debug-only code (logs, TestTags, test buttons)
      slipping into release.

## Code — first release only

- [ ] Upload keystore generated and stored safely (see
      [SIGNING.md](./SIGNING.md)).
- [ ] `keystore.properties` (or env vars) wired so
      `bundleRelease` produces a properly-signed AAB.
- [ ] R8 / ProGuard rules verified — `proguard-rules.pro` covers
      QuickInk's @Serializable types and reflection-using deps.
      Keep an eye out for runtime `ClassNotFoundException` after
      shipping; those usually mean a missing keep rule.
- [ ] Manifest declares only the permissions actually used
      (currently just `INTERNET`). ML Kit's document scanner
      handles its own camera permission.

## Console — Google Cloud (OAuth verification)

QuickInk uses the Drive OAuth scope, which Google treats as
**sensitive**. Verification is run by Google, takes 1–6 weeks,
and must pass before the app can request the scope from
non-internal users.

- [ ] **Privacy policy URL** published at a stable HTTPS URL.
      Required by Play AND by OAuth verification. Must explain
      what Drive data the app accesses, why, and how it's
      retained.
- [ ] **App home page URL** — also required for verification.
- [ ] **OAuth consent screen** completed in Google Cloud Console
      with logo, support email, scope justifications.
- [ ] **Scope justification video** recorded and uploaded
      (Google asks for a short Loom-style walkthrough of how the
      app uses each sensitive scope).
- [ ] **Domain ownership** verified for the privacy policy /
      home page domain (Search Console TXT record).
- [ ] **Submit for verification** — track status in Google Cloud
      Console > APIs & Services > OAuth consent screen.

## Console — Play Console listing

- [ ] **Developer account** created (one-time $25 fee, identity
      verification can take a few days for personal accounts).
- [ ] **App created** in Play Console with QuickInk's
      `app.quickink.mobile` package name.
- [ ] **Default language + app/game + free/paid** selected.
- [ ] **Store listing** filled out:
  - Short description (≤ 80 chars)
  - Full description (≤ 4000 chars)
  - App category, tags
  - Contact details (email + optional phone, website)
- [ ] **Content rating questionnaire** completed.
- [ ] **Target audience + content** declared (age range, etc.).
- [ ] **Data safety form** completed:
  - Data collected: Google account info (email, display name),
    Drive file content (scans + notes), OCR text on-device
  - Data shared: none (all sync is between user's own devices via
    their own Drive account)
  - Encryption in transit: yes (HTTPS to Drive)
  - Data retention + deletion: explain that uninstalling removes
    local data; Drive contents are user-managed
- [ ] **Ads declaration**: no ads.
- [ ] **News app, COVID-19 app, financial app declarations**: no.
- [ ] **App access** — if any feature is gated behind sign-in
      that reviewers can't reach, provide test credentials.

## Assets — store listing

- [ ] **App icon** 512×512 PNG (high-res for the Play listing,
      separate from the in-app launcher icon).
- [ ] **Feature graphic** 1024×500 PNG (banner shown at top of
      Play listing).
- [ ] **Phone screenshots** — at least 2, up to 8. 16:9 or 9:16,
      min 320px on the short side. Show: home, scanner, note view,
      search.
- [ ] *(Optional but recommended)* 7-inch + 10-inch tablet
      screenshots if the app supports tablets.
- [ ] *(Optional)* Promotional video — 30s YouTube link.

## Console — release tracks

QuickInk's first production release path:

1. **Internal testing** — push the AAB, add yourself as a
      tester, install on a real device. Iterate until clean.
2. **Closed testing** — required for new personal accounts.
   Need ≥ 12 testers participating ≥ 14 days before production
   access opens. Set up an email list of testers in Play Console.
3. **Production** — submit for review. First review can take up
   to 7 days; updates usually < 1 day.

- [ ] Testers recruited (12+ for personal accounts).
- [ ] Closed-test feedback collected and addressed.
- [ ] **Staged rollout** plan: start at 10% → monitor crashes
      → 50% → 100%. Use Play Console's "halt rollout" if metrics
      degrade.

## Things to remember

- **`versionCode` only goes up.** Reusing one is rejected.
- **Don't lose the upload keystore.** See [SIGNING.md](./SIGNING.md).
- **Aug 2026: `targetSdk` floor moves to 36** — schedule the
      bump well before that. Currently at 35.
- **Privacy policy and OAuth verification are the slow path.**
      Start them in parallel with the rest of this checklist.

---

## Status snapshot (auto-managed below this line)

Last checked against repo: 2026-05-02

- Signing config: ✅ wired (keystore.properties OR env vars)
- R8 + shrinkResources: ✅ on for release
- Permissions: ✅ INTERNET only (correct for current code)
- OAuth web client ID: ✅ real value in `strings.xml`
- Privacy policy URL: ❌ TODO (you)
- Drive OAuth verification: ❌ TODO (you)
- Closed-test track set up: ❌ TODO (you)
- Store listing assets: ❌ TODO (you)
