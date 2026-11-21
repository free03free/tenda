package com.example.modemcontrol

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * طريقتان للقطع تُختاران من الإعدادات (block_method = parental | mac):
 * parental: قطع الإنترنت مع بقاء الوايفاي، عبر "التحكم الأبوي" في لوحة Tenda (goform/setParentControl).
 * اللوحة تشفّر الجسم (__enc) بجافاسكريبتها، فننفّذ الطلب من داخل الـ WebView بدالة $.ajax الخاصة بالصفحة.
 *
 * القطع: نفعّل التحكم الأبوي على كل جهاز غير مستثنى، ونجعل جدول "وقت الاتصال" فارغاً (لا أيام) فلا يُسمح لهم بالإنترنت أبداً.
 * الاستعادة: نعطّل التحكم عن الأجهزة التي فعّلناها نحن فقط، ونعيد الجدول القديم.
 * الأجهزة الجديدة: لا يوجد في اللوحة إعداد عام لها، فيعيد التطبيق الفحص كل 30 ثانية ما دامت الشاشة الرئيسية حيّة.
 */
object PanelBlock {
    @Volatile var web: WeakReference<WebView>? = null
    @Volatile private var pending: CompletableDeferred<String>? = null
    private val lock = Mutex()

    /** يُستدعى من جسر الجافاسكريبت عند انتهاء العملية (نص JSON). */
    fun finish(msg: String) { pending?.complete(msg) }

