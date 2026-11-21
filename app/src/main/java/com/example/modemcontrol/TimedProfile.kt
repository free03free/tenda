package com.example.modemcontrol

import android.content.Context

/**
 * ملف مؤقت للقطع المحدد بمدة: عند بدء القطع يُفعَّل الدخول التلقائي والعمل في الخلفية،
 * وعند انتهائه واستعادة الإنترنت (أو إيقاف السويتش يدوياً) يعود كل إعداد إلى قيمته السابقة.
 * القيم السابقة محفوظة في الإعدادات فتبقى سليمة حتى لو قُتل التطبيق أو أُعيد تشغيل الهاتف.
 */
object TimedProfile {
    private const val ACTIVE = "tp_active"
    private const val PREV_LOGIN = "tp_prev_auto_login"
    private const val PREV_BG = "tp_prev_bg_force"

    /** يُستدعى بعد نجاح بدء قطع بمدة محددة. */
    fun enter(c: Context) {
        // لا نحفظ القيم مرتين: لو بدأ قطع جديد أثناء قطع قائم نُبقي القيم الأصلية الأولى
        if (Prefs.getString(c, ACTIVE) != "1") {
            Prefs.putString(c, PREV_LOGIN, Prefs.getString(c, "auto_login", "1"))
            Prefs.putString(c, PREV_BG, Prefs.getString(c, "bg_force", "1"))
            Prefs.putString(c, ACTIVE, "1")
        }
        Prefs.putString(c, "auto_login", "1")
        Prefs.putString(c, "bg_force", "1")
        BgKeep.apply(c)
    }

    /** يُستدعى بعد استعادة الإنترنت وإيقاف السويتش. آمن للاستدعاء أكثر من مرة. */
    fun leave(c: Context) {
        if (Prefs.getString(c, ACTIVE) != "1") return
        Prefs.putString(c, "auto_login", Prefs.getString(c, PREV_LOGIN, "1"))
        Prefs.putString(c, "bg_force", Prefs.getString(c, PREV_BG, "1"))
        Prefs.putString(c, ACTIVE, "0")
        BgKeep.apply(c) // يوقف الخدمة إن كان العمل في الخلفية مطفأً في الأصل، ويُبقيها إن كان مفعّلاً
    }
}
