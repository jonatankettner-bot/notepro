package com.gever.notepro

// NotePro's own palette (deliberately not Notability's colours).
const val ACCENT = 0xFF2B59C3.toInt()       // selection / primary buttons
const val ACCENT_SOFT = 0xFFE6EDFB.toInt()  // selected-tool background
const val ICON = 0xFF3A3F4B.toInt()
const val INK = 0xFF1C1F26.toInt()          // main text
const val SUB = 0xFF6B7280.toInt()          // secondary text
const val CARD = 0xFFFFFFFF.toInt()         // floating cards (toolbar, rail)
const val LINE = 0xFFE3E6EB.toInt()         // dividers

/** 3 rows x 8 colours for the full colour popover (pen). Highlighter uses the same hues, translucent. */
val PALETTE = intArrayOf(
    0xFF1F1F24.toInt(), 0xFF4B5563.toInt(), 0xFF9CA3AF.toInt(), 0xFF2155D6.toInt(), 0xFF0EA5E9.toInt(), 0xFF14B8A6.toInt(), 0xFF178A43.toInt(), 0xFF84CC16.toInt(),
    0xFFD62F2F.toInt(), 0xFFF43F5E.toInt(), 0xFFEC4899.toInt(), 0xFF7B3FE4.toInt(), 0xFF6366F1.toInt(), 0xFFE07A10.toInt(), 0xFFF59E0B.toInt(), 0xFFFACC15.toInt(),
    0xFF7C2D12.toInt(), 0xFFA16207.toInt(), 0xFF065F46.toInt(), 0xFF1E3A8A.toInt(), 0xFF581C87.toInt(), 0xFF9F1239.toInt(), 0xFFCBD5E1.toInt(), 0xFFFFFFFF.toInt(),
)
val HL_PALETTE = intArrayOf(
    0xFFFFE14D.toInt(), 0xFFFFC94D.toInt(), 0xFFFFB36B.toInt(), 0xFFFF8F8F.toInt(), 0xFFFF8FC2.toInt(), 0xFFE3A6FF.toInt(), 0xFFB8A6FF.toInt(), 0xFF8FB3FF.toInt(),
    0xFF7FC8FF.toInt(), 0xFF7FE3E0.toInt(), 0xFF8BE38B.toInt(), 0xFFC6EB6E.toInt(), 0xFFD9D9D9.toInt(), 0xFFFFD6A5.toInt(), 0xFFFDFFB6.toInt(), 0xFFCAFFBF.toInt(),
)
const val HL_ALPHA = 0x66
