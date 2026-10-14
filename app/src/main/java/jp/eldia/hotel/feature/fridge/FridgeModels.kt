package jp.eldia.hotel.feature.fridge

import androidx.compose.ui.graphics.Color
import jp.eldia.hotel.core.DateUtil
import jp.eldia.hotel.core.Hotel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 冷蔵庫の定数（iOS版 FridgeStore・本家 eldia-fridge-vercel と同じ） */
object FridgeConst {
    /** スロットの初期品目（本家 NAMES） */
    val defaultNames = listOf(
        "コカ・コーラ", "角ハイボール", "伊右衛門", "烏龍茶", "午後の紅茶",
        "ポカリスウェット", "クリスタルガイザー", "Red Bull", "キリン一番搾り",
        "アサヒスーパードライ", "のどごし生", "氷結レモン", "パワフルゴールド",
        "ダース ミルク", "フリースペース①", "フリースペース②",
    )

    /** 品目選択肢（本家 NAMES_LIST） */
    val nameChoices = listOf(
        "コカ・コーラ", "角ハイボール", "伊右衛門", "烏龍茶", "午後の紅茶",
        "ポカリスウェット", "クリスタルガイザー", "Red Bull", "キリン一番搾り",
        "アサヒスーパードライ", "のどごし生", "氷結レモン", "パワフルゴールド",
        "ストロングD", "ダース ミルク", "フリースペース①", "フリースペース②",
    )

    data class Floor(val floor: String, val rooms: List<String>)

    private val modern = listOf(
        Floor("6F", listOf("601", "602", "603")),
        Floor("5F", listOf("501", "502", "503", "505", "506", "507", "508")),
        Floor("4F", listOf("401", "403", "405", "406", "407", "408", "410", "411", "412", "413")),
        Floor("3F", listOf("301", "302", "303", "305", "306", "307", "308", "310", "311", "312")),
        Floor("2F", listOf("202", "203", "205", "206", "207")),
    )

    private val luxury = listOf(
        Floor("5F", listOf("L501", "L502", "L503", "L505", "L506", "L507", "L508", "L510", "L511")),
        Floor("4F", listOf("L401", "L403", "L405", "L406", "L407", "L408", "L410", "L411", "L412", "L413", "L415", "L416", "L417", "L418", "L420", "L421")),
        Floor("3F", listOf("L301", "L302", "L303", "L305", "L306", "L307", "L308", "L310", "L311", "L312", "L313", "L315", "L316", "L317", "L318", "L320")),
        Floor("2F", listOf("L201", "L202", "L203", "L205", "L206", "L207", "L208", "L210", "L211", "L212", "L213", "L215")),
    )

    fun floors(h: Hotel) = if (h == Hotel.MODERN) modern else luxury
    fun rooms(h: Hotel) = floors(h).flatMap { it.rooms }

    /** 店舗コード（LUXURY の部屋番号は "L" で始まる） */
    fun code(h: Hotel) = if (h == Hotel.MODERN) "M" else "L"
    fun codeOf(roomOrKey: String) = if (roomOrKey.startsWith("L")) "L" else "M"

    /** 0..15 → "A1".."D4" */
    fun slotLabel(idx: Int) = listOf("A", "B", "C", "D")[idx / 4] + (idx % 4 + 1)

    fun defaultSlots() = defaultNames.map { Slot(it, "") }

    fun thisMonthEnd(today: LocalDate = DateUtil.today()): LocalDate = today.withDayOfMonth(today.lengthOfMonth())
    fun nextMonthEnd(today: LocalDate = DateUtil.today()): LocalDate = today.plusMonths(1).let { it.withDayOfMonth(it.lengthOfMonth()) }

    /** 25日〜月末は期限確認の期間 */
    fun isCheckWindow(today: LocalDate = DateUtil.today()) = today.dayOfMonth >= 25
    fun checkDaysLeft(today: LocalDate = DateUtil.today()) = ChronoUnit.DAYS.between(today, thisMonthEnd(today)).toInt()

    /** 今月末以前 = 要即交換 / 来月末ちょうど = 月末交換 / 来月中のそれ以外 = 要即交換 / それ以降 = 正常（本家 cSt） */
    fun status(exp: String, today: LocalDate = DateUtil.today()): FridgeStatus {
        if (exp.isEmpty()) return FridgeStatus.NONE
        val d = DateUtil.parse(exp) ?: return FridgeStatus.NONE
        val tme = thisMonthEnd(today)
        val nme = nextMonthEnd(today)
        if (!d.isAfter(tme)) return FridgeStatus.RED
        if (!d.isAfter(nme)) return if (d == nme) FridgeStatus.ORANGE else FridgeStatus.RED
        return FridgeStatus.OK
    }

    /** 期限の日を過ぎている（今日が期限の日なら、まだ切れていない） */
    fun isExpired(exp: String, today: LocalDate = DateUtil.today()): Boolean =
        DateUtil.parse(exp)?.isBefore(today) == true

