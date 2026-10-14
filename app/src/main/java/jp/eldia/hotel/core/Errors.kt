package jp.eldia.hotel.core

import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException

private const val NETWORK_MESSAGE = "通信できませんでした。電波状況を確認してください"

/** 画面に出すエラーメッセージ（日本語） */
fun Throwable.userMessage(): String = when (this) {
    is AuthRestException -> when {
        message?.contains("Invalid login credentials", ignoreCase = true) == true ||
            message?.contains("invalid_credentials", ignoreCase = true) == true ->
            "メールアドレスまたはパスワードが違います"
        else -> message ?: "ログインできませんでした"
    }
    // ログインの期限切れ（401・JWT expired）
    is RestException -> if (statusCode == 401 ||
        message?.contains("JWT expired", ignoreCase = true) == true ||
        message?.contains("PGRST301") == true || message?.contains("PGRST303") == true
    ) "ログインの有効期限が切れました。もう一度お試しください"
    // メッセージの2行目以降は URL・ヘッダー（トークンを含む）なので出さない
    else message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: error
    is LoginRefreshPending -> "ログインを確認しています。電波の良い場所で少し待ってからお試しください"
    is HttpRequestException -> if (cause is LoginRefreshPending) cause!!.userMessage() else NETWORK_MESSAGE
    is HttpRequestTimeoutException, is ConnectTimeoutException, is IOException -> NETWORK_MESSAGE
    else -> message ?: "エラーが発生しました"
}
