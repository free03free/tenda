package com.example.modemcontrol

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.app.AlarmManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * خدمة أمامية (إشعار دائم) تُبقي التطبيق حيّاً في الخلفية، وتمسك WakeLock أثناء القطع حتى لا يوقف النظام
 * العدّاد والفحص الدوري. لا تستطيع إبقاء لوحة المودم حيّة إن أغلق المستخدم التطبيق من قائمة التطبيقات الأخيرة؛
 * في هذه الحالة ترسل إشعاراً عند انتهاء المدة يفتح التطبيق ليستعيد الإنترنت.
 */
class KeepAliveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wl: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var expiredNotified = false
    private var armedFor = -1L
    @Volatile private var restoring = false
    private var nextTry = 0L
    private var failCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        armedFor = -1L // أعد ضبط المنبّه (مثلاً بعد تغيير وقت الجهاز أو إعادة التشغيل)
        startForeground(NOTIF_ID, buildNotif("جاهز في الخلفية"))
        if (job == null) job = scope.launch { loop() }
        return START_STICKY
    }

    private suspend fun loop() {
        val nm = getSystemService(NotificationManager::class.java)
        while (scope.isActive) {
            val on = Prefs.getString(this, "block_state") == "1"
            val until = Prefs.getString(this, "block_until", "0").toLongOrNull() ?: 0L
            setWake(on)
            // شبكة أمان: إن أُغلق القطع بأي طريقة أخرى، أعد الإعدادات السابقة
            if (!on && Prefs.getString(this, "tp_active") == "1") { TimedProfile.leave(this); if (Prefs.getString(this, "bg_force", "1") != "1") return }
            val want = if (on && until > 0) until else 0L
            if (want != armedFor) { BgAlarm.schedule(this, want); armedFor = want }
            val text = when {
                !on -> "جاهز في الخلفية"
                until > 0 -> "القطع مفعّل، باقي " + fmt(until - System.currentTimeMillis())
                else -> "القطع مفعّل"
            }
            nm.notify(NOTIF_ID, buildNotif(text))
            // انتهت المدة: استعد الإنترنت من الخدمة نفسها (WebView خلفي) دون انتظار الواجهة أو الشاشة
            val now = System.currentTimeMillis()
            if (on && until > 0 && now >= until) {
                if (!restoring && now >= nextTry) {
                    restoring = true
                    scope.launch {
                        val ok = runCatching { PanelHost.restore(this@KeepAliveService) }.getOrDefault(false)
                        restoring = false
                        if (ok) {
                            failCount = 0
                            notifyRestored(nm)
                            if (Prefs.getString(this@KeepAliveService, "expire_launch", "1") == "1" && BgKeep.canLaunch(this@KeepAliveService))
                                runCatching { startActivity(launchIntent()) }
                            // أعد الدخول التلقائي والعمل في الخلفية إلى وضعهما السابق (قد يوقف الخدمة إن كان مطفأً في الأصل)
                            TimedProfile.leave(this@KeepAliveService)
                            if (Prefs.getString(this@KeepAliveService, "expire_stop_bg", "0") == "1") BgKeep.stop(this@KeepAliveService)
                        } else {
                            failCount++
                            nextTry = System.currentTimeMillis() + 15_000
                            // بعد 3 فشل متتالية نبّه المستخدم وافتح التطبيق يدوياً
                            if (failCount == 3) {
                                notifyExpired(nm)
                                if (BgKeep.canLaunch(this@KeepAliveService)) runCatching { startActivity(launchIntent()) }
                            }
                        }
                    }
                }
            } else { failCount = 0; nextTry = 0L }
            // اقترب موعد الانتهاء: اصحُ عنده بالضبط بدل انتظار 5 ثوانٍ
            val toEnd = if (on && until > 0) until - System.currentTimeMillis() else Long.MAX_VALUE
            delay(if (toEnd in 1..5000) toEnd + 50 else 5000)
        }
    }

    private fun fmt(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0); val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun launchIntent() = Intent(this, WebPanelActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(LockedActivity.EXTRA_AUTO, true)

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, WebPanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun buildNotif(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("ModemControl")
            .setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(openApp())
            .build()

    private fun notifyRestored(nm: NotificationManager) {
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("انتهت مدة القطع")
            .setContentText("أُعيد الإنترنت تلقائياً")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true).setContentIntent(openApp())
            .build()
        nm.notify(EXPIRED_ID, n)
    }

    private fun notifyExpired(nm: NotificationManager) {
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("انتهت مدة قطع الإنترنت")
            .setContentText("افتح التطبيق ليستعيد الإنترنت")
            .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(PendingIntent.getActivity(this, 1, launchIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT), true)
            .setAutoCancel(true).setContentIntent(openApp())
            .build()
        nm.notify(EXPIRED_ID, n)
    }

    private fun setWake(on: Boolean) {
        if (on && wl == null) {
            wl = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "modemcontrol:block").apply { acquire(24 * 60 * 60 * 1000L) }
        } else if (!on && wl != null) { runCatching { wl?.release() }; wl = null }
        // قفل الواي فاي: يمنع النظام من إطفاء الشبكة أثناء السبات فيبقى الاتصال بالمودم يعمل
        if (on && wifi == null) {
            wifi = runCatching {
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                applicationContext.getSystemService(WifiManager::class.java)
                    .createWifiLock(mode, "modemcontrol:wifi").apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        } else if (!on && wifi != null) { runCatching { wifi?.release() }; wifi = null }
    }

    override fun onDestroy() {
        runCatching { wl?.release() }; wl = null
        runCatching { wifi?.release() }; wifi = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "keepalive"
        private const val CHANNEL_ALERT = "expired_alert"
        private const val NOTIF_ID = 41
        private const val EXPIRED_ID = 42

        fun ensureChannel(c: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = c.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL) == null)
                    nm.createNotificationChannel(NotificationChannel(CHANNEL, "العمل في الخلفية", NotificationManager.IMPORTANCE_LOW))
                if (nm.getNotificationChannel(CHANNEL_ALERT) == null)
                    nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "انتهاء مدة القطع", NotificationManager.IMPORTANCE_HIGH))
            }
        }
    }
}

