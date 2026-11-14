package com.aismartmath.board.board

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.aismartmath.board.math.MathCache
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aismartmath.board.data.ImageStore
import com.aismartmath.board.model.Fig

val TextDefault = Color(0xFFFFF1DC)
private val Copper = Color(0xFFD08A4E)
private val Cream = Color(0xFFF1D3A5)

private fun DrawScope.picPart(img: androidx.compose.ui.graphics.ImageBitmap, c: List<Float>, x: Float, y: Float, w: Int, h: Int, a: Float) {
    val sx = (c[0] * img.width).toInt().coerceIn(0, img.width - 1)
    val sy = (c[1] * img.height).toInt().coerceIn(0, img.height - 1)
    val sw = ((c[2] - c[0]) * img.width).toInt().coerceIn(1, img.width - sx)
    val sh = ((c[3] - c[1]) * img.height).toInt().coerceIn(1, img.height - sy)
    drawImage(
        image = img,
        srcOffset = IntOffset(sx, sy),
        srcSize = IntSize(sw, sh),
        dstOffset = IntOffset(x.toInt(), y.toInt()),
        dstSize = IntSize(w, h),
        alpha = a.coerceIn(0f, 1f),
        filterQuality = FilterQuality.Medium,
    )
}

/**
 * A picture: the visible (cropped) part of the stored image, stretched into the box p[0] .. p[1].
 * [pop] grows it in when it lands (1 = full size), [turn] runs 0..1 while a page is turned: the old page slides off and fades,
 * the new one slides in. A page of a file also gets a soft paper shadow.
 */
fun DrawScope.drawPicture(cam: Camera, f: Fig, a0: Float, pop: Float = 1f, turn: Float = 1f) {
    val a = a0 * f.imgAlpha
    if (a <= 0f || f.p.size < 2) return
    val img = ImageStore.get(f.imgKey)
    val l = cam.sx(minOf(f.p[0].x, f.p[1].x))
    val t = cam.sy(maxOf(f.p[0].y, f.p[1].y))
    val r = cam.sx(maxOf(f.p[0].x, f.p[1].x))
    val b = cam.sy(minOf(f.p[0].y, f.p[1].y))
    val w = (r - l).toInt()
    val h = (b - t).toInt()
    if (w < 1 || h < 1) return
    if (img == null) {
        drawRect(Color(0xFF3B271B).copy(alpha = a), Offset(l, t), Size(w.toFloat(), h.toFloat()))
        return
    }
    val sc = pop.coerceAtLeast(0.02f)
    scale(sc, sc, Offset((l + r) / 2f, (t + b) / 2f)) {
        if (f.docPath.isNotEmpty()) {
            val d = 1.dp.toPx()
            drawRect(Color.Black.copy(alpha = 0.10f * a), Offset(l - 3 * d, t - 1 * d), Size(w + 6 * d, h + 9 * d))
            drawRect(Color.Black.copy(alpha = 0.16f * a), Offset(l - 1.5f * d, t), Size(w + 3 * d, h + 4 * d))
        }
        val c = f.crop
        if (turn < 1f && f.prevKey.isNotEmpty()) {
            // a real page turn: the page swings about its left edge (it narrows as it turns), with shade, a shadow
            // thrown on the page underneath and a bright fold line. Forward the old page leaves; backward the new one swings in.
            val old = ImageStore.get(f.prevKey)
            val e = turn * turn * (3f - 2f * turn)
            val fwd = f.flipDir >= 0
            val under = if (fwd) img else (old ?: img)
            val flipper = if (fwd) (old ?: img) else img
            val underC = if (fwd) c else listOf(0f, 0f, 1f, 1f)
            val flipC = if (fwd) listOf(0f, 0f, 1f, 1f) else c
            val k = (if (fwd) 1f - e else e).coerceAtLeast(0.001f)
            picPart(under, underC, l, t, w, h, a)
            drawRect(Color.Black.copy(alpha = 0.38f * k * a), Offset(l, t), Size(w.toFloat(), h.toFloat()))
            scale(k, 1f, Offset(l, (t + b) / 2f)) {
                picPart(flipper, flipC, l, t, w, h, a)
                drawRect(Color.Black.copy(alpha = 0.30f * (1f - k) * a), Offset(l, t), Size(w.toFloat(), h.toFloat()))
            }
            val fx = l + w * k
            val sh = (w * 0.16f * (0.35f + k)).coerceAtLeast(1f)
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.45f * a), Color.Transparent), fx, fx + sh),
                topLeft = Offset(fx, t), size = Size(sh, h.toFloat()),
            )
            drawRect(Color.White.copy(alpha = 0.55f * a * (1f - kotlin.math.abs(2f * e - 1f))), Offset(fx - 1.5.dp.toPx(), t), Size(3.dp.toPx(), h.toFloat()))
        } else {
            picPart(img, c, l, t, w, h, a)
        }
    }
}

