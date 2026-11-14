package com.aismartmath.board.board

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.aismartmath.board.ui.theme.BoardColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

// =====================================================================================================================
//  Files tab: pick a file, it opens full screen. PDFs and pictures turn page by page (swipe), zoom with two fingers,
//  jump to any page, and a box can cut a part out of a page onto the board. Text, Word and Excel files open as text / tables.
// =====================================================================================================================

enum class DocKind { PDF, TEXT, DOCX, XLSX, IMAGE, OTHER }

/** A file that was copied into the app's cache so it can always be read again. */
class OpenDoc(val name: String, val kind: DocKind, val file: File)

private val PAPER = Color(0xFFFFF8EC)
private val INK = Color(0xFF2B1B10)
private val VIEW_BG = Color(0xFF140D09)
private val BAR_BG = Color(0xFF2A1B13)

// ---------------------------------------------------------------------------------------------------------------------
//  Getting the file
// ---------------------------------------------------------------------------------------------------------------------

private val IMG_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic")
private val TEXT_EXT = setOf("txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "log", "kt", "java", "py", "js", "css", "tex", "c", "cpp", "h", "sh", "yaml", "yml", "ini")
private val OLD_EXT = setOf("doc", "xls", "ppt", "pptx", "odt", "ods", "odp", "rtf")

private fun nameOf(ctx: Context, uri: Uri): String {
    var n: String? = null
    try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) n = c.getString(i)
            }
        }
    } catch (e: Exception) {
        // fall back to the last part of the address
    }
    return n ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "file")
}

private fun looksLikeText(f: File): Boolean {
    return try {
        val buf = ByteArray(4096)
        val n = f.inputStream().use { it.read(buf) }
        if (n <= 0) false else (0 until n).none { buf[it].toInt() == 0 }
    } catch (e: Exception) {
        false
    }
}

private fun kindOf(name: String, mime: String?, f: File): DocKind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext == "pdf" || mime == "application/pdf" -> DocKind.PDF
        ext == "docx" -> DocKind.DOCX
        ext == "xlsx" || ext == "xlsm" -> DocKind.XLSX
        ext in IMG_EXT || (mime != null && mime.startsWith("image/")) -> DocKind.IMAGE
        ext in TEXT_EXT || (mime != null && mime.startsWith("text/")) -> DocKind.TEXT
        ext in OLD_EXT -> DocKind.OTHER
        looksLikeText(f) -> DocKind.TEXT
        else -> DocKind.OTHER
    }
}

/** Copies the chosen file into the cache (works for files in Drive, Downloads, anywhere) and says what kind it is. */
private fun importFile(ctx: Context, uri: Uri): OpenDoc? {
    return try {
        val name = nameOf(ctx, uri)
        val dir = File(ctx.cacheDir, "docs")
        dir.mkdirs()
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(8)?.forEach { it.delete() }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(60)
        val f = File(dir, System.currentTimeMillis().toString() + "_" + safe)
        val ins = ctx.contentResolver.openInputStream(uri) ?: return null
        ins.use { input -> FileOutputStream(f).use { out -> input.copyTo(out) } }
        OpenDoc(name, kindOf(name, ctx.contentResolver.getType(uri), f), f)
    } catch (e: Exception) {
        null
    }
}

// ---------------------------------------------------------------------------------------------------------------------
//  Pages: PDFs and pictures
// ---------------------------------------------------------------------------------------------------------------------

/** Something that can draw its pages as pictures: the whole page, or just a part of it (fractions 0..1 of the page). */
@androidx.compose.runtime.Stable
interface PageSource {
    val count: Int
    fun renderFull(i: Int, widthPx: Int): Bitmap?
    fun renderRegion(i: Int, x0: Float, y0: Float, x1: Float, y1: Float, outW: Int): Bitmap?
    fun close()
}

class PdfSource(file: File) : PageSource {
    private val pfd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = PdfRenderer(pfd)
    private val lock = Any()
    private var closed = false
    override val count: Int = renderer.pageCount

    override fun renderFull(i: Int, widthPx: Int): Bitmap? = synchronized(lock) {
        if (closed || i < 0 || i >= count) return@synchronized null
        try {
            val p = renderer.openPage(i)
            try {
                var sc = widthPx.coerceIn(200, 4096).toFloat() / p.width
                if (p.height * sc > 6000f) sc = 6000f / p.height
                val w = maxOf(1, (p.width * sc).roundToInt())
                val h = maxOf(1, (p.height * sc).roundToInt())
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                val m = Matrix()
                m.setScale(sc, sc)
                p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            } finally {
                p.close()
            }
        } catch (e: Throwable) {
            null
        }
    }

    override fun renderRegion(i: Int, x0: Float, y0: Float, x1: Float, y1: Float, outW: Int): Bitmap? = synchronized(lock) {
        if (closed || i < 0 || i >= count) return@synchronized null
        try {
            val p = renderer.openPage(i)
            try {
                val rw = (x1 - x0) * p.width
                val rh = (y1 - y0) * p.height
                if (rw < 1f || rh < 1f) return@synchronized null
                var sc = outW.coerceAtLeast(16).toFloat() / rw
                val maxPx = 9_000_000f
                if (rw * sc * rh * sc > maxPx) sc = sqrt(maxPx / (rw * rh))
                val w = maxOf(1, (rw * sc).roundToInt())
                val h = maxOf(1, (rh * sc).roundToInt())
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                val m = Matrix()
                m.setScale(sc, sc)
                m.postTranslate(-x0 * p.width * sc, -y0 * p.height * sc)
                p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            } finally {
                p.close()
            }
        } catch (e: Throwable) {
            null
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try { renderer.close() } catch (e: Throwable) { }
            try { pfd.close() } catch (e: Throwable) { }
        }
    }
}

