package com.example.modemcontrol

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/** أي شاشة ترث منه تطلب النمط (مرة واحدة فقط) إن كان مفعّلاً ولم يُفتح القفل بعد. */
abstract class LockedActivity : AppCompatActivity() {
    private var pending = false

    /** true عندما فتحه النظام تلقائياً لاستعادة الإنترنت (انتهاء مدة القطع) لا المستخدم. */
    private var autoMode = false

    private val unlock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pending = false
        when {
            it.resultCode == RESULT_OK -> { Session.unlocked = true; autoMode = false; onReady() }
            // فتح تلقائي ثم ألغى المستخدم النمط: أخفِ التطبيق فقط ولا تُغلقه، كي تكتمل الاستعادة في الخلفية
            autoMode -> { autoMode = false; moveTaskToBack(true) }
            else -> finishAffinity()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /** تُستدعى عندما تصبح الشاشة جاهزة (القفل غير مطلوب أو فُتح). */
    protected open fun onReady() {}

    override fun onStart() {
        super.onStart()
        if (pending) return // onStart يسبق وصول نتيجة النمط، فلا نطلبه مرة ثانية
        if (Prefs.hasPattern(this) && !Session.unlocked) {
            pending = true
            // فتح تلقائي للاستعادة: نشغّل تهيئة الشاشة (تحميل لوحة المودم) أسفل شاشة النمط دون كشف أي محتوى،
            // فتتم الاستعادة بلا حاجة لإدخال النمط. شاشة النمط تبقى فوقها ولا يُتجاوز القفل لعرض المحتوى.
            if (intent?.getBooleanExtra(EXTRA_AUTO, false) == true) {
                intent.removeExtra(EXTRA_AUTO)
                autoMode = true
                onReady()
            }
            unlock.launch(Intent(this, PatternActivity::class.java)
                .putExtra(PatternActivity.EXTRA_MODE, PatternActivity.MODE_VERIFY))
        } else onReady()
    }

    companion object { const val EXTRA_AUTO = "auto_restore" }
}
