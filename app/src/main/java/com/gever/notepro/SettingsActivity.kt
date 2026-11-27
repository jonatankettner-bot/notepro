package com.gever.notepro

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Settings: grouped cards of switches, plus default paper and app info / updates. */
class SettingsActivity : Activity() {
    private val prefs by lazy { Prefs(this) }
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(24), dp(16), dp(24), dp(32))
        }
        // A centred column of readable width: full-width rows on the 14.6" screen put the switch far from its label.
        val holder = android.widget.FrameLayout(this)
        val w = if (resources.configuration.screenWidthDp > 800) dp(720) else -1
        holder.addView(col, android.widget.FrameLayout.LayoutParams(w, -2, Gravity.CENTER_HORIZONTAL))
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFFF4F6F9.toInt()); addView(holder) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_forward_ios); imageTintList = android.content.res.ColorStateList.valueOf(ICON)
            background = pressable(this@SettingsActivity, 12f); contentDescription = "חזרה"; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(TextView(this).apply { text = "הגדרות"; textSize = 26f; setTextColor(INK); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        col.addView(top)

        section("כתיבה וכלים", listOf(
            Triple("עט רגיש ללחץ", "העובי משתנה לפי עוצמת הלחיצה", prefs::usePressure),
            Triple("חיזוי תנועה", "מקטין את ההשהיה בין העט לקו", prefs::usePrediction),
            Triple("צורות בהחזקת העט", "מציירים קו, עיגול, מלבן, משולש ועוד ועוצרים רגע: הצורה מתיישרת, ואפשר עוד לכוון אותה לפני ההרמה", prefs::snapLines),
            Triple("חזרה אוטומטית מהמחק", "אחרי מחיקה חוזרים לכלי הקודם", prefs::autoDeselectEraser),
            Triple("אצבע כותבת", "כבוי: רק העט כותב, האצבע גוללת ומגדילה", prefs::fingerDraws),
        ))
        section("מחוות", listOf(
            Triple("הקשה בשתי אצבעות = ביטול", "והקשה בשלוש אצבעות = חזרה", prefs::tapGestures),
        ))
        section("מראה", listOf(
            Triple("סרגל כלים קבוע", "הסרגל יושב בפס לבן מעל הדף, כך שהוא אף פעם לא מסתיר כתב", prefs::dockedToolbar),
            Triple("המסך נשאר דולק", "בזמן כתיבה בהערה", prefs::keepScreenOn),
        ))

        card("נייר ברירת מחדל להערות חדשות").apply {
            addView(TextView(this@SettingsActivity).apply {
                text = paperLabel(); textSize = 15f; setTextColor(ACCENT); setPadding(0, dp(6), 0, dp(4))
                setOnClickListener {
                    val p = runCatching { NoteCanvasView.Paper.valueOf(prefs.defaultPaper) }.getOrDefault(NoteCanvasView.Paper.LINED)
                    val c = if (prefs.defaultPaperColor == -1) Color.WHITE else prefs.defaultPaperColor
                    PaperDialog(this@SettingsActivity, p, c) { np, nc -> prefs.defaultPaper = np.name; prefs.defaultPaperColor = nc; text = paperLabel() }.show()
                }
            })
        }
        card("אודות").apply {
            addView(TextView(this@SettingsActivity).apply {
                text = "NotePro · גרסה " + BuildConfig.VERSION_CODE; textSize = 15f; setTextColor(INK); textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            })
            addView(TextView(this@SettingsActivity).apply {
                text = "בדיקת עדכון"; textSize = 15f; setTextColor(ACCENT); setPadding(0, dp(10), 0, dp(4))
                setOnClickListener {
                    Updater.check { rel ->
                        if (rel == null) Toast.makeText(this@SettingsActivity, "יש לך את הגרסה האחרונה", Toast.LENGTH_SHORT).show()
                        else { text = "מתקין גרסה " + rel.versionCode + "…"; Updater.install(this@SettingsActivity, rel) { msg -> text = msg } }
                    }
                }
            })
        }
    }

    private fun paperLabel(): String {
        val name = when (prefs.defaultPaper) { "BLANK" -> "ריק"; "GRID" -> "משבצות"; "DOTS" -> "נקודות"; else -> "שורות" }
        return "$name · שינוי…"
    }

    private fun card(title: String): LinearLayout {
        col.addView(TextView(this).apply { text = title; textSize = 13f; setTextColor(SUB); setPadding(dp(6), dp(20), dp(6), dp(6)) })
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 16 * d }
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        col.addView(c)
        return c
    }

    private fun section(title: String, rows: List<Triple<String, String, kotlin.reflect.KMutableProperty0<Boolean>>>) {
        val c = card(title)
        rows.forEachIndexed { i, (label, sub, prop) ->
            if (i > 0) c.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, 1))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10)) }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply { text = label; textSize = 16f; setTextColor(INK); textAlignment = View.TEXT_ALIGNMENT_VIEW_START })
            texts.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(SUB); textAlignment = View.TEXT_ALIGNMENT_VIEW_START })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            val sw = Switch(this).apply { isChecked = prop.get(); accent(); setOnCheckedChangeListener { _, on -> prop.set(on) } }
            row.addView(sw, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
            row.setOnClickListener { sw.toggle() }   // the whole row toggles, not only the small switch
            c.addView(row)
        }
    }
}