class ImageSource(file: File) : PageSource {
    private val bmp: Bitmap? = decode(file)
    override val count: Int get() = if (bmp != null) 1 else 0

    private fun decode(f: File): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var w = bounds.outWidth
            var h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            var sample = 1
            while (w / 2 >= 3200 || h / 2 >= 3200) {
                sample *= 2
                w /= 2
                h /= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val b = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return null
            val rot = try {
                when (android.media.ExifInterface(f.absolutePath).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90f
                    3 -> 180f
                    8 -> 270f
                    else -> 0f
                }
            } catch (e: Exception) {
                0f
            }
            if (rot == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot) }, true)
        } catch (e: Throwable) {
            null
        }
    }

    override fun renderFull(i: Int, widthPx: Int): Bitmap? = bmp

    override fun renderRegion(i: Int, x0: Float, y0: Float, x1: Float, y1: Float, outW: Int): Bitmap? {
        val b = bmp ?: return null
        val sx = (x0 * b.width).toInt().coerceIn(0, b.width - 1)
        val sy = (y0 * b.height).toInt().coerceIn(0, b.height - 1)
        val sw = ((x1 - x0) * b.width).toInt().coerceIn(1, b.width - sx)
        val sh = ((y1 - y0) * b.height).toInt().coerceIn(1, b.height - sy)
        return try {
            Bitmap.createBitmap(b, sx, sy, sw, sh)
        } catch (e: Throwable) {
            null
        }
    }

    override fun close() {}
}

// ---------------------------------------------------------------------------------------------------------------------
//  Text, Word and Excel readers
// ---------------------------------------------------------------------------------------------------------------------

/** One line of a text or Word file. heading 0 = normal, 1..3 = heading sizes. */
class DocLine(val text: String, val heading: Int = 0)

class Sheet(val name: String, val rows: List<List<String>>)

private fun readTextLines(f: File): List<DocLine> {
    val sb = StringBuilder()
    f.bufferedReader(Charsets.UTF_8).use { r ->
        val cb = CharArray(8192)
        var total = 0
        while (total < 3_000_000) {
            val n = r.read(cb)
            if (n < 0) break
            sb.append(cb, 0, n)
            total += n
        }
    }
    return sb.toString().replace("\r\n", "\n").replace('\r', '\n').split('\n').take(60000).map { DocLine(it) }
}

private fun readDocx(f: File): List<DocLine> {
    val out = ArrayList<DocLine>()
    ZipFile(f).use { zip ->
        val e = zip.getEntry("word/document.xml") ?: return out
        zip.getInputStream(e).use { ins ->
            val p = android.util.Xml.newPullParser()
            p.setInput(ins, "UTF-8")
            var ev = p.eventType
            val para = StringBuilder()
            var heading = 0
            var inTc = false
            val cell = StringBuilder()
            var row: ArrayList<String>? = null
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "w:p" -> {
                            para.setLength(0)
                            heading = 0
                        }
                        "w:t" -> para.append(p.nextText())
                        "w:tab" -> para.append('\t')
                        "w:br" -> para.append('\n')
                        "w:pStyle" -> {
                            val v = p.getAttributeValue(null, "w:val") ?: ""
                            heading = if (v == "Title") 1 else if (v.startsWith("Heading")) (v.removePrefix("Heading").toIntOrNull() ?: 1).coerceIn(1, 3) else 0
                        }
                        "w:tc" -> {
                            inTc = true
                            cell.setLength(0)
                        }
                        "w:tr" -> row = ArrayList()
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "w:p" -> {
                            val t = para.toString()
                            if (inTc) {
                                if (cell.isNotEmpty()) cell.append(' ')
                                cell.append(t.trim())
                            } else {
                                out.add(DocLine(t, heading))
                            }
                        }
                        "w:tc" -> {
                            inTc = false
                            row?.add(cell.toString().trim())
                        }
                        "w:tr" -> {
                            val r = row
                            if (r != null) out.add(DocLine(r.joinToString("  │  "), 0))
                            row = null
                        }
                    }
                }
                ev = p.next()
            }
        }
    }
    return out
}

private fun colIndex(ref: String): Int {
    var idx = 0
    var any = false
    for (ch in ref) {
        if (ch in 'A'..'Z') {
            idx = idx * 26 + (ch - 'A' + 1)
            any = true
        } else if (ch in 'a'..'z') {
            idx = idx * 26 + (ch - 'a' + 1)
            any = true
        } else break
    }
    return if (any) idx - 1 else 0
}

private fun niceNum(v: String): String {
    val d = v.toDoubleOrNull() ?: return v
    if (d == Math.rint(d) && abs(d) < 1e15) return d.toLong().toString()
    return try {
        java.math.BigDecimal(d).round(java.math.MathContext(10)).stripTrailingZeros().toPlainString()
    } catch (e: Exception) {
        v
    }
}

private fun putCell(row: ArrayList<String>?, col: Int, text: String) {
    if (row == null || col < 0 || col > 120) return
    while (row.size <= col) row.add("")
    row[col] = text
}

