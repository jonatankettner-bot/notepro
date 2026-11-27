package com.gever.notepro

import android.content.Context

/** App settings, stored in SharedPreferences. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun b(k: String, def: Boolean) = sp.getBoolean(k, def)
    private fun set(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    var autoDeselectEraser get() = b("autoDeselectEraser", true); set(v) = set("autoDeselectEraser", v)
    var snapLines get() = b("snapLines", true); set(v) = set("snapLines", v)
    var usePressure get() = b("usePressure", true); set(v) = set("usePressure", v)
    var usePrediction get() = b("usePrediction", true); set(v) = set("usePrediction", v)
    var fingerDraws get() = b("fingerDraws", false); set(v) = set("fingerDraws", v)
    var tapGestures get() = b("tapGestures", true); set(v) = set("tapGestures", v)
    /** Toolbar in a fixed strip above the page instead of floating over it. */
    var dockedToolbar get() = b("dockedToolbar", false); set(v) = set("dockedToolbar", v)
    var keepScreenOn get() = b("keepScreenOn", true); set(v) = set("keepScreenOn", v)

    var defaultPaper: String get() = sp.getString("defaultPaper", "LINED") ?: "LINED"
        set(v) = sp.edit().putString("defaultPaper", v).apply()
    var defaultPaperColor: Int get() = sp.getInt("defaultPaperColor", -1)
        set(v) = sp.edit().putInt("defaultPaperColor", v).apply()
}
