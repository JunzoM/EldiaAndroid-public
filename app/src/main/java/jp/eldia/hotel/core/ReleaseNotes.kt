package jp.eldia.hotel.core

import android.content.Context
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import jp.eldia.hotel.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * アップデートのお知らせ（更新後に1回だけ出す）。iOS版と共通。
 * 文章は共通 Supabase の app_settings「release_notes」に書く（アプリを作り直さずに差し替えられる）:
 * {
 *   "id": "2026-10-12",          // お知らせごとに変える。端末は最後に見た id を覚えて、同じものは出さない
 *   "title": "アップデートのお知らせ",
 *   "items": ["共通の項目", ...],
 *   "android_items": ["Android だけの項目"], "ios_items": ["iOS だけの項目"],   // 任意
 *   "android_min": 0, "ios_min": 0   // 任意：このビルド番号より古い版には出さない（機能が入っていない版で案内しないため）
 * }
 * id を空にすると誰にも出さない
 */
object ReleaseNotes {
    data class Note(val id: String, val title: String, val items: List<String>)

    private val _note = MutableStateFlow<Note?>(null)
    /** 表示するお知らせ（null なら出さない） */
    val note: StateFlow<Note?> = _note.asStateFlow()

    private const val PREFS = "eldia_release_notes"
    private const val KEY_SEEN = "seen_id"

    @Serializable
    private data class Setting(val key: String, val value: JsonObject)

    /** ログイン後・前面に戻ったときに呼ぶ。通信失敗やログイン前は何もしない */
    suspend fun check(ctx: Context) {
        if (SB.main.auth.currentSessionOrNull() == null) return
        val v = runCatching {
            SB.main.from("app_settings").select(Columns.list("key", "value")) {
                filter { eq("key", "release_notes") }
            }.decodeList<Setting>().firstOrNull()?.value
        }.getOrNull() ?: return
        _note.value = parse(v)?.takeIf { it.id != seenId(ctx) }
    }

    /** 「OK」で閉じたら、この id は二度と出さない */
    fun dismiss(ctx: Context) {
        val n = _note.value ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SEEN, n.id).apply()
        _note.value = null
    }

    private fun seenId(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SEEN, null)

    private fun parse(v: JsonObject): Note? {
        val id = v["id"].str()?.trim().orEmpty()
        if (id.isEmpty()) return null
        val min = v["android_min"]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() } ?: 0L
        if (BuildConfig.VERSION_CODE < min) return null
        val items = v["items"].strings() + v["android_items"].strings()
        if (items.isEmpty()) return null
        val title = v["title"].str()?.takeIf { it.isNotBlank() } ?: "アップデートのお知らせ"
        return Note(id, title, items)
    }

    private fun JsonElement?.str(): String? = runCatching { this?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonElement?.strings(): List<String> =
        (this as? JsonArray).orEmpty().mapNotNull { it.str()?.trim()?.takeIf { s -> s.isNotEmpty() } }
}
