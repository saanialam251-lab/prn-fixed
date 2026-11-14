package com.aismartmath.board.board

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aismartmath.board.model.Fig
import com.aismartmath.board.ui.components.PopIn
import com.aismartmath.board.ui.components.glass
import com.aismartmath.board.ui.theme.BoardColors
import java.io.File
import kotlin.math.roundToInt

/**
 * The bar of a picture or file page. It is only there while that picture is selected:
 * turn pages, copy the page, cut a part out (the page stays), size, see-through, pen, full screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileBar(state: BoardState, f: Fig) {
    val accent = BoardColors.Indigo
    val shape = RoundedCornerShape(14.dp)
    val isDoc = f.docPath.isNotEmpty()
    val total = f.docCount
    var chip = 0

    Column(
        Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)).glass(shape, accent, 8.dp).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (isDoc) "📄" else "🖼️", fontSize = 18.sp)
            Text(
                if (isDoc) f.docName else "Picture",
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (isDoc && total > 0) {
                AnimatedContent(
                    targetState = f.docPage,
                    transitionSpec = {
                        val up = targetState > initialState
                        (slideInVertically(tween(220)) { if (up) it else -it } + fadeIn(tween(220))) togetherWith
                            (slideOutVertically(tween(180)) { if (up) -it else it } + fadeOut(tween(120)))
                    },
                    label = "pageNo",
                ) { pg ->
                    Text(
                        "Page ${pg + 1} / $total",
                        color = Color(0xFFF1D3A5), fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x33D08A4E)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }

        if (isDoc && total > 1) {
            var v by remember(f.docPage) { mutableFloatStateOf(f.docPage.toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FileChip("⏮", "First", false) { state.turnPage(f, 0) }
                FileChip("◀", "Back", false) { state.turnPage(f, f.docPage - 1) }
                Slider(
                    value = v,
                    onValueChange = { v = it },
                    onValueChangeFinished = { state.turnPage(f, v.roundToInt()) },
                    valueRange = 0f..(total - 1).toFloat(),
                    steps = if (total > 2) total - 2 else 0,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFF1D3A5), activeTrackColor = accent, inactiveTrackColor = Color(0xFF5C3D28)),
                    modifier = Modifier.weight(1f).height(28.dp),
                )
                FileChip("▶", "Next", false) { state.turnPage(f, f.docPage + 1) }
                FileChip("⏭", "Last", false) { state.turnPage(f, total - 1) }
            }
            if (state.pageBusy) Text("Drawing the page…", color = BoardColors.Muted, fontSize = 10.sp)
        }

        if (isDoc && total > 1 && state.fileBarFull) {
            var typed by remember(f.docPage) { mutableStateOf("") }
            fun go() {
                val n = typed.toIntOrNull()
                if (n == null || n < 1 || n > total) {
                    state.showToast("Type a page from 1 to $total")
                } else {
                    state.turnPage(f, n - 1)
                    typed = ""
                }
            }
            val bx = RoundedCornerShape(8.dp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Go to page", color = BoardColors.Muted, fontSize = 11.sp)
                BasicTextField(
                    value = typed,
                    onValueChange = { typed = it.filter { ch -> ch.isDigit() }.take(5) },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go() }),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier.width(68.dp).clip(bx).background(Color(0xFF3B271B)).border(BorderStroke(1.dp, Color(0xFF6B4630)), bx).padding(horizontal = 8.dp, vertical = 6.dp),
                )
                FileChip("➜", "Go", false, wide = true) { go() }
                Text("of $total", color = BoardColors.Muted, fontSize = 11.sp)
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            PopIn(chip++) { FileChip("▁", "Minimise", false, wide = true) { state.minimizePic(f) } }
            PopIn(chip++) { FileChip("⤢", if (f.restP != null) "Shrink back" else "Enlarge", false, wide = true) { state.toggleEnlarge(f) } }
            if (!state.fileBarFull) {
                PopIn(chip++) { FileChip("✂", "Cut part", false, wide = true) { state.startBoxCut() } }
                PopIn(chip++) { FileChip("⋯", "Double-tap: all options", false, wide = true) { state.fileBarFull = true } }
            }
            if (state.fileBarFull) PopIn(chip++) { FileChip("✂", "Cut part", false, wide = true) { state.startBoxCut() } }
            if (state.fileBarFull) PopIn(chip++) { FileChip("⧉", "Copy page", false, wide = true) { state.duplicatePic(f) } }
            if (state.fileBarFull) PopIn(chip++) { FileChip("✏️", "Pen on it", false, wide = true) { state.selectTool("pen"); state.showToast("✏️ Write or draw right on the page — it stays on the board") } }
            if (state.fileBarFull) PopIn(chip++) { FileChip("＋", "Bigger", false, wide = true) { state.scalePic(f, 1.2) } }
            if (state.fileBarFull) PopIn(chip++) { FileChip("－", "Smaller", false, wide = true) { state.scalePic(f, 1 / 1.2) } }
            if (isDoc && state.fileBarFull) {
                PopIn(chip++) {
                    FileChip("⛶", "Full screen", false, wide = true) {
                        val kind = docKindOf(File(f.docPath))
                        state.openDoc = OpenDoc(f.docName, kind, File(f.docPath))
                    }
                }
            }
        }

        if (state.fileBarFull) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("◐ See-through", color = BoardColors.Muted, fontSize = 10.sp)
            Slider(
                value = f.imgAlpha,
                onValueChange = { f.imgAlpha = it.coerceIn(0.15f, 1f) },
                valueRange = 0.15f..1f,
                colors = SliderDefaults.colors(thumbColor = Color(0xFFF1D3A5), activeTrackColor = accent, inactiveTrackColor = Color(0xFF5C3D28)),
                modifier = Modifier.weight(1f).height(26.dp),
            )
        }
    }
}

/** A small button that squeezes with a spring and lights up when pressed. */
@Composable
private fun FileChip(icon: String, label: String, on: Boolean, wide: Boolean = false, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val clickNow by rememberUpdatedState(onClick)
    val sc by animateFloatAsState(if (pressed) 0.88f else 1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium), label = "chipPress")
    val bd by animateColorAsState(if (pressed || on) Color(0xFFF1D3A5) else Color(0xFF6B4630), tween(120), label = "chipBorder")
    val fill by animateColorAsState(if (pressed) BoardColors.Indigo else if (on) BoardColors.Indigo.copy(alpha = 0.6f) else Color(0xFF3B271B), tween(120), label = "chipFill")
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .graphicsLayer { scaleX = sc; scaleY = sc }
            .widthIn(min = 30.dp)
            .height(30.dp)
            .clip(shape)
            .background(fill)
            .border(BorderStroke(1.dp, bd), shape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { clickNow() },
                )
            }
            .padding(horizontal = if (wide) 9.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        Text(icon, fontSize = 13.sp, color = Color.White)
        if (wide) Text(label, fontSize = 11.sp, color = Color.White, maxLines = 1)
    }
}

/** The banner at the top that shows a file being scanned, then that it arrived. */
@Composable
fun DocArrival(state: BoardState, modifier: Modifier = Modifier) {
    val scanning = state.scanName
    val done = state.arrived
    AnimatedVisibility(
        visible = scanning != null || done != null,
        enter = slideInVertically(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)) { -it * 2 } + fadeIn(tween(200)),
        exit = slideOutVertically(tween(260)) { -it * 2 } + fadeOut(tween(200)),
        modifier = modifier,
    ) {
        val inf = rememberInfiniteTransition(label = "scan")
        val hue by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "scanHue")
        val shape = RoundedCornerShape(18.dp)
        val ring = Color.hsv(hue, 0.7f, 1f)
        Row(
            Modifier
                .clip(shape)
                .background(Color(0xF2201410))
                .border(BorderStroke(2.dp, ring), shape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (scanning != null) "📡" else "✅", fontSize = 16.sp)
            Text(
                if (scanning != null) "Scanning " + scanning + "…" else (done ?: ""),
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp),
            )
        }
    }
}