private fun readSheet(zip: ZipFile, path: String, shared: List<String>): List<List<String>> {
    val e = zip.getEntry(path) ?: return emptyList()
    val rows = ArrayList<List<String>>()
    zip.getInputStream(e).use { ins ->
        val p = android.util.Xml.newPullParser()
        p.setInput(ins, "UTF-8")
        var ev = p.eventType
        var cur: ArrayList<String>? = null
        var col = 0
        var type = ""
        while (ev != XmlPullParser.END_DOCUMENT && rows.size < 3000) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "row" -> cur = ArrayList()
                    "c" -> {
                        type = p.getAttributeValue(null, "t") ?: ""
                        col = colIndex(p.getAttributeValue(null, "r") ?: "")
                    }
                    "v" -> {
                        val v = p.nextText()
                        val shown = when (type) {
                            "s" -> shared.getOrElse(v.toIntOrNull() ?: -1) { "" }
                            "b" -> if (v == "1") "TRUE" else "FALSE"
                            "str", "e" -> v
                            else -> niceNum(v)
                        }
                        putCell(cur, col, shown)
                    }
                    "t" -> if (type == "inlineStr") putCell(cur, col, p.nextText())
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "row") {
                val c = cur
                if (c != null) rows.add(c)
                cur = null
            }
            ev = p.next()
        }
    }
    return rows
}

private fun readXlsx(f: File): List<Sheet> {
    val sheets = ArrayList<Sheet>()
    ZipFile(f).use { zip ->
        val shared = ArrayList<String>()
        zip.getEntry("xl/sharedStrings.xml")?.let { e ->
            zip.getInputStream(e).use { ins ->
                val p = android.util.Xml.newPullParser()
                p.setInput(ins, "UTF-8")
                var ev = p.eventType
                var sb = StringBuilder()
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        if (p.name == "si") sb = StringBuilder() else if (p.name == "t") sb.append(p.nextText())
                    } else if (ev == XmlPullParser.END_TAG && p.name == "si") {
                        shared.add(sb.toString())
                    }
                    ev = p.next()
                }
            }
        }
        val names = ArrayList<Pair<String, String>>()
        zip.getEntry("xl/workbook.xml")?.let { e ->
            zip.getInputStream(e).use { ins ->
                val p = android.util.Xml.newPullParser()
                p.setInput(ins, "UTF-8")
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && p.name == "sheet") {
                        names.add((p.getAttributeValue(null, "name") ?: "Sheet") to (p.getAttributeValue(null, "r:id") ?: ""))
                    }
                    ev = p.next()
                }
            }
        }
        val targets = HashMap<String, String>()
        zip.getEntry("xl/_rels/workbook.xml.rels")?.let { e ->
            zip.getInputStream(e).use { ins ->
                val p = android.util.Xml.newPullParser()
                p.setInput(ins, "UTF-8")
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && p.name == "Relationship") {
                        val id = p.getAttributeValue(null, "Id") ?: ""
                        val t = p.getAttributeValue(null, "Target") ?: ""
                        if (id.isNotEmpty()) targets[id] = t
                    }
                    ev = p.next()
                }
            }
        }
        names.forEachIndexed { i, (name, rid) ->
            val t = targets[rid]
            val path = if (t == null) "xl/worksheets/sheet${i + 1}.xml" else if (t.startsWith("/")) t.removePrefix("/") else "xl/$t"
            val rows = readSheet(zip, path, shared)
            sheets.add(Sheet(name, rows))
        }
    }
    return sheets
}

private sealed class Loaded {
    class Paged(val src: PageSource) : Loaded()
    class Lines(val lines: List<DocLine>) : Loaded()
    class Sheets(val sheets: List<Sheet>) : Loaded()
    class Failed(val msg: String) : Loaded()
}

private fun loadDoc(doc: OpenDoc): Loaded {
    return try {
        when (doc.kind) {
            DocKind.PDF -> {
                val s = PdfSource(doc.file)
                if (s.count == 0) Loaded.Failed("This PDF has no pages.") else Loaded.Paged(s)
            }
            DocKind.IMAGE -> {
                val s = ImageSource(doc.file)
                if (s.count == 0) Loaded.Failed("Could not read that picture.") else Loaded.Paged(s)
            }
            DocKind.TEXT -> Loaded.Lines(readTextLines(doc.file))
            DocKind.DOCX -> {
                val l = readDocx(doc.file)
                if (l.isEmpty()) Loaded.Failed("Nothing readable was found inside that Word file.") else Loaded.Lines(l)
            }
            DocKind.XLSX -> {
                val s = readXlsx(doc.file)
                if (s.isEmpty()) Loaded.Failed("Nothing readable was found inside that Excel file.") else Loaded.Sheets(s)
            }
            DocKind.OTHER -> Loaded.Failed("This kind of file cannot be shown here yet. Save it as a PDF (or .docx / .xlsx) and open that instead.")
        }
    } catch (e: SecurityException) {
        Loaded.Failed("This PDF is locked with a password. Remove the password and open it again.")
    } catch (e: OutOfMemoryError) {
        Loaded.Failed("That file is too big for this phone.")
    } catch (e: Exception) {
        Loaded.Failed("Could not open that file: " + (e.message ?: e.javaClass.simpleName))
    }
}

// ---------------------------------------------------------------------------------------------------------------------
//  The pop-up and the host
// ---------------------------------------------------------------------------------------------------------------------

