package com.example.modemcontrol

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch

/** شاشة الإعدادات الوحيدة: بيانات المودم، اختبار الاتصال، إعادة التشغيل، قفل النمط. */
class SettingsActivity : LockedActivity() {
    private var pending = 0 // 1 = تغيير النمط، 2 = إزالته
    private lateinit var etIp: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvLock: TextView
    private lateinit var dot: View

    private val createLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) { toast("تم حفظ النمط"); refresh() }
    }
    private val verifyLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode != RESULT_OK) return@registerForActivityResult
        if (pending == 1) launchCreate()
        else { Prefs.clearPattern(this); toast("تمت إزالة النمط"); refresh() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun pattern(mode: Int) = Intent(this, PatternActivity::class.java).putExtra(PatternActivity.EXTRA_MODE, mode)
    private fun launchCreate() = createLauncher.launch(pattern(PatternActivity.MODE_CREATE))
    private fun verifyThen(action: Int) { pending = action; verifyLauncher.launch(pattern(PatternActivity.MODE_VERIFY)) }
    private fun refresh() { tvLock.text = if (Prefs.hasPattern(this)) "قفل النمط: مفعّل" else "قفل النمط: غير مفعّل" }

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

    private fun saveReboot(etPath: EditText, etMethod: EditText, etBody: EditText) {
        Prefs.putString(this, "reboot_path", etPath.text.toString().trim())
        Prefs.putString(this, "reboot_method", if (etMethod.text.toString().trim().equals("POST", true)) "POST" else "GET")
        Prefs.putString(this, "reboot_body", etBody.text.toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        etIp = findViewById(R.id.etIp); etUser = findViewById(R.id.etUser); etPass = findViewById(R.id.etPass)
        tvStatus = findViewById(R.id.tvStatus); tvLock = findViewById(R.id.tvLock); dot = findViewById(R.id.statusDot)
        val etPath = findViewById<EditText>(R.id.etPath)
        val etMethod = findViewById<EditText>(R.id.etMethod)
        val etBody = findViewById<EditText>(R.id.etBody)

        etIp.setText(Prefs.getString(this, "ip", "192.168.0.1"))
        etUser.setText(Prefs.getString(this, "user", "admin"))
        etPass.setText(Prefs.getString(this, "pass"))
        etPath.setText(Prefs.getString(this, "reboot_path"))
        etMethod.setText(Prefs.getString(this, "reboot_method", "GET"))
        etBody.setText(Prefs.getString(this, "reboot_body"))
        setStatus("لم يتم اختبار الاتصال بعد", R.color.neutral)
        refresh()

        for ((id, key, def) in listOf(Triple(R.id.swAutoLogin, "auto_login", "1"), Triple(R.id.swDesktop, "desktop_mode", "0"))) {
            val sw = findViewById<MaterialSwitch>(id)
            sw.isChecked = Prefs.getString(this, key, def) == "1"
            sw.setOnCheckedChangeListener { _, c -> Prefs.putString(this, key, if (c) "1" else "0") }
        }

        findViewById<View>(R.id.btnSave).setOnClickListener { if (save()) setStatus("تم حفظ البيانات", R.color.success) }
        findViewById<View>(R.id.btnTest).setOnClickListener { test() }
        findViewById<View>(R.id.btnReboot).setOnClickListener { saveReboot(etPath, etMethod, etBody); confirmReboot() }
        findViewById<View>(R.id.btnSetPattern).setOnClickListener { if (Prefs.hasPattern(this)) verifyThen(1) else launchCreate() }
        findViewById<View>(R.id.btnRemovePattern).setOnClickListener {
            if (!Prefs.hasPattern(this)) return@setOnClickListener
            AlertDialog.Builder(this).setMessage("إزالة قفل النمط؟")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("إزالة") { _, _ -> verifyThen(2) }.show()
        }
        findViewById<View>(R.id.btnSaveReboot).setOnClickListener { saveReboot(etPath, etMethod, etBody); toast("تم الحفظ") }
        findViewById<View>(R.id.btnDone).setOnClickListener {
            saveReboot(etPath, etMethod, etBody)
            if (save()) finish()
        }
    }

    private fun test() {
        if (!save()) return
        setStatus("جارٍ الاختبار...", R.color.warn)
        lifecycleScope.launch {
            ModemClient.check(Prefs.getString(this@SettingsActivity, "ip"), etUser.text.toString(), etPass.text.toString())
                .fold({ setStatus("المودم متصل (HTTP $it)", R.color.success) },
                    { setStatus("فشل الاتصال: ${it.message}", R.color.danger) })
        }
    }

    private fun confirmReboot() {
        if (!save()) return
        val path = Prefs.getString(this, "reboot_path")
        if (path.isBlank()) { setStatus("حدّد مسار إعادة التشغيل أولاً", R.color.warn); return }
        AlertDialog.Builder(this).setMessage("هل تريد إعادة تشغيل المودم؟")
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("نعم") { _, _ ->
                lifecycleScope.launch {
                    ModemClient.reboot(
                        Prefs.getString(this@SettingsActivity, "ip"), path,
                        Prefs.getString(this@SettingsActivity, "reboot_method", "GET"),
                        Prefs.getString(this@SettingsActivity, "reboot_body"),
                        etUser.text.toString(), etPass.text.toString())
                        .fold({ setStatus("أُرسل الأمر (HTTP $it)", R.color.success) },
                            { setStatus("فشل: ${it.message}", R.color.danger) })
                }
            }.show()
    }
}
