package app.chessvision

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

data class RecognitionResult(val board: CharArray, val uncertainSquares: List<Int>, val occupiedSquares: Int)

/**
 * Finds the board, learns what the pieces look like from the STARTING position
 * (so it works with any piece set / theme), then reads positions as 64 chars
 * indexed rank*8+file ('A'-'Z' white, 'a'-'z' black, ' ' empty). a1 = index 0.
 */
class BoardVision {
    private var tpl: Map<Char, FloatArray>? = null
    private var thr = 0f
    var flipped = false; private set          // true = black pieces at the bottom of the screen
    val ready get() = tpl != null
    var learningDiagnostic: String = "Ожидаю начальную позицию"
        private set

    private class Sq(val edge: FloatArray, val energy: Float, val center: Float)
    private companion object {
        const val N = 32; const val EN = 0.03f   // TUNE: "square is not empty" threshold
        val FX = floatArrayOf(.5f, .3f, .7f, .3f, .7f); val FY = floatArrayOf(.5f, .3f, .3f, .7f, .7f)
    }

    private fun lum(p: Int) = 0.299f * (p shr 16 and 255) + 0.587f * (p shr 8 and 255) + 0.114f * (p and 255)
    private fun dist(a: Int, b: Int) = abs((a shr 16 and 255) - (b shr 16 and 255)) + abs((a shr 8 and 255) - (b shr 8 and 255)) + abs((a and 255) - (b and 255))

    // ---- 1. Find and validate a board robustly, tolerating highlighted squares.
    // Sample four points near the corners of every square (not the centre, where a piece sits).
    private fun checker(b: Bitmap, x0: Int, y0: Int, s: Int): Float {
        if (s <= 0 || x0 < 0 || y0 < 0 || x0 + s > b.width || y0 + s > b.height) return 0f
        val q = s / 8f
        val samples = Array(64) { ArrayList<Int>(4) }
        val offsets = floatArrayOf(.16f, .84f)
        for (r in 0..7) for (c in 0..7) {
            val list = samples[r * 8 + c]
            for (oy in offsets) for (ox in offsets) {
                val x = (x0 + (c + ox) * q).toInt().coerceIn(0, b.width - 1)
                val y = (y0 + (r + oy) * q).toInt().coerceIn(0, b.height - 1)
                list += b.getPixel(x, y)
            }
        }
        fun medianColor(colors: List<Int>): Int {
            val rs = colors.map { it shr 16 and 255 }.sorted()
            val gs = colors.map { it shr 8 and 255 }.sorted()
            val bs = colors.map { it and 255 }.sorted()
            val m = colors.size / 2
            return (rs[m] shl 16) or (gs[m] shl 8) or bs[m]
        }
        val groups = Array(2) { ArrayList<Int>() }
        for (r in 0..7) for (c in 0..7) groups[(r + c) and 1].addAll(samples[r * 8 + c])
        val med = intArrayOf(medianColor(groups[0]), medianColor(groups[1]))
        if (abs(lum(med[0]) - lum(med[1])) < 18f) return 0f
        var goodSquares = 0
        for (r in 0..7) for (c in 0..7) {
            val expected = med[(r + c) and 1]
            val matches = samples[r * 8 + c].count { dist(it, expected) < 68 }
            if (matches >= 2) goodSquares++
        }
        return goodSquares / 64f
    }

    fun find(b: Bitmap): Rect? {
        val w = b.width
        var best: Rect? = null
        var top = 0.74f
        val sizes = (listOf(w, w - w / 50, w - w / 25, w - w / 12) +
            listOf(w - w / 16, w - w / 20)).filter { it > w / 2 }.distinct()
        for (s in sizes) {
            val x0 = (w - s) / 2
            var y = 0
            while (y + s <= b.height) {
                val score = checker(b, x0, y, s)
                if (score > top) { top = score; best = Rect(x0, y, x0 + s, y + s) }
                y += 3
            }
        }
        return best
    }

    // A single highlighted last-move square must not make us lose the board.
    fun still(b: Bitmap, r: Rect) =
        r.width() > 0 && r.height() > 0 && r.right <= b.width && r.bottom <= b.height &&
            checker(b, r.left, r.top, min(r.width(), r.height())) >= 0.52f

    /** cheap fingerprint of the board, used to notice that something moved */
    fun signature(b: Bitmap, r: Rect): ByteArray {
        val q = r.width() / 8f
        return ByteArray(64) { i ->
            var t = 0f
            for (k in 0..4) t += lum(b.getPixel((r.left + (i % 8 + FX[k]) * q).toInt(), (r.top + (i / 8 + FY[k]) * q).toInt()))
            (t / 5 / 16).toInt().toByte()
        }
    }

    // ---- 2. per-square features: edge map + centre contrast against local square background ----
    private fun features(b: Bitmap, r: Rect): List<Sq> {
        val s = r.width(); val px = IntArray(s * s)
        Bitmap.createBitmap(b, r.left, r.top, s, s).getPixels(px, 0, s, 0, 0, s, s)
        val q = s / 8f; val step = q * .76f / N; val m = N - 2
        return (0..63).map { i ->
            val x0 = (i % 8) * q + q * .12f; val y0 = (i / 8) * q + q * .12f
            val g = Array(N) { y -> FloatArray(N) { x ->
                var t = 0f
                for (dy in 0..1) for (dx in 0..1) {
                    val yy = min(s - 1, (y0 + (y + .25f + dy * .5f) * step).toInt()); val xx = min(s - 1, (x0 + (x + .25f + dx * .5f) * step).toInt())
                    t += lum(px[yy * s + xx])
                }
                t / 4 } }
            val e = FloatArray(m * m); var en = 0f; var cn = 0
            for (y in 1 until N - 1) for (x in 1 until N - 1) {
                val gx = g[y][x + 1] - g[y][x - 1]; val gy = g[y + 1][x] - g[y - 1][x]
                val mag = sqrt(gx * gx + gy * gy); e[(y - 1) * m + x - 1] = mag
                if (y in 8..24 && x in 8..24) { en += mag; cn++ }
            }
            var c = 0f; for (y in 11..20) for (x in 11..20) c += g[y][x]
            // Compare the piece's centre with the local square background. This
            // compensates for alternating light/dark board squares better than
            // comparing every piece against one global brightness threshold.
            val background = (g[2][2] + g[2][N - 3] + g[N - 3][2] + g[N - 3][N - 3]) / 4f
            val centreMean = c / 100f
            Sq(unit(e), en / cn / 255f, (centreMean - background) / 100f)
        }
    }

