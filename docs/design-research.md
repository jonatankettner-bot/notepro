# Notability UI research (2026-11-27)

Source: Notability's official help centre (intercom.help/notability), which has current screenshots,
including the Android app. YouTube could not be read from the cloud session (YouTube blocks cloud IPs);
it was reviewed later from a home connection — see "YouTube review" below, which corrects some points here.
Use this for **layout only**. Never copy Notability's icons, colours or branding (see CLAUDE.md).

## Editor toolbar (Android screenshot)
- A floating, rounded card with **two rows**.
- Row 1, tools: pen, pencil, highlighter, eraser, text, rectangle select, insert (+), record (mic),
  hand/finger, laser pointer, zoom view, and a `>` chevron that shows more tools.
- Row 2, the "style tray" of the selected tool: 3 colour swatches (the selected one is ringed) and `+`,
  then a divider, 3 width presets (dots of growing size, the selected one ringed), then a divider and a
  line-style button (`~` wave).
- Tap the selected tool again to open the full popover:
  - colour wheel, HEX entry and eyedropper;
  - up to 8 "fast" colours per tool, and 64 saved colour slots;
  - size and brush style;
  - `~` toggles pressure sensitivity, dashed lines and dotted lines.
- Eraser popover: **Partial** eraser (cuts strokes; the remaining pieces stay editable) vs **Whole**
  eraser (removes the stroke), plus the eraser size.
  "Auto-deselect eraser" is a setting: after erasing, it switches back to the last tool.
- Pen, pencil, highlighter and eraser can be duplicated, each copy with its own style and colour.
- Toolbox customisation: swipe left and tap the gear. Drag handles reorder tools; a hide button moves a
  tool to "Hidden Tools".
- Tablet Focus Mode: a 4-finger tap hides the toolbars.

## Editor top bar
- Upper-right corner: the Content Manager (page thumbnails) and `⋯` options.
- Undo/redo are in the bar. Gesture option: 2-finger tap = undo, 3-finger tap = redo.
- Options menu: export, template settings, view settings (Seamless vs Single page), note info
  (rename, move to folder, dates, page size), version history, app settings.
- Content Manager tabs: All pages / Bookmarks / Annotated.
  - Drag a thumbnail to reorder pages; grid view toggle.
  - Per-page actions: add page after, cut, copy, create template, clear, delete.
  - Bulk select.

## Paper / templates sheet
- Full sheet titled "Templates", with Cancel and Create/Apply.
- Size dropdown (Letter, Legal, Tabloid, A3–A7, Letter Optimized) and a portrait/landscape toggle.
- "Basic" row of thumbnails: Plain, Rule, Grid, Dot. Each has a `⋮` for spacing options; the
  current one is outlined and badged.
- A row of paper colour circles: white, off-white, cream, black, light grey-blue, tan.
- More sections: Notepads/Education (Hexagonal grid, Cornell, College rule, Manuscript, 2/3 columns),
  then My Templates.

## Library / home (tablet)
- Left sidebar:
  - settings gear and a search field;
  - Home, Notes, Gallery;
  - a "Folders" header with `+`;
  - folders with a coloured dot and a `>` to expand subfolders (up to 6 levels).
- Top right: notification bell, `⋮` (sort, list/grid), and a filled "+ New" button.
- Home page: quick-action cards (Record a lecture / Import a file / Find a template), "Up next to learn",
  then "Favorite notes" as cover cards (title, date, folder tag).
- Long-press a folder: rename, colour, emoji, sort, insert subfolder, delete. Drag notes or folders
  into folders.
- Android: `+` at the bottom right of the library, then Create note. Record, import and scan are at
  the bottom of the page.

## Settings worth copying in spirit
- Default template for new notes, default title (with date).
- Straight lines (draw and hold), shape detection, palm detection, keep device awake.
- Hide status bar and navigation bar in note view.

## Proposed first design round (editor only, no new features)
1. Toolbar becomes a floating, rounded two-row card: tools row (pen, highlighter, eraser) and a style
   tray (colours, `+`, 3 widths).
2. Tap the selected tool again: a popover with more colours, a free colour picker and a width slider.
3. Top bar: back, title, undo/redo, `⋯` (paper moves there).
4. Paper picker as a sheet with thumbnails and paper colours.

