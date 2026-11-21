package com.example.modemcontrol

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * WebView خلفي تملكه الخدمة (لا الواجهة): يحمّل لوحة المودم ويسجّل الدخول وينفّذ الاستعادة والشاشة مطفأة
 * والتطبيق مغلق من الواجهة، فلا تتوقف الاستعادة على فتح الشاشة أو إدخال النمط.
 */
object PanelHost {
    private var host: WebView? = null
    private var attempts = 0

    private class Bridge { @JavascriptInterface fun done(s: String) { PanelBlock.finish(s) } }

    /** يُستدعى من الواجهة عند إغلاقها لإرجاع الـ WebView الخلفي كمرجع للوحة. */
    fun rebind() { host?.let { PanelBlock.web = WeakReference(it) } }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(c: Context): WebView = WebView(c.applicationContext).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        addJavascriptInterface(Bridge(), "MCBlk")
        val wv = this
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(wv, true) }
        webViewClient = object : WebViewClient() {
            override fun onReceivedHttpAuthRequest(v: WebView, h: HttpAuthHandler, hostName: String, realm: String) {
                val u = Prefs.getString(c, "user")
                if (u.isNotBlank()) h.proceed(u, Prefs.getString(c, "pass")) else h.cancel()
            }
            override fun onPageFinished(v: WebView, url: String?) {
                CookieManager.getInstance().flush()
                login(c, v)
            }
        }
        onResume(); resumeTimers()
    }

    /** يملأ كلمة المرور ويضغط الدخول دون اشتراط ظهور الحقل على الشاشة (الـ WebView هنا غير مرئي). */
    private fun login(c: Context, v: WebView) {
        val pass = Prefs.getString(c, "pass")
        if (Prefs.getString(c, "auto_login", "1") != "1" || pass.isBlank() || attempts >= 3) return
        val js = """(function(u,p){
          var pw=document.querySelector('input[type=password]'); if(!pw) return 'no';
          function setv(el,v){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');
            d.set.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}
          var scope=pw.form||document;
          if(u){var ins=scope.querySelectorAll('input[type=text],input[type=email],input:not([type])');
            for(var i=0;i<ins.length;i++){if(ins[i]!==pw&&ins[i].type!=='hidden'){setv(ins[i],u);break;}}}
          setv(pw,p);
          var btn=scope.querySelector('button[type=submit],input[type=submit],button,input[type=button],.btn');
          setTimeout(function(){if(btn)btn.click();else if(pw.form)pw.form.submit();},300);
          return 'ok';})(${JSONObject.quote(Prefs.getString(c, "user"))},${JSONObject.quote(pass)})"""
        v.evaluateJavascript(js) { if (it?.contains("ok") == true) attempts++ }
    }

    private suspend fun load(c: Context, ip: String) = withContext(Dispatchers.Main) {
        val w = host ?: create(c).also { host = it }
        attempts = 0
        val last = Prefs.getString(c, "last_url")
        val same = last.isNotEmpty() && Uri.parse(last).host == ip.substringBefore(':')
        w.loadUrl(if (same) last else "http://$ip")
        // إن لم يكن للواجهة WebView حيّ فهذا هو مرجع اللوحة
        if (PanelBlock.web?.get() == null) PanelBlock.web = WeakReference(w)
    }

    /** يتأكد أن لوحة المودم جاهزة ومسجّل الدخول فيها (من الواجهة إن كانت حيّة، وإلا من الـ WebView الخلفي). */
    suspend fun ensureReady(c: Context): Boolean {
        val ip = ModemClient.normalize(Prefs.getString(c, "ip")) ?: return false
        if (PanelBlock.probe(c)) return true
        // مرجع الواجهة ميّت أو غير جاهز: استخدم الخلفي
        withContext(Dispatchers.Main) { host?.let { PanelBlock.web = WeakReference(it) } }
        load(c, ip)
        for (i in 1..24) {
            delay(2500)
            if (PanelBlock.probe(c)) return true
            if (i % 6 == 0) load(c, ip) // أعد التحميل كل 15 ثانية إن لم تنجح
        }
        return false
    }

    /** استعادة الإنترنت بالكامل من الخدمة. true عند النجاح. */
    suspend fun restore(c: Context): Boolean {
        if (Prefs.getString(c, "block_state") != "1") return true
        if (!ensureReady(c)) return false
        val r = PanelBlock.setBlock(c, false)
        if (r.isFailure) return false
        Prefs.putString(c, "block_until", "0")
        runCatching { PanelBlock.verify(c, false) }
        return true
    }
}
