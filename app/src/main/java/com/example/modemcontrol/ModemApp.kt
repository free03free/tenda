package com.example.modemcontrol

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner

object Session { @Volatile var unlocked = false }

class ModemApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // عند خروج التطبيق للخلفية يُقفل من جديد
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) Session.unlocked = false
        })
    }
}
