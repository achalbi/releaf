# Onboarding Wizard — Handoff for Releaf

A self-contained package of the Inkcreate onboarding wizard, ready to port to
Releaf (SwiftUI + Jetpack Compose). The goal is visual and behavioural parity
with the web source, using native components instead of HTML/CSS/Stimulus.

```
onboarding/
├── README.md                              ← you are here
└── source/
    ├── _onboarding_wizard.html.erb        10-step wizard markup (Rails partial)
    ├── onboarding.css                     extracted CSS (all visuals + animations)
    ├── onboarding_wizard_controller.js    Stimulus controller (step state + dismiss)
    ├── onboarding_controller.rb           server dismiss endpoint (NOT needed in Releaf)
    └── app-icon.svg                       app mark shown in step 1 and step 9
```

The web source uses 10 step panels (Welcome + 9 feature tours). The "Step N of
9" badges are authored literally in the ERB — only steps 2–10 show them, and
they count from "Step 1 of 9" (Notebooks) through "Step 9 of 9" (final).

---

## 1. Step-by-step content

Copy verbatim unless noted. **Rename "Inkcreate" → "Releaf" in every string.**

| # | Badge         | Headline                        | Body copy (bold marked with **)                                                                                                                                                                  | Primary CTA    |
|---|---------------|---------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------|
| 1 | — (none)      | Welcome to Releaf               | Your workspace for projects, daily notes, voice recordings, scanned documents, and action items — all in one place.                                                                              | Get started →  |
| 2 | Step 1 of 9   | Notebooks for projects          | Create a notebook for each project. Organise it into **chapters**, then add **pages** with rich notes, photos, and voice recordings — everything in context.                                    | Next →         |
| 3 | Step 2 of 9   | Notepad for daily capture       | The Notepad gives each day its own **page**. Jot quick thoughts, record voice notes, scan documents, or build a to-do list — without creating a notebook and chapter first.                     | Next →         |
| 4 | Step 3 of 9   | Photos, your way                | Add photos directly to any page. Choose your preferred **capture quality** in Settings — balance between image clarity and storage size to suit your workflow.                                   | Next →         |
| 5 | Step 4 of 9   | Voice notes                     | Record a voice note on any page with one tap. Play it back inline, or generate a **transcription on demand** to turn speech into searchable text.                                                | Next →         |
| 6 | Step 5 of 9   | To-do lists                     | Add a checklist to any page. Set a **reminder** on an item to get notified at the right time, or **promote it to a Task** when it needs tracking across your workspace.                          | Next →         |
| 7 | Step 6 of 9   | Scan → PDF in seconds           | Point your camera at any document. Releaf detects the edges, lets you crop and enhance it, then saves it as a PDF attached to your page.                                                          | Next →         |
| 8 | Step 7 of 9   | Move pages into a Notebook      | When a Notepad page grows into something worth keeping, move it. Tap **Migrate to Notebook** on any Notepad page to place it into the right chapter — structure added, nothing lost.              | Next →         |
| 9 | Step 8 of 9   | Backed up to Google Drive       | Connect your Google Drive in Settings and Releaf will **back up your data automatically** — notes, voice recordings, photos, and scanned documents all kept safe in your own Drive.               | Next →         |
| 10| Step 9 of 9   | You're all set!                 | Start where it makes sense for you. Everything else will become clear as you go.                                                                                                                  | Let's go ✓     |

**Back button**: every step 2–10 shows `← Back` as a ghost (secondary) button to
the left of the primary action.

**Skip link**: top-right of the modal, label `Skip ✕`, available on every step.
Tapping it dismisses the wizard and marks onboarding complete (same as finish).

**Final-step CTA cards** (step 10, above the action row): two equal-width cards
that route to the first real destination in the app.

| Icon | Label (line 1 / line 2)         | Destination              |
|------|----------------------------------|--------------------------|
| 📓   | Create a / **Notebook**         | new notebook screen      |
| 📅   | Open today's / **Notepad**      | today's notepad entry    |

Tapping either card dismisses the wizard (via `finish`), marks onboarding
complete, and navigates.

---

## 2. Illustrations per step

Each step has a 140-pt illustration block above the headline. All visuals are
built from plain shapes + emoji + one SVG — no raster assets, so they port
cleanly to native.

