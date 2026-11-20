package com.example.modemcontrol

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * قطع/استعادة الإنترنت عبر لوحة Tenda نفسها: لوحة F6 V12 تشفّر جسم الطلب (__enc) داخل جافاسكريبت الصفحة،
 * فبدل تقليد التشفير ننفّذ طلب setNAT من داخل الـ WebView بدالة $.ajax الخاصة بالصفحة فتشفّره هي.
 * القطع = فلتر MAC بوضع Whitelist (filterMode=pass) وفيه المستثنون فقط. الاستعادة = Blacklist بقائمة فارغة (deny).
 */
object PanelBlock {
    @Volatile var web: WeakReference<WebView>? = null
    @Volatile private var pending: CompletableDeferred<String>? = null

    /** يُستدعى من جسر الجافاسكريبت عند انتهاء العملية: "OK|..." أو "ERR|...". */
    fun finish(msg: String) { pending?.complete(msg) }

    private val blockJs = """function(on,exempt){
      var j=window.jQuery;
      function done(ok,m){try{MCBlk.done((ok?'OK|':'ERR|')+m);}catch(e){}}
      function enc(v){return encodeURIComponent(v==null?'':String(v));}
      if(!j||!j.ajax){done(false,'الصفحة غير جاهزة (jQuery غير موجود). افتح لوحة المودم وسجّل الدخول ثم أعد المحاولة');return;}
      var mods='staticIPList,portList,ddns,dmz,ping,upnp,lanCfg,macFilter,localhost,wifiRelay,onlineList,apIsolation';
      function get(cb,eb){j.ajax({type:'GET',dataType:'text',
        url:'goform/getNAT?random='+Math.random()+'&modules='+encodeURIComponent(mods),
        success:function(t){var r;try{r=JSON.parse(t);}catch(e){eb('رد غير مفهوم (غالباً انتهت الجلسة، سجّل الدخول في اللوحة): '+String(t).slice(0,60));return;}cb(r);},
        error:function(x){eb('فشل قراءة الإعدادات HTTP '+x.status);}});}
      get(function(r){
        if((r.staticIPList&&r.staticIPList.length)||(r.portList&&r.portList.length)){
          done(false,'عندك قواعد Static IP أو Port Forward، لن أغيّر الإعدادات حتى لا تُمسح');return;}
        var d=r.ddns||{},m=r.dmz||{},u=r.upnp||{},a=r.apIsolation||{},p=r.ping||{};
        var list='';
        if(on){for(var i=0;i<exempt.length;i++){
          var nm=String(exempt[i].n||'').replace(/[\t\r\n]/g,' ').trim()||exempt[i].m;
          list+=nm+'\t\t'+exempt[i].m+'\n';}}
        var mode=on?'pass':'deny';
        var data='module6=macFilter&filterMode='+mode+'&macFilterList='+enc(list)
          +'&module1=staticIPList&staticList=&module2=portList&portList='
          +'&module3=dmz&dmzEn='+enc(m.dmzEn)+'&dmzHostIP='+enc(m.dmzHostIP)
          +'&module4=ddns&ddnsEn='+enc(d.ddnsEn)+'&ddnsServiceName='+enc(d.ddnsServiceName)+'&ddnsServer='+enc(d.ddnsServer)
          +'&ddnsUser='+enc(d.ddnsUser)+'&ddnsPwd='+enc(d.ddnsPwd)
          +'&module5=upnp&upnpEn='+enc(u.upnpEn)+'&module8=apIsolation&apIsolationEn='+enc(a.apIsolationEn)
          +'&module7=ping&pingEn='+enc(p.pingEn);
        j.ajax({type:'POST',url:'goform/setNAT',data:data,dataType:'text',
          success:function(t){
            var code='?';try{code=String(JSON.parse(t).errCode);}catch(e){}
            if(code!=='0'){done(false,'رفض المودم الطلب: '+String(t).slice(0,80));return;}
            setTimeout(function(){get(function(r2){
              var cur=r2.macFilter&&r2.macFilter.curFilterMode;
              if(cur===mode)done(true,'تم، وضع الفلتر الآن: '+(mode==='pass'?'Whitelist':'Blacklist'));
              else done(false,'قبل المودم الطلب لكن الوضع الحالي '+cur);
            },function(m2){done(false,m2);});},800);
          },
          error:function(x){done(false,'فشل إرسال الأمر HTTP '+x.status);}});
      },function(m){done(false,m);});
    }"""

    suspend fun setBlock(c: Context, on: Boolean): Result<Int> {
        val w = web?.get() ?: return Result.failure(IllegalStateException("افتح الشاشة الرئيسية (لوحة المودم) أولاً"))
        val arr = JSONArray(Exempt.list(c).map { JSONObject().put("n", it.name).put("m", it.mac.lowercase()) })
        val def = CompletableDeferred<String>()
        pending = def
        withContext(Dispatchers.Main) {
            w.evaluateJavascript("($blockJs)($on,$arr)", null)
        }
        val msg = withTimeoutOrNull(20000) { def.await() }
        pending = null
        if (msg == null) return Result.failure(IllegalStateException("لا رد من اللوحة خلال 20 ثانية"))
        ModemClient.lastResponse = msg.substringAfter('|')
        return if (msg.startsWith("OK|")) {
            Prefs.putString(c, "block_state", if (on) "1" else "0")
            Result.success(200)
        } else Result.failure(IllegalStateException(msg.substringAfter('|')))
    }
}