/** Pasted text: wraps inside the box p[0] .. p[1]; the letter height scales with the board. */
fun DrawScope.drawTextFig(cam: Camera, m: TextMeasurer, f: Fig, a: Float) {
    if (a <= 0f || f.p.size < 2 || f.txt.isEmpty()) return
    val px = (f.fsz * cam.u).toFloat()
    if (px < 2f || px > 1500f) return
    val col = f.col ?: TextDefault
    val wRatio = ((f.p[1].x - f.p[0].x) / f.fsz).toFloat().coerceAtLeast(0.5f)
    val mt = MathCache.text(f.txt, wRatio)
    val argb = col.copy(alpha = a).toArgb()
    val k = px / MathCache.REF
    val x0 = cam.sx(f.p[0].x)
    val y0 = cam.sy(f.p[0].y)
    drawIntoCanvas { c -> mt.draw(c.nativeCanvas, x0, y0, k, argb) }
}

private fun DrawScope.dashed(): PathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))

/** The box round everything that was picked with the sketch tool's "Drag" mode, with a corner to resize it. */
fun DrawScope.drawGroupBox(cam: Camera, b: DoubleArray, phase: Float) {
    val pad = 4.dp.toPx()
    val l = cam.sx(b[0]) - pad
    val t = cam.sy(b[3]) - pad
    val r = cam.sx(b[2]) + pad
    val bt = cam.sy(b[1]) + pad
    drawRect(Copper.copy(alpha = 0.10f), Offset(l, t), Size(r - l, bt - t), style = Fill)
    drawRect(
        Cream, Offset(l, t), Size(r - l, bt - t),
        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()), phase.dp.toPx())),
    )
    val hs = 7.dp.toPx()
    drawRect(Cream, Offset(r - hs, bt - hs), Size(hs * 2, hs * 2), style = Fill)
    drawRect(Copper, Offset(r - hs, bt - hs), Size(hs * 2, hs * 2), style = Stroke(2.dp.toPx()))
}

/** The rubber-band box while the finger drags it. */
fun DrawScope.drawLiveBox(a: Offset, b: Offset) {
    val l = minOf(a.x, b.x)
    val t = minOf(a.y, b.y)
    val w = kotlin.math.abs(a.x - b.x)
    val h = kotlin.math.abs(a.y - b.y)
    drawRect(Copper.copy(alpha = 0.14f), Offset(l, t), Size(w, h), style = Fill)
    drawRect(Cream, Offset(l, t), Size(w, h), style = Stroke(1.5.dp.toPx(), pathEffect = dashed()))
}

/** Crop frame of a picture: four big corner handles. */
fun DrawScope.drawCropFrame(cam: Camera, f: Fig) {
    if (f.p.size < 2) return
    val l = cam.sx(f.p[0].x)
    val t = cam.sy(f.p[0].y)
    val r = cam.sx(f.p[1].x)
    val b = cam.sy(f.p[1].y)
    drawRect(Cream, Offset(l, t), Size(r - l, b - t), style = Stroke(2.dp.toPx(), pathEffect = dashed()))
    val hs = 8.dp.toPx()
    for (o in listOf(Offset(l, t), Offset(r, t), Offset(r, b), Offset(l, b))) {
        drawRect(Cream, Offset(o.x - hs, o.y - hs), Size(hs * 2, hs * 2), style = Fill)
        drawRect(Copper, Offset(o.x - hs, o.y - hs), Size(hs * 2, hs * 2), style = Stroke(2.dp.toPx()))
    }
}

