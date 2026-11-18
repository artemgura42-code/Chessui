package app.chessvision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.hypot

class CaptureService : Service() {
    companion object { @Volatile var running = false }

    private var proj: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: ArrowView? = null
    private var eng: Engine? = null
    private val ui = Handler(Looper.getMainLooper())
    private val vision = BoardVision()
    private val moveTracker = MoveTracker()
    private var trackerInitialized = false
    private var last: Bitmap? = null
    private var board: Rect? = null
    private var boardMisses = 0
    private var prevSig = ByteArray(0); private var doneSig = ByteArray(0); private var stable = 0
    private var w = 0; private var h = 0; private var warned = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        i ?: return START_NOT_STICKY
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("cv", "Chess Vision", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "cv").setContentTitle("Chess Vision").setContentText("Захват экрана включён").setSmallIcon(android.R.drawable.ic_menu_view).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics(); wm.defaultDisplay.getRealMetrics(dm); w = dm.widthPixels; h = dm.heightPixels

        val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(i.getIntExtra("code", 0), i.getParcelableExtra<Intent>("data")!!)
        p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, ui)
        proj = p
        val rd = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2); reader = rd
        vd = p.createVirtualDisplay("cv", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rd.surface, null, null)

        val ov = ArrowView(this); overlay = ov
        val lp = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        wm.addView(ov, lp)

        running = true
        Thread { while (running) { try { step() } catch (e: Exception) { Log.e("CV", "step", e) }; Thread.sleep(250) } }.start()
        return START_NOT_STICKY
    }

    /** latest screen frame (waits up to [wait] ms for a new one; frames only arrive when the screen changes) */
    private fun frame(wait: Long): Bitmap? {
        val t = System.currentTimeMillis()
        do {
            val img = reader?.acquireLatestImage()
            if (img != null) {
                val pl = img.planes[0]
                val raw = Bitmap.createBitmap(pl.rowStride / pl.pixelStride, h, Bitmap.Config.ARGB_8888)
                raw.copyPixelsFromBuffer(pl.buffer); img.close()
                val bmp = Bitmap.createBitmap(raw, 0, 0, w, h); raw.recycle()
                val old = last; last = bmp; old?.recycle(); return bmp
            }
            Thread.sleep(20)
        } while (System.currentTimeMillis() - t < wait)
        return null
    }

    private fun step() {
        val bmp = frame(0) ?: last ?: return
        var r = board ?: vision.find(bmp)?.also { board = it } ?: run {
            status("Захват активен\nИщу шахматную доску...")
            return
        }
        if (!vision.still(bmp, r)) {
            boardMisses++
            val replacement = vision.find(bmp)
            if (replacement != null) {
                board = replacement
                r = replacement
                boardMisses = 0
            } else {
                stable = 0
                show(null, r)
                // Keep learned templates and the last board geometry through brief overlays/animations.
                if (boardMisses >= 12) board = null
                status("Захват активен\nПроверяю положение доски...\nОбучение сохранено: ${vision.ready}\nПовторная проверка: $boardMisses/12")
                return
            }
        } else {
            boardMisses = 0
        }

        if (!vision.ready) {
            if (vision.learn(bmp, r)) {
                val initial = vision.readDetailed(bmp, r)
                if (initial.uncertainSquares.isEmpty()) {
                    moveTracker.reset(initial.board, 'w')
                    trackerInitialized = true
                    status("Доска обнаружена\n${vision.learningDiagnostic}\nСторона снизу: ${if (vision.flipped) "чёрные" else "белые"}\nОтслеживание включено")
                } else {
                    status("${vision.learningDiagnostic}\nНеуверенно: ${initial.uncertainSquares.size} клеток\nКлетки: ${initial.uncertainSquares.take(8).joinToString { (it + 1).toString() }}")
                }
            } else {
                status("Доска обнаружена\n${vision.learningDiagnostic}")
            }
            return
        }
        if (!trackerInitialized) {
            val initial = vision.readDetailed(bmp, r)
            if (initial.uncertainSquares.isEmpty()) {
                moveTracker.reset(initial.board, 'w')
                trackerInitialized = true
                status("Начальная позиция распознана\n32 фигуры / 64 клетки\nСторона снизу: ${if (vision.flipped) "чёрные" else "белые"}\nОтслеживание ходов включено")
            } else {
                status("Шаблоны фигур готовы\nФигур на доске: ${initial.occupiedSquares}/32\nСомнительных клеток: ${initial.uncertainSquares.size}\nКлетки: ${initial.uncertainSquares.take(8).joinToString { (it + 1).toString() }}")
            }
            return
        }
        val sig = vision.signature(bmp, r)
        if (sig.contentEquals(prevSig)) stable++ else { prevSig = sig; stable = 0 }
        if (stable < 2 || sig.contentEquals(doneSig)) return      // position still moving, or already handled

        show(null, r); Thread.sleep(180)                           // hide our arrow so it doesn't pollute recognition
        val read = vision.readDetailed(frame(400) ?: last ?: return, r)
        if (read.uncertainSquares.isNotEmpty()) {
            status("Доска обнаружена\nРаспознано занятых клеток: ${read.occupiedSquares}\nСомнительных клеток: ${read.uncertainSquares.size}\nХод не засчитан — жду чёткого кадра")
            doneSig = sig
            stable = 0
            return
        }
        val g = read.board
        val update = moveTracker.update(g)
        if (update == null) {
            status("Доска обнаружена\nОбработано 64 клетки\n${moveTracker.lastError ?: "Изменение позиции не подтверждено"}\nХод и анализ не обновлены")
            doneSig = sig
            stable = 0
            return
        }
        val fen = moveTracker.fen(g)
        status("Доска: OK\nХод распознан: ${update.uci}\nСледующий ход: ${moveTracker.turn}\nЗапускаю Stockfish 19...")
        var mv: String? = null
        if (moveTracker.turn == (if (vision.flipped) 'b' else 'w')) {
            mv = try {
                val engine = (eng ?: Engine(this).also { eng = it })
                engine.best(fen).also { best ->
                    status("Доска: OK\nХод: ${update.uci}\nStockfish 19: ${best ?: "лучший ход не найден"}\n${if (best != null) "Стрелка показана на доске" else "Проверьте распознанную позицию"}")
                }
            } catch (e: Exception) {
                status("Доска: OK\nХод: ${update.uci}\nStockfish 19: ОШИБКА\n${e.message ?: "не удалось запустить движок"}")
                if (!warned) { warned = true; ui.post { Toast.makeText(this, "Ошибка запуска Stockfish: ${e.message}", Toast.LENGTH_LONG).show() } }
                null
            }
        } else {
            status("Доска: OK\nХод распознан: ${update.uci}\nСледующий ход: ${moveTracker.turn}\nОжидаю ход соперника")
        }
        show(mv, r); Thread.sleep(180)
        doneSig = vision.signature(frame(400) ?: last ?: return, r); prevSig = doneSig; stable = 0
    }

    private fun center(sq: String, r: Rect): Pair<Float, Float> {
        val file = sq[0] - 'a'; val rank = sq[1] - '1'; val q = r.width() / 8f
        val col = if (vision.flipped) 7 - file else file; val row = if (vision.flipped) rank else 7 - rank
        return Pair(r.left + (col + .5f) * q, r.top + (row + .5f) * q)
    }

    private fun status(message: String) {
        ui.post { overlay?.setStatus(message) }
    }

    private fun show(m: String?, r: Rect) {
        val a = if (m != null) { val (x1, y1) = center(m.substring(0, 2), r); val (x2, y2) = center(m.substring(2, 4), r); floatArrayOf(x1, y1, x2, y2) } else null
        val q = r.width() / 8f
        ui.post { overlay?.set(a, q) }
    }

    override fun onDestroy() {
        running = false
        overlay?.let { try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {} }
        vd?.release(); reader?.close(); proj?.stop(); eng?.close()
        super.onDestroy()
    }
}