    /** 期限切れの色（要交換の赤より濃い） */
    val expiredColor = Color(0xFF991B1B)

    fun daysLeft(exp: String): Long? = DateUtil.parse(exp)?.let { ChronoUnit.DAYS.between(DateUtil.today(), it) }

    fun daysLabel(exp: String): String {
        val d = daysLeft(exp) ?: return ""
        return when {
            d < 0 -> "${-d}日超過"
            d == 0L -> "本日期限"
            else -> "あと${d}日"
        }
    }

    /** "2026-10-31" → "10/31"（日付でなければ空） */
    fun md(s: String?): String = DateUtil.parse(s)?.let { DateUtil.md(it) } ?: ""

    private val weekdays = listOf("月", "火", "水", "木", "金", "土", "日")
    fun mdw(d: LocalDate) = "${d.monthValue}/${d.dayOfMonth}(${weekdays[d.dayOfWeek.value - 1]})"
}

enum class FridgeStatus(val label: String, val color: Color) {
    RED("要交換", Color(0xFFDC2626)),
    ORANGE("月末交換", Color(0xFFF59E0B)),
    OK("正常", Color(0xFF16A34A)),
    NONE("未入力", Color(0xFF8A8A8A)),
}

@Serializable
data class Slot(val name: String = "", val exp: String = "")

/** 未送信の期限・品目の変更（eldia_pv1） */
@Serializable
data class FridgePending(val st: String, val rn: String, val idx: Int, val nm: String, val exp: String, val ts: Double)

/** 取り出し中の追加・解除で、まだ送れていないもの（eldia_rpv1） */
@Serializable
data class RemovedOp(val key: String, val add: Boolean)

/** 操作の履歴（端末内のみ・elhv7） */
@Serializable
data class FridgeHistory(
    val dt: String,
    val rn: String,
    val idx: Int? = null,
    val name: String = "",
    val from: String? = null,
    val to: String? = null,
    val op: String? = null,
) {
    val key get() = dt + rn + (idx ?: -1) + (op ?: "")
}

data class FridgeAlert(
    val rn: String, val idx: Int, val name: String, val exp: String, val status: FridgeStatus,
    /** 期限の日を過ぎている（要交換のうち、特に急ぐもの） */
    val expired: Boolean = false,
) {
    val key get() = "$rn-$idx"
}

@Serializable
internal data class ItemRow(
    @SerialName("room_no") val roomNo: String,
    @SerialName("slot_idx") val slotIdx: Int,
    val name: String? = null,
    @SerialName("expiry_date") val expiryDate: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
internal data class ItemWrite(
    @SerialName("room_no") val roomNo: String,
    @SerialName("slot_idx") val slotIdx: Int,
    val name: String,
    @SerialName("expiry_date") val expiryDate: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
internal data class RemovedRow(val key: String)

/** 1店舗分の集計（部屋の状態・要交換一覧など） */
data class FridgeSummary(
    val alerts: List<FridgeAlert>,
    val roomStatus: Map<String, FridgeStatus>,
    val removedCount: Map<String, Int>,
    val red: Int,
    val orange: Int,
    val totalRemoved: Int,
    /** 期限切れ（要交換のうち期限の日を過ぎたもの） */
    val expired: Int = 0,
) {
    companion object {
        fun build(hotel: Hotel, data: Map<String, List<Slot>>, removed: Set<String>): FridgeSummary {
            val today = DateUtil.today()
            val alerts = mutableListOf<FridgeAlert>()
            val rs = mutableMapOf<String, FridgeStatus>()
            var red = 0
            var orange = 0
            var expired = 0
            for (r in FridgeConst.rooms(hotel)) {
                var hasRed = false
                var hasOrange = false
                var allNone = true
                (data[r] ?: FridgeConst.defaultSlots()).forEachIndexed { i, s ->
                    when (val st = FridgeConst.status(s.exp, today)) {
                        FridgeStatus.RED -> {
                            hasRed = true; red++; allNone = false
                            val ex = FridgeConst.isExpired(s.exp, today)
                            if (ex) expired++
                            alerts += FridgeAlert(r, i, s.name, s.exp, st, ex)
                        }
                        FridgeStatus.ORANGE -> { hasOrange = true; orange++; allNone = false; alerts += FridgeAlert(r, i, s.name, s.exp, st) }
                        FridgeStatus.OK -> allNone = false
                        FridgeStatus.NONE -> Unit
                    }
                }
                rs[r] = when {
                    hasRed -> FridgeStatus.RED
                    hasOrange -> FridgeStatus.ORANGE
                    allNone -> FridgeStatus.NONE
                    else -> FridgeStatus.OK
                }
            }
            val counts = removed.groupingBy { it.substringBeforeLast("-") }.eachCount()
            val total = FridgeConst.rooms(hotel).sumOf { counts[it] ?: 0 }
            return FridgeSummary(alerts, rs, counts, red, orange, total, expired)
        }
    }
}
