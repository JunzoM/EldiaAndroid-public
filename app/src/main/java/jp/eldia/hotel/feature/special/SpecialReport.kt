package jp.eldia.hotel.feature.special

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import jp.eldia.hotel.core.DateUtil
import jp.eldia.hotel.core.FacilityConst
import jp.eldia.hotel.core.Hotel
import java.time.LocalDate

/**
 * 特別清掃の日報（その日に記録した全室をまとめて LINE で報告する）と集計。
 * 設備の不具合報告と同じく、Messaging API は使わず共有シートで送る。
 */
object SpecialReport {

    /** その日の記録（store が null なら両店舗）。店舗→部屋番号の順 */
    fun dayRecords(all: List<SpecialRecord>, date: String, store: Hotel?): List<SpecialRecord> =
        all.filter { it.date.take(10) == date && (store == null || it.hotel == store) }
            .sortedWith(compareBy<SpecialRecord>({ it.hotel.ordinal }, { FacilityConst.roomNum(it.room, it.hotel) }, { it.timeIn ?: "" }))

    /** 記録のある日（新しい順） */
    fun recordedDates(all: List<SpecialRecord>, store: Hotel?): List<String> =
        all.filter { store == null || it.hotel == store }.map { it.date.take(10) }
            .filter { it.isNotBlank() }.distinct().sortedDescending()

    /** LINE で送る日報の文章 */
    fun dayText(all: List<SpecialRecord>, date: String, store: Hotel?, reporter: String): String {
        val recs = dayRecords(all, date, store)
        val dateLabel = DateUtil.parse(date)?.let { DateUtil.label(it) } ?: date
        val lines = mutableListOf<String>()
        lines += "【特別清掃 日報】"
        lines += "$dateLabel ${store?.let { it.english.uppercase() } ?: "LUXURY・MODERN"}"
        if (recs.isEmpty()) {
            lines += "記録はありません"
            return lines.joinToString("\n")
        }
        val total = recs.sumOf { it.durationMin ?: 0 }
        lines += buildString {
            append("計${recs.size}室・作業${recs.sumOf { it.tasks.size }}件")
            if (total > 0) append("・合計${SpecialLogic.durationLabel(total)}")
        }
        recs.groupBy { it.hotel }.forEach { (h, list) ->
            lines += ""
            if (store == null) lines += "◆ ${h.english.uppercase()}（${list.size}室）"
            list.forEach { r ->
                val meta = buildList {
                    r.staffName?.takeIf { it.isNotBlank() }?.let { add(it) }
                    if (!r.timeIn.isNullOrEmpty() && !r.timeOut.isNullOrEmpty()) {
                        add("${r.timeIn.take(5)}〜${r.timeOut.take(5)}" + (r.durationMin?.let { " ${SpecialLogic.durationLabel(it)}" } ?: ""))
                    }
                }.joinToString(" ")
                lines += "■ ${FacilityConst.roomNum(r.room, r.hotel)}号室" + if (meta.isNotEmpty()) "（$meta）" else ""
                if (r.tasks.isNotEmpty()) lines += "・" + r.tasks.joinToString("、")
                r.memo?.trim()?.takeIf { it.isNotEmpty() }?.let { lines += "メモ：$it" }
            }
        }
        // 担当者ごとの室数（2人以上のときだけ）
        val byStaff = recs.groupBy { it.staffName?.trim().orEmpty().ifEmpty { "未入力" } }
        if (byStaff.size > 1) {
            lines += ""
            lines += "担当：" + byStaff.entries.sortedByDescending { it.value.size }.joinToString("、") { "${it.key} ${it.value.size}室" }
        }
        reporter.trim().takeIf { it.isNotEmpty() }?.let {
            lines += ""
            lines += "報告者：$it"
        }
        return lines.joinToString("\n")
    }

    // MARK: - 集計

    enum class Period(val label: String) { MONTH("今月"), LAST_MONTH("先月"), QUARTER("3か月"), YEAR("今年"), ALL("全期間") }

    /** 期間の範囲（両端を含む。ALL は null） */
    fun range(p: Period, today: LocalDate = DateUtil.today()): Pair<LocalDate, LocalDate>? = when (p) {
        Period.MONTH -> today.withDayOfMonth(1) to today
        Period.LAST_MONTH -> today.minusMonths(1).withDayOfMonth(1).let { it to it.withDayOfMonth(it.lengthOfMonth()) }
        Period.QUARTER -> today.minusMonths(3).plusDays(1) to today
        Period.YEAR -> today.withDayOfYear(1) to today
        Period.ALL -> null
    }

