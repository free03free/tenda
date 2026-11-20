package com.example.modemcontrol

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.activity.addCallback
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONObject

/**
 * الشاشة الرئيسية: لوحة المودم. تبقى مفتوحة عند الخروج من التطبيق (زر الرجوع يُخفيه فقط)،
 * وتحفظ الجلسة وآخر صفحة، وتسجّل الدخول تلقائياً.
 */
class WebPanelActivity : LockedActivity() {
    private lateinit var web: WebView
    private var loadedHost: String? = null
    private var askedSettings = false
    private var attempts = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web)
        web = findViewById(R.id.web)
        val bar = findViewById<LinearProgressIndicator>(R.id.progress)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                bar.progress = p
                bar.visibility = if (p >= 100) android.view.View.GONE else android.view.View.VISIBLE
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedHttpAuthRequest(v: WebView, h: HttpAuthHandler, host: String, realm: String) {
                val u = Prefs.getString(this@WebPanelActivity, "user")
                if (u.isNotBlank()) h.proceed(u, Prefs.getString(this@WebPanelActivity, "pass")) else h.cancel()
            }

            override fun onPageFinished(v: WebView, url: String?) {
                CookieManager.getInstance().flush()
                val host = loadedHost ?: return
                if (url != null && Uri.parse(url).host == host.substringBefore(':')) {
                    Prefs.putString(this@WebPanelActivity, "last_url", url)
                    autoLogin(v, url, host)
                }
            }
        }
        findViewById<android.view.View>(R.id.btnRefresh).setOnClickListener { web.reload() }
        findViewById<android.view.View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // الرجوع يتنقل داخل اللوحة، وعند أول صفحة يُخفي التطبيق دون إغلاقه
        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
        }
    }

    /** تُستدعى بعد فتح القفل: تحمّل اللوحة فقط إذا تغيّر الـ IP، وإلا تبقى الصفحة كما هي. */
    override fun onReady() {
        val host = ModemClient.normalize(Prefs.getString(this, "ip"))
        if (host == null) {
            if (!askedSettings) { askedSettings = true; startActivity(Intent(this, SettingsActivity::class.java)) }
            return
        }
        if (host == loadedHost) return
        loadedHost = host
        attempts = 0
        findViewById<TextView>(R.id.tvHost).text = host
        val last = Prefs.getString(this, "last_url")
        val sameHost = last.isNotEmpty() && Uri.parse(last).host == host.substringBefore(':')
        web.loadUrl(if (sameHost) last else "http://$host")
    }

    /** يملأ كلمة المرور ويضغط زر الدخول، فقط داخل صفحات المودم نفسه ولمحاولتين كحد أقصى. */
    private fun autoLogin(v: WebView, url: String, host: String) {
        val pass = Prefs.getString(this, "pass")
        if (Prefs.getString(this, "auto_login", "1") != "1" || pass.isBlank() || attempts >= 2) return
        val js = """
            (function(u,p){
              var pw=document.querySelector('input[type=password]');
              if(!pw||pw.offsetParent===null) return 'no';
              function setv(el,v){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');
                d.set.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}
              var scope=pw.form||document;
              if(u){var ins=scope.querySelectorAll('input[type=text],input[type=email],input:not([type])');
                for(var i=0;i<ins.length;i++){if(ins[i].offsetParent!==null){setv(ins[i],u);break;}}}
              setv(pw,p);
              var btn=scope.querySelector('button[type=submit],input[type=submit],button,input[type=button],.btn');
              setTimeout(function(){if(btn)btn.click();else if(pw.form)pw.form.submit();},300);
              return 'ok';
            })(${JSONObject.quote(Prefs.getString(this, "user"))},${JSONObject.quote(pass)})
        """.trimIndent()
        v.evaluateJavascript(js) { if (it?.contains("ok") == true) attempts++ }
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }
}
