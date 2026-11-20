package com.example.modemcontrol

import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class PatternActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_VERIFY = 0
        const val MODE_CREATE = 1
        private const val MAX_FAILS = 5
        private const val LOCK_MS = 30_000L
    }

    private lateinit var pv: PatternView
    private lateinit var msg: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var timer: CountDownTimer? = null
    private var first: List<Int>? = null
    private var fails = 0
    private var mode = MODE_VERIFY

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pattern)
        mode = intent.getIntExtra(EXTRA_MODE, MODE_VERIFY)
        pv = findViewById(R.id.pattern)
        msg = findViewById(R.id.msg)
        findViewById<TextView>(R.id.title).text =
            if (mode == MODE_CREATE) "ارسم نمطاً جديداً" else "ارسم النمط للدخول"
        findViewById<Button>(R.id.btnCancel).setOnClickListener { cancel() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancel()
        })
        pv.onComplete = { if (mode == MODE_CREATE) create(it) else verify(it) }
        if (mode == MODE_VERIFY) checkLock()
    }

    private fun cancel() { setResult(RESULT_CANCELED); finish() }

    private fun err(text: String) {
        msg.text = text
        pv.showError()
        handler.postDelayed({ pv.reset() }, 700)
    }

    private fun verify(p: List<Int>) {
        if (Prefs.checkPattern(this, p)) { setResult(RESULT_OK); finish(); return }
        fails++
        if (fails >= MAX_FAILS) {
            fails = 0
            Prefs.putLong(this, "lock_until", System.currentTimeMillis() + LOCK_MS)
            checkLock()
        } else err("نمط خاطئ (${MAX_FAILS - fails} محاولات متبقية)")
    }

    private fun checkLock() {
        val remain = Prefs.getLong(this, "lock_until") - System.currentTimeMillis()
        if (remain <= 0) return
        pv.inputEnabled = false
        pv.reset()
        timer = object : CountDownTimer(remain, 1000) {
            override fun onTick(ms: Long) { msg.text = "محاولات كثيرة، انتظر ${ms / 1000 + 1} ثانية" }
            override fun onFinish() { pv.inputEnabled = true; msg.text = "" }
        }.start()
    }

    private fun create(p: List<Int>) {
        val f = first
        when {
            p.size < 4 -> err("اربط 4 نقاط على الأقل")
            f == null -> { first = p; msg.text = "أعد رسم النمط للتأكيد"; pv.reset() }
            f == p -> { Prefs.setPattern(this, p); Session.unlocked = true; setResult(RESULT_OK); finish() }
            else -> { first = null; err("النمطان غير متطابقين، ابدأ من جديد") }
        }
    }

    override fun onDestroy() {
        timer?.cancel()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