    private val blockJsPc = """function(on,exempt,flipped,prev){
      var j=window.jQuery;
      function done(ok,m,st){try{MCBlk.done(JSON.stringify({ok:ok,msg:m,st:st||null}));}catch(e){}}
      function enc(v){return encodeURIComponent(v==null?'':String(v));}
      function clean(v){return String(v==null?'':v).replace(/[\t\r\n]/g,' ');}
      if(!j||!j.ajax){done(false,'الصفحة غير جاهزة. افتح لوحة المودم وسجّل الدخول ثم أعد المحاولة');return;}
      function get(cb,eb){j.ajax({type:'GET',dataType:'text',
        url:'goform/getParentControl?random='+Math.random()+'&modules=parentCtrlList%2CparentAccessCtrl',
        success:function(t){var r;try{r=JSON.parse(t);}catch(e){eb('رد غير مفهوم (غالباً انتهت الجلسة، سجّل الدخول في اللوحة): '+String(t).slice(0,60));return;}cb(r);},
        error:function(x){eb('فشل قراءة الأجهزة HTTP '+x.status);}});}
      get(function(r){
        var ac=r.parentAccessCtrl||{},list=r.parentCtrlList||[];
        if((ac.parentCtrlURL&&ac.parentCtrlURL.length)||(ac.parentCtrlURLFilterMode&&ac.parentCtrlURLFilterMode!=='disable')){
          done(false,'عندك فلترة روابط مفعّلة في التحكم الأبوي، لن أغيّر الإعدادات حتى لا تُمسح');return;}
        var ex={},fl={},i;
        for(i=0;i<exempt.length;i++)ex[String(exempt[i]).toLowerCase()]=1;
        for(i=0;i<flipped.length;i++)fl[String(flipped[i]).toLowerCase()]=1;
        var rows=[],want={},changed=false,blocked=0;
        for(i=0;i<list.length;i++){
          var d=list[i],mac=String(d.parentCtrlMAC).toLowerCase(),en=String(d.parentCtrlEn);
          if(on){
            if(ex[mac]){if(fl[mac]){en='false';delete fl[mac];}}
            else{if(en!=='true')fl[mac]=1;en='true';blocked++;}
          }else{if(fl[mac])en='false';}
          if(en!==String(d.parentCtrlEn))changed=true;
          want[mac]=en;
          rows.push(clean(d.parentCtrlHostname)+'\t'+clean(d.parentCtrlRemark)+'\t'+d.parentCtrlMAC+'\t'+d.parentCtrlIP+'\t'+en);}
        var pt=(prev&&prev.t)?prev.t:ac.parentCtrlOnlineTime, pd=(prev&&prev.d)?prev.d:ac.parentCtrlOnlineDate;
        var tries=on?[[pt,'00000000'],['03:00-03:01','11111111']]:[[pt,pd]];
        function state(){return on?{flipped:Object.keys(fl),t:pt,d:pd}:{flipped:[],t:'',d:''};}
        var matches=false;
        for(i=0;i<tries.length;i++)if(ac.parentCtrlOnlineTime===tries[i][0]&&ac.parentCtrlOnlineDate===tries[i][1])matches=true;
        if(!changed&&matches){done(true,on?'القطع فعّال ولا أجهزة جديدة':'لا شيء للاستعادة',state());return;}
        function attempt(k){
          var t=tries[k][0],dt=tries[k][1];
          var data='module1=parentCtrlList&onlineList='+enc(rows.join('\n'))
            +'&module2=parentAccessCtrl&parentCtrlOnlineTime='+enc(t)+'&parentCtrlOnlineDate='+enc(dt)
            +'&parentCtrlURLFilterMode=disable&urlList=';
          function next(why){if(k+1<tries.length)attempt(k+1);else done(false,why);}
          j.ajax({type:'POST',url:'goform/setParentControl',data:data,dataType:'text',
            success:function(tx){
              var code='?';try{code=String(JSON.parse(tx).errCode);}catch(e){}
              if(code!=='0'){next('رفض المودم الطلب: '+String(tx).slice(0,80));return;}
              setTimeout(function(){get(function(r2){
                var a2=r2.parentAccessCtrl||{},ok=(a2.parentCtrlOnlineTime===t&&a2.parentCtrlOnlineDate===dt);
                var l2=r2.parentCtrlList||[];
                for(var q=0;q<l2.length;q++){var m2=String(l2[q].parentCtrlMAC).toLowerCase();
                  if(want[m2]!==undefined&&want[m2]!==String(l2[q].parentCtrlEn))ok=false;}
                if(ok)done(true,on?('تم: الإنترنت مقطوع عن '+blocked+' جهاز مع بقائهم على الوايفاي'+(k>0?' (جدول بديل: نافذة دقيقة واحدة يومياً)':'')):'تمت الاستعادة',state());
                else next('قبل المودم الطلب لكن القيم لم تُحفظ كما طُلب');
              },function(m3){next(m3);});},800);
            },
            error:function(x){next('فشل إرسال الأمر HTTP '+x.status);}});
        }
        attempt(0);
      },function(m){done(false,m);});
    }"""