/** Selection frame for pictures and text. */
fun DrawScope.drawBoxSelection(cam: Camera, f: Fig, phase: Float, pulse: Float) {
    if (f.p.size < 2) return
    val l = cam.sx(f.p[0].x)
    val t = cam.sy(f.p[0].y)
    val r = cam.sx(f.p[1].x)
    val b = cam.sy(f.p[1].y)
    val line = androidx.compose.ui.graphics.lerp(Color.White, Copper, pulse)
    drawRect(
        line, Offset(l, t), Size(r - l, b - t),
        style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()), phase.dp.toPx())),
    )
}

private fun hueNow(speed: Long = 6_000_000L): Float = (System.nanoTime() / speed % 360L).toFloat()

/** Rings that spread from the finger when a picture is clicked; their colour runs through the rainbow. */
fun DrawScope.drawRipple(o: Offset, k: Float) {
    if (k <= 0f || k >= 1f) return
    val h0 = hueNow(3_000_000L)
    for (i in 0..2) {
        val kk = (k * 1.5f - i * 0.2f).coerceIn(0f, 1f)
        if (kk <= 0f || kk >= 1f) continue
        drawCircle(
            Color.hsv((h0 + i * 70f) % 360f, 0.75f, 1f).copy(alpha = (1f - kk) * 0.85f),
            (12f + 130f * kk).dp.toPx(), o, style = Stroke((5f * (1f - kk) + 1f).dp.toPx()),
        )
    }
    drawCircle(Color.White.copy(alpha = (1f - k) * 0.55f), (6f + 16f * (1f - k)).dp.toPx(), o)
}

/**
 * Selection of a picture or file page: a rainbow-changing glowing frame, a light that runs round it,
 * and four round corner handles that breathe. Dragging a corner resizes, dragging inside moves.
 */
fun DrawScope.drawPictureSelection(cam: Camera, f: Fig, phase: Float, pulse: Float) {
    if (f.p.size < 2) return
    val l = cam.sx(minOf(f.p[0].x, f.p[1].x))
    val t = cam.sy(maxOf(f.p[0].y, f.p[1].y))
    val r = cam.sx(maxOf(f.p[0].x, f.p[1].x))
    val b = cam.sy(minOf(f.p[0].y, f.p[1].y))
    val w = r - l
    val h = b - t
    val sz = Size(w, h)
    val hue = hueNow()
    val c1 = Color.hsv(hue, 0.7f, 1f)
    val c2 = Color.hsv((hue + 120f) % 360f, 0.7f, 1f)
    for ((wd, al) in listOf(16f to 0.05f, 11f to 0.08f, 7f to 0.12f, 3.5f to 0.2f)) {
        drawRect(c1.copy(alpha = al + 0.06f * pulse), Offset(l, t), sz, style = Stroke(wd.dp.toPx()))
    }
    drawRect(
        c1, Offset(l, t), sz,
        style = Stroke(1.8.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()), phase.dp.toPx())),
    )
    // a light running round the edge, with a fading tail
    val per = 2f * (w + h)
    if (per > 10f) {
        val u0 = (System.nanoTime() / 1_600_000L % 1000L) / 1000f
        for (j in 0..7) {
            val d = (((u0 - j * 0.012f) % 1f) + 1f) % 1f * per
            val q = when {
                d < w -> Offset(l + d, t)
                d < w + h -> Offset(r, t + d - w)
                d < 2 * w + h -> Offset(r - (d - w - h), b)
                else -> Offset(l, b - (d - 2 * w - h))
            }
            drawCircle(Color.White.copy(alpha = 0.9f - j * 0.11f), (5f - j * 0.5f).dp.toPx(), q)
            drawCircle(c2.copy(alpha = 0.35f - j * 0.04f), (11f - j).dp.toPx(), q)
        }
    }
    for (o in listOf(Offset(l, t), Offset(r, t), Offset(r, b), Offset(l, b))) {
        drawCircle(c1.copy(alpha = 0.30f - 0.18f * pulse), (15f + 4f * pulse).dp.toPx(), o)
        drawCircle(Color.White, 9.dp.toPx(), o)
        drawCircle(c2, 9.dp.toPx(), o, style = Stroke(2.5.dp.toPx()))
        drawCircle(c1, 3.dp.toPx(), o)
    }
}