/** تشغيل/إيقاف الخدمة حسب إعداد "العمل في الخلفية بشكل إجباري" وطلب الأذونات اللازمة. */
object BgKeep {
    fun apply(c: Context) {
        val i = Intent(c, KeepAliveService::class.java)
        if (Prefs.getString(c, "bg_force", "1") == "1") runCatching { ContextCompat.startForegroundService(c, i) }
        else c.stopService(i)
    }

    /** يوقف الخدمة الأمامية (والإشعار الدائم) دون تغيير الإعداد؛ تعود تلقائياً عند فتح التطبيق أو تشغيل قطع جديد. */
    fun stop(c: Context) { c.stopService(Intent(c, KeepAliveService::class.java)) }

    /** هل يستطيع التطبيق فتح نفسه من الخلفية؟ (أندرويد 10+ يتطلب إذن "العرض فوق التطبيقات الأخرى"). */
    fun canLaunch(c: Context) = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(c)

    /** يفتح شاشة إذن "العرض فوق التطبيقات الأخرى" لهذا التطبيق. */
    fun askOverlay(a: Activity) {
        runCatching {
            a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + a.packageName)))
        }
    }

    /** يطلب إذن الإشعارات (أندرويد 13+) واستثناء التطبيق من توفير البطارية. */
    fun askExemptions(a: Activity) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(a, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77)
        val pm = a.getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(a.packageName)) {
            runCatching {
                a.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + a.packageName)))
            }
        }
    }
}


/** منبّه من نوع AlarmClock: يوقظ الجهاز حتى في السبات العميق ولا يخضع لتقييد Doze ولا يحتاج إذن المنبّهات الدقيقة. */
object BgAlarm {
    private fun wakePi(c: Context) = PendingIntent.getBroadcast(
        c, 7, Intent(c, BgReceiver::class.java).setAction(BgReceiver.ACTION_WAKE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun showPi(c: Context) = PendingIntent.getActivity(
        c, 8, Intent(c, WebPanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(c: Context, atMs: Long) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (atMs <= 0L) { am.cancel(wakePi(c)); return }
        runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(atMs, showPi(c)), wakePi(c)) }
    }
}
