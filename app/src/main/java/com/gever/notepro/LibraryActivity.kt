package com.gever.notepro

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val FOLDER_COLORS = intArrayOf(
    0xFF2B59C3.toInt(), 0xFF178A43.toInt(), 0xFFD62F2F.toInt(), 0xFFE07A10.toInt(),
    0xFF7B3FE4.toInt(), 0xFF0EA5E9.toInt(), 0xFFEC4899.toInt(), 0xFF6B7280.toInt())
private const val SIDEBAR_BG = 0xFFF4F6F9.toInt()
private const val DESK_BG = 0xFFFFFFFF.toInt()

/**
 * Library (home) screen: sidebar with sections and nested folders, a grid of note cards, search,
 * and a floating + button. Opens notes in MainActivity.
 */
class LibraryActivity : Activity() {
    private sealed class Section {
        object All : Section(); object Favorites : Section(); object Recent : Section(); object Unfiled : Section()
        class InFolder(val id: String) : Section()
    }

    private val store by lazy { NoteStore(this) }
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()
    private fun rounded(c: Int, r: Float) = GradientDrawable().apply { setColor(c); cornerRadius = r * d }

    private var section: Section = Section.All
    private var query = ""
    private var folders = mutableListOf<Folder>()
    private val expanded = mutableSetOf<String>()
    private var shown = listOf<NoteMeta>()