class ArrowView(c: Context) : View(c) {
    private var a: FloatArray? = null; private var q = 0f
    @Volatile private var diagnostic = "Chess Vision\nЗапуск захвата..."
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDD101820.toInt() }
    private val diagnosticText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 13f * resources.displayMetrics.density
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC15781B.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC15781B.toInt() }

    fun set(a: FloatArray?, q: Float) { this.a = a; this.q = q; invalidate() }
    fun setStatus(value: String) { diagnostic = value; postInvalidate() }

    override fun onDraw(c: Canvas) {
        val density = resources.displayMetrics.density
        val lines = diagnostic.split('\n').take(7)
        val left = 12f * density
        val top = 42f * density
        val lineHeight = 18f * density
        val width = minOf(width.toFloat() - 24f * density, 350f * density)
        val height = (lines.size * lineHeight + 18f * density)
        c.drawRoundRect(left, top, left + width, top + height, 10f * density, 10f * density, panel)
        lines.forEachIndexed { index, text ->
            c.drawText(text.take(54), left + 10f * density, top + (index + 1) * lineHeight, diagnosticText)
        }
        val a = a ?: return
        val dx = a[2] - a[0]; val dy = a[3] - a[1]; val l = hypot(dx, dy); if (l < 1f) return
        val ux = dx / l; val uy = dy / l; val hd = q * .45f
        val ex = a[2] - ux * hd; val ey = a[3] - uy * hd
        line.strokeWidth = q * .17f
        c.drawLine(a[0], a[1], ex, ey, line)
        val head = Path().apply {
            moveTo(a[2], a[3]); lineTo(ex - uy * hd * .6f, ey + ux * hd * .6f); lineTo(ex + uy * hd * .6f, ey - ux * hd * .6f); close()
        }
        c.drawPath(head, fill)
    }
}
