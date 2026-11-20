package com.example.modemcontrol

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/** أي شاشة ترث منه تطلب النمط (مرة واحدة فقط) إن كان مفعّلاً ولم يُفتح القفل بعد. */
abstract class LockedActivity : AppCompatActivity() {
    private var pending = false

    private val unlock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pending = false
        if (it.resultCode == RESULT_OK) { Session.unlocked = true; onReady() } else finishAffinity()
    }

    /** تُستدعى عندما تصبح الشاشة جاهزة (القفل غير مطلوب أو فُتح). */
    protected open fun onReady() {}

    override fun onStart() {
        super.onStart()
        if (pending) return // onStart يسبق وصول نتيجة النمط، فلا نطلبه مرة ثانية
        if (Prefs.hasPattern(this) && !Session.unlocked) {
            pending = true
            unlock.launch(Intent(this, PatternActivity::class.java)
                .putExtra(PatternActivity.EXTRA_MODE, PatternActivity.MODE_VERIFY))
        } else onReady()
    }
}