    // الطريقة 2: فلتر MAC بوضع Whitelist (المستثنون فقط). تقطع الإنترنت وتمنع الاتصال بالوايفاي أيضاً.
    private val blockJsMac = """function(on,exempt){
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

    // قراءة فقط: جدول التحكم الأبوي وحالة كل جهاز، ووضع فلتر MAC الحالي
    private val verifyJs = """function(){
      var j=window.jQuery;
      function done(o){try{MCBlk.done('V|'+JSON.stringify(o));}catch(e){}}
      if(!j||!j.ajax){done({err:'الصفحة غير جاهزة'});return;}
      var out={};
      j.ajax({type:'GET',dataType:'text',
        url:'goform/getParentControl?random='+Math.random()+'&modules=parentCtrlList%2CparentAccessCtrl',
        success:function(t){
          try{var r=JSON.parse(t),a=r.parentAccessCtrl||{};
            out.pc={t:a.parentCtrlOnlineTime,d:a.parentCtrlOnlineDate,devs:(r.parentCtrlList||[]).map(function(x){
              return {m:String(x.parentCtrlMAC).toLowerCase(),e:String(x.parentCtrlEn)};})};
          }catch(e){out.err='رد غير مفهوم (غالباً انتهت الجلسة)';done(out);return;}
          j.ajax({type:'GET',dataType:'text',url:'goform/getNAT?random='+Math.random()+'&modules=macFilter',
            success:function(t2){try{out.mac=JSON.parse(t2).macFilter.curFilterMode;}catch(e){}done(out);},
            error:function(){done(out);}});
        },
        error:function(x){done({err:'فشل القراءة HTTP '+x.status});}});
    }"""

    /** هل الصفحة الحالية جاهزة ومسجّل الدخول فيها؟ (قراءة فقط، بلا أي تعديل في المودم) */
    suspend fun probe(c: Context): Boolean = lock.withLock {
        val w = web?.get() ?: return@withLock false
        val method = Prefs.getString(c, "block_method_active", Prefs.getString(c, "block_method", "parental"))
        val url = if (method == "mac") "goform/getNAT?random='+Math.random()+'&modules=macFilter"
                  else "goform/getParentControl?random='+Math.random()+'&modules=parentCtrlList"
        val js = """(function(){var j=window.jQuery;function d(s){try{MCBlk.done('P|'+s);}catch(e){}}
          if(!j||!j.ajax){d('no');return;}
          j.ajax({type:'GET',dataType:'text',url:'$url',
            success:function(t){try{JSON.parse(t);d('ok');}catch(e){d('nologin');}},error:function(){d('err');}});})()"""
        val def = CompletableDeferred<String>()
        pending = def
        withContext(Dispatchers.Main) { w.evaluateJavascript(js, null) }
        val raw = withTimeoutOrNull(8000) { def.await() }
        pending = null
        raw == "P|ok"
    }

    /** يقرأ الحالة الفعلية من المودم ويقارنها بالمتوقع. النتيجة: نص التأكيد، أو فشل بسبب عدم المطابقة. */
    suspend fun verify(c: Context, expectOn: Boolean): Result<String> = lock.withLock {
        val w = web?.get() ?: return@withLock Result.failure(IllegalStateException("لوحة المودم غير مفتوحة"))
        val def = CompletableDeferred<String>()
        pending = def
        withContext(Dispatchers.Main) { w.evaluateJavascript("($verifyJs)()", null) }
        val raw = withTimeoutOrNull(15000) { def.await() }
        pending = null
        if (raw == null || !raw.startsWith("V|")) return@withLock Result.failure(IllegalStateException("لا رد من اللوحة"))
        val o = runCatching { JSONObject(raw.substringAfter('|')) }.getOrNull()
            ?: return@withLock Result.failure(IllegalStateException("رد غير مفهوم"))
        if (o.has("err")) return@withLock Result.failure(IllegalStateException(o.optString("err")))
        val method = Prefs.getString(c, "block_method_active", Prefs.getString(c, "block_method", "parental"))
        if (method == "mac") {
            val mode = o.optString("mac")
            val ok = if (expectOn) mode == "pass" else mode == "deny"
            val label = if (mode == "pass") "Whitelist" else if (mode == "deny") "Blacklist" else "غير معروف"
            return@withLock if (ok) Result.success("وضع الفلتر: $label") else Result.failure(IllegalStateException("وضع الفلتر الحالي $label"))
        }
        val pc = o.optJSONObject("pc") ?: return@withLock Result.failure(IllegalStateException("لا بيانات تحكم أبوي"))
        val t = pc.optString("t"); val dd = pc.optString("d")
        val blockedSched = (dd == "00000000") || (t == "03:00-03:01" && dd == "11111111")
        val exempt = Exempt.list(c).map { it.mac.lowercase() }.toSet()
        val devs = pc.optJSONArray("devs") ?: JSONArray()
        var other = 0; var other_on = 0
        for (i in 0 until devs.length()) {
            val dv = devs.getJSONObject(i)
            if (dv.optString("m") in exempt) continue
            other++; if (dv.optString("e") == "true") other_on++
        }
        if (expectOn) {
            val ok = blockedSched && other_on == other
            if (ok) Result.success("الإنترنت مقطوع عن $other جهاز") else
                Result.failure(IllegalStateException("لم يكتمل القطع: ${other - other_on} من $other جهاز غير خاضع للتحكم" + if (!blockedSched) "، والجدول ليس جدول القطع" else ""))
        } else {
            val ok = !blockedSched
            if (ok) Result.success("الجدول الحالي $t") else Result.failure(IllegalStateException("جدول القطع ما زال مفعّلاً"))
        }
    }

    suspend fun setBlock(c: Context, on: Boolean, sweep: Boolean = false): Result<Int> = lock.withLock {
        if (sweep && Prefs.getString(c, "block_state") != "1") return@withLock Result.failure(IllegalStateException("sweep_skipped"))
        val w = web?.get() ?: return@withLock Result.failure(IllegalStateException("افتح الشاشة الرئيسية (لوحة المودم) أولاً"))
        val macs = JSONArray(Exempt.list(c).map { it.mac.lowercase() })
        val wasOn = Prefs.getString(c, "block_state") == "1"
        val flipped = runCatching { JSONArray(Prefs.getString(c, "pc_flipped", "[]")) }.getOrDefault(JSONArray())
        val prev = JSONObject().apply {
            if (wasOn) { put("t", Prefs.getString(c, "pc_prev_t")); put("d", Prefs.getString(c, "pc_prev_d")) }
        }
        // الطريقة المستخدمة: المختارة في الإعدادات عند بدء القطع، وتبقى نفسها حتى الاستعادة حتى لو غُيّرت الإعدادات
        val method = if (wasOn) Prefs.getString(c, "block_method_active", "parental")
                     else Prefs.getString(c, "block_method", "parental")
        if (sweep && method == "mac") return@withLock Result.failure(IllegalStateException("sweep_skipped"))
        val def = CompletableDeferred<String>()
        pending = def
        withContext(Dispatchers.Main) {
            if (method == "mac") {
                val objs = JSONArray(Exempt.list(c).map { JSONObject().put("n", it.name).put("m", it.mac.lowercase()) })
                w.evaluateJavascript("($blockJsMac)($on,$objs)", null)
            } else w.evaluateJavascript("($blockJsPc)($on,$macs,$flipped,$prev)", null)
        }
        val raw = withTimeoutOrNull(25000) { def.await() }
        pending = null
        if (raw == null) return@withLock Result.failure(IllegalStateException("لا رد من اللوحة خلال 25 ثانية"))
        // طريقة MAC تردّ بنص "OK|..." أو "ERR|..."، وطريقة التحكم الأبوي بـ JSON
        val o = if (raw.startsWith("OK|") || raw.startsWith("ERR|"))
            JSONObject().put("ok", raw.startsWith("OK|")).put("msg", raw.substringAfter('|'))
        else runCatching { JSONObject(raw) }.getOrNull()
            ?: return@withLock Result.failure(IllegalStateException("رد غير مفهوم من اللوحة"))
        val msg = o.optString("msg")
        if (!sweep) ModemClient.lastResponse = msg
        if (!o.optBoolean("ok")) return@withLock Result.failure(IllegalStateException(msg))
        o.optJSONObject("st")?.let { st ->
            Prefs.putString(c, "pc_flipped", (st.optJSONArray("flipped") ?: JSONArray()).toString())
            Prefs.putString(c, "pc_prev_t", st.optString("t"))
            Prefs.putString(c, "pc_prev_d", st.optString("d"))
        }
        Prefs.putString(c, "block_state", if (on) "1" else "0")
        if (on) Prefs.putString(c, "block_method_active", method)
        // 201 = تغيّر شيء فعلاً (مثلاً قُطع جهاز جديد)، 200 = لا تغيير (لا أجهزة جديدة / لا شيء للاستعادة)
        val changed = !(msg.contains("ولا أجهزة جديدة") || msg.contains("لا شيء للاستعادة"))
        Result.success(if (changed) 201 else 200)
    }
}