    private lateinit var sideList: LinearLayout
    private lateinit var heading: TextView
    private lateinit var grid: GridView
    private lateinit var empty: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptySub: TextView
    private lateinit var emptyBtn: TextView
    private lateinit var countLine: TextView
    private lateinit var emptyIcon: ImageView
    private lateinit var updateChip: TextView
    private var pending: Updater.Release? = null
    private val adapter = CardAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(DESK_BG)
        }
        root.addView(sidebar(), LinearLayout.LayoutParams(dp(272), -1))
        root.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(1, -1))
        root.addView(content(), LinearLayout.LayoutParams(0, -1, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
        Updater.check { rel ->
            pending = rel
            updateChip.visibility = if (rel != null) View.VISIBLE else View.GONE
            if (rel != null) updateChip.text = "עדכון זמין · גרסה " + rel.versionCode
        }
    }

    // ================= sidebar =================
    private fun sidebar(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(SIDEBAR_BG)
            setPadding(dp(12), dp(16), dp(12), dp(12))
        }
        // App mark + name. VIEW_START keeps the Latin name on the right in the right-to-left sidebar.
        val brand = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), dp(14)) }
        brand.addView(FrameLayout(this).apply {
            background = rounded(ACCENT, 9f)
            addView(ImageView(this@LibraryActivity).apply { setImageResource(R.drawable.ic_launcher_foreground); scaleType = ImageView.ScaleType.FIT_CENTER },
                FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        brand.addView(TextView(this).apply {
            text = "NotePro"; textSize = 21f; setTextColor(INK); textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        col.addView(brand)
        sideList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ScrollView(this).apply { addView(sideList) }, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, 1).apply { bottomMargin = dp(6) })
        col.addView(navRow("הגדרות", null, false, R.drawable.ic_tune) { startActivity(Intent(this, SettingsActivity::class.java)) })
        return col
    }

    private fun buildSidebar() {
        sideList.removeAllViews()
        val all = store.notes()
        sideList.addView(navRow("כל ההערות", null, section is Section.All, R.drawable.ic_notes, all.size) { go(Section.All) })
        sideList.addView(navRow("אחרונות", null, section is Section.Recent, R.drawable.ic_recent) { go(Section.Recent) })
        sideList.addView(navRow("מועדפים", null, section is Section.Favorites, R.drawable.ic_star, all.count { it.favorite }) { go(Section.Favorites) })
        sideList.addView(navRow("ללא תיקייה", null, section is Section.Unfiled, R.drawable.ic_inbox) { go(Section.Unfiled) })

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply { text = "תיקיות"; textSize = 13f; setTextColor(SUB); setPaddingRelative(dp(10), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_add); imageTintList = ColorStateList.valueOf(ACCENT)
            background = pressable(this@LibraryActivity, 10f); contentDescription = "תיקייה חדשה"
            setOnClickListener { folderDialog(null, null) }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        if (folders.isEmpty()) {
            sideList.addView(header, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            sideList.addView(TextView(this).apply {
                text = "אין תיקיות עדיין"; textSize = 13f; setTextColor(0xFF9AA1AC.toInt()); setPadding(dp(10), dp(4), dp(10), 0)
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            })
            return
        }
        sideList.addView(header, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        addFolders(null, 0)
    }

    private fun addFolders(parent: String?, depth: Int) {
        for (f in folders.filter { it.parentId == parent }.sortedBy { it.name }) {
            val hasKids = folders.any { it.parentId == f.id }
            val sel = (section as? Section.InFolder)?.id == f.id
            val row = navRow(f.name, f, sel) { go(Section.InFolder(f.id)) }
            if (depth > 0) (row.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(16) * depth
            if (hasKids) row.addView(TextView(this).apply {
                text = if (f.id in expanded) "⌄" else "‹"; textSize = 16f; setTextColor(SUB); gravity = Gravity.CENTER
                setOnClickListener { if (!expanded.remove(f.id)) expanded.add(f.id); buildSidebar() }
            }, LinearLayout.LayoutParams(dp(32), dp(32)))
            row.setOnLongClickListener { folderMenu(it, f); true }
            sideList.addView(row)
            if (hasKids && f.id in expanded) addFolders(f.id, depth + 1)
        }
    }

    private fun navRow(label: String, folder: Folder?, selected: Boolean, icon: Int = 0, count: Int = -1, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pressable(this@LibraryActivity, 10f, if (selected) ACCENT_SOFT else Color.TRANSPARENT)
            setPadding(dp(10), dp(8), dp(10), dp(8)); minimumHeight = dp(42)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) }
        }
        if (folder != null) {
            // Folders: their emoji, or a folder icon in the folder's colour.
            if (folder.emoji.isNotEmpty()) row.addView(TextView(this).apply { text = folder.emoji; textSize = 16f; gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(22), -2))
            else row.addView(ImageView(this).apply { setImageResource(R.drawable.ic_folder); imageTintList = ColorStateList.valueOf(folder.color) },
                LinearLayout.LayoutParams(dp(22), dp(22)))
        } else if (icon != 0) {
            row.addView(ImageView(this).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(if (selected) ACCENT else ICON) },
                LinearLayout.LayoutParams(dp(22), dp(22)))
        }
        row.addView(TextView(this).apply {
            text = label; textSize = 15f; setTextColor(if (selected) ACCENT else INK)
            if (selected) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setPaddingRelative(dp(12), 0, dp(4), 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (count > 0) row.addView(TextView(this).apply { text = count.toString(); textSize = 13f; setTextColor(if (selected) ACCENT else SUB) })
        return row
    }

    private fun folderMenu(anchor: View, f: Folder) {
        showMenu(anchor, listOf(
            MenuItem("תיקייה חדשה בתוכה") { folderDialog(null, f.id) },
            MenuItem("עריכה") { folderDialog(f, f.parentId) },
            MenuItem("מחיקה", danger = true, separatorBefore = true) {
                AlertDialog.Builder(this).setTitle("למחוק את \"${f.name}\"?")
                    .setMessage("ההערות שבתוכה יעברו ל\"ללא תיקייה\". תת-התיקיות יימחקו.")
                    .setPositiveButton("מחיקה") { _, _ -> deleteFolder(f) }.setNegativeButton("ביטול", null).show()
            },
        ))
    }

    private fun deleteFolder(f: Folder) {
        val ids = mutableSetOf(f.id)
        var grew = true
        while (grew) { grew = folders.filter { it.parentId in ids && it.id !in ids }.map { ids.add(it.id) }.isNotEmpty() }
        folders.removeAll { it.id in ids }; store.saveFolders(folders)
        for (n in store.notes()) if (n.folderId in ids) { n.folderId = null; store.saveMeta(n) }
        if ((section as? Section.InFolder)?.id in ids) section = Section.All
        reload()
    }

    /** Create (existing == null) or edit a folder: name, colour, optional emoji. */
    private fun folderDialog(existing: Folder?, parentId: String?) {
        var color = existing?.color ?: FOLDER_COLORS[folders.size % FOLDER_COLORS.size]
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val name = EditText(this).apply { hint = "שם התיקייה"; setText(existing?.name ?: ""); textDirection = View.TEXT_DIRECTION_RTL }
        val emoji = EditText(this).apply { hint = "אימוג׳י (לא חובה)"; setText(existing?.emoji ?: "") }
        box.addView(name); box.addView(emoji)
        val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        fun paintDots() {
            for (i in 0 until dots.childCount) {
                val c = FOLDER_COLORS[i]
                dots.getChildAt(i).background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(c); if (c == color) setStroke(dp(3), INK)
                }
            }
        }
        for (c in FOLDER_COLORS) dots.addView(View(this).apply { setOnClickListener { color = c; paintDots() } },
            LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(8) })
        paintDots(); box.addView(dots)
        AlertDialog.Builder(this).setTitle(if (existing == null) "תיקייה חדשה" else "עריכת תיקייה").setView(box)
            .setPositiveButton("שמירה") { _, _ ->
                val n = name.text.toString().trim().ifEmpty { "תיקייה" }
                val e = emoji.text.toString().trim().take(4)
                if (existing == null) {
                    folders.add(Folder(UUID.randomUUID().toString(), n, color, parentId, e))
                    parentId?.let { expanded.add(it) }
                } else { existing.name = n; existing.color = color; existing.emoji = e }
                store.saveFolders(folders); reload()
            }
            .setNegativeButton("ביטול", null).show()
    }

    // ================= content =================
    private fun content(): View {
        val frame = FrameLayout(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(18), dp(24), 0) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading = TextView(this).apply {
            textSize = 26f; setTextColor(INK); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        countLine = TextView(this).apply { textSize = 13f; setTextColor(SUB); textAlignment = View.TEXT_ALIGNMENT_VIEW_START }
        titles.addView(heading); titles.addView(countLine)
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        updateChip = TextView(this).apply {
            textSize = 13f; setTextColor(Color.WHITE); background = rounded(ACCENT, 16f)
            setPadding(dp(12), dp(6), dp(12), dp(6)); visibility = View.GONE
            setOnClickListener { pending?.let { rel -> Updater.install(this@LibraryActivity, rel) { msg -> text = msg } } }
        }
        top.addView(updateChip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })
        val search = EditText(this).apply {
            hint = "חיפוש הערות"; textSize = 15f; isSingleLine = true; textDirection = View.TEXT_DIRECTION_RTL
            background = rounded(SIDEBAR_BG, 12f); setPadding(dp(12), dp(10), dp(12), dp(10))
            setHintTextColor(0xFF9AA1AC.toInt())
            val mag = getDrawable(R.drawable.ic_search)!!.mutate().apply { setTint(SUB); setBounds(0, 0, dp(20), dp(20)) }
            setCompoundDrawablesRelative(mag, null, null, null); compoundDrawablePadding = dp(8)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s.toString().trim(); refreshNotes() }
            })
        }
        top.addView(search, LinearLayout.LayoutParams(dp(260), -2))
        col.addView(top)

        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT; columnWidth = dp(172); stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(22); verticalSpacing = dp(26); clipToPadding = false
            setPadding(dp(4), dp(22), dp(4), dp(110)); selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            adapter = this@LibraryActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> open(shown[pos]) }
        }
        col.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        frame.addView(col, FrameLayout.LayoutParams(-1, -1))

        // Empty state: big page icon, a line of text and a direct "new note" button.
        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE
            addView(FrameLayout(this@LibraryActivity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ACCENT_SOFT) }
                emptyIcon = ImageView(this@LibraryActivity).apply { setImageResource(R.drawable.ic_notes); imageTintList = ColorStateList.valueOf(ACCENT) }
                addView(emptyIcon,
                    FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(96), dp(96)))
            emptyTitle = TextView(this@LibraryActivity).apply {
                textSize = 18f; setTextColor(INK); gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            addView(emptyTitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
            emptySub = TextView(this@LibraryActivity).apply { textSize = 14f; setTextColor(SUB); gravity = Gravity.CENTER }
            addView(emptySub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            emptyBtn = TextView(this@LibraryActivity).apply {
                text = "הערה חדשה"; textSize = 15f; setTextColor(ACCENT); gravity = Gravity.CENTER
                background = pressable(this@LibraryActivity, 20f, ACCENT_SOFT); setPadding(dp(20), dp(10), dp(20), dp(10))
                setOnClickListener { newNote() }
            }
            addView(emptyBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        }
        frame.addView(empty, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // Split button: the main part creates a note at once; the arrow opens the other ways to start one.
        val fab = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(ACCENT, 28f); elevation = 8 * d
        }
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pressable(this@LibraryActivity, 28f); setPadding(dp(18), dp(14), dp(16), dp(14))
            addView(ImageView(this@LibraryActivity).apply { setImageResource(R.drawable.ic_add); imageTintList = ColorStateList.valueOf(Color.WHITE) },
                LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(TextView(this@LibraryActivity).apply {
                text = "הערה חדשה"; textSize = 16f; setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPaddingRelative(dp(8), 0, 0, 0)
            })
            setOnClickListener { newNote() }
        }
        fab.addView(main)
        fab.addView(View(this).apply { setBackgroundColor(0x40FFFFFF) }, LinearLayout.LayoutParams(1, dp(26)))
        val more = ImageButton(this).apply {
            setImageResource(R.drawable.ic_expand_more); imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = pressable(this@LibraryActivity, 28f); contentDescription = "אפשרויות נוספות"
        }
        more.setOnClickListener { fabMenu(more) }
        fab.addView(more, LinearLayout.LayoutParams(dp(48), dp(50)))
        frame.addView(fab, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.LEFT).apply { setMargins(dp(24), 0, dp(24), dp(24)) })
        return frame
    }

    private fun newNote() {
        val pr = Prefs(this); open(store.create(defaultTitle(), currentFolder(), pr.defaultPaper, pr.defaultPaperColor))
    }

    private fun fabMenu(anchor: View) {
        showMenu(anchor, listOf(
            MenuItem("הערה חדשה") { newNote() },
            MenuItem("הערה חדשה מתבנית…") {
                PaperDialog(this, NoteCanvasView.Paper.LINED, Color.WHITE) { p, c -> open(store.create(defaultTitle(), currentFolder(), p.name, c)) }.show()
            },
            MenuItem("תיקייה חדשה") { folderDialog(null, currentFolder()) },
            MenuItem("ייבוא PDF (בקרוב)", enabled = false, separatorBefore = true),
        ))
    }

    private fun currentFolder() = (section as? Section.InFolder)?.id

    private fun open(n: NoteMeta) = startActivity(Intent(this, MainActivity::class.java).putExtra(EXTRA_NOTE, n.id))

    private fun go(s: Section) { section = s; refreshNotes() }

    private fun reload() { folders = store.folders(); refreshNotes() }

    /** Rebuilds the sidebar (selection, counts) and the card grid. */
    private fun refreshNotes() {
        buildSidebar()
        val all = store.notes()
        heading.text = when (val s = section) {
            Section.All -> "כל ההערות"; Section.Favorites -> "מועדפים"; Section.Recent -> "אחרונות"; Section.Unfiled -> "ללא תיקייה"
            is Section.InFolder -> folders.find { it.id == s.id }?.name ?: "תיקייה"
        }
        var list = when (val s = section) {
            Section.All -> all
            Section.Favorites -> all.filter { it.favorite }
            Section.Recent -> all.sortedByDescending { it.opened }.take(24)
            Section.Unfiled -> all.filter { it.folderId == null || folders.none { f -> f.id == it.folderId } }
            is Section.InFolder -> all.filter { it.folderId == s.id }
        }
        if (section !is Section.Recent) list = list.sortedByDescending { it.modified }
        if (query.isNotEmpty()) list = list.filter { it.title.contains(query, ignoreCase = true) }
        shown = list
        adapter.notifyDataSetChanged()
        countLine.text = when (list.size) { 0 -> ""; 1 -> "הערה אחת"; else -> "${list.size} הערות" }
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val searching = query.isNotEmpty()
        emptyTitle.text = when {
            searching -> "לא נמצאו הערות"
            section is Section.Favorites -> "אין מועדפים עדיין"
            section is Section.InFolder -> "התיקייה ריקה"
            else -> "אין כאן הערות עדיין"
        }
        emptySub.text = when {
            searching -> "אין הערה ששמה מכיל \"$query\""
            section is Section.Favorites -> "בתפריט של הערה בוחרים \"הוספה למועדפים\""
            else -> "הערה חדשה נפתחת בלחיצה אחת"
        }
        emptyBtn.visibility = if (searching || section is Section.Favorites) View.GONE else View.VISIBLE
        emptyIcon.setImageResource(when { searching -> R.drawable.ic_search; section is Section.Favorites -> R.drawable.ic_star
            section is Section.InFolder -> R.drawable.ic_folder; else -> R.drawable.ic_notes })
    }

    // ================= cards =================
    private val dateFmt = SimpleDateFormat("d בMMM yyyy", Locale.forLanguageTag("he"))

    private inner class CardAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(pos: Int, convert: View?, parent: ViewGroup?): View {
            val n = shown[pos]
            val card = LinearLayout(this@LibraryActivity).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            // The thumbnail keeps the page's A4 shape (it used to sit letterboxed in a fixed-height box).
            val thumb = AspectImageView(this@LibraryActivity, NoteCanvasView.PAGE_H / NoteCanvasView.PAGE_W).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(Color.WHITE, 10f).apply { setStroke(dp(1), LINE) }
                setPadding(dp(1), dp(1), dp(1), dp(1)); clipToOutline = true; elevation = 3 * d
                val bmp = store.thumbnail(n.id)
                if (bmp != null) setImageBitmap(bmp) else setImageDrawable(rounded(if (n.paperColor != -1) n.paperColor else Color.WHITE, 10f))
            }
            val thumbBox = FrameLayout(this@LibraryActivity).apply { setPadding(dp(2), dp(2), dp(2), dp(4)) }
            thumbBox.addView(thumb, FrameLayout.LayoutParams(-1, -2))
            if (n.favorite) thumbBox.addView(ImageView(this@LibraryActivity).apply {
                setImageResource(R.drawable.ic_star); imageTintList = ColorStateList.valueOf(0xFFE0A100.toInt())
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xF2FFFFFF.toInt()) }
                setPadding(dp(5), dp(5), dp(5), dp(5)); elevation = 4 * d
            }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.RIGHT).apply { setMargins(0, dp(8), dp(8), 0) })
            card.addView(thumbBox, LinearLayout.LayoutParams(-1, -2))
            val row = LinearLayout(this@LibraryActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
            val texts = LinearLayout(this@LibraryActivity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this@LibraryActivity).apply {
                text = n.title; textSize = 15f; setTextColor(INK); maxLines = 2
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                ellipsize = android.text.TextUtils.TruncateAt.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            })
            texts.addView(TextView(this@LibraryActivity).apply {
                text = relativeDate(n.modified); textSize = 12f; setTextColor(SUB); textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            })
            folders.find { it.id == n.folderId }?.let { f ->
                texts.addView(TextView(this@LibraryActivity).apply {
                    text = (if (f.emoji.isNotEmpty()) f.emoji + " " else "") + f.name; textSize = 11f; setTextColor(f.color)
                    background = rounded((f.color and 0x00FFFFFF) or 0x1F000000, 8f); setPadding(dp(8), dp(2), dp(8), dp(2))
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            }
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            val more = ImageButton(this@LibraryActivity).apply {
                setImageResource(R.drawable.ic_more_vert); imageTintList = ColorStateList.valueOf(SUB)
                background = null; isFocusable = false
            }
            more.setOnClickListener { cardMenu(more, n) }
            row.addView(more, LinearLayout.LayoutParams(dp(36), dp(36)))
            card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            return card
        }
    }

    private fun cardMenu(anchor: View, n: NoteMeta) {
        showMenu(anchor, listOf(
            MenuItem("שינוי שם") {
                val input = EditText(this).apply { setText(n.title); setSelection(text.length); textDirection = View.TEXT_DIRECTION_RTL }
                AlertDialog.Builder(this).setTitle("שם ההערה").setView(input)
                    .setPositiveButton("שמירה") { _, _ -> input.text.toString().trim().takeIf { t -> t.isNotEmpty() }?.let { t -> n.title = t; store.saveMeta(n); refreshNotes() } }
                    .setNegativeButton("ביטול", null).show()
            },
            MenuItem(if (n.favorite) "הסרה מהמועדפים" else "הוספה למועדפים") { n.favorite = !n.favorite; store.saveMeta(n); refreshNotes() },
            MenuItem("שכפול") { store.duplicate(n, n.title + " (עותק)"); refreshNotes() },
            MenuItem("העברה לתיקייה…") { moveDialog(n) },
            MenuItem("מחיקה", danger = true, separatorBefore = true) {
                AlertDialog.Builder(this).setTitle("למחוק את \"${n.title}\"?").setMessage("אי אפשר לבטל את המחיקה.")
                    .setPositiveButton("מחיקה") { _, _ -> store.delete(n.id); refreshNotes() }.setNegativeButton("ביטול", null).show()
            },
        ))
    }

    private fun moveDialog(n: NoteMeta) {
        val ordered = mutableListOf<Pair<Folder, Int>>()
        fun walk(parent: String?, depth: Int) { for (f in folders.filter { it.parentId == parent }.sortedBy { it.name }) { ordered += f to depth; walk(f.id, depth + 1) } }
        walk(null, 0)
        val labels = arrayOf("ללא תיקייה") + ordered.map { (f, depth) -> "    ".repeat(depth) + (if (f.emoji.isNotEmpty()) f.emoji + " " else "") + f.name }
        AlertDialog.Builder(this).setTitle("העברה לתיקייה").setItems(labels) { _, which ->
            n.folderId = if (which == 0) null else ordered[which - 1].first.id
            store.saveMeta(n); refreshNotes()
        }.show()
    }
}
