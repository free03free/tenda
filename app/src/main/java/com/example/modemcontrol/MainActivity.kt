package com.example.modemcontrol

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : LockedActivity() {
    private lateinit var etIp: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var tvStatus: TextView
    private lateinit var dot: android.view.View
    private var autoOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        etIp = findViewById(R.id.etIp)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        tvStatus = findViewById(R.id.tvStatus)
        dot = findViewById(R.id.statusDot)
        etIp.setText(Prefs.getString(this, "ip", "192.168.0.1"))
        etUser.setText(Prefs.getString(this, "user", "admin"))
        etPass.setText(Prefs.getString(this, "pass"))
        setStatus("لم يتم اختبار الاتصال بعد", R.color.neutral)

        findViewById<android.view.View>(R.id.btnSave).setOnClickListener { if (save()) setStatus("تم حفظ البيانات", R.color.success) }
        findViewById<android.view.View>(R.id.btnTest).setOnClickListener { test() }
        findViewById<android.view.View>(R.id.btnPanel).setOnClickListener { if (save()) openPanel() }
        findViewById<android.view.View>(R.id.btnReboot).setOnClickListener { confirmReboot() }
        findViewById<android.view.View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    /** الفتح التلقائي للوحة المودم مرة واحدة بعد فتح القفل. */
    override fun onReady() {
        if (autoOpened) return
        autoOpened = true
        if (Prefs.getString(this, "auto_open", "0") == "1" &&
            ModemClient.normalize(Prefs.getString(this, "ip")) != null) openPanel()
    }

    private fun openPanel() = startActivity(Intent(this, WebPanelActivity::class.java))

    private fun setStatus(text: String, color: Int) {
        tvStatus.text = text
        dot.background.mutate().setTint(ContextCompat.getColor(this, color))
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
        setStatus("جارٍ الاختبار...", R.color.warn)
        lifecycleScope.launch {
            val r = ModemClient.check(Prefs.getString(this@MainActivity, "ip"), etUser.text.toString(), etPass.text.toString())
            r.fold({ setStatus("المودم متصل (HTTP $it)", R.color.success) },
                { setStatus("فشل الاتصال: ${it.message}", R.color.danger) })
        }
    }

    private fun confirmReboot() {
        if (!save()) return
        val path = Prefs.getString(this, "reboot_path")
        if (path.isBlank()) { setStatus("حدّد مسار إعادة التشغيل من الإعدادات أولاً", R.color.warn); return }
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
                    r.fold({ setStatus("أُرسل الأمر (HTTP $it)", R.color.success) },
                        { setStatus("فشل: ${it.message}", R.color.danger) })
                }
            }.show()
    }
}