## YouTube review (2026-11-27, from a home connection)
Watched the 5 videos listed under Sources (subtitles + frames every 2–6 s). Three show the **Android tablet
app** (two are Notability's own Android tutorials); one is iPad (2026 version), one is iPhone. Text only here,
no screenshots (public repo). Layout only, as above.

### Corrections to the notes above
- **The toolbar is one row by default, not two.** The style tray (row 2) appears only after tapping the
  *selected* tool again, and tapping it again hides it. So the toolbar is short most of the time.
- **The toolbar floats at the top centre of the canvas, not in a bar.** It is a small rounded pill with a
  `•••` grab handle above it (it can be dragged). On iPhone it sits at the bottom. A small down-arrow
  collapses it (shown on iPhone).
- **On the Android tablet there is no classic top bar.** Instead:
  - top-left, floating on the canvas: `<` back and the note title (grey; default title is the date,
    e.g. "Note 2 Nov 2026");
  - right edge: a **narrow vertical floating rail** (rounded card), top to bottom: two page/panel icons
    (one opens the Pages panel and turns blue while it is open; I could not tell for sure what the other
    one does), undo, redo, share, `⋮`;
  - bottom-right of the canvas: a small page counter ("10 of 28").
  - On iPad the same items sit in a top-right cluster (undo, redo, `⋯`, pages) instead of a rail.
- The paper picker on Android is a **centred dialog**, not a full sheet (details below).

### Toolbar row 1 (Android, left to right)
Pen, pencil, highlighter, eraser, text (`Tt`), rectangle select/lasso, insert `+`, mic, a divider,
a hand/"quick tool" icon, and `>` for more tools (laser pointer, ruler/"scale", tape, text…).
The selected tool gets a light grey rounded background behind its icon.

### Style tray (row 2) per tool
- **Pen / pencil / highlighter:** the current colour first (a filled circle with a small `v` — tap it for
  the full palette), then ~4 more colour swatches, a `+`, a divider, **3 width dots** (selected one ringed),
  a divider, and a **line-style button** (`~`).
- **Highlighter** has its own colour set (yellow, green, pink, cyan…), separate from the pen.
- **Eraser:** 3 size dots + two chips **"Whole"** and **"Partial"** (chip with border = selected).
- **Lasso:** two chips, **"Freeform"** and a rectangle mode (label blurry in the video) — no colours or widths.

### Full colour popover (tap the `v` on the current colour)
- Opens under the toolbar. Header "Colors" with an **eyedropper** and a **colour-wheel** button on the right.
- A grid of about **3 rows × 8** swatches. The first slot is a clock icon = recent colours.
- Selected swatch has a ring. Last column includes white/light grey.

### Lasso / selection
- Selection shows a **dashed rectangle** with round handles at the 4 corners (resize) and a short line
  with a dot sticking out of the right side (**rotate handle**).
- Tapping the selection opens a **horizontal context menu above it**: style (colour/width), copy, delete,
  convert to text, convert to math.
- The style popover for a selection: a "Stroke" width slider, about 4 line-style options (solid and
  variants incl. dashed/dotted; hard to read), and the colour grid.

### Eraser behaviour (confirmed)
- "Whole" removes complete strokes; "Partial" cuts them.
- **Auto-deselect eraser is ON by default**: after erasing, the app jumps back to the previous tool.
  It can be turned off in Settings → Note editing → Handwriting & drawing.

### Insert `+` menu
A small dropdown under the `+`: Add files, Add photo, Take photo, Add GIF (Giphy), Add sticker,
Insert math (LaTeX).

### S Pen quick tools (very relevant for us)
- Hover the pen near the screen and press the pen button: a **small radial menu opens at the pen tip**.
  The current tool is in the centre; other tools/colours sit around it (pen, pencil, eraser, a colour dot).
- Note: in NotePro the side button is already the stroke eraser while held. A radial menu could be a
  separate gesture (e.g. a click without drawing). To decide with the owner.

### Pages panel (Content Manager)
- **Docked panel on the right side**, about 1/4 of the screen width; the page stays visible next to it,
  and the right rail moves to the left edge of the panel.
- Header: "Pages", search icon, `⋮`, `×`.
- Filter chips: **All / Bookmarks / Notes** (selected chip is filled). "Notes" = pages you wrote on.
- One column of thumbnails with the page number under each; the current page has a coloured outline.
- **Bookmark: tap the top corner of a thumbnail** (a ribbon icon appears there).
- Each thumbnail has a small `⋯` button → menu: Add page, Cut, Copy, Duplicate, Create template,
  Clear page, Delete (red). A "Select" button at the bottom starts multi-select.
- The search box at the top searches handwriting and PDF text, and highlights the hit on the page.

### Paper / templates dialog (Android)
- Opened from the rail's `⋮` → "Template settings".
- **Centred modal card** titled "Templates" with `×`. Three tabs: **Preset / Gallery / My Templates**.
- An "Edit note cover" row, then "Go to:" chips for categories (Notepads, Academic, Creative, Planning,
  Self care).
- In another version of the same dialog: size dropdown (e.g. Letter) and orientation dropdown (Portrait),
  plus a row of paper-colour dots on the right.
- "Basic Templates": Plain, Rule, Grid, Dots as tall thumbnails. Each has a **star** (favourite) and a small
  options button (line spacing). The applied one has a "Current" badge and a coloured outline.
- Footer: Cancel (text) and **Save** (filled button).

### `⋮` menu in the editor (Android)
Template settings, Save as template, Start study session (Pomodoro timer), Comments, Version history,
App settings, Disconnect stylus.

### Library (Android tablet)
- **Left sidebar**: Home, Notes, Gallery, Shared with me; then "Folders" with `+`. Folders have a coloured
  dot or an **emoji**, and a `>` to expand subfolders.
- Creating/editing a folder opens a small card: name field, then a colour/emoji toggle and an emoji grid
  with search.
- Long-press a folder → Insert folder, Edit, Rename, Delete.
- **Notes page**: search field at the top; tabs **All Notes / Recent / Favorites / Unfiled**;
  "Modified date" sort; a grid of note cards (cover thumbnail, title, date, a folder tag, `⋮`).
- Note card `⋮`: Rename, Favorite, Duplicate, Export, Export options, Open in new window,
  Sort to folder, Copy note ID, Report problem, Delete.
- **Home page**: "Let's get started" with two big cards (**Record a lecture / Take notes**), then
  "Up next to learn" and recent notes. Newer version: a date header, quick actions (Upload flashcards /
  Record a lecture / Import a file) and a "Coming up" list from a connected calendar.
- **Floating blue `+` at the bottom right**; tapping it expands upward into: Import, Templates,
  Document scan, Record a lecture, and **Create note** (the filled main action).
- A new empty note shows three faint shortcuts at the bottom of the page: Record, Import, Templates.

### Other things seen (later stages, not now)
- Text tool: tap to type, or drag with the pen to make a text box; a formatting strip above the keyboard
  (B / I / U, colour, styles).
- Shape detection: draw a shape and hold the pen → it snaps to a clean shape with editable corners;
  tap it → style (line width, line style, line colour, fill colour).
- Split screen: "Open in new window" to put a PDF and a note side by side.
- Audio: tap mic to record, tap again to stop; the icon becomes playback with a progress bar and speed.
  Tapping ink jumps the audio to when it was written.
- Gestures confirmed: 2-finger tap = undo, 3-finger tap = redo.

### What this changes in our proposed first design round
1. Toolbar: a **floating pill at the top centre**, one row; the style tray opens **only on a second tap**
   on the selected tool. Add a grab handle so it can be moved later.
2. **Drop the full-width top bar**: back + title float top-left (title in the start corner for RTL);
   undo/redo, pages, share and `⋮` go in a **narrow vertical rail on the edge** (left edge in RTL — to check
   with the owner which side feels right with the pen hand).
3. Eraser tray: 3 sizes + Whole/Partial chips. Auto-switch back after erasing, with a setting.
4. Paper picker: a centred dialog with Plain/Lined/Grid/Dots thumbnails, paper colour dots, Cancel/Save.
5. Later: a docked Pages panel (right side in LTR → left side in RTL), with All/Bookmarks/Notes chips.

## Recent videos (round 2 research, 2026-11-27)
Only videos from the last ~6 months. Four are Notability's own Android tutorials (Aug–Oct 2026),
one is an independent review of Notability 16 (Aug 2026). Text only, layout only.

### Corrections / additions to the editor notes
- **The rail's top icon opens a "Learn" panel** (AI: smart notes, transcript, chat, flashcards/quiz);
  the second opens Pages. Both are **docked side panels** (~1/3 width) with a header row:
  `←`, a title with a `v` dropdown (e.g. Smart notes ↔ Transcripts), `⋮`, `×`.
- **Recording playback = a second row attached under the tool pill**: skip back, skip forward,
  play/pause, a timeline slider, elapsed time, `⋮`. The `⋮` opens a small "Recordings" card: list of
  recordings (name, date, delete) and "Playback speed" chips 1x / 1.5x / 2x. Tapping a transcript
  timestamp seeks the audio. While recording, the mic icon in the pill turns red.
- Share icon in the rail opens: link sharing toggle (+ "allow editing" checkbox), and export as
  PDF / note / JPEG / PNG with options: password, page range, include audio.
- `⋮` → "Template settings" also opens from the **library** (note card menu): a card with the note
  cover preview, background colour and paper colour pickers.

### Note covers (new)
- A note's library thumbnail is its **cover**, or the first page if it has none.
- `⋮` → Template settings → "Edit note cover" → a "Note cover preview" dialog: the cover with the
  note title + date on a label, and a row of **preset covers** (solid colours / simple patterns).
  The cover can then be decorated with stickers, images and ink.
- Pages panel → a page's `⋯` → "Add note cover" makes that page the cover.

### Templates gallery (new)
- Sidebar item "Gallery": search bar, tabs (Discover / Popular / Trending / Following), horizontal
  shelves with "See all": Academic planners, Templates, Planners…, each card has favourite + share.
- "Save to library" turns a gallery item into a note. `⋮` → "Save as template" saves the current note.
- "My Templates" tab = recent + favourite templates.
- The library `+` button menu has "Templates" as an entry point too.

### Import
- Library `+` → Import: pick a file → a small dialog: "Create new note" vs "Add to existing note",
  title field, folder picker, page selection, then ✓. Android "Open with → NotePro" from other apps
  should create a note directly.

### What an independent reviewer criticised in Notability 16 (things to do BETTER)
- **The floating toolbar covers the writing** when the page is zoomed to the screen width, wherever you
  put it. → Ours: offer a "dock" mode (toolbar in a thin strip above the page, page starts below it) and
  a collapse button; the page fit already leaves room at the top.
- **Few pen types** (basically fountain + ballpoint), little customisation. → Ours: several pen types
  from androidx.ink stock brushes (pen with pressure, marker, pencil-like, highlighter) + width slider.
- **Too many taps / scrolling to switch colours and tools** because the bar is kept short.
  → Ours: keep the 5 quick colour slots always one tap away; allow pinning favourite pens
  (same tool type, different colour/width) as separate pill buttons.
- AI features slow and reloading each time; transcription froze. → Not in scope now; when we add audio,
  keep recording on a background thread and never block ink.

## Round 2 build plan (UI-complete app)
Goal (owner): every main screen exists and looks finished; deep features can be shallow at first.
Order chosen so each step is usable on its own and ships separately:
1. **Storage + library.** Save notes locally (strokes via androidx.ink serialization + JSON metadata).
   Library screen: left sidebar (Home, Notes, Gallery, Settings; Folders with `+`, colour/emoji,
   subfolders), notes grid (cover thumbnail, title, date, `⋮` menu: rename, favourite, duplicate,
   move to folder, delete), tabs All / Recent / Favourites / Unfiled, search box, floating `+`
   (New note, Import, Templates). Editor back button returns here.
2. **Pages panel** docked on the side (right side in our RTL layout is the free side; rail stays
   left): thumbnails, All / Bookmarks / Written chips, page menu (add after, duplicate, clear, delete,
   set as cover), drag to reorder.
3. **Lasso** tool in the pill: freeform / rectangle; selection box with handles; move, resize, rotate;
   context menu: colour, width, copy, cut, duplicate, delete.
4. **More pens + text + shapes**: pen types (pressure pen, marker, pencil), text boxes, shape snap
   (hold at stroke end), ruler later.
5. **Insert `+` menu**: image from gallery/camera, stickers (our own simple set), PDF import as pages.
6. **Settings screen**: tools (auto-return eraser, pressure, prediction, finger draws, palm),
   gestures (2-finger tap undo / 3-finger tap redo), default paper & colour, appearance (light/dark
   theme for the app chrome), toolbar mode (floating / docked), about & updates.
7. **Note covers + template gallery** (simple: our own covers and paper templates).
8. Audio recording + playback row (round 3 unless time allows).

## Sources
- https://intercom.help/notability/en-us/articles/16299999-getting-started-with-notability-on-android
- https://intercom.help/notability/en-us/articles/16300068-customize-your-toolbox
- https://intercom.help/notability/en-us/articles/16300155-custom-colors
- https://intercom.help/notability/en-us/articles/16300189-eraser
- https://intercom.help/notability/en-us/articles/16300200-highlighter
- https://intercom.help/notability/en-us/articles/16300226-content-manager-and-bulk-actions
- https://intercom.help/notability/en-us/articles/16300085-options-menu
- https://intercom.help/notability/en-us/articles/16300003-notability-home-screen
- https://intercom.help/notability/en-us/articles/16300020-organize-notes-with-folders
- https://intercom.help/notability/en-us/articles/16300123-android-settings
- https://intercom.help/notability/en-us/articles/16300218-settings-appearances-tools-gestures
- https://intercom.help/notability/en-us/articles/16300179-custom-templates-and-note-background
- Round 2 videos: PnxSt0qp9jY (templates & covers, Android, 2026-10-13), qR1sX1NZgTU (sharing,
  Android, 2026-10-03), ZMhonHZKXi8 (audio & AI, Android, 2026-08-29), NJ11QBaUnno (import/export,
  Android, 2026-08-22), FOz49uern98 ("Notability 16 Review", Paperless X, 2026-08-12).
- YouTube videos (reviewed 2026-11-27, see "YouTube review"):
  - V_oJ_B2QMuQ "Notability for Android – 18 Powerful Features" (2 weeks old)
  - mFt-QNU8pk0 "How to take notes on Android using Notability"
  - vV9fo-6cFT4 "How to organize notes on Android with Notability"
  - YJNQ54Plnbs "Notability Beginner Guide 2026"
  - PgkbJ6Y41Ro "How to Customize and Hide Tools in Notability"
