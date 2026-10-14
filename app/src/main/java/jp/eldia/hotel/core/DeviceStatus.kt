package jp.eldia.hotel.core

import android.content.Context
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import jp.eldia.hotel.BuildConfig
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * この端末の状態（未送信の記録の件数）をサーバーへ知らせる。
 * 月1回の点検（private.run_health_check）が「送れずに残っている記録」を見つけるのに使う
 */
object DeviceStatus {
    private const val PREFS = "eldia_device"
    private const val KEY_ID = "device_id"
    private const val KEY_LAST = "last_report"

    private fun deviceId(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also { p.edit().putString(KEY_ID, it).apply() }
    }

    /**
     * 未送信の件数を報告する（失敗しても何もしない）。
     * 同じ内容なら1時間に1回まで（ホームを開くたびに送らない）
     */
    suspend fun report(ctx: Context, hotel: Hotel, pending: Map<String, Int>) {
        if (SB.main.auth.currentSessionOrNull() == null) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sig = pending.toSortedMap().toString() + hotel.rawValue
        val last = p.getString(KEY_LAST, null)?.split("|", limit = 2)
        if (last != null && last.size == 2 && last[1] == sig &&
            System.currentTimeMillis() - (last[0].toLongOrNull() ?: 0) < 3_600_000
        ) return
        runCatching {
            SB.main.postgrest.rpc("report_device_status", buildJsonObject {
                put("p_device", deviceId(ctx))
                put("p_platform", "android")
                put("p_app_version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                put("p_hotel", hotel.rawValue)
                put("p_pending", buildJsonObject { pending.forEach { (k, v) -> put(k, v) } })
            })
            p.edit().putString(KEY_LAST, "${System.currentTimeMillis()}|$sig").apply()
        }
    }
}
