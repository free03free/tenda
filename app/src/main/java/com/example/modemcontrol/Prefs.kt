package com.example.modemcontrol

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** تخزين مشفّر لكل الإعدادات، والنمط يُحفظ كـ hash مملّح فقط. */
object Prefs {
    @Volatile private var cached: SharedPreferences? = null

    private fun sp(c: Context): SharedPreferences = cached ?: synchronized(this) {
        cached ?: EncryptedSharedPreferences.create(
            c.applicationContext, "secure_prefs",
            MasterKey.Builder(c.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).also { cached = it }
    }

    fun getString(c: Context, k: String, d: String = "") = sp(c).getString(k, d) ?: d
    fun putString(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()
    fun getLong(c: Context, k: String) = sp(c).getLong(k, 0L)
    fun putLong(c: Context, k: String, v: Long) = sp(c).edit().putLong(k, v).apply()

    private fun hash(p: List<Int>, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(p.joinToString("").toCharArray(), salt, 20000, 256)).encoded

    fun hasPattern(c: Context) = getString(c, "pat_hash").isNotEmpty()

    fun setPattern(c: Context, p: List<Int>) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        putString(c, "pat_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
        putString(c, "pat_hash", Base64.encodeToString(hash(p, salt), Base64.NO_WRAP))
    }

    fun checkPattern(c: Context, p: List<Int>): Boolean {
        val salt = Base64.decode(getString(c, "pat_salt"), Base64.NO_WRAP)
        val saved = Base64.decode(getString(c, "pat_hash"), Base64.NO_WRAP)
        return MessageDigest.isEqual(saved, hash(p, salt))
    }

    fun clearPattern(c: Context) { putString(c, "pat_hash", ""); putString(c, "pat_salt", "") }
}
