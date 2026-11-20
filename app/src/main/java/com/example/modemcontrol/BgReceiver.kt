package com.example.modemcontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/** يعيد تشغيل الخدمة الأمامية بعد الإقلاع/التحديث/تغيّر الوقت أو عند رنين منبّه انتهاء المدة (ويوقظ المعالج لحظياً). */
class BgReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val wl = c.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "modemcontrol:receiver")
        runCatching { wl.acquire(30_000L) }
        BgKeep.apply(c)
    }
    companion object { const val ACTION_WAKE = "com.example.modemcontrol.WAKE" }
}
