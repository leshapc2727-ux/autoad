package com.autoad

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private val Y = Color.parseColor("#FFD60A")
    private val BG = Color.parseColor("#0A0A0A")
    private val CARD = Color.parseColor("#1C1C1E")
    private lateinit var status: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, r: Int = 16) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(36))
        }

        root.addView(TextView(this).apply {
            text = "AUTO·AD"; setTextColor(Y); textSize = 30f
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL)
            letterSpacing = 0.15f
        })
        root.addView(TextView(this).apply {
            text = "авто-просмотр рекламы"; setTextColor(Color.GRAY); textSize = 14f
            setPadding(0, 0, 0, dp(20))
        })

        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f
            setPadding(dp(16), dp(14), dp(16), dp(14)); background = bg(CARD)
        }
        root.addView(status, lp(bottom = 16))

        root.addView(button("ЗАПУСТИТЬ ПАНЕЛЬ") { start() }, lp(bottom = 8))
        root.addView(button("ОСТАНОВИТЬ ПАНЕЛЬ", outline = true) {
            stopService(Intent(this, OverlayService::class.java)); refresh()
        }, lp(bottom = 24))

        root.addView(TextView(this).apply {
            text = "НАСТРОЙКИ"; setTextColor(Y); textSize = 13f; letterSpacing = 0.2f
            setPadding(0, 0, 0, dp(8))
        })
        for (s in Prefs.settings) root.addView(settingRow(s), lp(bottom = 8))

        root.addView(TextView(this).apply {
            text = "Как пользоваться:\n1. Нажми «Запустить панель» и дай разрешения.\n" +
                "2. Открой игру. На панели нажми ＋ и наведи рамку на кнопку «Смотреть рекламу», тапни по рамке.\n" +
                "3. Снова ＋: рамку на кнопку «Пропустить/✕», тапни.\n" +
                "4. Нажми ▶. Для сброса кнопок: －."
            setTextColor(Color.GRAY); textSize = 13f; setPadding(0, dp(12), 0, 0)
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })
    }

    private fun lp(bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(bottom) }

    private fun button(t: String, outline: Boolean = false, click: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f
        setTextColor(if (outline) Y else Color.BLACK)
        background = if (outline) GradientDrawable().apply {
            setColor(Color.TRANSPARENT); setStroke(dp(1), Y); cornerRadius = dp(16).toFloat()
        } else bg(Y)
        stateListAnimator = null
        setOnClickListener { click() }
    }

    private fun settingRow(s: Setting): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8)); background = bg(CARD)
        }
        val cur = Prefs.get(this, s.key)
        val label = TextView(this).apply {
            text = "${s.title}: $cur"; setTextColor(Color.WHITE); textSize = 14f
        }
        val bar = SeekBar(this).apply {
            max = s.max - s.min; progress = cur - s.min
            progressTintList = android.content.res.ColorStateList.valueOf(Y)
            thumbTintList = android.content.res.ColorStateList.valueOf(Y)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + s.min
                    label.text = "${s.title}: $v"
                    Prefs.set(this@MainActivity, s.key, v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(label); box.addView(bar)
        return box
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Разреши показ поверх других приложений", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (ClickService.instance == null) {
            Toast.makeText(this, "Включи службу «Auto Ad» в Специальных возможностях", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val m = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(m.createScreenCaptureIntent(), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, OverlayService::class.java)
                .putExtra("code", resultCode).putExtra("data", data)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        fun mark(b: Boolean) = if (b) "✓" else "✗"
        status.text = "${mark(Settings.canDrawOverlays(this))}  Поверх других приложений\n" +
            "${mark(ClickService.instance != null)}  Служба нажатий\n" +
            "${mark(OverlayService.running)}  Панель запущена"
    }
}
