package com.example.modemcontrol

import android.util.Base64
import android.util.Patterns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object ModemClient {
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
            if (method == "POST" && body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            return c.responseCode
        } finally { c.disconnect() }
    }

    suspend fun check(host: String, user: String, pass: String) = withContext(Dispatchers.IO) {
        runCatching { send("http://$host", "GET", user, pass, null) }
    }

    suspend fun reboot(host: String, path: String, method: String, body: String, user: String, pass: String) =
        withContext(Dispatchers.IO) {
            runCatching {
                val p = if (path.startsWith("/")) path else "/$path"
                send("http://$host$p", if (method.equals("POST", true)) "POST" else "GET", user, pass, body.ifBlank { null })
            }
        }
}
