@file:OptIn(ExperimentalInkCustomBrushApi::class)

package com.gever.notepro

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushCoat
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.StockBrushes
import java.util.IdentityHashMap
import kotlin.math.PI

/** Line types in the pen's style popover. */
enum class PenType { PRESSURE, MARKER, DASHED, DOTTED }

/** Optional effect on top of the line type. */
enum class PenEffect { NONE, RAINBOW, GLITTER }

/**
 * Highlighter tips: slanted flat nib (angle −60°..+60°), straight band with square ends, band with round ends,
 * dashes along the stroke, round dots.
 */
enum class HlTip { SLANTED, STRAIGHT, ROUND, DASHED, DOTTED }

/**
 * All brush families NotePro draws with, and the one-byte code each stroke stores in ink.bin.
 *
 * Code layout: low 4 bits = kind, high 4 bits = pen stabilization level 0..10 (0 = off).
 * Kinds: 0 pen (pressure), 1 marker (fixed width), 2 highlighter, 3 dashed, 4 dotted, 5 fountain pen,
 * 6 pencil, 7..10 rainbow + (pressure, fixed, dashed, dotted), 11..14 glitter + the same four.
 * Codes 0..3 are what older files contain, so they load unchanged.
 * Kind 15 = highlighter with a chosen tip; bits 8..10 = HlTip, bits 11..15 = slant (−60°..+60° in 5° steps).
 * The default highlighter (slanted −30°) keeps kind 2 = the library's stock highlighter.
 * ink.bin v2 stores the code as an int (v1 had one byte).
 */
object Families {
    private const val K_PEN = 0
    private const val K_MARKER = 1
    private const val K_HIGHLIGHTER = 2
    private const val K_DASHED = 3
    private const val K_DOTTED = 4
    const val K_FOUNTAIN = 5
    const val K_PENCIL = 6
    private val TYPE_KINDS = intArrayOf(K_PEN, K_MARKER, K_DASHED, K_DOTTED)

    val pen: BrushFamily = StockBrushes.pressurePen()
    val marker: BrushFamily = StockBrushes.marker()
    val highlighter: BrushFamily = StockBrushes.highlighter()
    val dashed: BrushFamily = StockBrushes.dashedLine()

    private val cache = HashMap<Int, BrushFamily>()
    private val codes = IdentityHashMap<BrushFamily, Int>()

    init {
        remember(K_PEN, pen); remember(K_MARKER, marker); remember(K_HIGHLIGHTER, highlighter); remember(K_DASHED, dashed)
    }

    private fun remember(code: Int, f: BrushFamily): BrushFamily { cache[code] = f; codes[f] = code; return f }

    /** The stored code of a stroke's brush (0 = pen if unknown). */
    fun code(b: Brush): Int = codes[b.family] ?: 0

    /** Family for a stored code. Built once and cached; an unknown or unbuildable code falls back to the pen. */
    fun of(code: Int): BrushFamily {
        val c = code and 0xFFFF
        cache[c]?.let { return it }
        val built = runCatching { build(c) }.getOrNull()
        if (built == null) { cache[c] = pen; return pen }
        return remember(c, built)
    }

    /** High-nibble value meaning "exact geometry": no input smoothing (used for snapped shapes). */
    private const val EXACT = 15

    /**
     * The same brush family without input smoothing. The normal smoothing lags behind at the start of a
     * stroke, which bent the beginning of perfect circles; shapes must follow their outline exactly.
     */
    fun exact(f: BrushFamily): BrushFamily { val c = codes[f] ?: 0; return of((c and 0xF0.inv()) or (EXACT shl 4)) }

    private const val K_HL_TIP = 15
    /** The library highlighter's nib is rotated 150°: its top leans 30° to the left = −30° in our terms. */
    const val HL_DEFAULT_ANGLE = -30

    /** True for every highlighter variant. */
    fun isHighlighter(f: BrushFamily): Boolean { val k = (codes[f] ?: 0) and 0xF; return k == K_HIGHLIGHTER || k == K_HL_TIP }

