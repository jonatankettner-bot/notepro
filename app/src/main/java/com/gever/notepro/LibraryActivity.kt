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
    private lateinit var empty: TextView
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
        col.addView(TextView(this).apply {
            text = "NotePro"; textSize = 22f; setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(8), 0, dp(8), dp(12))
        })
        sideList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ScrollView(this).apply { addView(sideList) }, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(navRow("הגדרות", null, false) { startActivity(Intent(this, SettingsActivity::class.java)) })
        return col
    }

    private fun buildSidebar() {
        sideList.removeAllViews()
        sideList.addView(navRow("כל ההערות", null, section is Section.All) { go(Section.All) })
        sideList.addView(navRow("אחרונות", null, section is Section.Recent) { go(Section.Recent) })
        sideList.addView(navRow("מועדפים", null, section is Section.Favorites) { go(Section.Favorites) })
        sideList.addView(navRow("ללא תיקייה", null, section is Section.Unfiled) { go(Section.Unfiled) })

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply { text = "תיקיות"; textSize = 13f; setTextColor(SUB); setPadding(dp(10), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "+"; textSize = 22f; setTextColor(ACCENT); gravity = Gravity.CENTER
            setOnClickListener { folderDialog(null, null) }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
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

    private fun navRow(label: String, folder: Folder?, selected: Boolean, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(if (selected) ACCENT_SOFT else Color.TRANSPARENT, 10f)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) }
        }
        if (folder != null) {
            if (folder.emoji.isNotEmpty()) row.addView(TextView(this).apply { text = folder.emoji; textSize = 16f })
            else row.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(folder.color) } },
                LinearLayout.LayoutParams(dp(11), dp(11)))
        }
        row.addView(TextView(this).apply {
            text = label; textSize = 15f; setTextColor(if (selected) ACCENT else INK)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(10), 0, dp(4), 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun folderMenu(anchor: View, f: Folder) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "תיקייה חדשה בתוכה"); pm.menu.add(0, 2, 1, "עריכה"); pm.menu.add(0, 3, 2, "מחיקה")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> folderDialog(null, f.id)
                2 -> folderDialog(f, f.parentId)
                3 -> AlertDialog.Builder(this).setTitle("למחוק את \"${f.name}\"?")
                    .setMessage("ההערות שבתוכה יעברו ל\"ללא תיקייה\". תת-התיקיות יימחקו.")
                    .setPositiveButton("מחיקה") { _, _ -> deleteFolder(f) }.setNegativeButton("ביטול", null).show()
            }
            true
        }
        pm.show()
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
        heading = TextView(this).apply {
            textSize = 26f; setTextColor(INK); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        top.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        updateChip = TextView(this).apply {
            textSize = 13f; setTextColor(Color.WHITE); background = rounded(ACCENT, 16f)
            setPadding(dp(12), dp(6), dp(12), dp(6)); visibility = View.GONE
            setOnClickListener { pending?.let { rel -> Updater.install(this@LibraryActivity, rel) { msg -> text = msg } } }
        }
        top.addView(updateChip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })
        val search = EditText(this).apply {
            hint = "חיפוש"; textSize = 15f; isSingleLine = true; textDirection = View.TEXT_DIRECTION_RTL
            background = rounded(SIDEBAR_BG, 12f); setPadding(dp(14), dp(9), dp(14), dp(9))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s.toString().trim(); refreshNotes() }
            })
        }
        top.addView(search, LinearLayout.LayoutParams(dp(240), -2))
        col.addView(top)

        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT; columnWidth = dp(196); stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(18); verticalSpacing = dp(22); clipToPadding = false
            setPadding(0, dp(20), 0, dp(96)); selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            adapter = this@LibraryActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> open(shown[pos]) }
        }
        col.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        frame.addView(col, FrameLayout.LayoutParams(-1, -1))

        empty = TextView(this).apply {
            textSize = 16f; setTextColor(SUB); gravity = Gravity.CENTER; visibility = View.GONE
            text = "אין כאן הערות עדיין.\nלחץ על + כדי ליצור הערה חדשה."
        }
        frame.addView(empty, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        val fab = TextView(this).apply {
            text = "+  הערה חדשה"; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = rounded(ACCENT, 28f); elevation = 8 * d
            setPadding(dp(22), dp(14), dp(22), dp(14))
        }
        fab.setOnClickListener { fabMenu(fab) }
        frame.addView(fab, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.LEFT).apply { setMargins(dp(24), 0, dp(24), dp(24)) })
        return frame
    }

    private fun fabMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "הערה חדשה"); pm.menu.add(0, 2, 1, "הערה חדשה מתבנית…"); pm.menu.add(0, 3, 2, "ייבוא PDF")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> { val pr = Prefs(this); open(store.create(defaultTitle(), currentFolder(), pr.defaultPaper, pr.defaultPaperColor)) }
                2 -> PaperDialog(this, NoteCanvasView.Paper.LINED, Color.WHITE) { p, c ->
                    open(store.create(defaultTitle(), currentFolder(), p.name, c)) }.show()
                3 -> Toast.makeText(this, "ייבוא PDF מגיע בשלב הבא", Toast.LENGTH_SHORT).show()
            }
            true
        }
        pm.show()
    }

    private fun currentFolder() = (section as? Section.InFolder)?.id

    private fun open(n: NoteMeta) = startActivity(Intent(this, MainActivity::class.java).putExtra(EXTRA_NOTE, n.id))

    private fun go(s: Section) { section = s; buildSidebar(); refreshNotes() }

    private fun reload() { folders = store.folders(); buildSidebar(); refreshNotes() }

    private fun refreshNotes() {
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
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
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
            val thumb = ImageView(this@LibraryActivity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true
                background = rounded(Color.WHITE, 12f).apply { setStroke(dp(1), LINE) }
                setPadding(dp(1), dp(1), dp(1), dp(1)); clipToOutline = true; elevation = 2 * d
                val bmp = store.thumbnail(n.id)
                if (bmp != null) setImageBitmap(bmp) else setImageDrawable(rounded(if (n.paperColor != -1) n.paperColor else Color.WHITE, 12f))
            }
            card.addView(thumb, LinearLayout.LayoutParams(-1, dp(250)))
            val row = LinearLayout(this@LibraryActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
            val texts = LinearLayout(this@LibraryActivity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this@LibraryActivity).apply {
                text = (if (n.favorite) "★ " else "") + n.title; textSize = 15f; setTextColor(INK); maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            texts.addView(TextView(this@LibraryActivity).apply { text = dateFmt.format(Date(n.modified)); textSize = 12f; setTextColor(SUB) })
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
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "שינוי שם")
        pm.menu.add(0, 2, 1, if (n.favorite) "הסרה מהמועדפים" else "הוספה למועדפים")
        pm.menu.add(0, 3, 2, "שכפול")
        pm.menu.add(0, 4, 3, "העברה לתיקייה…")
        pm.menu.add(0, 5, 4, "מחיקה")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> {
                    val input = EditText(this).apply { setText(n.title); setSelection(text.length); textDirection = View.TEXT_DIRECTION_RTL }
                    AlertDialog.Builder(this).setTitle("שם ההערה").setView(input)
                        .setPositiveButton("שמירה") { _, _ -> input.text.toString().trim().takeIf { t -> t.isNotEmpty() }?.let { t -> n.title = t; store.saveMeta(n); refreshNotes() } }
                        .setNegativeButton("ביטול", null).show()
                }
                2 -> { n.favorite = !n.favorite; store.saveMeta(n); refreshNotes() }
                3 -> { store.duplicate(n, n.title + " (עותק)"); refreshNotes() }
                4 -> moveDialog(n)
                5 -> AlertDialog.Builder(this).setTitle("למחוק את \"${n.title}\"?").setMessage("אי אפשר לבטל את המחיקה.")
                    .setPositiveButton("מחיקה") { _, _ -> store.delete(n.id); refreshNotes() }.setNegativeButton("ביטול", null).show()
            }
            true
        }
        pm.show()
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