private val PDF_TYPES = arrayOf("application/pdf")
private val TEXT_TYPES = arrayOf("text/*", "application/json", "application/xml")
private val WORD_TYPES = arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/msword")
private val EXCEL_TYPES = arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "text/csv")
private val PICTURE_TYPES = arrayOf("image/*")
private val ANY_TYPES = arrayOf("*/*")

/** Sits inside the board: shows the "what kind of file" pop-up, and the viewer when a file is open. */
@Composable
fun FilesHost(state: BoardState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            state.showToast("Opening…")
            scope.launch {
                val d = withContext(Dispatchers.IO) { importFile(ctx, uri) }
                if (d == null) {
                    state.showToast("Could not open that file")
                } else {
                    state.lastDoc = d
                    state.filesDialog = false
                    if (d.kind != DocKind.OTHER) {
                        val kept = withContext(Dispatchers.IO) { keepDoc(ctx, d) }
                        state.placeDoc(kept.absolutePath, d.name)
                    } else {
                        state.openDoc = d
                    }
                }
            }
        }
    }
    if (state.filesDialog) {
        FileTypeDialog(state) { types -> launcher.launch(types) }
    }
    val doc = state.openDoc
    if (doc != null) {
        FileViewer(state, doc) { state.openDoc = null }
    }
}

@Composable
private fun TypeRow(index: Int, icon: String, title: String, hint: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    var pressed by remember { mutableStateOf(false) }
    val sc by androidx.compose.animation.core.animateFloatAsState(
        if (pressed) 0.96f else 1f,
        androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = androidx.compose.animation.core.Spring.StiffnessMedium),
        label = "typePress",
    )
    com.aismartmath.board.ui.components.PopIn(index = index, fromBelow = true) {
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { scaleX = sc; scaleY = sc }
                .clip(shape)
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF4A2E1C), Color(0xFF2F1F15))))
                .border(BorderStroke(1.dp, if (pressed) Color(0xFFF1D3A5) else Color(0xFF6B4630)), shape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                        onTap = { onClick() },
                    )
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Color(0x33D08A4E)),
                contentAlignment = Alignment.Center,
            ) { Text(icon, fontSize = 20.sp, color = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(hint, color = BoardColors.Muted, fontSize = 10.sp)
            }
            Text("›", color = Color(0xFFF1D3A5), fontSize = 20.sp)
        }
    }
}

@Composable
private fun FileTypeDialog(state: BoardState, onPick: (Array<String>) -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Dialog(onDismissRequest = { state.filesDialog = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.padding(horizontal = 22.dp)) {
            Column(
                Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(BAR_BG)
                    .border(BorderStroke(1.dp, Color(0xFF6B4630)), shape)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📁  Add a file", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Pill("✕") { state.filesDialog = false }
                }
                Text("Every file rests on your board as a small card. Double-tap one to enlarge it.", color = BoardColors.Muted, fontSize = 11.sp)
                val last = state.lastDoc
                if (last != null && last.file.exists()) {
                    TypeRow(0, "↩", "Continue: " + last.name, "Put the file you had last back on the board") {
                        state.filesDialog = false
                        if (last.kind != DocKind.OTHER) state.placeDoc(last.file.absolutePath, last.name)
                        else state.openDoc = last
                    }
                }
                TypeRow(1, "📕", "PDF", ".pdf — lands on the board: drag, resize, turn pages") { onPick(PDF_TYPES) }
                TypeRow(2, "📝", "Text", ".txt .md .csv .json … — lands on the board as pages") { onPick(TEXT_TYPES) }
                TypeRow(3, "📘", "Word", ".docx — lands on the board as pages") { onPick(WORD_TYPES) }
                TypeRow(4, "📗", "Excel", ".xlsx — every sheet lands on the board") { onPick(EXCEL_TYPES) }
                TypeRow(5, "🖼️", "Picture", ".jpg .png .webp — lands on the board") { onPick(PICTURE_TYPES) }
                TypeRow(6, "📂", "Any file", "Look through everything on the phone") { onPick(ANY_TYPES) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
//  Viewer shell
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun Pill(label: String, on: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (on) BoardColors.Indigo.copy(alpha = 0.55f) else Color(0xFF3B271B))
            .border(BorderStroke(1.dp, if (on) Color(0xFFF1D3A5) else Color(0xFF6B4630)), shape)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 12.sp, maxLines = 1) }
}

@Composable
private fun ViewerTop(title: String, sub: String, onClose: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(BAR_BG).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill("✕", false, onClose)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, color = BoardColors.Muted, fontSize = 10.sp, maxLines = 1)
        }
        actions()
    }
}