    /** Code for the highlighter with the given tip, slant (−60..60°, used by SLANTED) and stabilization 0..10. */
    fun hlCode(tip: HlTip, angle: Int, stab: Int): Int {
        val s = stab.coerceIn(0, 10) shl 4
        val a = Math.round(angle.coerceIn(-60, 60) / 5f) * 5
        if (tip == HlTip.SLANTED && a == HL_DEFAULT_ANGLE) return K_HIGHLIGHTER or s
        val ang = if (tip == HlTip.SLANTED) a else 0        // the slant only matters for the slanted nib
        return K_HL_TIP or s or (tip.ordinal shl 8) or (((ang + 60) / 5) shl 11)
    }

    /** The stock highlighter with a different nib (keeps its paint, soft ends and see-through overlap). */
    private fun highlighterTip(tip: HlTip, angle: Int): BrushFamily {
        val coat = highlighter.coats[0]
        if (tip == HlTip.DASHED || tip == HlTip.DOTTED) {
            // Separate marks ("particles") with gaps; dashes turn with the stroke like the library's dashed line.
            val t = if (tip == HlTip.DASHED)
                dashed.coats[0].tip.toBuilder().setScaleX(1.3f).setScaleY(0.62f).setCornerRounding(0.25f).setParticleGapDistanceScale(1.6f).build()
            else
                BrushTip.builder().setScaleX(0.62f).setScaleY(0.62f).setCornerRounding(1f).setParticleGapDistanceScale(1.0f).build()
            return highlighter.toBuilder().setCoats(listOf(coat.toBuilder().setTip(t).build())).build()
        }
        val keep = coat.tip.behaviors.filter { b ->
            tip == HlTip.SLANTED || b.terminalNodes.none { n -> n is BrushBehavior.TargetNode && n.target == BrushBehavior.Target.CORNER_ROUNDING_OFFSET }
        }
        val t = when (tip) {
            // a thin nib: 0° stands upright, positive angles lean its top to the right (checked on screen)
            HlTip.SLANTED -> coat.tip.toBuilder().setRotationDegrees((((angle % 180) + 180) % 180).toFloat())
            HlTip.STRAIGHT -> coat.tip.toBuilder().setScaleX(0.7f).setScaleY(0.7f).setCornerRounding(0f).setRotationDegrees(0f)
            else -> coat.tip.toBuilder().setScaleX(0.7f).setScaleY(0.7f).setCornerRounding(1f).setRotationDegrees(0f)
        }.setBehaviors(keep).build()
        return highlighter.toBuilder().setCoats(listOf(coat.toBuilder().setTip(t).build())).build()
    }

    /** Code for the regular pen with the given style; [stab] is 0..10 (stabilization 0.0..1.0). */
    fun penCode(type: PenType, effect: PenEffect, stab: Int): Int {
        val t = type.ordinal
        val kind = when (effect) { PenEffect.NONE -> TYPE_KINDS[t]; PenEffect.RAINBOW -> 7 + t; PenEffect.GLITTER -> 11 + t }
        return kind or (stab.coerceIn(0, 10) shl 4)
    }

    private fun build(code: Int): BrushFamily {
        val kind = code and 0xF
        val stab = (code shr 4) and 0xF
        val base = when (kind) {
            K_PEN -> pen; K_MARKER -> marker; K_HIGHLIGHTER -> highlighter; K_DASHED -> dashed
            K_DOTTED -> dotted()
            K_FOUNTAIN -> fountain()
            K_PENCIL -> pencil()
            in 7..10 -> rainbow(of(TYPE_KINDS[kind - 7]))
            in 11..14 -> glitter(of(TYPE_KINDS[kind - 11]))
            K_HL_TIP -> highlighterTip(HlTip.values().getOrElse((code shr 8) and 7) { HlTip.SLANTED }, ((code shr 11) and 31) * 5 - 60)
            else -> pen
        }
        // Stabilization = the library's sliding-window input smoothing; stronger = longer window.
        return when {
            stab == EXACT -> base.toBuilder().setInputModel(BrushFamily.EXPERIMENTAL_NAIVE_MODEL).build()
            stab > 0 -> base.toBuilder().setInputModel(BrushFamily.SlidingWindowModel(stab * 15L, 180)).build()
            else -> base
        }
    }

    // ---------- building blocks ----------
    private fun source(s: BrushBehavior.Source, from: Float, to: Float, out: BrushBehavior.OutOfRange = BrushBehavior.OutOfRange.CLAMP) =
        BrushBehavior.SourceNode(s, from, to, out)

