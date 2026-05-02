# QuickInk Android — release signing

This module signs Play uploads with an **upload key** that you
generate locally and never commit. Google's Play App Signing
service holds the actual deployment signing key on its side and
re-signs the AAB before distribution; the upload key only
authenticates uploads to Play Console.

## One-time: generate the upload keystore

Run this once, on a machine you control. The command produces a
`.jks` file containing one key alias.

```bash
keytool -genkey -v \
  -keystore upload-keystore.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias upload
```

You'll be prompted for:
- A **store password** — protects the file.
- A **key password** — protects the alias inside it. Use the same
  as the store password unless you have a reason not to.
- Distinguished name fields (CN, OU, O, L, ST, C). For a personal
  account, your name + city/country is fine.

**Where to put the file:**
- Personal machine: somewhere outside the repo, e.g.
  `~/.android/keystores/quickink-upload.jks`.
- CI: never check in. Store as a base64-encoded secret, materialize
  to disk in the build step.

**Back the file up.** If you lose the keystore you cannot push
updates to the same Play listing — you'd have to register a new
listing and migrate users. Keep a copy in your password manager
or encrypted cloud storage.

## Wiring the build

`apps/quickink/android/app/build.gradle.kts` reads keystore
secrets from one of two sources, in order:

### 1. Local props file (preferred for dev machines)

Create `apps/quickink/android/keystore.properties` with:

```properties
storeFile=/Users/you/.android/keystores/quickink-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

`storeFile` may be absolute or relative to the **repo root**. The
file is gitignored.

### 2. Environment variables (preferred for CI)

```bash
export QUICKINK_UPLOAD_STORE_FILE=/path/to/upload-keystore.jks
export QUICKINK_UPLOAD_STORE_PASSWORD=...
export QUICKINK_UPLOAD_KEY_ALIAS=upload
export QUICKINK_UPLOAD_KEY_PASSWORD=...
```

If neither source is configured, `:apps:quickink:bundleRelease`
still produces an AAB but signed with the **debug** keystore.
That AAB is fine for local install + smoke testing; Play Console
will reject it.

## Building the AAB

```bash
cd shared/android
./gradlew :apps:quickink:bundleRelease
```

Output:
`apps/quickink/android/app/build/outputs/bundle/release/quickink-release.aab`

## First upload to Play Console

1. Create the QuickInk app listing in Play Console.
2. On first AAB upload, **enroll in Play App Signing**. Upload
   your locally-built AAB; Play extracts the signing certificate
   from it and registers it as the upload certificate. Play
   generates its own deployment key on the server side.
3. From this point on, every update must be signed with the same
   upload key (the `.jks` file you just generated). If you lose
   it, contact Play support to reset the upload key — Play will
   continue to re-sign with the (unchanged) deployment key, so
   users get updates seamlessly.

## SHA-1 fingerprints (for OAuth)

The Google Cloud OAuth client tied to QuickInk needs the SHA-1
fingerprint of the certificate that ultimately signs the
installed APK on a user's device. After enrolling in Play App
Signing:

- **App signing key SHA-1** — Play Console > Setup > App Integrity.
  Add this fingerprint to the OAuth Android client in Google Cloud
  Console.
- **Upload key SHA-1** — also listed there. Add this too if you
  want internal/closed-test builds (signed only with the upload
  key, before Play re-signs) to authenticate against Google APIs.

```bash
# Get the upload-key SHA-1 locally:
keytool -list -v -keystore upload-keystore.jks -alias upload
```

The current OAuth client ID (`google_web_client_id` in
`res/values/strings.xml`) is `534102618638-…`. That's the
**web** client; the Android client is what carries the SHA-1
fingerprints.
