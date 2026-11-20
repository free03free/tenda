package com.example.modemcontrol

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.HttpAuthHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback

/** يفتح لوحة إدارة المودم الأصلية داخل التطبيق (تعمل مع أي موديل). */
class WebPanelActivity : LockedActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web)
        val host = ModemClient.normalize(Prefs.getString(this, "ip"))
        if (host == null) { finish(); return }
        val web = findViewById<WebView>(R.id.web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedHttpAuthRequest(v: WebView, h: HttpAuthHandler, host: String, realm: String) {
                val u = Prefs.getString(this@WebPanelActivity, "user")
                if (u.isNotBlank()) h.proceed(u, Prefs.getString(this@WebPanelActivity, "pass")) else h.cancel()
            }
        }
        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) web.goBack() else this@WebPanelActivity.finish()
        }
        web.loadUrl("http://$host")
    }
}
