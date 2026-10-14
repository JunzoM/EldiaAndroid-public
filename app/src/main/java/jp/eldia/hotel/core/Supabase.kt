package jp.eldia.hotel.core

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import io.ktor.client.engine.okhttp.OkHttp
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * ログインの更新を待っている（電波が悪くてトークンの更新に失敗している）間に、
 * データベース・写真の読み書きをしようとしたときのエラー。
 * IOException なので「通信できなかった」と同じ扱いになる（保存は未送信に回し、読み込みは前の表示を残す）
 */
class LoginRefreshPending : IOException("ログインの更新を待っています")

/**
 * ログインなし（共通の鍵だけ）でデータベース・写真に行くリクエストを止める（通信の部品 OkHttp に挟む）。
 * SDK はログインの更新に失敗している間、ログインなしで送ってしまう。
 * するとデータベースは「0件」を返し（エラーにならない）、画面が空になったり、保存が「権限なし」で失敗したりする
 */
private val requireLogin = okhttp3.Interceptor { chain ->
    val req = chain.request()
    val path = req.url.encodedPath
    if (path.startsWith("/rest/v1") || path.startsWith("/storage/v1")) {
        val auth = req.header("Authorization")
        if (auth == null || auth == "Bearer ${AppConfig.SUPABASE_ANON_KEY}") throw LoginRefreshPending()
    }
    chain.proceed(req)
}

/** 共通プロジェクトへの接続（アプリ全体で1つ。ログインの保存と自動更新は SDK が行う） */
object SB {
    val main: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = AppConfig.SUPABASE_URL,
            supabaseKey = AppConfig.SUPABASE_ANON_KEY,
        ) {
            install(Auth) {
                // 電波が悪くてログインの更新に失敗したときの再試行の間隔（既定 10 秒）。
                // 失敗している間はサーバーの読み書きがログインなし扱いになるため、早めに復帰させる
                retryDelay = 5.seconds
            }
            // データベース・写真はログインしているときだけ（ログインなしで送らない）
            httpEngine = OkHttp.create { addInterceptor(requireLogin) }
            install(Postgrest)
            install(Realtime)
            install(Storage)
        }
    }
}
