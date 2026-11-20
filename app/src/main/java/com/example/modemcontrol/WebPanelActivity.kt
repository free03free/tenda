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
    private var desktopApplied: Boolean? = null
    private var defaultUa = ""
    private val desktopUa = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private var widthApplied = 0

    private fun desktopWidth() =
        (Prefs.getString(this, "desktop_width", "900").toIntOrNull() ?: 900).coerceIn(600, 1400)

    // يفرض عرض صفحة ثابتاً (من الإعدادات) فيناسبه WebView مع شاشة الهاتف، والتكبير بالإصبعين متاح
    private fun viewportJs() = """(function(){var m=document.querySelector('meta[name=viewport]');
        if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);window.__mcVp=null;}
        else if(window.__mcVp===undefined){window.__mcVp=m.content;}
        m.content='width=${desktopWidth()}';window.dispatchEvent(new Event('resize'));})()"""

    // يعيد إعداد العرض الأصلي للصفحة (أو عرض الجهاز) دون إعادة تحميلها
    private val viewportOffJs = """(function(){var m=document.querySelector('meta[name=viewport]');
        if(!m)return;
        m.content=(window.__mcVp===undefined||window.__mcVp===null)?'width=device-width, initial-scale=1':window.__mcVp;
        window.dispatchEvent(new Event('resize'));})()"""

    /** تطبيق تغيير الوضع على الصفحة الحالية مباشرة، بدون reload ودون الرجوع لأول صفحة. */
    private fun applyLive() {
        web.evaluateJavascript(if (desktopApplied == true) viewportJs() else viewportOffJs, null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web)
        web = findViewById(R.id.web)
        val bar = findViewById<LinearProgressIndicator>(R.id.progress)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        defaultUa = web.settings.userAgentString
        web.settings.setSupportZoom(true)
        web.settings.builtInZoomControls = true
        web.settings.displayZoomControls = false
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

            override fun onPageCommitVisible(v: WebView, url: String?) {
                if (desktopApplied == true) v.evaluateJavascript(viewportJs(), null)
            }

            override fun onPageFinished(v: WebView, url: String?) {
                CookieManager.getInstance().flush()
                if (desktopApplied == true) v.evaluateJavascript(viewportJs(), null)
                val host = loadedHost ?: return
                if (url != null && Uri.parse(url).host == host.substringBefore(':')) {
                    Prefs.putString(this@WebPanelActivity, "last_url", url)
                    autoLogin(v, url, host)
                }
            }
        }
        findViewById<android.view.View>(R.id.btnRefresh).setOnClickListener { web.reload() }
        findViewById<android.view.View>(R.id.btnDesktop).setOnClickListener {
            val on = Prefs.getString(this, "desktop_mode", "0") != "1"
            Prefs.putString(this, "desktop_mode", if (on) "1" else "0")
            android.widget.Toast.makeText(this, if (on) "عرض سطح المكتب: مفعّل" else "عرض الهاتف: مفعّل",
                android.widget.Toast.LENGTH_SHORT).show()
            onReady()
        }
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
        val modeChanged = applyMode()
        val host = ModemClient.normalize(Prefs.getString(this, "ip"))
        if (host == null) {
            if (!askedSettings) { askedSettings = true; startActivity(Intent(this, SettingsActivity::class.java)) }
            return
        }
        if (host == loadedHost) { if (modeChanged) applyLive(); return }
        loadedHost = host
        attempts = 0
        findViewById<TextView>(R.id.tvHost).text = host
        val last = Prefs.getString(this, "last_url")
        val sameHost = last.isNotEmpty() && Uri.parse(last).host == host.substringBefore(':')
        web.loadUrl(if (sameHost) last else "http://$host")
    }

    /** يطبّق وضع سطح المكتب/الهاتف؛ يرجع true إذا تغيّر الوضع. */
    private fun applyMode(): Boolean {
        val desktop = Prefs.getString(this, "desktop_mode", "0") == "1"
        findViewById<android.view.View>(R.id.btnDesktop).alpha = if (desktop) 1f else 0.5f
        val w = desktopWidth()
        if (desktop == desktopApplied && (!desktop || w == widthApplied)) return false
        desktopApplied = desktop
        widthApplied = w
        web.settings.userAgentString = if (desktop) desktopUa else defaultUa
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = desktop
        return true
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
