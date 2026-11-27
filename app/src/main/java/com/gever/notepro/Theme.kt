package com.gever.notepro

// NotePro's own palette (deliberately not Notability's colours).
const val ACCENT = 0xFF2B59C3.toInt()       // selection / primary buttons
const val ACCENT_SOFT = 0xFFE6EDFB.toInt()  // selected-tool background
const val ICON = 0xFF3A3F4B.toInt()
const val INK = 0xFF1C1F26.toInt()          // main text
const val SUB = 0xFF6B7280.toInt()          // secondary text
const val CARD = 0xFFFFFFFF.toInt()         // floating cards (toolbar, rail)
const val LINE = 0xFFE3E6EB.toInt()         // dividers
const val DANGER = 0xFFC62828.toInt()       // destructive menu rows

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

/** Pages of the colour popover (2 rows x 8 each): bright, pastel, deep, neon. Own selection. */
val COLOR_PAGES: List<IntArray> = listOf(
    longArrayOf(0xFFE53935, 0xFFF57C00, 0xFFFBC02D, 0xFF7CB342, 0xFF2E7D32, 0xFF00897B, 0xFF00ACC1, 0xFF1E88E5,
        0xFF3949AB, 0xFF8E24AA, 0xFFD81B60, 0xFF6D4C41, 0xFF1F1F24, 0xFF4B5563, 0xFF9CA3AF, 0xFFFFFFFF),
    longArrayOf(0xFFF8A5A5, 0xFFFFC48C, 0xFFFFE58F, 0xFFC9E69B, 0xFF9ED9A9, 0xFF8FD8CF, 0xFF9BDCEB, 0xFF9EC5F5,
        0xFFB1B9EF, 0xFFD2A8E8, 0xFFF5A8CB, 0xFFD9BBA8, 0xFFE3E6EB, 0xFFCBD5E1, 0xFFF3EFE6, 0xFFFFF6D5),
    longArrayOf(0xFF8B1A1A, 0xFF9A3B00, 0xFF8A6D00, 0xFF3F6212, 0xFF14532D, 0xFF134E4A, 0xFF155E75, 0xFF1E3A8A,
        0xFF312E81, 0xFF581C87, 0xFF831843, 0xFF4A2C1F, 0xFF111827, 0xFF1F2937, 0xFF374151, 0xFF57534E),
    longArrayOf(0xFFFF1744, 0xFFFF6D00, 0xFFFFD600, 0xFFAEEA00, 0xFF00E676, 0xFF1DE9B6, 0xFF00E5FF, 0xFF2979FF,
        0xFF651FFF, 0xFFD500F9, 0xFFF50057, 0xFFFF4081, 0xFF76FF03, 0xFFFFAB00, 0xFF00B0FF, 0xFFC51162),
).map { page -> IntArray(page.size) { page[it].toInt() } }

