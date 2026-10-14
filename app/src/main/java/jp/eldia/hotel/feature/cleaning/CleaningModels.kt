package jp.eldia.hotel.feature.cleaning

import androidx.compose.ui.graphics.Color
import jp.eldia.hotel.core.Hotel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 清掃記録の部屋・定数（iOS版 CleaningStore と同じ） */
object CleaningConst {
    data class Floor(val floor: String, val rooms: List<String>)

    val contentOptions = listOf("はがし", "風呂", "メイク")

    /** 仕事内容の色（Web版 COLORS と同じ） */
    fun contentColor(c: String): Color = when (c) {
        "はがし" -> Color(0xFF2D70ED)
        "風呂" -> Color(0xFF9B51E0)
        "メイク" -> Color(0xFF27AE60)
        else -> Color(0xFF607D8B)
    }

    val inColor = Color(0xFF2D7A52)
    val outColor = Color(0xFFC0212E)

    fun floors(h: Hotel): List<Floor> = when (h) {
        Hotel.MODERN -> listOf(
            Floor("6F", listOf("601", "602", "603")),
            Floor("5F", listOf("501", "502", "503", "505", "506", "507", "508")),
            Floor("4F", listOf("401", "403", "405", "406", "407", "408", "410", "411", "412", "413")),
            Floor("3F", listOf("301", "302", "303", "305", "306", "307", "308", "310", "311", "312")),
            Floor("2F", listOf("202", "203", "205", "206", "207")),
        )
        Hotel.LUXURY -> listOf(
            Floor("5F", listOf("501", "502", "503", "505", "506", "507", "508", "510", "511")),
            Floor("4F", listOf("401", "403", "405", "406", "407", "408", "410", "411", "412", "413", "415", "416", "417", "418", "420", "421")),
            Floor("3F", listOf("301", "302", "303", "305", "306", "307", "308", "310", "311", "312", "313", "315", "317", "318", "320")),
            Floor("2F", listOf("201", "202", "203", "205", "206", "207", "208", "210", "211", "212", "215")),
        )
    }
}

/** スタッフ名簿（共通の staff 表。名前が一意） */
@Serializable
data class Staff(val name: String, val store: String = "modern") {
    val hotel: Hotel get() = Hotel.from(store) ?: Hotel.MODERN
}

/** 清掃記録（cleaning_logs）。time_in / time_out は DB では time 型（"HH:MM:SS"） */
@Serializable
data class CleaningLog(
    val id: String,
    val room: String = "",
    val store: String = "modern",
    val date: String = "",
    @SerialName("staff_name") val staffName: String = "",
    val contents: List<String> = emptyList(),
    @SerialName("time_in") val timeInRaw: String? = null,
    @SerialName("time_out") val timeOutRaw: String? = null,
    @SerialName("duration_min") val durationMin: Int? = null,
    /** メモ（お客様の忘れ物など）。全スタッフに表示する */
    val memo: String? = null,
) {
    val hotel: Hotel get() = Hotel.from(store) ?: Hotel.MODERN
    /** 空白だけのメモは無しとして扱う */
    val memoText: String? get() = memo?.trim()?.takeIf { it.isNotEmpty() }
    val timeIn: String? get() = timeInRaw?.take(5)
    val timeOut: String? get() = timeOutRaw?.take(5)
    val timeLabel: String
        get() = "${timeIn ?: "--:--"} → ${timeOut ?: "--:--"}" + (durationMin?.let { " (${it}分)" } ?: "")
}

/** 書き込み用（DB 仕様：id は text で既定値なし → アプリで採番。time_in / time_out は必須） */
@Serializable
data class CleaningLogWrite(
    val id: String,
    val room: String,
    val store: String,
    val date: String,
    @SerialName("staff_name") val staffName: String,
    val contents: List<String>,
    @SerialName("time_in") val timeIn: String,
    @SerialName("time_out") val timeOut: String,
    @SerialName("duration_min") val durationMin: Int?,
    val memo: String? = null,
) {
    fun toLog() = CleaningLog(id, room, store, date, staffName, contents, timeIn, timeOut, durationMin, memo)
}

/**
 * 清掃中の記録（IN を押してから保存するまでの入力）。端末にだけ保存し、部屋の一覧に「清掃中」と出す。
 * 画面を閉じても消えないので、OUT の押し忘れに気づける
 */
@Serializable
data class CleaningDraft(
    val store: String,
    val room: String,
    val date: String,
    val staffName: String,
    val contents: List<String> = emptyList(),
    val timeIn: String? = null,
    val timeOut: String? = null,
    val memo: String = "",
) {
    val key: String get() = "$store|$room"
}

/** ある日の記録（pendingIds は端末にだけある未送信の記録） */
data class DayLogs(val logs: List<CleaningLog>, val pendingIds: Set<String> = emptySet())

/** 集計の区分（作業内容の組み合わせ） */
enum class WorkKind(val label: String) {
    PEEL_ONLY("はがしのみ"),
    BATH_ONLY("風呂のみ"),
    MAKE_ONLY("メイクのみ"),
    PEEL_BATH("はがし＋風呂"),
    ALL("はがし＋風呂＋メイク"),
    OTHER("その他");

    companion object {
        fun of(contents: List<String>): WorkKind {
            val set = contents.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            return when (set) {
                setOf("はがし") -> PEEL_ONLY
                setOf("風呂") -> BATH_ONLY
                setOf("メイク") -> MAKE_ONLY
                setOf("はがし", "風呂") -> PEEL_BATH
                setOf("はがし", "風呂", "メイク") -> ALL
                else -> OTHER
            }
        }
    }
}

data class StaffStat(val name: String, val count: Int, val kinds: Map<WorkKind, Int>) {
    fun count(k: WorkKind): Int = kinds[k] ?: 0
}

fun List<CleaningLog>.staffStats(): List<StaffStat> =
    groupBy { it.staffName }
        .map { (n, l) -> StaffStat(n, l.size, l.groupingBy { WorkKind.of(it.contents) }.eachCount()) }
        .sortedWith(compareByDescending<StaffStat> { it.count }.thenBy { it.name })