    private fun unit(v: FloatArray): FloatArray {
        val mean = v.average().toFloat(); var n = 0f
        for (i in v.indices) { v[i] -= mean; n += v[i] * v[i] }
        n = sqrt(n) + 1e-6f; for (i in v.indices) v[i] /= n; return v
    }
    private fun dot(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s }

    // screen square index (row*8+col) -> (file, rank)
    private fun fr(i: Int) = if (!flipped) Pair(i % 8, 7 - i / 8) else Pair(7 - i % 8, i / 8)
    private fun startPiece(f: Int, r: Int) = when (r) { 0 -> "RNBQKBNR"[f]; 1 -> 'P'; 6 -> 'p'; else -> "rnbqkbnr"[f] }

    // ---- 3. learn from the starting position; also detects which colour is at the bottom ----
    fun learn(b: Bitmap, r: Rect): Boolean {
        val f = features(b, r)
        val occ = f.map { it.energy > EN }
        val total = occ.count { it }
        val upper = (0..15).count { occ[it] }
        val middle = (16..47).count { occ[it] }
        val lower = (48..63).count { occ[it] }

        if (upper != 16 || lower != 16 || middle != 0) {
            learningDiagnostic = "Фигуры: $total/32\nВерхние 2 ряда: $upper/16\nСередина: $middle/32\nНижние 2 ряда: $lower/16\nПорог энергии: $EN"
            return false
        }

        val up = (0..15).map { f[it].center }.average().toFloat()
        val dn = (48..63).map { f[it].center }.average().toFloat()
        val contrast = abs(up - dn)
        // center is already normalized by 100 in features(); comparing it to 40
        // made the check impossible. Use a threshold in the same normalized scale.
        if (contrast < 0.04f) {
            learningDiagnostic = "Найдено 32 фигуры, но цвет сторон не различён\nКонтраст: ${"%.3f".format(java.util.Locale.US, contrast)}\nНужно: не менее 0.040\nПроверьте тему и качество изображения"
            return false
        }

        flipped = dn < up
        val sum = HashMap<Char, FloatArray>()
        for (i in (0..15) + (48..63)) {
            val (file, rank) = fr(i)
            val c = startPiece(file, rank)
            val a = sum.getOrPut(c.lowercaseChar()) { FloatArray(f[i].edge.size) }
            for (k in a.indices) a[k] += f[i].edge[k]
        }
        // Average signed centre contrast of the 16 white and 16 black pieces.
        thr = f.filterIndexed { index, _ -> index in 0..15 || index in 48..63 }
            .map { it.center }.average().toFloat()
        tpl = sum.mapValues { unit(it.value) }
        learningDiagnostic = "Обучение завершено: 32/32 фигур\nКонтраст сторон: ${"%.3f".format(java.util.Locale.US, contrast)}\nШаблонов фигур: ${tpl?.size ?: 0}/6"
        return true
    }

    // ---- 4. read a position ----
    /** Reads all squares and reports low-confidence piece classifications separately.
     * The caller must not send a position to the engine while uncertainSquares is non-empty.
     */
    fun readDetailed(b: Bitmap, r: Rect): RecognitionResult {
        val templates = tpl ?: return RecognitionResult(CharArray(64) { ' ' }, (0..63).toList(), 0)
        val features = features(b, r)
        val board = CharArray(64) { ' ' }
        val uncertain = ArrayList<Int>()
        var occupied = 0
        for (i in 0..63) if (features[i].energy > EN) {
            occupied++
            val ranked = templates.entries
                .map { it.key to dot(it.value, features[i].edge) }
                .sortedByDescending { it.second }
            val best = ranked[0]
            val second = ranked.getOrNull(1)?.second ?: -1f
            // Reject weak matches instead of silently inventing a piece type.
            if (best.second < 0.10f || best.second - second < 0.018f) uncertain += i
            val (file, rank) = fr(i)
            board[rank * 8 + file] = if (features[i].center > thr) best.first.uppercaseChar() else best.first
        }
        return RecognitionResult(board, uncertain, occupied)
    }

    fun read(b: Bitmap, r: Rect): CharArray = readDetailed(b, r).board

    fun fen(g: CharArray, turn: Char): String {
        val sb = StringBuilder()
        for (rank in 7 downTo 0) {
            var e = 0
            for (file in 0..7) { val c = g[rank * 8 + file]; if (c == ' ') e++ else { if (e > 0) { sb.append(e); e = 0 }; sb.append(c) } }
            if (e > 0) sb.append(e); if (rank > 0) sb.append('/')
        }
        var cs = ""
        if (g[4] == 'K') { if (g[7] == 'R') cs += "K"; if (g[0] == 'R') cs += "Q" }
        if (g[60] == 'k') { if (g[63] == 'r') cs += "k"; if (g[56] == 'r') cs += "q" }
        return "$sb $turn ${cs.ifEmpty { "-" }} - 0 1"
    }
}
