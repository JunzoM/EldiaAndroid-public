package jp.eldia.hotel.feature.home

import jp.eldia.hotel.core.DateUtil
import jp.eldia.hotel.ui.AppTab
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** ホーム「今日やること」に出す項目の種類（FACILITY はチェックリストではなくお知らせ用） */
enum class HomeTaskKind(val title: String, val tab: AppTab) {
    CLEANING("清掃記録", AppTab.CLEANING),
    ORDER("発注", AppTab.ORDER),
    AQUA("水槽チェック", AppTab.CLEANING),
    FRIDGE("冷蔵庫の期限確認", AppTab.FRIDGE),
    FACILITY("設備の不具合", AppTab.FACILITY);

    /** 発注は店舗ごとではなく両店舗まとめて行う */
    val isShared: Boolean get() = this == ORDER
}

/**
 * 実施ルール（すべて日本時間で判定。iOS版 HomeSchedule と同じ）
 * - 清掃記録: 毎日
 * - 発注: 毎週 水曜・日曜（両店舗まとめて）
 * - 水槽チェック: 月〜水のうち週1回。忙しい週は飛ばしてよいが、14日を超えたら要実施
 * - 冷蔵庫の期限確認: 毎月25日〜月末のうち1回
 */
class HomeSchedule(val today: LocalDate = DateUtil.today()) {
    companion object {
        /** 0=日 … 6=土 */
        val orderWeekdays = setOf(0, 3)
        val aquaWeekdays = 1..3
        /** 最も古い点検からこの日数を「超えたら」（15日目以降）要実施 */
        const val AQUA_LIMIT_DAYS = 14
        const val FRIDGE_START_DAY = 25
        private val weekdaysJP = listOf("日", "月", "火", "水", "木", "金", "土")
    }

    /** 0=日 … 6=土 */
    fun weekday(d: LocalDate): Int = d.dayOfWeek.value % 7
    val weekday: Int get() = weekday(today)
    val day: Int get() = today.dayOfMonth

    /** 今週の月曜日（週は月曜始まり） */
    val mondayOfWeek: LocalDate get() = today.minusDays(((weekday + 6) % 7).toLong())

    fun lastDayOfMonth(d: LocalDate): LocalDate = d.withDayOfMonth(d.lengthOfMonth())

    fun daysSince(d: LocalDate): Int = ChronoUnit.DAYS.between(d, today).toInt()

    // 今日が対象日か
    val isOrderDay: Boolean get() = weekday in orderWeekdays
    val isAquaWindow: Boolean get() = weekday in aquaWeekdays
    val aquaDaysLeft: Int get() = maxOf(0, aquaWeekdays.last - weekday)
    val isFridgeWindow: Boolean get() = day >= FRIDGE_START_DAY
    val fridgeDaysLeft: Int get() = ChronoUnit.DAYS.between(today, lastDayOfMonth(today)).toInt()

    // 表示用

    /** "10月7日（水）" */
    val dateLabel: String get() = "${today.monthValue}月${today.dayOfMonth}日（${weekdaysJP[weekday]}）"

    /** "10/7（水）" */
    fun mdw(d: LocalDate): String = "${d.monthValue}/${d.dayOfMonth}（${weekdaysJP[weekday(d)]}）"

    fun md(d: LocalDate): String = "${d.monthValue}/${d.dayOfMonth}"

    data class Upcoming(val kind: HomeTaskKind, val start: LocalDate, val label: String) {
        val title: String get() = if (kind.isShared) "${kind.title}（両店舗）" else kind.title
    }

    /** 明日以降で次に来る 発注日 / 水槽チェック期間 / 冷蔵庫確認期間 */
    fun upcoming(): List<Upcoming> {
        val list = mutableListOf<Upcoming>()
        (1L..7L).map { today.plusDays(it) }.firstOrNull { weekday(it) in orderWeekdays }?.let {
            list += Upcoming(HomeTaskKind.ORDER, it, mdw(it))
        }
        (1L..7L).map { today.plusDays(it) }.firstOrNull { weekday(it) == aquaWeekdays.first }?.let {
            val end = it.plusDays((aquaWeekdays.last - aquaWeekdays.first).toLong())
            list += Upcoming(HomeTaskKind.AQUA, it, "${mdw(it)}〜${md(end)}")
        }
        val thisStart = today.withDayOfMonth(FRIDGE_START_DAY)
        val start = if (isFridgeWindow) thisStart.plusMonths(1) else thisStart
        list += Upcoming(HomeTaskKind.FRIDGE, start, "${md(start)}〜${md(lastDayOfMonth(start))}")
        return list.sortedBy { it.start }
    }
}
