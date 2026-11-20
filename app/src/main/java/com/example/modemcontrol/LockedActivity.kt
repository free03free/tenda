package com.example.modemcontrol

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/** أي شاشة ترث من هذا الصنف تطلب النمط إن كان مفعّلاً ولم يُفتح القفل بعد. */
abstract class LockedActivity : AppCompatActivity() {
    private val unlock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) Session.unlocked = true else finishAffinity()
    }

    override fun onStart() {
        super.onStart()
        if (Prefs.hasPattern(this) && !Session.unlocked)
            unlock.launch(Intent(this, PatternActivity::class.java)
                .putExtra(PatternActivity.EXTRA_MODE, PatternActivity.MODE_VERIFY))
    }
}
