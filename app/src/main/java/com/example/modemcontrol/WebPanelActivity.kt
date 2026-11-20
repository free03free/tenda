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
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch
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
        web.addJavascriptInterface(CapBridge(), "MCCap")
        web.addJavascriptInterface(BlockBridge(), "MCBlk")
        PanelBlock.web = java.lang.ref.WeakReference(web)
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
                v.evaluateJavascript(capJs, null)
                if (desktopApplied == true) v.evaluateJavascript(viewportJs(), null)
            }

            override fun onPageFinished(v: WebView, url: String?) {
                CookieManager.getInstance().flush()
                v.evaluateJavascript(capJs, null)
                if (desktopApplied == true) v.evaluateJavascript(viewportJs(), null)
                val host = loadedHost ?: return
                if (url != null && Uri.parse(url).host == host.substringBefore(':')) {
                    Prefs.putString(this@WebPanelActivity, "last_url", url)
                    autoLogin(v, url, host)
                }
            }
        }
        findViewById<android.view.View>(R.id.btnRefresh).setOnClickListener { web.reload() }
        findViewById<android.view.View>(R.id.btnLog).setOnClickListener { showLog() }
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
        Prefs.seedBlockDefaults(this)
        setupBlockSwitch()
        // اللوحة لا تملك إعداداً للأجهزة الجديدة، فنعيد فحص الشبكة كل 30 ثانية والقطع مفعّل لنشمل ما اتصل حديثاً
        lifecycleScope.launch {
            var waited = 0L
            while (true) {
                kotlinx.coroutines.delay(1000)
                waited++
                val secs = (Prefs.getString(this@WebPanelActivity, "sweep_secs", "30").toLongOrNull() ?: 30L).coerceIn(5L, 3600L)
                if (waited < secs) continue
                waited = 0
                if (Prefs.getString(this@WebPanelActivity, "sweep_on", "1") == "1" &&
                    Prefs.getString(this@WebPanelActivity, "block_state") == "1" &&
                    Exempt.list(this@WebPanelActivity).isNotEmpty())
                    PanelBlock.setBlock(this@WebPanelActivity, true, sweep = true)
            }
        }
        // الرجوع يتنقل داخل اللوحة، وعند أول صفحة يُخفي التطبيق دون إغلاقه
        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
        }
    }

    /** تُستدعى بعد فتح القفل: تحمّل اللوحة فقط إذا تغيّر الـ IP، وإلا تبقى الصفحة كما هي. */
    override fun onReady() {
        val modeChanged = applyMode()
        renderBlock(Prefs.getString(this, "block_state") == "1")
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

    /** مفتاح قطع الإنترنت: يرسل طلب القطع/الاستعادة المحدد في الإعدادات، ويتراجع عند الفشل. */
    private var blockSilent = false
    private fun renderBlock(on: Boolean) {
        val sw = findViewById<MaterialSwitch>(R.id.swBlock)
        blockSilent = true; sw.isChecked = on; blockSilent = false
        findViewById<TextView>(R.id.tvBlock).text =
            if (on) "الإنترنت مقطوع عن كل الأجهزة" else "قطع الإنترنت عن كل الأجهزة"
    }

    private fun setupBlockSwitch() {
        val sw = findViewById<MaterialSwitch>(R.id.swBlock)
        renderBlock(Prefs.getString(this, "block_state") == "1")

        fun send(want: Boolean) {
            sw.isEnabled = false
            lifecycleScope.launch {
                PanelBlock.setBlock(this@WebPanelActivity, want).fold({
                    renderBlock(want)
                    val r = ModemClient.lastResponse.replace(Regex("\\s+"), " ").take(80)
                    android.widget.Toast.makeText(this@WebPanelActivity,
                        (if (want) "أُرسل أمر القطع" else "أُرسل أمر الاستعادة") + if (r.isNotEmpty()) "\nرد المودم: $r" else "",
                        android.widget.Toast.LENGTH_LONG).show()
                }, {
                    renderBlock(!want)
                    android.widget.Toast.makeText(this@WebPanelActivity, "فشل الأمر: ${it.message}" + ModemClient.lastResponse.replace(Regex("\\s+"), " ").take(80).let { r -> if (r.isNotEmpty()) "\nرد المودم: $r" else "" },
                        android.widget.Toast.LENGTH_LONG).show()
                })
                sw.isEnabled = true
            }
        }

        sw.setOnCheckedChangeListener { _, want ->
            if (blockSilent) return@setOnCheckedChangeListener
            // تنبيه واحد فقط: لا أجهزة مستثناة، فسيُقطع حتى عن هذا الهاتف وقد لا تستطيع الاستعادة
            if (want && Exempt.list(this).isEmpty()) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setMessage("لا توجد أجهزة مستثناة. سيُقطع الإنترنت عن كل الأجهزة (تبقى على الوايفاي) وقد يشمل هذا الهاتف. أضف هاتفك للمستثنين من الإعدادات أولاً. هل تريد المتابعة؟")
                    .setNegativeButton("إلغاء") { _, _ -> renderBlock(false) }
                    .setPositiveButton("متابعة") { _, _ -> send(true) }
                    .setOnCancelListener { renderBlock(false) }
                    .show()
                return@setOnCheckedChangeListener
            }
            send(want)
        }
    }

    // ---- التقاط الطلبات التي ترسلها لوحة المودم نفسها (لمعرفة صيغة أمر الحظر الصحيحة لموديلك) ----
    private val captured = java.util.ArrayDeque<String>()

    private inner class BlockBridge {
        @android.webkit.JavascriptInterface
        fun done(s: String) { PanelBlock.finish(s) }
    }

    private inner class CapBridge {
        @android.webkit.JavascriptInterface
        fun log(s: String) {
            synchronized(captured) {
                captured.addLast(s.take(2200))
                while (captured.size > 100) captured.removeFirst()
            }
        }
    }

    // يخفي أي قيمة اسمها يحوي pass/pwd/password قبل تسجيلها.
    // يلتقط أيضاً القيم "قبل التشفير": استدعاءات jQuery.ajax، ومكتبات JSEncrypt/CryptoJS، والدوال العامة التي في اسمها enc/crypt،
    // وآخر نتائج JSON.stringify قبل كل طلب مشفّر (PRE)، مع مسار الاستدعاء (STACK).
    private val capJs = """(function(){if(window.__mcCap)return;window.__mcCap=1;
        function L(s){try{MCCap.log(s);}catch(e){}}
        var JS=JSON.stringify;
        function mask(t){t=String(t==null?'':t);
          t=t.replace(/((?:pass|pwd|password)[^&=]*=)[^&]*/ig,function(m,a){return a+'***';});
          t=t.replace(/("[^"]*(?:pass|pwd)[^"]*"\s*:\s*")[^"]*/ig,function(m,a){return a+'***';});
          return t;}
        function skip(u){return /\.(js|css|png|jpg|gif|ico|svg|woff2?|html?)(\?|$)/i.test(u);}
        var recent=[];
        function note(s){try{s=String(s);if(s.length<3000){recent.push({t:Date.now(),s:s});if(recent.length>6)recent.shift();}}catch(e){}}
        JSON.stringify=function(){var r=JS.apply(this,arguments);note(r);return r;};
        function stack(){try{return new Error().stack.split('\n').slice(2,12).map(function(l){
          return l.trim().replace(/^at /,'').replace(/https?:\/\/[^\/]+\//g,'');}).join(' < ');}catch(e){return '';}}
        var o=XMLHttpRequest.prototype.open,s=XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.open=function(m,u){this.__m=m;this.__u=u;return o.apply(this,arguments);};
        XMLHttpRequest.prototype.send=function(b){var x=this;
          if(x.__u&&!skip(String(x.__u))){
            var u=String(x.__u),bs=(b==null)?'':String(b),enc=bs.indexOf('__enc')>=0;
            var rec=x.__m+' '+mask(u)+(bs?'\nBODY: '+mask(bs.slice(0,enc?60:500))+(enc?'...(مشفّر)':''):'');
            if(enc){var now=Date.now();
              var pre=recent.filter(function(r){return now-r.t<4000;}).map(function(r){return mask(r.s).slice(0,500);});
              rec+='\nPRE: '+(pre.length?pre.join(' || '):'(none)')+'\nSTACK: '+stack();}
            var big=/goform\/get/i.test(u)&&u.indexOf('modules=ddns')<0;
            x.addEventListener('loadend',function(){try{L(rec+'\nRESP '+x.status+': '+mask(String(x.responseText||'').slice(0,big?1500:150)));}catch(e){}});}
          return s.apply(this,arguments);};
        if(window.fetch){var f=window.fetch;window.fetch=function(u,i){try{var url=(typeof u==='string')?u:u.url;
          if(!skip(url)){L(((i&&i.method)||'GET')+' '+mask(url)+(i&&i.body?'\nBODY: '+mask(i.body):''));}}catch(e){}
          return f.apply(this,arguments);};}
        function hook(){
          var j=window.jQuery;
          if(j&&j.ajax&&!j.ajax.__mc){var oa=j.ajax;
            j.ajax=function(a,b){try{var st=(typeof a==='object'&&a)?a:(b||{});var u=(typeof a==='string')?a:st.url;
              if(u&&!skip(String(u))&&String(u).indexOf('modules=ddns')<0){var d=st.data;
                var ds=(d==null)?'':(typeof d==='string'?d:JS(d));
                L('AJAX-PRE '+(st.type||st.method||'GET')+' '+mask(String(u))+(ds?'\nDATA: '+mask(ds).slice(0,600):''));}}catch(e){}
              return oa.apply(this,arguments);};
            for(var k in oa){try{j.ajax[k]=oa[k];}catch(e){}}
            j.ajax.__mc=1;}
          var JE=window.JSEncrypt;
          if(JE&&JE.prototype&&JE.prototype.encrypt&&!JE.prototype.encrypt.__mc){var oe=JE.prototype.encrypt;
            JE.prototype.encrypt=function(t){L('JSEncrypt IN: '+mask(String(t)).slice(0,600));return oe.apply(this,arguments);};
            JE.prototype.encrypt.__mc=1;}
          var C=window.CryptoJS;
          if(C){['AES','DES','TripleDES','RC4','Rabbit'].forEach(function(n){var m=C[n];
            if(m&&m.encrypt&&!m.encrypt.__mc){var oe2=m.encrypt;
              m.encrypt=function(t){var v;try{v=(typeof t==='string')?t:t.toString(C.enc.Utf8);}catch(e){v='[wordarray]';}
                L('CryptoJS.'+n+' IN: '+mask(v).slice(0,600));return oe2.apply(this,arguments);};
              m.encrypt.__mc=1;}});}
          try{Object.keys(window).forEach(function(k){var fn=window[k];
            if(typeof fn!=='function'||fn.__mc||!/enc|crypt|aes|rsa|sm4/i.test(k))return;
            var src=Function.prototype.toString.call(fn);
            if(/\[native code\]/.test(src)||/^class\s/.test(src)||Object.keys(fn).length>0)return;
            var cnt=0;
            var w=function(){try{if(cnt++<6)L('FN '+k+' IN: '+mask(Array.prototype.map.call(arguments,function(a){
              return (a&&typeof a==='object')?'[obj]':String(a);}).join(' , ')).slice(0,500));}catch(e){}
              return fn.apply(this,arguments);};
            w.__mc=1;w.prototype=fn.prototype;
            try{window[k]=w;}catch(e){}});}catch(e){}
        }
        var n=0;(function tick(){try{hook();}catch(e){}setTimeout(tick,n++<40?250:3000);})();
      })()"""

    private fun showLog() {
        val all = synchronized(captured) { captured.toList() }
        // يحذف التكرار: الطلبات الدورية نفسها (تختلف فقط بالرقم العشوائي) يبقى آخر واحد منها
        val seen = HashSet<String>()
        val uniq = all.filterNot { it.contains("goform/getStatus") || it.contains("modules=ddns\nRESP") }
            .asReversed().filter { seen.add(it.replace(Regex("(random=)?0\\.[0-9]{6,}&?"), "")) }.asReversed()
        val text = uniq.takeLast(25).joinToString("\n\n").ifBlank {
            "لا توجد طلبات ملتقطة بعد (طلبات التحديث الدورية مخفية).\nنفّذ الإجراء داخل لوحة المودم (مثلاً غيّر فلتر MAC واضغط OK) ثم افتح السجل."
        }
        val tv = TextView(this).apply {
            this.text = text; textSize = 12f; setTextIsSelectable(true)
            textDirection = android.view.View.TEXT_DIRECTION_LTR; setPadding(40, 24, 40, 0)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("آخر الطلبات الملتقطة")
            .setView(android.widget.ScrollView(this).apply { addView(tv) })
            .setPositiveButton("نسخ") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("log", text))
                android.widget.Toast.makeText(this, "تم النسخ", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("مسح") { _, _ -> synchronized(captured) { captured.clear() } }
            .setNeutralButton("إغلاق", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }
}
