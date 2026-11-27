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
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFFF4F6F9.toInt()); addView(col) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply { text = "→"; textSize = 24f; setTextColor(ICON); gravity = Gravity.CENTER; setOnClickListener { finish() } },
            LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(TextView(this).apply { text = "הגדרות"; textSize = 26f; setTextColor(INK); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        col.addView(top)

        section("כתיבה וכלים", listOf(
            Triple("עט רגיש ללחץ", "העובי משתנה לפי עוצמת הלחיצה", prefs::usePressure),
            Triple("חיזוי תנועה", "מקטין את ההשהיה בין העט לקו", prefs::usePrediction),
            Triple("קו ישר בהחזקת העט", "צייר קו, החזק את העט חצי שנייה והרם", prefs::snapLines),
            Triple("חזרה אוטומטית מהמחק", "אחרי מחיקה חוזרים לכלי הקודם", prefs::autoDeselectEraser),
            Triple("אצבע כותבת", "כבוי: רק העט כותב, האצבע גוללת ומגדילה", prefs::fingerDraws),
        ))
        section("מחוות", listOf(
            Triple("הקשה בשתי אצבעות = ביטול", "והקשה בשלוש אצבעות = חזרה", prefs::tapGestures),
        ))
        section("מראה", listOf(
            Triple("סרגל כלים קבוע", "הסרגל בפס קבוע מעל הדף ולא מרחף מעליו", prefs::dockedToolbar),
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
            addView(TextView(this@SettingsActivity).apply { text = "NotePro · גרסה " + BuildConfig.VERSION_CODE; textSize = 15f; setTextColor(INK) })
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
            texts.addView(TextView(this).apply { text = label; textSize = 16f; setTextColor(INK) })
            texts.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(SUB) })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Switch(this).apply { isChecked = prop.get(); setOnCheckedChangeListener { _, on -> prop.set(on) } })
            c.addView(row)
        }
    }
}
