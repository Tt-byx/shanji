package com.shanji.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.biometric.BiometricManager
import java.security.SecureRandom

/* 应用锁：生物识别（指纹/人脸）优先，PIN 码兜底。
 * 会话内解锁状态只存内存，进程重启后重新上锁。 */
object LockManager {

    private const val PREFS = "shanji_lock"

    /** 进程内会话是否已解锁 */
    @Volatile
    var unlocked: Boolean = false

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)

    /** 设置/修改 PIN（4~6 位数字），成功后立即进入已解锁会话 */
    fun setPin(context: Context, pin: String): Boolean {
        if (pin.length !in 4..6 || !pin.all { it.isDigit() }) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(context).edit()
            .putBoolean("enabled", true)
            .putInt("pinLen", pin.length)
            .putString("salt", salt.joinToString(","))
            .putString("hash", hash(pin, salt))
            .apply()
        unlocked = true
        return true
    }

    /** PIN 长度（用于输入满位后自动校验） */
    fun pinLength(context: Context): Int = prefs(context).getInt("pinLen", 4)

    fun disable(context: Context) {
        prefs(context).edit().putBoolean("enabled", false).apply()
        unlocked = false
    }

    fun verify(context: Context, pin: String): Boolean {
        val p = prefs(context)
        val salt = p.getString("salt", null)?.split(",")?.map { it.toByte() }?.toByteArray()
            ?: return false
        val hash = p.getString("hash", null) ?: return false
        return hash(pin, salt) == hash
    }

    private fun hash(pin: String, salt: ByteArray): String {
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, 12_000, 256)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        return factory.generateSecret(spec).encoded.joinToString("") { "%02x".format(it) }
    }

    /** 设备是否具备可用的生物识别或锁屏凭据 */
    fun hasBiometric(context: Context): Boolean = runCatching {
        BiometricManager.from(context)
            .canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            ) == BiometricManager.BIOMETRIC_SUCCESS
    }.getOrDefault(false)
}
