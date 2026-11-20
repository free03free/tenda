package com.example.modemcontrol

import android.util.Base64
import android.util.Patterns
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object ModemClient {
    /** بداية رد المودم على آخر طلب (للتشخيص فقط). */
    @Volatile var lastResponse: String = ""

    /** يقبل 192.168.1.1 أو 192.168.1.1:8080 ويُرجع null إن لم يكن IP صالحاً. */
    fun normalize(input: String): String? {
        val s = input.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
        if (s.isEmpty()) return null
        return if (Patterns.IP_ADDRESS.matcher(s.substringBefore(':')).matches()) s else null
    }

    private fun send(url: String, method: String, user: String, pass: String, body: String?): Int {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000
            c.readTimeout = 8000
            c.requestMethod = method
            if (user.isNotBlank())
                c.setRequestProperty("Authorization", "Basic " +
                    Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP))
            // نمرّر جلسة لوحة المودم (الكوكيز) وReferer لأن أغلب المودمات تتحقق منهما
            val origin = URL(url).let { u -> "${u.protocol}://${u.authority}" }
            c.setRequestProperty("Referer", "$origin/")
            c.setRequestProperty("Origin", origin)
            CookieManager.getInstance().getCookie(origin)?.takeIf { it.isNotBlank() }
                ?.let { c.setRequestProperty("Cookie", it) }
            if (method == "POST" && body != null) {
                c.doOutput = true
                val json = body.trimStart().let { it.startsWith("{") || it.startsWith("[") }
                c.setRequestProperty("Content-Type",
                    if (json) "application/json" else "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            lastResponse = runCatching {
                (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText().take(300) }
            }.getOrNull().orEmpty().trim()
            return code
        } finally { c.disconnect() }
    }

    /** طلب عام للمودم: المسار + GET/POST + جسم اختياري. */
    suspend fun call(host: String, path: String, method: String, body: String, user: String, pass: String) =
        withContext(Dispatchers.IO) {
            runCatching {
                val p = if (path.startsWith("/")) path else "/$path"
                send("http://$host$p", if (method.equals("POST", true)) "POST" else "GET", user, pass, body.ifBlank { null })
            }
        }

    suspend fun check(host: String, user: String, pass: String) = withContext(Dispatchers.IO) {
        runCatching { send("http://$host", "GET", user, pass, null) }
    }

    suspend fun reboot(host: String, path: String, method: String, body: String, user: String, pass: String) =
        call(host, path, method, body, user, pass)

    /** مفتاح قطع الإنترنت: on=true يقطع عن الجميع، false يعيده. يُرجع Result<Int> (كود HTTP). */
    suspend fun setBlock(c: android.content.Context, on: Boolean): Result<Int> {
        val k = if (on) "block_on" else "block_off"
        val path = Exempt.apply(c, Prefs.getString(c, "${k}_path"))
        if (path.isBlank()) return Result.failure(IllegalStateException("not_configured"))
        val host = normalize(Prefs.getString(c, "ip"))
            ?: return Result.failure(IllegalStateException("bad_ip"))
        val r = call(host, path, Prefs.getString(c, "${k}_method", "GET"), Exempt.apply(c, Prefs.getString(c, "${k}_body")),
            Prefs.getString(c, "user"), Prefs.getString(c, "pass"))
        return r.mapCatching { code ->
            if (code !in 200..399) throw IllegalStateException("HTTP $code")
            if (lastResponse.contains("Document Error", true) || lastResponse.contains("Access Error", true))
                throw IllegalStateException("رفض المودم الطلب (المسار أو الصيغة غير صحيحة)")
            Prefs.putString(c, "block_state", if (on) "1" else "0")
            code
        }
    }
}
