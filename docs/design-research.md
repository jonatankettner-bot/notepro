# Notability UI research (2026-11-27)

Source: Notability's official help centre (intercom.help/notability), which has current screenshots,
including the Android app. YouTube could not be read from the cloud session: YouTube blocks cloud IPs.
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
- YouTube candidates to review from a home connection (via Piped search):
  - V_oJ_B2QMuQ "Notability for Android – 18 Powerful Features" (2 weeks old)
  - mFt-QNU8pk0 "How to take notes on Android using Notability"
  - vV9fo-6cFT4 "How to organize notes on Android with Notability"
  - YJNQ54Plnbs "Notability Beginner Guide 2026"
  - PgkbJ6Y41Ro "How to Customize and Hide Tools in Notability"
