package jp.eldia.hotel.core

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo

/**
 * 最後にログインが確認できたユーザー。
 * 電波が悪くてログインの更新（トークンの更新）に失敗している間は SDK の currentUserOrNull() が null になるため、
 * 画面の表示（担当者名など）はこちらを使う。ログアウトしたら null にする（AuthViewModel が更新）
 */
object KnownUser {
    @Volatile
    var user: UserInfo? = null

    fun current(): UserInfo? = SB.main.auth.currentUserOrNull() ?: user
}
