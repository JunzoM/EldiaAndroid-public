package jp.eldia.hotel.core

import android.content.Context
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * 電波がないときの一時保存（iOS版の Offline に相当）。
 * 送れなかった記録を端末に保存しておき、電波が戻ったら送る。
 */
object Offline {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("eldia_offline", Context.MODE_PRIVATE)

    fun <T> load(ctx: Context, key: String, serializer: KSerializer<T>): List<T> =
        prefs(ctx).getString(key, null)?.let {
            runCatching { json.decodeFromString(ListSerializer(serializer), it) }.getOrNull()
        } ?: emptyList()

    fun <T> save(ctx: Context, key: String, serializer: KSerializer<T>, items: List<T>) {
        prefs(ctx).edit().putString(key, json.encodeToString(ListSerializer(serializer), items)).apply()
    }

    /**
     * 通信できなかった（サーバーに届かなかった）エラーか。
     * ログインの期限切れ（401・JWT expired）もここに含める：電波が戻ってログインが更新されれば送れるので、
     * 記録は捨てずに未送信として残す
     */
    fun isOffline(e: Throwable): Boolean =
        e is HttpRequestException || e is HttpRequestTimeoutException || e is IOException ||
            isLoginExpired(e) || (e.cause?.let { isOffline(it) } ?: false)

    /** ログインの期限切れで断られたエラーか */
    private fun isLoginExpired(e: Throwable): Boolean =
        e is RestException && (e.statusCode == 401 ||
            e.message?.contains("JWT expired", ignoreCase = true) == true ||
            e.message?.contains("PGRST301") == true || e.message?.contains("PGRST303") == true)

    /** 同じ id がサーバーに既にある（前回の送信が実は届いていた）エラーか */
    fun isDuplicate(e: Throwable): Boolean =
        e is RestException && (e.message?.contains("duplicate key", ignoreCase = true) == true ||
            e.message?.contains("23505") == true)
}
