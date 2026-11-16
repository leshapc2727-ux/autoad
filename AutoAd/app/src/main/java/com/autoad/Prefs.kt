package com.autoad

import android.content.Context

class Setting(val key: String, val title: String, val min: Int, val max: Int, val def: Int)

object Prefs {
    val settings = listOf(
        Setting("thr", "Точность совпадения, %", 70, 99, 92),
        Setting("scan", "Интервал поиска, мс", 200, 2000, 700),
        Setting("adWait", "Пауза после клика по рекламе, мс", 500, 10000, 3000),
        Setting("maxWatch", "Ждать кнопку пропуска, сек", 5, 180, 60),
        Setting("pause", "Пауза между циклами, мс", 0, 10000, 1500),
        Setting("maxCycles", "Лимит повторов (0 = бесконечно)", 0, 500, 0),
        Setting("frame", "Размер рамки выбора, dp", 24, 120, 56)
    )

    private fun sp(c: Context) = c.getSharedPreferences("autoad", Context.MODE_PRIVATE)

    fun get(c: Context, key: String): Int =
        sp(c).getInt(key, settings.first { it.key == key }.def)

    fun set(c: Context, key: String, v: Int) { sp(c).edit().putInt(key, v).apply() }
}
