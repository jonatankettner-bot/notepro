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
- Editor layout (owner, 2026-11-27): floating one-row tool pill at the top centre (tap the selected tool
  again for its style tray); back + title float in the top start corner; action rail (undo/redo/paper/⋯)
  on the **left** screen edge (maybe movable later, not now). S Pen side button stays = eraser while held
  (no radial quick-tools menu). See docs/design-research.md.
- Pace (owner): get to a polished, Notability-like app fast, then improve. Breadth of UI first
  (settings, themes, stickers, clips…), polish later.
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

## Building in a fresh cloud session
The Android SDK is not preinstalled. Gradle and Java 17+ usually are. Install the SDK once per session:
```bash
export ANDROID_HOME=/opt/android-sdk && mkdir -p $ANDROID_HOME/cmdline-tools && cd /tmp
curl -sO https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
unzip -q -o commandlinetools-linux-16111833_latest.zip -d $ANDROID_HOME/cmdline-tools
mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-36" "build-tools;36.0.0"
echo "sdk.dir=$ANDROID_HOME" > <repo>/local.properties   # local.properties is git-ignored
```
Then `./gradlew assembleDebug`. Maven Central sometimes answers 429 through the proxy — wait a minute and retry.
A cloud session has no emulator: the owner tests on the tablet, so describe what to check after each release.
Docs-only commits: add `[skip ci]` to the commit message so no empty release is published.

On the owner's Windows PC: JDK 17 and the Android SDK are portable in
`Documents\NotePro-research\buildtools` (`jdk`, `sdk`). Not under %LOCALAPPDATA%: writes there from the
Claude app are virtualized and invisible to other processes. Gradle needs TEMP/TMP pointed at a short path
(`C:\nptmp`) and `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=C:\nptmp -Djdk.net.unixdomain.tmpdir=C:\nptmp`,
or it fails with "Unable to establish loopback connection".
There is a tablet-sized emulator there too: AVD `NoteProTab` (2960x1848, 320 dpi, Android 36 x86_64).
Build for it with `gradlew assembleDebug -Pemu` (adds the x86_64 ABI; release builds stay arm64-only),
drive it with `adb shell input tap/stylus swipe`, and check screens with `adb exec-out screencap -p`.

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
- `MainActivity.kt` — editor screen: floating tool pill + style tray (5 colour slots per tool, full palette
  popover, 3 widths; eraser sizes + Whole/Partial), title, left rail, page counter, ⋮ menu (its toggles write
  to `Prefs`). Optional docked toolbar (`Prefs.dockedToolbar`): a white strip behind pill + title. Autosaves
  3 s after the last change, plus on onPause.
- Toolbar (MainActivity, "floating toolbar (two rows)"): row 1 = pen, fountain pen, pencil, highlighter,
  eraser, lasso, insert, shapes, sticky note | text, collapse chevron (insert/shapes/sticky/text only show
  "coming soon" for now). Row 2 = the tool's options: colour slots (tap selected = colour popover,
  long-press = change/remove, + adds up to 6) | 3 width presets (tap selected = width slider) | pen style.
  Per-tool colours/widths live in `InkSettings.kt` and are saved in Prefs (keys `pen.*`, `fountain.*`,
  `pencil.*`, `hl.*`, `pen.type`, `pen.effect`, `pen.stab`, `tool`, `recentColors`).
- `Popovers.kt` — Bubble popover with arrow (`showPopover`), colour popover (recent colours, 4 swipeable
  palette pages from `COLOR_PAGES`, eyedropper, custom colour dialog), width slider, pen style popover
  (variable / fixed / dashed / dotted, rainbow / glitter effects, stabilization 0.0–1.0), shared small views.
- `Brushes.kt` — `Families`: every brush family and the 1-byte code stored per stroke in ink.bin
  (low 4 bits kind, high 4 bits stabilization level). Custom families use the ink custom-brush API
  (`@ExperimentalInkCustomBrushApi`): dotted = particle tip, fountain = flat nib at 45°, pencil = pressure
  opacity + noise, rainbow = hue offset by distance, glitter = extra sparkle coat, stabilization =
  `SlidingWindowModel`. Codes 0..3 are the old ones, so old notes load unchanged.
