package com.example.modemcontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** يعيد تشغيل الخدمة الأمامية بعد إقلاع الجهاز أو تحديث التطبيق أو عند رنين منبّه انتهاء المدة. */
class BgReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        BgKeep.apply(c)
    }
    companion object { const val ACTION_WAKE = "com.example.modemcontrol.WAKE" }
}
