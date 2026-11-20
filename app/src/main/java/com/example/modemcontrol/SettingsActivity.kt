package com.example.modemcontrol

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog

class SettingsActivity : LockedActivity() {
    private var pending = 0 // 1 = تغيير النمط، 2 = إزالته
    private lateinit var tvLock: TextView

    private val createLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) { toast("تم حفظ النمط"); refresh() }
    }
    private val verifyLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode != RESULT_OK) return@registerForActivityResult
        if (pending == 1) launchCreate()
        else { Prefs.clearPattern(this); toast("تمت إزالة النمط"); refresh() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun launchCreate() = createLauncher.launch(
        Intent(this, PatternActivity::class.java).putExtra(PatternActivity.EXTRA_MODE, PatternActivity.MODE_CREATE))

    private fun verifyThen(action: Int) {
        pending = action
        verifyLauncher.launch(
            Intent(this, PatternActivity::class.java).putExtra(PatternActivity.EXTRA_MODE, PatternActivity.MODE_VERIFY))
    }

    private fun refresh() {
        tvLock.text = if (Prefs.hasPattern(this)) "قفل النمط: مفعّل" else "قفل النمط: غير مفعّل"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        tvLock = findViewById(R.id.tvLock)
        val etPath = findViewById<EditText>(R.id.etPath)
        val etMethod = findViewById<EditText>(R.id.etMethod)
        val etBody = findViewById<EditText>(R.id.etBody)
        etPath.setText(Prefs.getString(this, "reboot_path"))
        etMethod.setText(Prefs.getString(this, "reboot_method", "GET"))
        etBody.setText(Prefs.getString(this, "reboot_body"))
        refresh()

        findViewById<android.view.View>(R.id.btnSetPattern).setOnClickListener {
            if (Prefs.hasPattern(this)) verifyThen(1) else launchCreate()
        }
        findViewById<android.view.View>(R.id.btnRemovePattern).setOnClickListener {
            if (!Prefs.hasPattern(this)) return@setOnClickListener
            AlertDialog.Builder(this).setMessage("إزالة قفل النمط؟")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("إزالة") { _, _ -> verifyThen(2) }.show()
        }
        findViewById<android.view.View>(R.id.btnSaveReboot).setOnClickListener {
            Prefs.putString(this, "reboot_path", etPath.text.toString().trim())
            Prefs.putString(this, "reboot_method",
                if (etMethod.text.toString().trim().equals("POST", true)) "POST" else "GET")
            Prefs.putString(this, "reboot_body", etBody.text.toString())
            toast("تم الحفظ")
        }
    }
}
