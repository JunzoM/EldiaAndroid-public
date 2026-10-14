package jp.eldia.hotel.core

import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.seconds

/** 直接 HTTP を呼ぶとき用のログイン情報（スリープ明けで期限切れのトークンを送らないため） */
object Session {
    private val refreshLock = Mutex()

    /**
     * いまのログインのアクセストークン。残り60秒以内なら先に更新してから返す。
     * ログインしていなければ null。更新に失敗したら（電波なし等）手元のトークンをそのまま返す
     */
    suspend fun freshAccessToken(): String? {
        val auth = SB.main.auth
        val s = auth.currentSessionOrNull() ?: return null
        if (s.expiresAt - Clock.System.now() > 60.seconds) return s.accessToken
        return refreshLock.withLock {
            // 待っている間に他で更新済みならそれを使う
            val cur = auth.currentSessionOrNull() ?: return@withLock null
            if (cur.expiresAt - Clock.System.now() > 60.seconds) return@withLock cur.accessToken
            try {
                auth.refreshCurrentSession()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withLock cur.accessToken
            }
            auth.currentSessionOrNull()?.accessToken ?: cur.accessToken
        }
    }
}

/** Session.freshAccessToken() の短縮形 */
suspend fun freshAccessToken(): String? = Session.freshAccessToken()
