package jp.eldia.hotel.feature.fridge

import android.content.Context
import android.util.Base64
import io.github.jan.supabase.postgrest.postgrest
import jp.eldia.hotel.core.Offline
import jp.eldia.hotel.core.SB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class PinResult(val message: String?) {
    OK(null),
    WRONG("パスワードが違います"),
    LOCKED("間違いが続いたため、10分ほど待ってからお試しください"),
    UNAVAILABLE("サーバーに接続できません。電波を確認してください"),
}

/**
 * 管理用パスワード（iOS版 AppPins と同じ）。
 * パスワードはアプリに持たず、サーバーの verify_app_pin で照合する。
 * 電波がないときに備えて、サーバーで確認できたパスワードを PBKDF2（端末ごとのソルト付き）で端末に残す。
 * オフライン照合はキャッシュが30日以内のときだけ（5回間違えると10分ロック）。
 */
object FridgePin {
    private const val KIND = "fridge_admin"
    private const val PREFS = "eldia_fridge_pin"
    // 旧形式（ソルトなし SHA-256）のキー。見つけたら削除する
    private const val KEY_LEGACY_DIGEST = "pin_digest_$KIND"
    private const val KEY_HASH = "pin_pbkdf2_$KIND"
    private const val KEY_SALT = "pin_salt"
    private const val KEY_CACHED_AT = "pin_cached_at_$KIND"
    private const val KEY_FAILS = "pin_offline_fails"
    private const val KEY_LOCK = "pin_offline_lock_until"

    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 32 * 8
    private const val SALT_BYTES = 16
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // 旧形式のキャッシュは使わずに消す（一度はオンラインで確認が必要）
    private fun dropLegacy(ctx: Context) {
        val p = prefs(ctx)
        if (p.contains(KEY_LEGACY_DIGEST)) p.edit().remove(KEY_LEGACY_DIGEST).apply()
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun unb64(s: String): ByteArray? = try {
        Base64.decode(s, Base64.NO_WRAP)
    } catch (e: IllegalArgumentException) {
        null
    }

    /** 端末ごとのソルト（なければ作る） */
    private fun salt(ctx: Context): ByteArray {
        val p = prefs(ctx)
        p.getString(KEY_SALT, null)?.let { unb64(it) }?.takeIf { it.size == SALT_BYTES }?.let { return it }
        val s = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        p.edit().putString(KEY_SALT, b64(s)).apply()
        return s
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec("$KIND:$pin".toCharArray(), salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    suspend fun verify(ctx: Context, pin: String): PinResult {
        val p = prefs(ctx)
        dropLegacy(ctx)
        return try {
            val ok = SB.main.postgrest.rpc("verify_app_pin", buildJsonObject {
                put("p_kind", KIND)
                put("p_pin", pin)
            }).decodeAs<Boolean>()
            if (ok) {
                // オンラインで確認できたらキャッシュを更新
                val hash = withContext(Dispatchers.Default) { derive(pin, salt(ctx)) }
                p.edit()
                    .putString(KEY_HASH, b64(hash))
                    .putLong(KEY_CACHED_AT, System.currentTimeMillis())
                    .remove(KEY_FAILS).remove(KEY_LOCK)
                    .apply()
            }
            if (ok) PinResult.OK else PinResult.WRONG
        } catch (e: Exception) {
            when {
                Offline.isOffline(e) -> withContext(Dispatchers.Default) { verifyOffline(ctx, pin) }
                e.message?.contains("too many attempts") == true -> PinResult.LOCKED
                else -> PinResult.UNAVAILABLE
            }
        }
    }

    private fun verifyOffline(ctx: Context, pin: String): PinResult {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val cached = p.getString(KEY_HASH, null)?.let { unb64(it) } ?: return PinResult.UNAVAILABLE
        val cachedAt = p.getLong(KEY_CACHED_AT, 0L)
        // 30日を過ぎた（または時計が戻った）キャッシュは使わない
        if (cachedAt <= 0L || now - cachedAt > MAX_AGE_MS || cachedAt > now) {
            p.edit().remove(KEY_HASH).remove(KEY_CACHED_AT).apply()
            return PinResult.UNAVAILABLE
        }
        val lockUntil = p.getLong(KEY_LOCK, 0L)
        if (lockUntil > now) return PinResult.LOCKED
        val saltStr = p.getString(KEY_SALT, null) ?: return PinResult.UNAVAILABLE
        val salt = unb64(saltStr) ?: return PinResult.UNAVAILABLE
        if (MessageDigest.isEqual(cached, derive(pin, salt))) {
            p.edit().remove(KEY_FAILS).remove(KEY_LOCK).apply()
            return PinResult.OK
        }
        val count = (if (lockUntil == 0L) p.getInt(KEY_FAILS, 0) else 0) + 1
        return if (count >= 5) {
            p.edit().putInt(KEY_FAILS, 0).putLong(KEY_LOCK, now + 10 * 60 * 1000).apply()
            PinResult.LOCKED
        } else {
            p.edit().putInt(KEY_FAILS, count).remove(KEY_LOCK).apply()
            PinResult.WRONG
        }
    }
}
