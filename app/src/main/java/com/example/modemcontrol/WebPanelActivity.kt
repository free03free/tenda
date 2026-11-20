package com.example.modemcontrol

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONObject

/** لوحة المودم داخل التطبيق: تحفظ الجلسة (Cookies) وتسجّل الدخول تلقائياً. */
class WebPanelActivity : LockedActivity() {
    private var attempts = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web)
        val host = ModemClient.normalize(Prefs.getString(this, "ip"))
        if (host == null) { finish(); return }
        val web = findViewById<WebView>(R.id.web)
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
                autoLogin(v, url, host)
            }
        }
        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) web.goBack() else this@WebPanelActivity.finish()
        }
        web.loadUrl("http://$host")
    }

    /** يملأ حقل كلمة المرور ويضغط زر الدخول، فقط داخل صفحات المودم نفسه ولمحاولتين كحد أقصى. */
    private fun autoLogin(v: WebView, url: String?, host: String) {
        val pass = Prefs.getString(this, "pass")
        if (Prefs.getString(this, "auto_login", "1") != "1" || pass.isBlank() || attempts >= 2) return
        if (Uri.parse(url ?: return).host != host.substringBefore(':')) return
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