- `Shapes.kt` — draw + rest the pen ~0.4 s = shape snapping (owner's spec, 2026-11-27):
  `ShapeRecognizer` turns the stroke into a line / broken line / circle / ellipse / square / rectangle /
  equilateral, isosceles or right triangle / (isosceles) trapezoid / parallelogram / regular 5–8-gon / free
  polygon, preferring the "perfect" variant when close. Arcs and curves stay freehand. While the pen is
  still down it drags the nearest handle (ellipse axis, rectangle corner, line end ...); circle / square
  snap and their guide lines turn solid. After lifting, the shape keeps handles: drag a handle, drag the
  outline to move, rotate with the ↻ handle; a finger tap on any shape brings its handles back.
  Smart guides (NoteCanvasView "smart guides"): magnet to other shapes' points, align with other strokes'
  edges / centres and the page centre, level / upright / 45° / parallel lines (pink dashed guides).
  Shapes are saved in notes/<id>/shapes.json keyed by stroke index. Shape strokes use
  `Families.exact()` (no input smoothing — it bent the start of perfect circles).
- `Items.kt` — page items under the ink: pictures (gallery / camera, copied into notes/<id>/media/,
  ≤2048 px, EXIF-upright), stickers (emoji in 4 topics, drawn as bitmaps) and sticky notes (coloured card
  with text). Saved in notes/<id>/items.json. On the canvas (NoteCanvasView "page items"): a finger tap
  selects, then drag to move, corners to resize (notes: free w/h; others keep aspect), the ↻ handle to rotate
  (snaps to 15°); the lasso pen picks items up directly. Floating menu in MainActivity (edit / colour for
  notes, duplicate, delete). Every change is one `Act.Items` undo step. New items go to a free spot that is
  on screen (`freeSpot`), and the view scrolls to anything placed off screen (`bringIntoView`).
  The shapes tool button (MainActivity `shapesPopover`) places ready perfect shapes with the pen's settings.
- `Ui.kt` — shared UI: `showMenu` (our own RTL popup menu; use it instead of PopupMenu, which lays out
  left-to-right on an English-language device), `pressable` ripple background, `Switch.accent()`,
  `AspectImageView`, `relativeDate`.
  Gotcha: inside `Paint().apply { }` a bare `color` means the Paint's own colour, so never name a colour
  parameter `color` in a custom view (this once drew every colour swatch black).
- `SettingsActivity.kt` / `Prefs.kt` — settings screen (centred 720dp column) and the stored preferences.
- `PaperDialog.kt` — centred template dialog (paper type thumbnails + paper colours, Cancel/Save).
- `Theme.kt` — NotePro's colours and the colour palettes.
- `NoteCanvasView.kt` — pages, view transform (world↔screen), pen vs finger input, whole/partial eraser,
  undo/redo, rendering, page counter callback.
  Known perf TODO: every frame redraws all visible strokes; add per-page bitmap caching when notes get long.
- `LibraryActivity.kt` — launcher/home: sidebar (All / Recent / Favourites / Unfiled, nested folders with
  colour or emoji, long-press menu), note-card grid with thumbnails and `⋮` menu, search, "+ הערה חדשה".
- `NoteStore.kt` — local storage in filesDir: library.json (folders), notes/<id>/meta.json, ink.bin (own
  binary stroke format), thumb.png, shapes.json, items.json, media/. Brush families live in Brushes.kt.
- Icons: own simple stroke drawings (ic_notes, ic_recent, ic_star, ic_inbox, ic_tune, ic_search, ic_add,
  ic_folder, ic_expand_more, ic_pages, ic_lasso, ic_more_vert) plus a few Material Symbols. App icon:
  adaptive `mipmap-anydpi-v26/ic_launcher.xml` (blue background, tilted ruled page, highlighter stripe,
  handwriting line), own design.
- Round 2 plan and order: docs/design-research.md → "Round 2 build plan".
- `Updater.kt` — GitHub release check + PackageInstaller update flow.