    private fun behavior(target: BrushBehavior.Target, from: Float, to: Float, input: BrushBehavior.ValueNode) =
        BrushBehavior(listOf(BrushBehavior.TargetNode(target, from, to, input)))

    private fun pressureTo(target: BrushBehavior.Target, from: Float, to: Float) =
        behavior(target, from, to, source(BrushBehavior.Source.NORMALIZED_PRESSURE, 0f, 1f))

    private fun noiseTo(target: BrushBehavior.Target, from: Float, to: Float, seed: Int, period: Float) =
        behavior(target, from, to, BrushBehavior.NoiseNode(seed, BrushBehavior.DampingSource.DISTANCE_IN_MULTIPLES_OF_BRUSH_SIZE, period))

    private fun withTipBehavior(f: BrushFamily, extra: BrushBehavior): BrushFamily =
        f.toBuilder().setCoats(f.coats.map { c ->
            c.toBuilder().setTip(c.tip.toBuilder().setBehaviors(c.tip.behaviors + extra).build()).build()
        }).build()

    // ---------- families ----------
    /** Round dots spaced about one dot apart. */
    private fun dotted() = BrushFamily(
        BrushTip.builder().setCornerRounding(1f).setParticleGapDistanceScale(2.2f).build(), BrushPaint(), "np:dotted")

    /** Broad flat nib held at 45°: thick and thin strokes like a fountain / calligraphy pen. */
    private fun fountain() = BrushFamily(
        BrushTip.builder().setScaleX(1f).setScaleY(0.24f).setCornerRounding(0.4f).setRotationDegrees(45f)
            .setBehaviors(listOf(pressureTo(BrushBehavior.Target.SIZE_MULTIPLIER, 0.6f, 1.15f))).build(),
        BrushPaint(), "np:fountain")

    /** Thin, pressure-dependent opacity with a little grain. */
    private fun pencil() = BrushFamily(
        BrushTip.builder().setCornerRounding(1f).setBehaviors(listOf(
            pressureTo(BrushBehavior.Target.OPACITY_MULTIPLIER, 0.35f, 1f),
            pressureTo(BrushBehavior.Target.SIZE_MULTIPLIER, 0.85f, 1.1f),
            noiseTo(BrushBehavior.Target.OPACITY_MULTIPLIER, 0.7f, 1f, 7, 0.5f),
        )).build(), BrushPaint(), "np:pencil")

    /** Hue cycles along the stroke (drawn from a saturated base colour, see NoteCanvasView.brush()). */
    private fun rainbow(base: BrushFamily) = withTipBehavior(base, behavior(BrushBehavior.Target.HUE_OFFSET_IN_RADIANS, 0f, (2 * PI).toFloat(),
        source(BrushBehavior.Source.DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE, 0f, 40f, BrushBehavior.OutOfRange.REPEAT)))

    /** The line plus a second coat of small, lighter, randomly scattered sparkles. */
    private fun glitter(base: BrushFamily): BrushFamily {
        val sparkles = BrushCoat(BrushTip.builder().setScaleX(0.42f).setScaleY(0.42f).setCornerRounding(1f)
            .setParticleGapDistanceScale(0.35f)
            .setBehaviors(listOf(
                noiseTo(BrushBehavior.Target.POSITION_OFFSET_LATERAL_IN_MULTIPLES_OF_BRUSH_SIZE, -0.5f, 0.5f, 11, 0.3f),
                noiseTo(BrushBehavior.Target.POSITION_OFFSET_FORWARD_IN_MULTIPLES_OF_BRUSH_SIZE, -0.3f, 0.3f, 23, 0.3f),
                noiseTo(BrushBehavior.Target.LUMINOSITY, 0.25f, 0.8f, 37, 0.25f),
                noiseTo(BrushBehavior.Target.OPACITY_MULTIPLIER, 0.25f, 1f, 51, 0.2f),
                noiseTo(BrushBehavior.Target.SIZE_MULTIPLIER, 0.4f, 1.3f, 67, 0.25f),
            )).build(), BrushPaint())
        return base.toBuilder().setCoats(base.coats + sparkles).build()
    }
}
