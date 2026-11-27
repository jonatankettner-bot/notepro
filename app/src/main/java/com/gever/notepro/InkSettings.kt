package com.gever.notepro

/** Width scales: slider step 1..12 → brush size in page units (about 1 unit = 1 dp at fit zoom). */
val PEN_WIDTHS = floatArrayOf(0.8f, 1.2f, 1.7f, 2.4f, 3.2f, 4f, 5f, 6.5f, 8f, 10f, 12.5f, 15f)
val HL_WIDTHS = floatArrayOf(8f, 11f, 14f, 18f, 22f, 26f, 30f, 35f, 40f, 46f, 52f, 60f)
const val MAX_SLOTS = 6

/**
 * Quick settings of one inking tool, saved between sessions: its colour slots (the selected slot is the
 * active colour) and three width presets (slider steps), one of them selected.
 */
class InkSettings(private val key: String, defSlots: IntArray, defWidths: IntArray, val scale: FloatArray) {
    val slots = defSlots.toMutableList()
    var slot = 0
    val widths = defWidths.copyOf()
    var widthIdx = 1

    val color get() = slots[slot.coerceIn(0, slots.lastIndex)]
    val size get() = sizeOf(widths[widthIdx])
    fun sizeOf(step: Int) = scale[(step - 1).coerceIn(0, scale.lastIndex)]
    val steps get() = scale.size

    fun load(p: Prefs) {
        p.str("$key.slots", "").split(',').mapNotNull { it.toLongOrNull()?.toInt() }.take(MAX_SLOTS)
            .takeIf { it.isNotEmpty() }?.let { slots.clear(); slots.addAll(it) }
        slot = p.int("$key.slot", 0).coerceIn(0, slots.lastIndex)
        p.str("$key.widths", "").split(',').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 3 }
            ?.forEachIndexed { i, v -> widths[i] = v.coerceIn(1, scale.size) }
        widthIdx = p.int("$key.widx", 1).coerceIn(0, 2)
    }

    fun save(p: Prefs) {
        p.putStr("$key.slots", slots.joinToString(",") { (it.toLong() and 0xFFFFFFFFL).toString() })
        p.putInt("$key.slot", slot)
        p.putStr("$key.widths", widths.joinToString(","))
        p.putInt("$key.widx", widthIdx)
    }
}
