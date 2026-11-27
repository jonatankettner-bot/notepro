# NotePro — working notes for Claude

Personal handwriting/notebook app for the owner's **Samsung Galaxy Tab S10 Ultra + S Pen**.
Goal: one app that combines what he likes in Samsung Notes, Notability and GoodNotes.
The owner is a chemistry undergraduate; the app is mainly for lecture notes and study.

## Talking with the owner
- Reply in **Hebrew**. Be direct, no filler. Bottom line first, then the key points.
- He is not a professional Android developer: explain decisions in plain words, not jargon.
- Move in stages; don't dump a whole plan or a huge change at once.
- Hedge when unsure instead of stating guesses as fact.

## Decisions already made (2026-11-27)
- **Native Android (Kotlin, Views)**, not a PWA. A side-by-side test showed native ink is far better.
- Ink engine: **androidx.ink 1.0.0** (`InProgressStrokesView` for low-latency live strokes,
  `CanvasStrokeRenderer` for finished strokes) + `androidx.input:input-motionprediction`.
- Pen only draws; finger/palm touches are ignored (optional "finger draws" toggle).
  S Pen side button (BUTTON_STYLUS_PRIMARY) = stroke eraser.
- UI language: Hebrew, RTL. minSdk 29, target/compile 36, arm64-v8a only.
- **Repo is public.** Never commit secrets: keystore, passwords, tokens, API keys.
- Sync plan (later stage): small metadata in Firebase (free Spark plan), heavy files (PDF, audio)
  in the owner's **Google Drive** — Cloud Storage for Firebase now requires the paid Blaze plan.

## How releases and updates work
- Every push to `main` runs `.github/workflows/release.yml`: builds a **signed** release APK and publishes
  GitHub release `v<run number>` with asset `NotePro.apk`. `versionCode` = workflow run number.
- Signing uses repo secrets `KEYSTORE_B64` (base64 PKCS12, alias `notepro`) and `KEYSTORE_PASSWORD`.
  The key must never change, or installed copies can't update. Without the secrets the workflow only
  does a debug build check and publishes nothing.
- The app checks `releases/latest` on start (`Updater.kt`), shows "עדכון זמין", downloads the APK and
  installs it through PackageInstaller; Android asks the user to confirm.
- So: to ship a change, commit and push to `main`. Make sure it compiles first (`./gradlew assembleDebug`).

## Roadmap (agreed order; adjust with the owner)
1. ~~Writing canvas test~~ — done (pen, pressure, prediction, palm rejection, stroke eraser, undo/redo).
2. ~~Pages~~ — first version done: continuous vertical pages (A4 ratio, world units 1000x1414), finger scroll/fling,
   pinch zoom (0.5x–5x of fit), paper lined/grid/dots/blank, auto-adds a page after the last used one.
   UI follows Notability's *layout* (top bar + tool bar + pages) with our own visual design — never copy
   their icons, colours or branding. Icons are Material Symbols (Apache 2.0) in res/drawable.
3. Tools: highlighter, colors/widths, lasso select + move/resize, undo/redo polish.
4. Organization: subjects → notebooks, local save.
5. PDF import and annotation (lecture slides).
6. Audio recording synced to ink (Notability's signature feature).
7. Cloud sync across devices.

## Code map
- `MainActivity.kt` — editor screen: top bar (title, update chip, ⋯ menu), tool bar (tools, colours, widths, undo/redo, paper).
- `NoteCanvasView.kt` — pages, view transform (world↔screen), pen vs finger input, eraser, undo/redo, rendering.
  Known perf TODO: every frame redraws all visible strokes; add per-page bitmap caching when notes get long.
- NOTHING IS SAVED YET — next stage is the library (subjects → notebooks) plus saving.
- `Updater.kt` — GitHub release check + PackageInstaller update flow.