@Composable
fun FileViewer(state: BoardState, doc: OpenDoc, onClose: () -> Unit) {
    BackHandler { onClose() }
    val loaded by produceState<Loaded?>(null, doc) { value = withContext(Dispatchers.IO) { loadDoc(doc) } }
    val l = loaded
    DisposableEffect(l) {
        onDispose { if (l is Loaded.Paged) l.src.close() }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(VIEW_BG)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        when (l) {
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BoardColors.Indigo, modifier = Modifier.size(34.dp))
            }
            is Loaded.Paged -> PagedViewer(state, doc, l.src, onClose)
            is Loaded.Lines -> TextViewer(doc, l.lines, onClose)
            is Loaded.Sheets -> SheetViewer(doc, l.sheets, onClose)
            is Loaded.Failed -> {
                ViewerTop(doc.name, "", onClose) {}
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(l.msg, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
//  PDF / picture viewer
// ---------------------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagedViewer(state: BoardState, doc: OpenDoc, src: PageSource, onClose: () -> Unit) {
    val total = src.count
    val pager = rememberPagerState(pageCount = { total })
    val scope = rememberCoroutineScope()
    var zoomed by remember { mutableStateOf(false) }
    var cutMode by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { zoomed = false }

    Column(Modifier.fillMaxSize()) {
        ViewerTop(doc.name, if (total > 1) "Page ${pager.currentPage + 1} of $total" else "Zoom with two fingers", onClose) {
            Pill(if (cutMode) "✂ Cut: ON" else "✂ Cut", cutMode) {
                cutMode = !cutMode
                if (cutMode) state.showToast("✂ Drag a box over the part you want — it goes onto the board")
            }
            Spacer(Modifier.width(5.dp))
            Pill("📌 Page") {
                val i = pager.currentPage
                state.showToast("Putting the page on the board…")
                scope.launch {
                    val b = withContext(Dispatchers.Default) { src.renderFull(i, 2200) }
                    if (b != null) {
                        state.addImage(b, null, 0.9)
                        onClose()
                    } else {
                        state.showToast("Could not draw that page")
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            HorizontalPager(
                state = pager,
                userScrollEnabled = !zoomed && !cutMode,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val offNow = (page - pager.currentPage) - pager.currentPageOffsetFraction
                Box(
                    Modifier
                        .fillMaxSize()
                        .zIndex(if (offNow < 0f) 1f else 0f)
                        .graphicsLayer {
                            // page turn: the page being turned swings away from its left edge, the next page waits underneath
                            val off = (page - pager.currentPage) - pager.currentPageOffsetFraction
                            val w = size.width
                            translationX = -off * w
                            cameraDistance = 18f * density
                            if (off < 0f) {
                                val t = (-off).coerceIn(0f, 1f)
                                transformOrigin = TransformOrigin(0f, 0.5f)
                                rotationY = 75f * t
                                alpha = 1f - 0.3f * t
                                scaleX = 1f
                                scaleY = 1f
                            } else {
                                val k = off.coerceIn(0f, 1f)
                                val sc = 1f - 0.06f * k
                                transformOrigin = TransformOrigin.Center
                                rotationY = 0f
                                alpha = 1f
                                scaleX = sc
                                scaleY = sc
                            }
                        }
                        .drawWithContent {
                            drawContent()
                            val off = (page - pager.currentPage) - pager.currentPageOffsetFraction
                            if (off < 0f) drawRect(Color.Black.copy(alpha = (-off).coerceIn(0f, 1f) * 0.45f))
                        },
                ) {
                    ZoomPage(
                        src = src,
                        index = page,
                        active = page == pager.currentPage,
                        cutMode = cutMode,
                        onZoomed = { zoomed = it },
                        onCut = { bmp ->
                            state.addImage(bmp, null, 0.7)
                            onClose()
                        },
                    )
                }
            }
            if (cutMode) {
                Text(
                    "✂ Drag a box over the part to cut out",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xCC2A1B13))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        if (total > 1) PageBar(state, pager, total)
    }
}

/** The bar at the bottom: slider, previous / next, and a box to type the page you want (1 to the last page). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageBar(state: BoardState, pager: androidx.compose.foundation.pager.PagerState, total: Int) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var field by remember { mutableStateOf("1") }
    var drag by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(pager.currentPage) { field = (pager.currentPage + 1).toString() }

    fun goTo(n: Int) {
        val t = n.coerceIn(1, total)
        field = t.toString()
        scope.launch {
            if (abs(t - 1 - pager.currentPage) <= 2) pager.animateScrollToPage(t - 1) else pager.scrollToPage(t - 1)
        }
    }

    fun jump() {
        val n = field.toIntOrNull()
        if (n == null) {
            state.showToast("Type a page number from 1 to $total")
            field = (pager.currentPage + 1).toString()
        } else {
            if (n < 1 || n > total) state.showToast("This file has pages 1 to $total")
            goTo(n)
        }
        focus.clearFocus()
    }

    Column(Modifier.fillMaxWidth().background(BAR_BG).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Slider(
            value = drag ?: (pager.currentPage + 1).toFloat(),
            onValueChange = { drag = it },
            onValueChangeFinished = {
                val t = (drag ?: (pager.currentPage + 1).toFloat()).roundToInt()
                drag = null
                goTo(t)
            },
            valueRange = 1f..total.toFloat(),
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = BoardColors.Indigo,
                activeTrackColor = BoardColors.Indigo,
                inactiveTrackColor = Color(0xFF5C3D28),
            ),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill("⏮") { goTo(1) }
            Pill("◀") { goTo(pager.currentPage) }
            Text("Page", color = BoardColors.Muted, fontSize = 11.sp)
            BasicTextField(
                value = field,
                onValueChange = { s -> field = s.filter { it.isDigit() }.take(6) },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { jump() }),
                modifier = Modifier
                    .width(62.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0xFF3B271B))
                    .border(BorderStroke(1.dp, BoardColors.Indigo), RoundedCornerShape(9.dp))
                    .padding(vertical = 7.dp),
            )
            Text("of $total", color = BoardColors.Muted, fontSize = 11.sp)
            Pill("Go") { jump() }
            Pill("▶") { goTo(pager.currentPage + 2) }
            Pill("⏭") { goTo(total) }
        }
    }
}

private class HiRes(val img: ImageBitmap, val s: Float, val ox: Float, val oy: Float, val left: Int, val top: Int, val w: Int, val h: Int)

private class Geo(val vw: Float, val vh: Float, val fx: Float, val fy: Float, val fw: Float, val fh: Float)

/**
 * One page: pinch to zoom (up to 8x), drag to move when zoomed, double-tap to zoom in and out. When the zoom settles the
 * part you are looking at is drawn again at full screen sharpness, so text stays crisp. In cut mode a drag draws a box
 * and that part of the page is drawn at high quality and sent to the board.
 */
@Composable
private fun ZoomPage(
    src: PageSource,
    index: Int,
    active: Boolean,
    cutMode: Boolean,
    onZoomed: (Boolean) -> Unit,
    onCut: (Bitmap) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var vw by remember { mutableIntStateOf(0) }
    var vh by remember { mutableIntStateOf(0) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }
    var hi by remember { mutableStateOf<HiRes?>(null) }
    var cutA by remember { mutableStateOf<Offset?>(null) }
    var cutB by remember { mutableStateOf<Offset?>(null) }
    val cutNow by rememberUpdatedState(cutMode)
    val onCutNow by rememberUpdatedState(onCut)

    LaunchedEffect(vw) {
        if (vw > 0 && bmp == null) {
            bmp = withContext(Dispatchers.Default) { src.renderFull(index, (vw * 1.5f).toInt()) }
        }
    }

    val b = bmp
    val ar = if (b != null && b.height > 0) b.width.toFloat() / b.height else 1f
    val fw = if (vh > 0 && vw / ar <= vh) vw.toFloat() else vh * ar
    val fh = if (ar > 0f) fw / ar else 0f
    val geo by rememberUpdatedState(Geo(vw.toFloat(), vh.toFloat(), (vw - fw) / 2f, (vh - fh) / 2f, fw, fh))

    fun clampX(o: Float, s: Float): Float {
        val g = geo
        val sw = g.fw * s
        return if (sw <= g.vw) (g.vw - sw) / 2f - g.fx * s else o.coerceIn(g.vw - (g.fx + g.fw) * s, -g.fx * s)
    }

    fun clampY(o: Float, s: Float): Float {
        val g = geo
        val sh = g.fh * s
        return if (sh <= g.vh) (g.vh - sh) / 2f - g.fy * s else o.coerceIn(g.vh - (g.fy + g.fh) * s, -g.fy * s)
    }

    fun transform(z: Float, pan: Offset, c: Offset) {
        val ns = (scale * z).coerceIn(1f, 8f)
        val nox = c.x + pan.x - (c.x - ox) / scale * ns
        val noy = c.y + pan.y - (c.y - oy) / scale * ns
        scale = ns
        ox = clampX(nox, ns)
        oy = clampY(noy, ns)
    }

    fun finishCut(a: Offset, e: Offset) {
        val l = minOf(a.x, e.x)
        val r = maxOf(a.x, e.x)
        val t = minOf(a.y, e.y)
        val bt = maxOf(a.y, e.y)
        if (r - l < 24f || bt - t < 24f) return
        val g = geo
        if (g.fw < 1f || g.fh < 1f) return
        val s = scale
        val x = ox
        val y = oy
        val x0 = (((l - x) / s - g.fx) / g.fw).coerceIn(0f, 1f)
        val x1 = (((r - x) / s - g.fx) / g.fw).coerceIn(0f, 1f)
        val y0 = (((t - y) / s - g.fy) / g.fh).coerceIn(0f, 1f)
        val y1 = (((bt - y) / s - g.fy) / g.fh).coerceIn(0f, 1f)
        if (x1 - x0 < 0.01f || y1 - y0 < 0.01f) return
        scope.launch {
            val out = withContext(Dispatchers.Default) { src.renderRegion(index, x0, y0, x1, y1, 2400) }
            if (out != null) onCutNow(out)
        }
    }

    LaunchedEffect(scale, active) { if (active) onZoomed(scale > 1.02f) }

    // sharp re-draw of what is on screen once the zoom and position stop changing
    LaunchedEffect(scale, ox, oy, bmp, vw, vh) {
        if (scale < 1.25f || bmp == null || vw == 0) {
            hi = null
            return@LaunchedEffect
        }
        delay(260)
        val g = geo
        val s = scale
        val x = ox
        val y = oy
        if (g.fw < 1f || g.fh < 1f) return@LaunchedEffect
        val cx0 = maxOf(g.fx, -x / s)
        val cx1 = minOf(g.fx + g.fw, (g.vw - x) / s)
        val cy0 = maxOf(g.fy, -y / s)
        val cy1 = minOf(g.fy + g.fh, (g.vh - y) / s)
        if (cx1 - cx0 < 2f || cy1 - cy0 < 2f) return@LaunchedEffect
        val sw = ((cx1 - cx0) * s).roundToInt().coerceAtLeast(1)
        val sh = ((cy1 - cy0) * s).roundToInt().coerceAtLeast(1)
        val out = withContext(Dispatchers.Default) {
            src.renderRegion(index, (cx0 - g.fx) / g.fw, (cy0 - g.fy) / g.fh, (cx1 - g.fx) / g.fw, (cy1 - g.fy) / g.fh, sw)
        }
        if (out != null) {
            hi = HiRes(out.asImageBitmap(), s, x, y, (cx0 * s + x).roundToInt(), (cy0 * s + y).roundToInt(), sw, sh)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged {
                vw = it.width
                vh = it.height
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { pos ->
                    if (!cutNow) {
                        if (scale > 1.05f) {
                            scale = 1f
                            ox = clampX(0f, 1f)
                            oy = clampY(0f, 1f)
                        } else {
                            transform(2.5f, Offset.Zero, pos)
                        }
                    }
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startPos = down.position
                    var multi = false
                    do {
                        val ev = awaitPointerEvent()
                        val pressedN = ev.changes.count { it.pressed }
                        if (pressedN >= 2) {
                            if (!multi) {
                                multi = true
                                cutA = null
                                cutB = null
                            }
                            val z = ev.calculateZoom()
                            val pan = ev.calculatePan()
                            val c = ev.calculateCentroid()
                            if (z != 1f || pan != Offset.Zero) {
                                transform(z, pan, c)
                                hi = null
                            }
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (!multi && cutNow) {
                            val ch = ev.changes.firstOrNull()
                            if (ch != null && ch.pressed) {
                                cutA = startPos
                                cutB = ch.position
                                ch.consume()
                            }
                        } else if (!multi && scale > 1.02f) {
                            val pan = ev.calculatePan()
                            if (pan != Offset.Zero) {
                                transform(1f, pan, startPos)
                                hi = null
                            }
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                    if (scale < 1.03f && scale != 1f) {
                        scale = 1f
                        ox = clampX(0f, 1f)
                        oy = clampY(0f, 1f)
                    }
                    val a = cutA
                    val e = cutB
                    cutA = null
                    cutB = null
                    if (!multi && cutNow && a != null && e != null) finishCut(a, e)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val img = remember(b) { b?.asImageBitmap() }
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = scale
                        scaleY = scale
                        translationX = ox
                        translationY = oy
                    },
            )
        } else {
            CircularProgressIndicator(color = BoardColors.Indigo, modifier = Modifier.size(30.dp))
        }
        val h = hi
        if (h != null && h.s == scale && h.ox == ox && h.oy == oy) {
            Canvas(Modifier.fillMaxSize()) {
                drawImage(
                    image = h.img,
                    dstOffset = IntOffset(h.left, h.top),
                    dstSize = IntSize(h.w, h.h),
                    filterQuality = FilterQuality.High,
                )
            }
        }
        val ca = cutA
        val cb = cutB
        if (ca != null && cb != null) {
            Canvas(Modifier.fillMaxSize()) { drawLiveBox(ca, cb) }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
//  Text, Word and Excel viewers
// ---------------------------------------------------------------------------------------------------------------------

/** Two fingers change a number (text size) without stopping the one-finger scroll. */
private fun Modifier.pinchZoom(onZoom: (Float) -> Unit): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val ev = awaitPointerEvent()
            if (ev.changes.count { it.pressed } >= 2) {
                val z = ev.calculateZoom()
                if (z != 1f) onZoom(z)
                ev.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (ev.changes.any { it.pressed })
    }
}

@Composable
private fun TextViewer(doc: OpenDoc, lines: List<DocLine>, onClose: () -> Unit) {
    var fs by remember { mutableFloatStateOf(14f) }
    Column(Modifier.fillMaxSize()) {
        ViewerTop(doc.name, "${lines.size} lines · pinch to change the text size", onClose) {
            Pill("A−") { fs = (fs - 2f).coerceAtLeast(8f) }
            Spacer(Modifier.width(5.dp))
            Pill("A＋") { fs = (fs + 2f).coerceAtMost(44f) }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(PAPER)
                .pinchZoom { z -> fs = (fs * z).coerceIn(8f, 44f) },
        ) {
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
                    items(lines) { ln ->
                        val k = when (ln.heading) {
                            1 -> 1.6f
                            2 -> 1.35f
                            3 -> 1.18f
                            else -> 1f
                        }
                        Text(
                            if (ln.text.isEmpty()) " " else ln.text,
                            color = INK,
                            fontSize = (fs * k).sp,
                            fontWeight = if (ln.heading > 0) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(top = if (ln.heading > 0) 8.dp else 0.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetViewer(doc: OpenDoc, sheets: List<Sheet>, onClose: () -> Unit) {
    var idx by remember { mutableIntStateOf(0) }
    var fs by remember { mutableFloatStateOf(12f) }
    val sh = sheets[idx.coerceIn(0, sheets.size - 1)]
    val cols = (sh.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    val cellW = (fs * 8f).dp
    val numW = 38.dp
    val grid = Color(0xFFD9C7AE)
    Column(Modifier.fillMaxSize()) {
        ViewerTop(doc.name, "${sh.rows.size} rows · ${cols} columns", onClose) {
            Pill("A−") { fs = (fs - 1.5f).coerceAtLeast(7f) }
            Spacer(Modifier.width(5.dp))
            Pill("A＋") { fs = (fs + 1.5f).coerceAtMost(30f) }
        }
        if (sheets.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                sheets.forEachIndexed { i, s -> Pill(s.name, i == idx) { idx = i } }
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(PAPER)
                .pinchZoom { z -> fs = (fs * z).coerceIn(7f, 30f) },
        ) {
            Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                LazyColumn(Modifier.width(numW + cellW * cols).fillMaxHeight()) {
                    itemsIndexed(sh.rows) { r, row ->
                        Row {
                            Box(Modifier.width(numW).background(Color(0xFFEADFCB)).border(0.5.dp, grid).padding(4.dp)) {
                                Text("${r + 1}", color = Color(0xFF7A6650), fontSize = fs.sp)
                            }
                            for (c in 0 until cols) {
                                Box(Modifier.width(cellW).border(0.5.dp, grid).padding(4.dp)) {
                                    Text(row.getOrElse(c) { "" }, color = INK, fontSize = fs.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


/** Copies a picked file to the app's own storage so a file on the board can always be opened again. */
private fun keepDoc(ctx: Context, d: OpenDoc): File {
    return try {
        val dir = File(ctx.filesDir, "boarddocs")
        dir.mkdirs()
        val f = File(dir, d.file.name)
        if (!f.exists()) d.file.copyTo(f, overwrite = true)
        f
    } catch (e: Exception) {
        d.file
    }
}

/** Keeps the files that sit on the board open, so a page can be turned or cut again at any time. */
object DocHub {
    private val sources = HashMap<String, PageSource>()

    fun looksLikePdf(f: File): Boolean = try {
        f.inputStream().use { ins ->
            val h = ByteArray(4)
            ins.read(h) == 4 && h[0] == '%'.code.toByte() && h[1] == 'P'.code.toByte() && h[2] == 'D'.code.toByte() && h[3] == 'F'.code.toByte()
        }
    } catch (e: Exception) {
        false
    }

    @Synchronized
    fun source(path: String): PageSource? {
        sources[path]?.let { return it }
        val f = File(path)
        if (!f.exists()) return null
        return try {
            val s: PageSource = if (looksLikePdf(f)) PdfSource(f) else when (kindOf(f.name, null, f)) {
                DocKind.TEXT -> TextPageSource(readTextLines(f))
                DocKind.DOCX -> TextPageSource(readDocx(f))
                DocKind.XLSX -> TextPageSource(
                    readXlsx(f).flatMap { sh ->
                        listOf(DocLine(sh.name, 1)) + sh.rows.map { r -> DocLine(r.joinToString("  |  ")) }
                    },
                )
                else -> ImageSource(f)
            }
            if (s.count == 0) {
                s.close()
                null
            } else {
                sources[path] = s
                s
            }
        } catch (e: Throwable) {
            null
        }
    }
}


/** Which kind of file this is (for opening it full screen again). */
fun docKindOf(f: File): DocKind = if (DocHub.looksLikePdf(f)) DocKind.PDF else kindOf(f.name, null, f)

/** Text, Word and Excel files drawn as paper pages, so they can rest on the board like a PDF. */
class TextPageSource(lines: List<DocLine>) : PageSource {
    private val pageW = 1240
    private val pageH = 1754
    private val margin = 90
    private class Placed(val layout: android.text.StaticLayout, val y: Float)
    private val pages = ArrayList<List<Placed>>()

    init {
        val body = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2B1B10.toInt()
            textSize = 30f
        }
        val head = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2B1B10.toInt()
            textSize = 44f
            isFakeBoldText = true
        }
        var cur = ArrayList<Placed>()
        var y = margin.toFloat()
        var chars = 0
        for (ln in lines) {
            if (chars > 600_000 || pages.size >= 400) break
            val text = if (ln.text.isEmpty()) " " else ln.text
            chars += text.length
            val paint = if (ln.heading > 0) head else body
            val lay = android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, pageW - 2 * margin)
                .setLineSpacing(0f, 1.15f).build()
            val gap = if (ln.heading > 0) 22f else 12f
            if (y + lay.height > pageH - margin && cur.isNotEmpty()) {
                pages.add(cur)
                cur = ArrayList()
                y = margin.toFloat()
            }
            cur.add(Placed(lay, y))
            y += lay.height + gap
        }
        if (cur.isNotEmpty()) pages.add(cur)
    }

    override val count: Int get() = pages.size

    private fun draw(i: Int, bmp: Bitmap, sc: Float, tx: Float, ty: Float) {
        val c = android.graphics.Canvas(bmp)
        c.drawColor(0xFFFFFDF6.toInt())
        c.scale(sc, sc)
        c.translate(tx, ty)
        for (pl in pages[i]) {
            c.save()
            c.translate(margin.toFloat(), pl.y)
            pl.layout.draw(c)
            c.restore()
        }
    }

    override fun renderFull(i: Int, widthPx: Int): Bitmap? {
        if (i < 0 || i >= count) return null
        return try {
            val sc = widthPx.coerceIn(200, 3000).toFloat() / pageW
            val bmp = Bitmap.createBitmap((pageW * sc).roundToInt(), (pageH * sc).roundToInt(), Bitmap.Config.ARGB_8888)
            draw(i, bmp, sc, 0f, 0f)
            bmp
        } catch (e: Throwable) {
            null
        }
    }

    override fun renderRegion(i: Int, x0: Float, y0: Float, x1: Float, y1: Float, outW: Int): Bitmap? {
        if (i < 0 || i >= count) return null
        return try {
            val rw = (x1 - x0) * pageW
            val rh = (y1 - y0) * pageH
            if (rw < 1f || rh < 1f) return null
            val sc = outW.coerceIn(16, 3000).toFloat() / rw
            val bmp = Bitmap.createBitmap(maxOf(1, (rw * sc).roundToInt()), maxOf(1, (rh * sc).roundToInt()), Bitmap.Config.ARGB_8888)
            draw(i, bmp, sc, -x0 * pageW, -y0 * pageH)
            bmp
        } catch (e: Throwable) {
            null
        }
    }

    override fun close() {}
}
