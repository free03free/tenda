package com.example.modemcontrol

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** الأجهزة المستثناة من قطع الإنترنت (الاسم + MAC)، تُخزَّن مشفّرة ضمن Prefs. */
object Exempt {
    data class Dev(val name: String, val mac: String)

    private val withSep = Regex("(?<![0-9A-Fa-f])[0-9A-Fa-f]{2}([:\\-.][0-9A-Fa-f]{2}){5}(?![0-9A-Fa-f])")
    private val plain = Regex("(?<![0-9A-Fa-f])[0-9A-Fa-f]{12}(?![0-9A-Fa-f])")

    /** يستخرج أول عنوان MAC من أي نص ملصوق (مثل "MAC (34:79:16:c7:83:25)") ويوحّد صيغته، أو null. */
    fun normalize(s: String): String? {
        val m = withSep.find(s)?.value ?: plain.find(s)?.value ?: return null
        return m.replace(Regex("[:\\-.]"), "").uppercase().chunked(2).joinToString(":")
    }

    fun list(c: Context): List<Dev> = runCatching {
        val a = JSONArray(Prefs.getString(c, "exempt", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Dev(o.getString("n"), o.getString("m")) } }
    }.getOrDefault(emptyList())

    private fun save(c: Context, l: List<Dev>) = Prefs.putString(c, "exempt",
        JSONArray(l.map { JSONObject().put("n", it.name).put("m", it.mac) }).toString())

    /** يُرجع false إن كان الـ MAC غير صالح. */
    fun add(c: Context, name: String, mac: String): Boolean {
        val m = normalize(mac) ?: return false
        save(c, list(c).filter { it.mac != m } + Dev(name.trim().ifBlank { m }, m))
        return true
    }

    fun remove(c: Context, mac: String) = save(c, list(c).filter { it.mac != mac })

    /** يستبدل {MACS} (فاصلة) و{MACS_CR}/{MACS_LF} (فاصل سطر مرمّز) و{MACS_JSON} في المسار أو الجسم. */
    fun apply(c: Context, text: String): String {
        if (!text.contains("{MACS")) return text
        val macs = list(c).map { it.mac }
        return text.replace("{MACS_JSON}", JSONArray(macs).toString())
            .replace("{MACS_CR}", macs.joinToString("%0D"))
            .replace("{MACS_LF}", macs.joinToString("%0A"))
            .replace("{MACS}", macs.joinToString(","))
    }
}
