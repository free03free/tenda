package com.example.modemcontrol

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** الأجهزة المستثناة من قطع الإنترنت (الاسم + MAC)، تُخزَّن مشفّرة ضمن Prefs. */
object Exempt {
    data class Dev(val name: String, val mac: String)

    /** يقبل AA:BB:CC:DD:EE:FF أو AA-BB-... أو بدون فواصل، ويُرجع الصيغة الموحّدة أو null. */
    fun normalize(s: String): String? {
        val h = s.trim().replace(":", "").replace("-", "").replace(".", "")
        return if (Regex("[0-9A-Fa-f]{12}").matches(h)) h.uppercase().chunked(2).joinToString(":") else null
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

    /** يستبدل {MACS} (مفصولة بفاصلة) و{MACS_JSON} (مصفوفة JSON) في المسار أو الجسم. */
    fun apply(c: Context, text: String): String {
        if (!text.contains("{MACS")) return text
        val macs = list(c).map { it.mac }
        return text.replace("{MACS_JSON}", JSONArray(macs).toString()).replace("{MACS}", macs.joinToString(","))
    }
}