    fun inPeriod(all: List<SpecialRecord>, p: Period, store: Hotel): List<SpecialRecord> {
        val r = range(p)
        return all.filter { rec ->
            rec.hotel == store && (r == null || DateUtil.parse(rec.date)?.let { !it.isBefore(r.first) && !it.isAfter(r.second) } == true)
        }
    }

    data class StaffStat(val name: String, val rooms: Int, val tasks: Int, val minutes: Int, val timed: Int) {
        val avg: Int? get() = if (timed == 0) null else Math.round(minutes.toDouble() / timed).toInt()
    }

    data class Summary(
        val rooms: Int,          // 記録数（室）
        val uniqueRooms: Int,    // 実施した部屋の数（重複なし）
        val tasks: Int,          // 作業件数
        val minutes: Int,        // 合計時間
        val timed: Int,          // 時間を入れた記録数
        val days: Int,           // 実施日数
    ) {
        val avg: Int? get() = if (timed == 0) null else Math.round(minutes.toDouble() / timed).toInt()
    }

    fun summary(list: List<SpecialRecord>) = Summary(
        rooms = list.size,
        uniqueRooms = list.map { it.room }.distinct().size,
        tasks = list.sumOf { it.tasks.size },
        minutes = list.sumOf { it.durationMin ?: 0 },
        timed = list.count { it.durationMin != null },
        days = list.map { it.date.take(10) }.distinct().size,
    )

    /** 作業別の件数（多い順）。設定にある作業は 0 件でも出す */
    fun taskCounts(list: List<SpecialRecord>, allTasks: List<String>): List<Pair<String, Int>> {
        val counts = list.flatMap { it.tasks }.groupingBy { it }.eachCount()
        return (allTasks + counts.keys.filter { it !in allTasks }).distinct()
            .map { it to (counts[it] ?: 0) }
            .sortedByDescending { it.second }
    }

    fun staffStats(list: List<SpecialRecord>): List<StaffStat> =
        list.groupBy { it.staffName?.trim().orEmpty().ifEmpty { "未入力" } }
            .map { (name, recs) ->
                StaffStat(name, recs.size, recs.sumOf { it.tasks.size }, recs.sumOf { it.durationMin ?: 0 }, recs.count { it.durationMin != null })
            }
            .sortedWith(compareByDescending<StaffStat> { it.rooms }.thenByDescending { it.tasks })

    /** 月別の室数（古い順・直近 n か月。記録がない月も 0 で出す） */
    fun monthly(all: List<SpecialRecord>, store: Hotel, months: Int = 6, today: LocalDate = DateUtil.today()): List<Pair<String, Int>> {
        val counts = all.filter { it.hotel == store }.mapNotNull { DateUtil.parse(it.date) }
            .groupingBy { "${it.year}-${it.monthValue}" }.eachCount()
        return (months - 1 downTo 0).map { i ->
            val m = today.minusMonths(i.toLong())
            "${m.monthValue}月" to (counts["${m.year}-${m.monthValue}"] ?: 0)
        }
    }

    /** 最終清掃から日数が経っている部屋（未記録が先頭、その後は古い順） */
    fun overdueRooms(all: List<SpecialRecord>, store: Hotel, limit: Int = 10): List<Pair<String, Int?>> {
        val latest = SpecialLogic.latestByRoom(all)
        return FacilityConst.rooms(store)
            .map { room -> room.num to SpecialLogic.daysSince(latest[SpecialLogic.key(room.id, store)]?.date) }
            .sortedWith(compareByDescending<Pair<String, Int?>> { it.second ?: Int.MAX_VALUE }.thenBy { it.first })
            .take(limit)
    }

    /** 部屋の状況の内訳（7日以内・30日以内・30日超・未記録） */
    fun freshness(all: List<SpecialRecord>, store: Hotel): List<Int> {
        val latest = SpecialLogic.latestByRoom(all)
        val days = FacilityConst.rooms(store).map { SpecialLogic.daysSince(latest[SpecialLogic.key(it.id, store)]?.date) }
        return listOf(
            days.count { it != null && it <= 7 },
            days.count { it != null && it in 8..30 },
            days.count { it != null && it > 30 },
            days.count { it == null },
        )
    }
}

/**
 * 文章を共有シートで渡す（LINE のグループを選んで送る）。
 * LINE が文章を落としたときのために、クリップボードにも入れておく。
 */
fun shareSpecialReport(context: Context, text: String) {
    runCatching {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("特別清掃 日報", text))
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try {
        context.startActivity(Intent.createChooser(send, "特別清掃の日報をLINEで報告").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}
