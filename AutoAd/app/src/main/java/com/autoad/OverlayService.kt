package com.autoad

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

class OverlayService : Service() {
    companion object { @Volatile var running = false }

    private val Y = Color.parseColor("#FFD60A")
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var panel: LinearLayout? = null
    private var frame: FrameLayout? = null
    private var playBtn: TextView? = null
    private var statusTv: TextView? = null

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var sw = 0
    private var sh = 0
    private var lastBmp: Bitmap? = null

    @Volatile private var active = false
    private var adT: Matcher.Gray? = null
    private var skipT: Matcher.Gray? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(t: String) { ui.post { Toast.makeText(this, t, Toast.LENGTH_LONG).show() } }

    override fun onBind(i: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running || intent == null) return START_NOT_STICKY
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val ch = NotificationChannel("autoad", "Auto Ad", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        val n = Notification.Builder(this, "autoad")
            .setContentTitle("Auto Ad работает")
            .setSmallIcon(android.R.drawable.ic_media_play).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)

        val code = intent.getIntExtra("code", 0)
        val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, ui)

        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        sw = dm.widthPixels; sh = dm.heightPixels
        reader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
        vd = projection!!.createVirtualDisplay(
            "autoad", sw, sh, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null
        )

        loadTemplates()
        buildPanel()
        running = true
        return START_NOT_STICKY
    }

    // ---------- Снимок экрана ----------

    @Synchronized
    @Suppress("DEPRECATION")
    private fun capture(fresh: Boolean = false): Bitmap? {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        if (dm.widthPixels != sw || dm.heightPixels != sh) { // поворот экрана
            sw = dm.widthPixels; sh = dm.heightPixels
            reader?.close()
            reader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
            vd?.resize(sw, sh, dm.densityDpi)
            vd?.surface = reader!!.surface
            lastBmp = null
        }
        if (fresh) lastBmp = null
        var tries = if (lastBmp == null) 15 else 1
        while (tries-- > 0) {
            val img = reader?.acquireLatestImage()
            if (img != null) {
                try {
                    val pl = img.planes[0]
                    val w = pl.rowStride / pl.pixelStride
                    val full = Bitmap.createBitmap(w, img.height, Bitmap.Config.ARGB_8888)
                    full.copyPixelsFromBuffer(pl.buffer)
                    lastBmp = if (w == img.width) full else Bitmap.createBitmap(full, 0, 0, img.width, img.height)
                } finally { img.close() }
                break
            }
            if (lastBmp == null) Thread.sleep(80)
        }
        return lastBmp
    }

    // ---------- Шаблоны ----------

    private fun loadTemplates() {
        fun load(name: String): Matcher.Gray? {
            val f = File(filesDir, "$name.png")
            if (!f.exists()) return null
            return BitmapFactory.decodeFile(f.path)?.let { Matcher.gray(it) }
        }
        adT = load("ad"); skipT = load("skip")
    }

    private fun startCapture() {
        if (frame != null) { removeFrame(); return }
        val kind = when {
            !File(filesDir, "ad.png").exists() -> "ad"
            !File(filesDir, "skip.png").exists() -> "skip"
            else -> { toast("Обе кнопки уже заданы. Нажми －, чтобы сбросить"); return }
        }
        toast(if (kind == "ad") "Наведи рамку на кнопку «Смотреть рекламу» и тапни по рамке"
              else "Наведи рамку на кнопку «Пропустить / ✕» и тапни по рамке")

        val size = dp(Prefs.get(this, "frame"))
        val f = FrameLayout(this)
        f.background = GradientDrawable().apply {
            setColor(Color.parseColor("#33FFD60A")); setStroke(dp(3), Y); cornerRadius = dp(8).toFloat()
        }
        val lp = overlayParams(size, size).apply { x = (sw - size) / 2; y = sh / 2 }
        attachDrag(f, f, lp) { confirmCapture(kind) }
        frame = f
        wm.addView(f, lp)
    }

    private fun removeFrame() {
        frame?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        frame = null
    }

    private fun confirmCapture(kind: String) {
        val f = frame ?: return
        val lp = f.layoutParams as WindowManager.LayoutParams
        val x = lp.x; val y = lp.y; val size = lp.width
        f.visibility = View.INVISIBLE
        Thread {
            Thread.sleep(300)
            val b = capture(fresh = true)
            ui.post { removeFrame() }
            if (b == null) { toast("Не удалось сделать снимок экрана"); return@Thread }
            val cx = x.coerceIn(0, b.width - size)
            val cy = y.coerceIn(0, b.height - size)
            val crop = Bitmap.createBitmap(b, cx, cy, size, size)
            FileOutputStream(File(filesDir, "$kind.png")).use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            loadTemplates()
            toast(if (kind == "ad") "Кнопка рекламы сохранена ✓" else "Кнопка пропуска сохранена ✓")
        }.start()
    }

    private fun resetTemplates() {
        stopLoop()
        File(filesDir, "ad.png").delete(); File(filesDir, "skip.png").delete()
        adT = null; skipT = null
        toast("Кнопки сброшены")
    }

    // ---------- Цикл ----------

    private fun sleepMs(ms: Int) {
        var left = ms
        while (left > 0 && active) { Thread.sleep(minOf(100, left).toLong()); left -= 100 }
    }

    private fun tap(h: Matcher.Hit) {
        val s = ClickService.instance
        if (s == null) { toast("Служба нажатий выключена"); active = false; return }
        s.tap(h.x.toFloat(), h.y.toFloat())
    }

    private fun toggle() {
        if (active) { stopLoop(); return }
        if (adT == null || skipT == null) { toast("Сначала задай обе кнопки через ＋"); return }
        active = true
        playBtn?.text = "⏸"
        Thread { loop() }.start()
    }

    private fun stopLoop() {
        active = false
        ui.post { playBtn?.text = "▶" }
    }

    private fun loop() {
        var cycles = 0
        while (active) {
            val ad = adT; val sk = skipT
            if (ad == null || sk == null) { stopLoop(); break }
            val maxC = Prefs.get(this, "maxCycles")
            if (maxC in 1..cycles) { toast("Готово: $cycles повторов"); stopLoop(); break }
            val thr = Prefs.get(this, "thr") / 100f
            val scan = Prefs.get(this, "scan")

            val hit = capture()?.let { Matcher.find(Matcher.gray(it), ad) }
            if (hit != null && hit.score >= thr) {
                tap(hit)
                sleepMs(Prefs.get(this, "adWait"))
                val end = System.currentTimeMillis() + Prefs.get(this, "maxWatch") * 1000L
                while (active && System.currentTimeMillis() < end) {
                    val h = capture()?.let { Matcher.find(Matcher.gray(it), sk) }
                    if (h != null && h.score >= thr) { tap(h); break }
                    sleepMs(scan)
                }
                cycles++
                ui.post { statusTv?.text = "$cycles" }
                sleepMs(Prefs.get(this, "pause"))
            } else sleepMs(scan)
        }
    }

    // ---------- Панель ----------

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun attachDrag(handle: View, target: View, lp: WindowManager.LayoutParams, onTap: (() -> Unit)?) {
        var ox = 0; var oy = 0; var tx = 0f; var ty = 0f; var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ox = lp.x; oy = lp.y; tx = e.rawX; ty = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx; val dy = e.rawY - ty
                    if (dx * dx + dy * dy > 144) moved = true
                    if (moved) {
                        lp.x = ox + dx.toInt(); lp.y = oy + dy.toInt()
                        try { wm.updateViewLayout(target, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap?.invoke()
            }
            true
        }
    }

    private fun item(t: String, color: Int = Y, size: Float = 24f, click: (() -> Unit)? = null) =
        TextView(this).apply {
            text = t; setTextColor(color); textSize = size; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            click?.let { c -> setOnClickListener { c() } }
        }

    private fun buildPanel() {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60A0A0A")); cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.parseColor("#66FFD60A"))
            }
        }
        playBtn = item("▶", size = 28f) { toggle() }
        p.addView(playBtn)
        p.addView(item("＋", size = 28f) { startCapture() })
        p.addView(item("－", size = 28f) { resetTemplates() })
        p.addView(item("⚙") {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        statusTv = item("0", Color.GRAY, 13f)
        p.addView(statusTv)
        p.addView(item("✕", Color.parseColor("#B0642A"), 20f) { stopSelf() })
        val handle = item("✥", Color.GRAY, 26f)
        p.addView(handle)

        val lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
            .apply { x = 0; y = dp(160) }
        attachDrag(handle, p, lp, null)
        panel = p
        wm.addView(p, lp)
    }

    override fun onDestroy() {
        active = false; running = false
        removeFrame()
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        try { vd?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        super.onDestroy()
    }
}