| # | Illustration                                                                                       |
|---|----------------------------------------------------------------------------------------------------|
| 1 | 72×72 app icon (rounded 18) with coral shadow + three coral sparkles (✦) scattered around it.     |
| 2 | Three stacked notebook emoji cards (📑 back @ 50% opacity rotated −8°, 📄 mid @ 75% rotated −3°, 📓 front).|
| 3 | Calendar card: coral "April" header, huge date number (today's day-of-month), two grey text lines.|
| 4 | White square photo frame with 📷 lens, coral pill labelled `Settings → Quality` below.             |
| 5 | 🎙️ icon beside a 9-bar coral waveform (bars of varying heights).                                  |
| 6 | Three to-do rows: ✓ checked ("Buy groceries", strikethrough), unchecked ("Call dentist" + ⏰ tag), unchecked ("Finish report" + coral `Task` pill). |
| 7 | Document rectangle with 4 coral L-shaped corner marks and 4 grey text lines inside, coral `PDF ✓` badge below, then a row of 4 pills (`Capture`, `Detect`, `Enhance` peach "done", `Save` coral "active"). |
| 8 | Small calendar card (Apr + day) → coral arrow → single 📓 notebook card.                           |
| 9 | App icon → coral arrow → Google Drive logo (multicolour SVG). Both at 64×64 with soft shadow.      |
|10 | 72×72 coral-outlined circle with a coral checkmark (inline SVG — see `source/_onboarding_wizard.html.erb` lines 268–271). |

The Google Drive SVG is embedded inline at lines 241–248 of the ERB — copy it
verbatim into the Compose/SwiftUI asset set as `gdrive.svg`.

---

## 3. Design tokens

Extracted from `source/onboarding.css`. Use these exact values so the native
port matches the web.

### Colors
```
coral (primary)          #ff5f4e
coral faded (done-dot)   #ffb8b2
coral 15% (hover shadow) rgba(255,95,78,0.15)
coral 25% (logo shadow)  rgba(255,95,78,0.25)
cream bg (modal)         #fdf8f4
white cards              #ffffff
text primary             #1b1b1d
text body                #4a4845
text muted (ghost btn)   #7a7670
text subtle (dot rest)   #9e9990
border (rest)            #e2dbd4
strip / line fill        #ede8e3
ghost hover bg           #f0ece8
backdrop                 rgba(27,27,29,0.55) + 6px blur
shadow (modal)           0 24px 64px rgba(27,27,29,0.22)
shadow (cards)           0 4px 16px rgba(27,27,29,0.12)
todo-task pill bg/fg     #fff0e8 / #e06020
scan-done pill bg/fg     #ffd5d1 / #c0392b
```

### Typography
```
headline      1.45rem (≈ 23pt) / 800 / #1b1b1d / line-height 1.25
body          0.92rem (≈ 15pt) / 400 / #4a4845 / line-height 1.60
step badge    0.70rem (≈ 11pt) / 700 / #9e9990 / uppercase / letter-spacing 0.06em
button        0.88rem (≈ 14pt) / 700
skip link     0.75rem (≈ 12pt) / 400 / #9e9990
calendar hdr  0.70rem (≈ 11pt) / 700 / #ff5f4e / uppercase / letter-spacing 0.05em
calendar num  2.40rem (≈ 38pt) / 800 / #1b1b1d
cta card      0.80rem (≈ 13pt) / 400 / line-height 1.4
```

Use system fonts — SF Pro on iOS, Roboto on Android. No custom typeface.

### Spacing & radii
```
modal                max-width 440pt, padding 32/28/28 (top / sides / bottom)
modal radius         20pt (top-only on mobile bottom-sheet: 20/20/0/0)
illustration height  140pt
dots row             gap 8pt, bottom margin 24pt
dot                  8×8pt, radius 50% (rest) → 24×8pt radius 4pt (active)
step badge           margin-bottom 8pt
headline             margin-bottom 10pt
body                 margin-bottom 24pt
action row           gap 10pt, right-aligned (centred on final step)
button               padding 10/20pt, radius 10pt
cta card             padding 14/12pt, radius 14pt, border 2pt #e2dbd4
cta cards row        gap 12pt, bottom margin 20pt
mobile bottom-sheet  full-width, padding 28/20/36, radius top-only
```

### Icons & emoji
Steps 2–6, 8, 10 reuse system emoji (📑 📄 📓 📅 📷 🎙️ ✓ ⏰ →). On SwiftUI
use `Text("📓")`. On Compose use `Text("📓")`. No special font loading needed.

The app icon and the Google Drive icon are the only proper SVGs — ship them as
vector assets:
- iOS: import `app-icon.svg` as an Asset Catalog symbol, import `gdrive.svg`
  similarly.
- Android: use Android Studio's "New → Vector Asset" to import both as
  `app_icon.xml` and `ic_gdrive.xml`.

---

## 4. Animations

| Event               | Duration | Easing                              | Transform                       |
|---------------------|----------|-------------------------------------|---------------------------------|
| Backdrop fade in    | 0.25s    | ease                                | opacity 0 → 1                   |
| Modal enter (desktop)| 0.30s   | cubic-bezier(0.22, 1, 0.36, 1)      | translateY 24pt → 0, opacity 0 → 1 |
| Modal enter (mobile, ≤480pt wide) | 0.35s | cubic-bezier(0.22, 1, 0.36, 1) | translateY 100% → 0 (bottom sheet) |
| Dismiss             | 0.35s    | ease forwards                       | opacity 1 → 0                   |
| Dot state change    | 0.20s    | linear (default transition)         | background, transform, width    |

### SwiftUI mapping
- Enter: `.transition(.move(edge: .bottom).combined(with: .opacity))` with
  `.animation(.timingCurve(0.22, 1, 0.36, 1, duration: 0.35), value: isShown)`.
- Dots: `.animation(.easeInOut(duration: 0.20), value: currentStep)`.

### Compose mapping
- Enter: `AnimatedVisibility(visible = isShown, enter = slideInVertically(...) + fadeIn(...))`
  with `tween(350, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f))`.
- Dots: `animateDpAsState(target = if (isActive) 24.dp else 8.dp)` +
  `animateColorAsState(...)`.

On phones (both platforms), present as a bottom sheet. On iPad / tablet,
present as a centred card with the desktop slide-up animation.

---

## 5. State machine

The web controller exposes five actions. Match this in your view model —
naming is up to you.

```
show()       reset currentStep = 1, display the modal
next()       if currentStep < 10 → currentStep++; else finish()
prev()       if currentStep > 1  → currentStep--
goTo(n)      jump to step n (1..10); used by dot taps
finish()     dismiss + mark complete
skip()       dismiss + mark complete (same semantics as finish)
```

The web version persists "dismissed" by POSTing to `/onboarding/dismiss` which
sets `users.onboarding_completed_at`. **Replace this with local persistence:**

### iOS
```swift
@AppStorage("onboarding.completedAt") private var completedAt: Double = 0

func markComplete() {
    completedAt = Date().timeIntervalSince1970
}

var shouldAutoshow: Bool { completedAt == 0 }
```

### Android
```kotlin
// DataStore
val ONBOARDING_COMPLETED_AT = longPreferencesKey("onboarding_completed_at")

suspend fun markComplete(ctx: Context) {
    ctx.dataStore.edit { it[ONBOARDING_COMPLETED_AT] = System.currentTimeMillis() }
}

val shouldAutoshow: Flow<Boolean> =
    dataStore.data.map { (it[ONBOARDING_COMPLETED_AT] ?: 0L) == 0L }
```

Drop the entire `onboarding_controller.rb` — it only exists because the web
stores dismiss state on the server. Also drop the `sessionStorage` guard in
the JS controller; on native, the single `completedAt` check is enough.

---

## 6. SwiftUI port recipe

Structure: a single `OnboardingWizard` view held by the root app view.
Presented modally (full-screen cover on phone, sheet on tablet).

```swift
struct OnboardingWizard: View {
    @Binding var isShown: Bool
    @AppStorage("onboarding.completedAt") private var completedAt: Double = 0
    @State private var step = 1
    private let totalSteps = 10

    var body: some View {
        VStack(spacing: 0) {
            header                // Skip button, top-right
            dots                  // progress dots row
            TabView(selection: $step) {
                WelcomeStep(next: advance).tag(1)
                NotebooksStep(back: back, next: advance).tag(2)
                NotepadStep(back: back, next: advance).tag(3)
                PhotosStep(back: back, next: advance).tag(4)
                VoiceStep(back: back, next: advance).tag(5)
                TodoStep(back: back, next: advance).tag(6)
                ScanStep(back: back, next: advance).tag(7)
                MigrateStep(back: back, next: advance).tag(8)
                BackupStep(back: back, next: advance).tag(9)
                DoneStep(back: back, finish: finish).tag(10)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))   // we draw our own dots
            .animation(.easeInOut(duration: 0.25), value: step)
        }
        .padding(.horizontal, 28).padding(.top, 32).padding(.bottom, 28)
        .frame(maxWidth: 440)
        .background(Color(hex: "fdf8f4"))
        .clipShape(RoundedRectangle(cornerRadius: 20))
    }

    private func advance() { step < totalSteps ? step += 1 : finish() }
    private func back()    { if step > 1 { step -= 1 } }
    private func finish()  { completedAt = Date().timeIntervalSince1970; isShown = false }
}
```

Wire it up in the root:
```swift
@AppStorage("onboarding.completedAt") private var completedAt: Double = 0
@State private var showOnboarding = false

WorkspaceView()
    .onAppear { if completedAt == 0 { showOnboarding = true } }
    .fullScreenCover(isPresented: $showOnboarding) {
        OnboardingWizard(isShown: $showOnboarding)
    }
```

**Dots row** — eight rounded rects that grow to 24pt when active:
```swift
HStack(spacing: 8) {
    ForEach(1...totalSteps, id: \.self) { i in
        RoundedRectangle(cornerRadius: i == step ? 4 : 4)
            .fill(dotColor(for: i))
            .frame(width: i == step ? 24 : 8, height: 8)
            .onTapGesture { step = i }
            .animation(.easeInOut(duration: 0.20), value: step)
    }
}

func dotColor(for i: Int) -> Color {
    if i == step { return Color(hex: "ff5f4e") }
    if i  < step { return Color(hex: "ffb8b2") }
    return Color(hex: "e2dbd4")
}
```

---

## 7. Compose port recipe

```kotlin
@Composable
fun OnboardingWizard(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = 0, pageCount = { 10 })

    fun next() = scope.launch {
        if (pager.currentPage < 9) pager.animateScrollToPage(pager.currentPage + 1)
        else finish(context, onDismiss)
    }
    fun back() = scope.launch {
        if (pager.currentPage > 0) pager.animateScrollToPage(pager.currentPage - 1)
    }

    ModalBottomSheet(
        onDismissRequest = { finish(context, onDismiss) },
        containerColor = Color(0xFFFDF8F4),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 28.dp, bottom = 36.dp)) {
            SkipButton(onClick = { finish(context, onDismiss) })
            Dots(current = pager.currentPage, total = 10, onTap = { i ->
                scope.launch { pager.animateScrollToPage(i) }
            })
            HorizontalPager(state = pager) { page ->
                when (page) {
                    0 -> WelcomeStep(onNext = ::next)
                    1 -> NotebooksStep(onBack = ::back, onNext = ::next)
                    2 -> NotepadStep(onBack = ::back, onNext = ::next)
                    3 -> PhotosStep(onBack = ::back, onNext = ::next)
                    4 -> VoiceStep(onBack = ::back, onNext = ::next)
                    5 -> TodoStep(onBack = ::back, onNext = ::next)
                    6 -> ScanStep(onBack = ::back, onNext = ::next)
                    7 -> MigrateStep(onBack = ::back, onNext = ::next)
                    8 -> BackupStep(onBack = ::back, onNext = ::next)
                    9 -> DoneStep(onBack = ::back, onFinish = { finish(context, onDismiss) })
                }
            }
        }
    }
}

private suspend fun finish(ctx: Context, onDismiss: () -> Unit) {
    ctx.dataStore.edit { it[ONBOARDING_COMPLETED_AT] = System.currentTimeMillis() }
    onDismiss()
}
```

**Dots row:**
```kotlin
@Composable
fun Dots(current: Int, total: Int, onTap: (Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { i ->
            val active = i == current
            val done   = i < current
            val width by animateDpAsState(if (active) 24.dp else 8.dp)
            val color by animateColorAsState(
                when {
                    active -> Color(0xFFFF5F4E)
                    done   -> Color(0xFFFFB8B2)
                    else   -> Color(0xFFE2DBD4)
                }
            )
            Box(
                Modifier.size(width, 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
                    .clickable { onTap(i) }
            )
        }
    }
}
```

Wire-up at the root:
```kotlin
val completedAt by context.dataStore.data
    .map { it[ONBOARDING_COMPLETED_AT] ?: 0L }
    .collectAsState(initial = -1L)

when {
    completedAt == -1L -> {}                         // still loading
    completedAt == 0L  -> OnboardingWizard(onDismiss = { /* completedAt recomposes */ })
    else               -> WorkspaceScreen()
}
```

---

## 8. Acceptance checklist

Port is done when:

- [ ] All 10 steps render with the copy in section 1 (verbatim, with "Inkcreate" → "Releaf").
- [ ] Step badges say "Step N of 9" on steps 2–10; step 1 has no badge.
- [ ] Dots: inactive 8×8 grey, active 24×8 coral pill, completed 8×8 coral-faded. Tapping a dot jumps to that step.
- [ ] Skip link works on every step and marks complete.
- [ ] Back is hidden on step 1 and visible everywhere else.
- [ ] Final step shows the two CTA cards (Notebook, Notepad); each navigates and dismisses.
- [ ] "Let's go ✓" dismisses and marks complete.
- [ ] Dismiss writes a timestamp to local storage (UserDefaults / DataStore); re-launching the app after dismissing does **not** re-show the wizard.
- [ ] Auto-show on first launch after install (when `completedAt == 0`).
- [ ] Enters as a bottom sheet on phones and a centred card on tablets.
- [ ] All animation timings match section 4 (tolerance: ±50ms).
- [ ] All colours match section 3 (eyeball check on steps 1, 7, 10 — the most visually distinct).

---

## 9. What's intentionally dropped in the native port

- `onboarding_controller.rb` — no server. Dismiss state lives in local storage.
- `form_authenticity_token` / CSRF — N/A without a server.
- `sessionStorage` guard — not needed; the `completedAt` check is the source of truth.
- Turbo navigation guards — N/A on native.
- The `autoshow` local-assigns flag — decide with `completedAt == 0` at the root.
