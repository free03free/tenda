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
import com.google.android.material.slider.Slider
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

    private val blockFields = listOf(
        Triple("block_on", R.id.etOnPath, Pair(R.id.etOnMethod, R.id.etOnBody)),
        Triple("block_off", R.id.etOffPath, Pair(R.id.etOffMethod, R.id.etOffBody)))

    private fun loadBlock() {
        for ((k, path, mb) in blockFields) {
            findViewById<EditText>(path).setText(Prefs.getString(this, "${k}_path"))
            findViewById<EditText>(mb.first).setText(Prefs.getString(this, "${k}_method", "GET"))
            findViewById<EditText>(mb.second).setText(Prefs.getString(this, "${k}_body"))
        }
    }

    private fun saveBlock() {
        for ((k, path, mb) in blockFields) {
            Prefs.putString(this, "${k}_path", findViewById<EditText>(path).text.toString().trim())
            Prefs.putString(this, "${k}_method",
                if (findViewById<EditText>(mb.first).text.toString().trim().equals("POST", true)) "POST" else "GET")
            Prefs.putString(this, "${k}_body", findViewById<EditText>(mb.second).text.toString())
        }
    }

    private fun renderExempt() {
        val box = findViewById<android.widget.LinearLayout>(R.id.exemptList)
        box.removeAllViews()
        val list = Exempt.list(this)
        if (list.isEmpty()) {
            box.addView(TextView(this).apply { text = "لا توجد أجهزة مستثناة"; alpha = 0.6f })
            return
        }
        for (d in list) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply { text = "${d.name}\n${d.mac}"; textDirection = View.TEXT_DIRECTION_LTR },
                android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(com.google.android.material.button.MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = "حذف"
                setOnClickListener { Exempt.remove(this@SettingsActivity, d.mac); exemptChanged() }
            })
            box.addView(row)
        }
    }

    /** بعد أي تعديل للقائمة: إن كان القطع مفعّلاً يُعاد إرسال طلب القطع ليشمل الاستثناءات الجديدة. */
    private fun exemptChanged() {
        renderExempt()
        if (Prefs.getString(this, "block_state") != "1") return
        lifecycleScope.launch {
            PanelBlock.setBlock(this@SettingsActivity, true)
                .fold({ toast("حُدّث القطع مع الاستثناءات"); PanelBlock.web?.get()?.reload() }, { toast("فشل تحديث القطع: ${it.message}") })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        renderExempt()
        findViewById<View>(R.id.btnAddExempt).setOnClickListener {
            val etName = findViewById<EditText>(R.id.etExName)
            val etMac = findViewById<EditText>(R.id.etExMac)
            if (!Exempt.add(this, etName.text.toString(), etMac.text.toString())) {
                etMac.error = "عنوان MAC غير صالح"; return@setOnClickListener
            }
            etName.text.clear(); etMac.text.clear()
            exemptChanged()
        }
        // العمل في الخلفية بشكل إجباري + تأكيد/تحديث الصفحة بعد كل أمر
        findViewById<MaterialSwitch>(R.id.swBgForce).apply {
            isChecked = Prefs.getString(this@SettingsActivity, "bg_force", "1") == "1"
            setOnCheckedChangeListener { _, c ->
                Prefs.putString(this@SettingsActivity, "bg_force", if (c) "1" else "0")
                BgKeep.apply(this@SettingsActivity)
                if (c) BgKeep.askExemptions(this@SettingsActivity)
            }
        }
        findViewById<MaterialSwitch>(R.id.swExpireLaunch).apply {
            isChecked = Prefs.getString(this@SettingsActivity, "expire_launch", "1") == "1"
            setOnCheckedChangeListener { _, c ->
                Prefs.putString(this@SettingsActivity, "expire_launch", if (c) "1" else "0")
                if (c && !BgKeep.canLaunch(this@SettingsActivity)) {
                    toast("فعّل \"العرض فوق التطبيقات الأخرى\" لـ ModemControl ثم ارجع")
                    BgKeep.askOverlay(this@SettingsActivity)
                }
            }
        }
        findViewById<MaterialSwitch>(R.id.swExpireStopBg).apply {
            isChecked = Prefs.getString(this@SettingsActivity, "expire_stop_bg", "0") == "1"
            setOnCheckedChangeListener { _, c -> Prefs.putString(this@SettingsActivity, "expire_stop_bg", if (c) "1" else "0") }
        }
        findViewById<MaterialSwitch>(R.id.swRefreshAfter).apply {
            isChecked = Prefs.getString(this@SettingsActivity, "refresh_after", "1") == "1"
            setOnCheckedChangeListener { _, c -> Prefs.putString(this@SettingsActivity, "refresh_after", if (c) "1" else "0") }
        }
        // الفحص الدوري للأجهزة الجديدة: تشغيل/إيقاف وفاصل مخصص بالثواني
        findViewById<MaterialSwitch>(R.id.swSweep).apply {
            isChecked = Prefs.getString(this@SettingsActivity, "sweep_on", "1") == "1"
            setOnCheckedChangeListener { _, c -> Prefs.putString(this@SettingsActivity, "sweep_on", if (c) "1" else "0") }
        }
        findViewById<MaterialSwitch>(R.id.swSweepRefresh).apply {
            isChecked = Prefs.getString(this@SettingsActivity, "sweep_refresh", "1") == "1"
            setOnCheckedChangeListener { _, c -> Prefs.putString(this@SettingsActivity, "sweep_refresh", if (c) "1" else "0") }
        }
        findViewById<EditText>(R.id.etSweepSecs).apply {
            setText(Prefs.getString(this@SettingsActivity, "sweep_secs", "30"))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val n = s?.toString()?.toLongOrNull()
                    if (n != null) Prefs.putString(this@SettingsActivity, "sweep_secs", n.coerceIn(5L, 3600L).toString())
                }
            })
        }
        // اختيار طريقة القطع: التحكم الأبوي (الافتراضي) أو فلتر MAC
        findViewById<android.widget.RadioGroup>(R.id.rgBlockMethod).apply {
            check(if (Prefs.getString(this@SettingsActivity, "block_method", "parental") == "mac") R.id.rbMethodMac else R.id.rbMethodParental)
            setOnCheckedChangeListener { _, id ->
                Prefs.putString(this@SettingsActivity, "block_method", if (id == R.id.rbMethodMac) "mac" else "parental")
            }
        }
        // طلبات القطع صارت تُنفَّذ من داخل لوحة المودم نفسها، فأخفي بطاقة إعداد المسارات القديمة
        (findViewById<View>(R.id.btnSaveBlock).parent.parent as View).visibility = View.GONE
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

        val slider = findViewById<Slider>(R.id.sbWidth)
        val tvWidth = findViewById<TextView>(R.id.tvWidth)
        fun widthLabel(w: Int) = "عرض الصفحة: $w بكسل (الرقم الأقل = حجم أكبر)"
        val saved = (Prefs.getString(this, "desktop_width", "900").toIntOrNull() ?: 900).coerceIn(600, 1400)
        val w0 = (saved - 600) / 50 * 50 + 600
        slider.value = w0.toFloat()
        tvWidth.text = widthLabel(w0)
        slider.addOnChangeListener { _, v, _ ->
            tvWidth.text = widthLabel(v.toInt())
            Prefs.putString(this, "desktop_width", v.toInt().toString())
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
            saveBlock()
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
