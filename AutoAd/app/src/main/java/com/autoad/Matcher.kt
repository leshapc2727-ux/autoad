package com.autoad

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max

object Matcher {
    const val S = 4 // во сколько раз уменьшаем картинку для скорости

    class Gray(val w: Int, val h: Int, val a: IntArray)
    class Hit(val x: Int, val y: Int, val score: Float)

    fun gray(b: Bitmap): Gray {
        val w = max(1, b.width / S)
        val h = max(1, b.height / S)
        val sc = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h)
        sc.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            px[i] = (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8
        }
        if (sc !== b) sc.recycle()
        return Gray(w, h, px)
    }

    /** Ищет шаблон t на картинке src. Возвращает центр в координатах экрана. */
    fun find(src: Gray, t: Gray): Hit? {
        if (t.w > src.w || t.h > src.h) return null
        val n = ((t.h + 1) / 2) * ((t.w + 1) / 2)
        var best = Long.MAX_VALUE
        var bx = 0
        var by = 0
        var y = 0
        while (y <= src.h - t.h) {
            var x = 0
            while (x <= src.w - t.w) {
                var sad = 0L
                var ty = 0
                while (ty < t.h && sad < best) {
                    val so = (y + ty) * src.w + x
                    val to = ty * t.w
                    var tx = 0
                    while (tx < t.w) {
                        sad += abs(src.a[so + tx] - t.a[to + tx])
                        tx += 2
                    }
                    ty += 2
                }
                if (sad < best) { best = sad; bx = x; by = y }
                x += 2
            }
            y += 2
        }
        val score = 1f - best.toFloat() / (n * 255f)
        return Hit((bx + t.w / 2) * S, (by + t.h / 2) * S, score)
    }
}
