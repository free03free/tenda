package com.example.modemcontrol

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : LockedActivity() {
    private lateinit var etIp: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        etIp = findViewById(R.id.etIp)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        tvStatus = findViewById(R.id.tvStatus)
        etIp.setText(Prefs.getString(this, "ip", "192.168.1.1"))
        etUser.setText(Prefs.getString(this, "user", "admin"))
        etPass.setText(Prefs.getString(this, "pass"))

        findViewById<android.view.View>(R.id.btnSave).setOnClickListener { if (save()) tvStatus.text = "تم الحفظ" }
        findViewById<android.view.View>(R.id.btnTest).setOnClickListener { test() }
        findViewById<android.view.View>(R.id.btnPanel).setOnClickListener {
            if (save()) startActivity(Intent(this, WebPanelActivity::class.java))
        }
        findViewById<android.view.View>(R.id.btnReboot).setOnClickListener { confirmReboot() }
        findViewById<android.view.View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun save(): Boolean {
        val host = ModemClient.normalize(etIp.text.toString())
        if (host == null) { etIp.error = "عنوان IP غير صالح"; return false }
        Prefs.putString(this, "ip", host)
        Prefs.putString(this, "user", etUser.text.toString())
        Prefs.putString(this, "pass", etPass.text.toString())
        return true
    }

    private fun test() {
        if (!save()) return
        tvStatus.text = "جارٍ الاختبار..."
        lifecycleScope.launch {
            val r = ModemClient.check(Prefs.getString(this@MainActivity, "ip"), etUser.text.toString(), etPass.text.toString())
            tvStatus.text = r.fold({ "المودم متصل (HTTP $it)" }, { "فشل الاتصال: ${it.message}" })
        }
    }

    private fun confirmReboot() {
        if (!save()) return
        val path = Prefs.getString(this, "reboot_path")
        if (path.isBlank()) { tvStatus.text = "حدّد مسار إعادة التشغيل من الإعدادات أولاً"; return }
        AlertDialog.Builder(this)
            .setMessage("هل تريد إعادة تشغيل المودم؟")
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("نعم") { _, _ ->
                lifecycleScope.launch {
                    val r = ModemClient.reboot(
                        Prefs.getString(this@MainActivity, "ip"), path,
                        Prefs.getString(this@MainActivity, "reboot_method", "GET"),
                        Prefs.getString(this@MainActivity, "reboot_body"),
                        etUser.text.toString(), etPass.text.toString())
                    tvStatus.text = r.fold({ "أُرسل الأمر (HTTP $it)" }, { "فشل: ${it.message}" })
                }
            }.show()
    }
}
