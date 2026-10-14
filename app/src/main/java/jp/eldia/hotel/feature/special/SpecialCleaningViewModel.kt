package jp.eldia.hotel.feature.special

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import jp.eldia.hotel.core.DateUtil
import jp.eldia.hotel.core.FacilityConst
import jp.eldia.hotel.core.Hotel
import jp.eldia.hotel.core.Offline
import jp.eldia.hotel.core.SB
import jp.eldia.hotel.core.StatusColor
import jp.eldia.hotel.core.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.time.temporal.ChronoUnit

/** 特別清掃の記録（special_cleaning_logs。id は "luxury-L301-ミリ秒"） */
@Serializable
data class SpecialRecord(
    val id: String,
    val date: String = "",
    val room: String = "",
    val store: String = "luxury",
    val tasks: List<String> = emptyList(),
    val memo: String? = null,
    @SerialName("staff_name") val staffName: String? = null,
    @SerialName("time_in") val timeIn: String? = null,
    @SerialName("time_out") val timeOut: String? = null,
    @SerialName("duration_min") val durationMin: Int? = null,
) {
    val hotel: Hotel get() = Hotel.from(store) ?: Hotel.LUXURY
}

@Serializable
private data class SettingRow(val key: String, val value: List<String>)

/** 特別清掃の判定（iOS版 SpecialCleaningLogic と同じ） */
object SpecialLogic {
    fun records(all: List<SpecialRecord>, roomId: String, h: Hotel) =
        all.filter { it.hotel == h && it.room == roomId }.sortedByDescending { it.date }

    fun latest(all: List<SpecialRecord>, roomId: String, h: Hotel) = records(all, roomId, h).firstOrNull()

    /** latestByRoom のキー */
    fun key(roomId: String, h: Hotel) = "${h.name}|$roomId"

    /**
     * 部屋ごとの最新記録を 1 回の走査で作る。
     * 日付が同じ記録が複数あるときはリストで先に出てくる方（latest() と同じ結果）
     */
    fun latestByRoom(all: List<SpecialRecord>): Map<String, SpecialRecord> {
        val map = HashMap<String, SpecialRecord>()
        for (rec in all) {
            val k = key(rec.room, rec.hotel)
            val cur = map[k]
            if (cur == null || rec.date > cur.date) map[k] = rec
        }
        return map
    }

    fun daysSince(date: String?): Int? =
        DateUtil.parse(date)?.let { ChronoUnit.DAYS.between(it, DateUtil.today()).toInt() }

    /** 最終清掃からの日数で色分け（7日以内 緑 / 30日以内 橙 / それ以上 赤） */
    fun color(days: Int?): Color? = when {
        days == null -> null
        days <= 7 -> StatusColor.done
        days <= 30 -> StatusColor.progress
        else -> StatusColor.open
    }

    /** 作業ごとの前回実施日からの経過（半年未満 緑 / 1年未満 黄 / 2年未満 橙 / それ以上 赤） */
    fun taskColor(lastDate: String?): Color? {
        val d = daysSince(lastDate) ?: return null
        val years = d / 365.25
        return when {
            years < 0.5 -> StatusColor.done
            years < 1 -> Color(0xFFC9A400)
            years < 2 -> StatusColor.progress
            else -> StatusColor.open
        }
    }

    fun durationLabel(min: Int): String = if (min < 60) "${min}分" else "${min / 60}時間${min % 60}分"
}

/** 特別清掃（共通プロジェクトの special_cleaning_logs と app_settings の作業内容） */
class SpecialCleaningViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("eldia_special", Context.MODE_PRIVATE)
    private val db get() = SB.main

    private val _records = MutableStateFlow<List<SpecialRecord>>(emptyList())
    val records: StateFlow<List<SpecialRecord>> = _records.asStateFlow()

    private val _tasks = MutableStateFlow(
        prefs.getString(KEY_TASKS, null)?.let {
            runCatching { Offline.json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull()
        } ?: FacilityConst.defaultCleaningTasks,
    )
    /** 作業内容の一覧（設備管理の設定で編集したもの） */
    val tasks: StateFlow<List<String>> = _tasks.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 前回入力した担当者名 */
    var savedName: String
        get() = prefs.getString("eldia_comment_name", "").orEmpty()
        set(v) = prefs.edit().putString("eldia_comment_name", v).apply()

    suspend fun load() {
        try {
            _records.value = db.from("special_cleaning_logs").select {
                order("date", Order.DESCENDING)
            }.decodeList()
            _error.value = null
        } catch (e: Exception) {
            _error.value = "清掃記録を読み込めませんでした：" + e.userMessage()
        }
        runCatching {
            db.from("app_settings").select(Columns.list("key", "value")) {
                filter { eq("key", "eldia_cleaning_tasks") }
            }.decodeList<SettingRow>().firstOrNull()?.value
        }.getOrNull()?.let { v ->
            _tasks.value = v
            prefs.edit().putString(KEY_TASKS, Offline.json.encodeToString(ListSerializer(String.serializer()), v)).apply()
        }
    }

    /** @return エラーメッセージ（成功時 null） */
    suspend fun save(rec: SpecialRecord): String? = try {
        db.from("special_cleaning_logs").upsert(rec)
        load()
        null
    } catch (e: Exception) {
        e.userMessage()
    }

    /**
     * 自分の記録か（清掃記録と同じく、担当者名が自分の名前と一致するときだけ削除できる）。
     * 自分の名前＝ログインの表示名・前回入力した担当者名（空白の違いは無視）
     */
    fun isMine(rec: SpecialRecord, myName: String?): Boolean {
        fun norm(s: String?) = s.orEmpty().replace(" ", "").replace("　", "")
        val target = norm(rec.staffName)
        if (target.isEmpty()) return false
        return listOf(myName, savedName).any { n -> norm(n).let { it.isNotEmpty() && it == target } }
    }

    suspend fun delete(id: String): String? = try {
        db.from("special_cleaning_logs").delete { filter { eq("id", id) } }
        _records.value = _records.value.filter { it.id != id }
        null
    } catch (e: Exception) {
        e.userMessage()
    }

    companion object {
        private const val KEY_TASKS = "special_tasks"
    }
}
