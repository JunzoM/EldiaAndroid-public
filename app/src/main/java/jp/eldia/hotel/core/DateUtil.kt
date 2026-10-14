package jp.eldia.hotel.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** 日付はすべて日本時間で扱う（iOS版の DateUtil と同じ方針。元の Web 版にあった UTC ずれを再現しない） */
object DateUtil {
    val TZ: ZoneId = ZoneId.of("Asia/Tokyo")
    private val ymd: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun today(): LocalDate = LocalDate.now(TZ)

    /** "2026-10-02" */
    fun todayString(): String = ymd(today())

    fun ymd(d: LocalDate): String = d.format(ymd)

    /**
     * 日付の読み取り。"yyyy-MM-dd"（後ろに時刻があってもよい）に加えて、
     * 古いデータに混ざっている "2026/3/31"・"2026年3月31日"・JavaScript の日付文字列
     * （"Fri Apr 30 2027 00:00:00 GMT+0900 (Japan Standard Time)"）も読む（iOS版 DateUtil.parse と同じ）
     */
    fun parse(s: String?): LocalDate? {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) return null
        runCatching { LocalDate.parse(t.take(10), ymd) }.getOrNull()?.let { return it }
        loose.find(t)?.let { m ->
            val (y, mo, d) = m.destructured
            return runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()
        }
        jsDate.find(t)?.let { m ->
            val (mon, d, y) = m.destructured
            val mo = jsMonths.indexOf(mon.lowercase()) + 1
            if (mo > 0) return runCatching { LocalDate.of(y.toInt(), mo, d.toInt()) }.getOrNull()
        }
        return null
    }

    private val loose = Regex("""^(\d{4})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})""")
    private val jsDate = Regex("""^[A-Za-z]{3},?\s+([A-Za-z]{3})\s+(\d{1,2})\s+(\d{4})""")
    private val jsMonths = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** "10月2日（金）" */
    fun todayLabel(): String = label(today())

    /** "10月2日（金）" */
    fun label(d: LocalDate): String {
        val w = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPANESE)
        return "${d.monthValue}月${d.dayOfMonth}日（$w）"
    }

    /** "10/2" */
    fun md(d: LocalDate): String = "${d.monthValue}/${d.dayOfMonth}"

    /** 今の時刻 "HH:mm"（日本時間） */
    fun hhmm(): String = LocalTime.now(TZ).format(HHMM)

    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** "HH:mm" 同士の差（分）。OUT が IN より前なら null */
    fun durationMinutes(timeIn: String?, timeOut: String?): Int? {
        if (timeIn.isNullOrEmpty() || timeOut.isNullOrEmpty()) return null
        val a = timeIn.split(":").mapNotNull { it.toIntOrNull() }
        val b = timeOut.split(":").mapNotNull { it.toIntOrNull() }
        if (a.size < 2 || b.size < 2) return null
        val d = (b[0] * 60 + b[1]) - (a[0] * 60 + a[1])
        return if (d < 0) null else d
    }

    /** "HH:mm" として正しいか */
    fun isHHmm(s: String): Boolean = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$").matches(s)

    /** 今週の日曜（Web版の集計と同じ日曜始まり） */
    fun startOfWeekSunday(d: LocalDate = today()): LocalDate =
        d.minusDays((d.dayOfWeek.value % 7).toLong())

    /** 今週の月曜（水槽チェックの週） */
    fun startOfWeekMonday(d: LocalDate = today()): LocalDate =
        d.minusDays((d.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())

    /** 日付ピッカー（UTC のミリ秒）⇔ 日付 */
    fun fromPickerMillis(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.of("UTC")).toLocalDate()
    fun toPickerMillis(d: LocalDate): Long = d.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
}
