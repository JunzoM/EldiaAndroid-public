package jp.eldia.hotel.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import jp.eldia.hotel.core.DateUtil
import jp.eldia.hotel.core.Hotel
import jp.eldia.hotel.core.SB
import jp.eldia.hotel.feature.aqua.AquaConst
import jp.eldia.hotel.feature.aqua.MaintenanceLog
import jp.eldia.hotel.feature.cleaning.CleaningLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** ホームの1行分 */
data class HomeRow(
    val kind: HomeTaskKind,
    val detail: String,
    val done: Boolean,
    val badge: String,
    val tone: Tone,
) {
    enum class Tone { NORMAL, WARN, URGENT, DONE, MUTED }
}

enum class OrderState { LOADING, READY, FAILED }

/** 冷蔵庫の状態（FridgeViewModel から画面側で渡す） */
data class FridgeInput(
    /** 冷蔵庫の画面が今読み込んでいる店舗 */
    val hotel: Hotel,
    /** この店舗をサーバーと同期済みか */
    val synced: Boolean,
    /** 直近の通信が失敗したか */
    val offline: Boolean,
    val red: Int,
    val orange: Int,
    /** 期限切れ（要交換のうち期限の日を過ぎたもの） */
    val expired: Int = 0,
)

/** 端末にだけある記録（未送信の清掃記録・水槽画面で読み込み済みの記録）を読む口 */
class HomeLocal(
    val cleaningPending: (Hotel, String) -> List<CleaningLog>,
    val aquaLogs: () -> List<MaintenanceLog>,
)

data class HomeState(
    val cleaningCount: Map<Hotel, Int> = emptyMap(),
    val cleaningLastOut: Map<Hotel, String> = emptyMap(),
    /** 清掃記録を読み込んだ日（日付が変わったら前日の結果は使わない） */
    val cleaningDay: String? = null,
    val aquaLatest: Map<String, LocalDate> = emptyMap(),
    val aquaLoaded: Boolean = false,
    val orderTodayAt: OffsetDateTime? = null,
    val orderState: OrderState = OrderState.LOADING,
    /** 承認待ちの発注数（直近31日分） */
    val orderPendingCount: Int? = null,
    /** LINE送信済みで未納品の発注数（直近31日分） */
    val orderUndeliveredCount: Int? = null,
    /** 店舗（外構部は "exterior"）ごとの 未対応・対応中・今日の新規 */
    val issueOpen: Map<String, Int> = emptyMap(),
    val issueProgress: Map<String, Int> = emptyMap(),
    val issueNewToday: Map<String, Int> = emptyMap(),
    /** 店舗ごとの「要確認」（未完了のうち、7日以内に報告されたもの＋優先度・高） */
    val issueAttention: Map<String, Int> = emptyMap(),
    val issuesLoaded: Boolean = false,
    val loading: Boolean = false,
    /** 読み込みに失敗した項目。表示は「前回の結果」か「確認できません」にする */
    val failed: Set<HomeTaskKind> = emptySet(),
) {
    val cleaningLoaded: Boolean get() = cleaningDay == DateUtil.todayString()
}

@Serializable
private data class CleaningHomeRow(
    val room: String = "",
    val store: String = "",
    @SerialName("time_out") val timeOut: String? = null,
)

@Serializable
private data class AquaHomeRow(@SerialName("room_number") val roomNumber: String = "", val date: String = "")

@Serializable
private data class OrderHomeRow(val id: String, val status: String = "", @SerialName("ordered_at") val orderedAt: String? = null)

@Serializable
private data class IssueHomeRow(
    val id: Long? = null,
    val store: String = "",
    val status: String = "",
    @SerialName("report_date") val reportDate: String? = null,
    val priority: String? = null,
)

/**
 * 気づくべき不具合か（未完了のうち、7日以内に報告されたもの・優先度が高いもの）。
 * 古くから残っている不具合は一覧にそのまま残すが、ホームの赤い表示には数えない
 * （常に数十件の赤い数字が出ていると、新しい不具合に気づけなくなるため）
 */
fun isAttentionIssue(status: String, reportDate: String?, priority: String?, today: java.time.LocalDate = DateUtil.today()): Boolean {
    if (status == "done") return false
    if (priority == "high") return true
    val d = DateUtil.parse(reportDate) ?: return false
    return java.time.temporal.ChronoUnit.DAYS.between(d, today) <= ATTENTION_DAYS
}

/** 「新しい不具合」とみなす日数 */
const val ATTENTION_DAYS = 7L

/**
 * ホーム「今日やること」の完了判定（iOS版 HomeStore と同じ）。
 * 各モジュールの記録を共通プロジェクトから軽く読み、記録があれば自動で完了にする（手動チェックはしない）。
 * - 清掃記録: cleaning_logs に今日の記録がある（未送信の記録も含める）
 * - 発注:     orders に今日（日本時間）の発注がある（取消を除く・両店舗共通）
 * - 水槽:     maintenance_logs で店舗の全水槽に今週（月曜以降）の記録がある
 * - 冷蔵庫:   要交換（赤）の品が 0 件（FridgeViewModel のデータを使う）
 */
class HomeViewModel : ViewModel() {
    private val db get() = SB.main

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val lock = Mutex()
    private var lastRefresh = 0L

    /**
     * 読み直す。force でなければ直前60秒以内に読み込み済みのとき省く（タブの切替・アプリに戻ったとき）。
     * 読み込み中に来た依頼は終わるのを待ってから判断する。
     */
    fun reload(local: HomeLocal, force: Boolean = false): Job = viewModelScope.launch {
        lock.withLock {
            val s = _state.value
            if (!force && System.currentTimeMillis() - lastRefresh < MIN_INTERVAL_MS && s.cleaningLoaded) return@withLock
            _state.update { it.copy(loading = true) }
            try {
                coroutineScope {
                    launch { loadCleaning(local) }
                    launch { loadAqua(local) }
                    launch { loadOrders() }
                    launch { loadIssues() }
                }
                lastRefresh = System.currentTimeMillis()
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    private fun fail(kind: HomeTaskKind) = _state.update { it.copy(failed = it.failed + kind) }

    /** 失敗しても前回の値を残す（「記録0件」と誤って表示しない） */
    private suspend fun loadCleaning(local: HomeLocal) {
        val today = DateUtil.todayString()
        try {
            val rows = db.from("cleaning_logs").select(Columns.list("room", "store", "time_out")) {
                filter { eq("date", today) }
                limit(2000)
            }.decodeList<CleaningHomeRow>()
            val counts = mutableMapOf<Hotel, Int>()
            val lastOut = mutableMapOf<Hotel, String>()
            for (h in Hotel.entries) {
                // 電波がなく端末に保存中の記録も「記録済み」に含める
                val pending = local.cleaningPending(h, today)
                val mine = rows.filter { it.store == h.rawValue }
                counts[h] = (mine.map { it.room } + pending.map { it.room }).toSet().size
                val outs = mine.mapNotNull { it.timeOut?.take(5) } + pending.mapNotNull { it.timeOut }
                outs.maxOrNull()?.let { lastOut[h] = it }
            }
            _state.update {
                it.copy(cleaningCount = counts, cleaningLastOut = lastOut, cleaningDay = today, failed = it.failed - HomeTaskKind.CLEANING)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(HomeTaskKind.CLEANING)
        }
    }

    /** 直近8週間の最終点検日だけを取得（全期間の記録は読まない） */
    private suspend fun loadAqua(local: HomeLocal) {
        val since = DateUtil.ymd(DateUtil.today().minusDays(56))
        try {
            val rows = db.from("maintenance_logs").select(Columns.list("room_number", "date")) {
                filter { gte("date", since) }
                limit(3000)
            }.decodeList<AquaHomeRow>()
            val map = mutableMapOf<String, LocalDate>()
            fun put(room: String, date: String) {
                val d = DateUtil.parse(date) ?: return
                val cur = map[room]
                if (cur == null || d.isAfter(cur)) map[room] = d
            }
            rows.forEach { put(it.roomNumber, it.date) }
            // 水槽画面で読み込み済みの記録・端末に保存中の記録も反映
            local.aquaLogs().forEach { put(it.roomNumber, it.date) }
            _state.update { it.copy(aquaLatest = map, aquaLoaded = true, failed = it.failed - HomeTaskKind.AQUA) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(HomeTaskKind.AQUA)
        }
    }

    /** 今日の発注に加えて「承認待ち・未納品」も数えるため、直近31日分を1回で読む */
    private suspend fun loadOrders() {
        val start = DateUtil.today().atStartOfDay(DateUtil.TZ)
        val since = start.minusDays(31).toInstant().toString()
        try {
            val rows = db.from("orders").select(Columns.list("id", "status", "ordered_at")) {
                filter { gte("ordered_at", since) }
                limit(1000)
            }.decodeList<OrderHomeRow>()
            val startInstant = start.toInstant()
            val todayAt = rows
                .filter { it.status != "cancelled" }
                .mapNotNull { r -> r.orderedAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } }
                .filter { !it.toInstant().isBefore(startInstant) }
                .minByOrNull { it.toInstant() }
            _state.update {
                it.copy(
                    orderTodayAt = todayAt,
                    orderPendingCount = rows.count { r -> r.status == "pending" },
                    orderUndeliveredCount = rows.count { r -> r.status == "sent" },
                    orderState = OrderState.READY,
                    failed = it.failed - HomeTaskKind.ORDER,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                // 前回読めていれば前回の結果を残す
                it.copy(orderState = if (it.orderState == OrderState.READY) OrderState.READY else OrderState.FAILED, failed = it.failed + HomeTaskKind.ORDER)
            }
        }
    }

    /** 未完了の不具合（件数だけ使う） */
    private suspend fun loadIssues() {
        val today = DateUtil.todayString()
        try {
            val rows = db.from("issues").select(Columns.list("id", "store", "status", "report_date", "priority")) {
                filter { neq("status", "done") }
                limit(2000)
            }.decodeList<IssueHomeRow>()
            // 今日の新規は完了済みも含めて数える（報告日が今日のもの）
            val newRows = db.from("issues").select(Columns.list("id", "store", "status", "report_date")) {
                filter { eq("report_date", today) }
                limit(500)
            }.decodeList<IssueHomeRow>()
            _state.update {
                it.copy(
                    issueOpen = rows.filter { r -> r.status == "open" }.groupingBy { r -> r.store }.eachCount(),
                    issueProgress = rows.filter { r -> r.status == "progress" }.groupingBy { r -> r.store }.eachCount(),
                    issueNewToday = newRows.groupingBy { r -> r.store }.eachCount(),
                    issueAttention = rows.filter { r -> isAttentionIssue(r.status, r.reportDate, r.priority) }
                        .groupingBy { r -> r.store }.eachCount(),
                    issuesLoaded = true,
                    failed = it.failed - HomeTaskKind.FACILITY,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(HomeTaskKind.FACILITY)
        }
    }

    companion object {
        private const val MIN_INTERVAL_MS = 60_000L
        private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun hhmm(t: OffsetDateTime): String = t.atZoneSameInstant(DateUtil.TZ).format(HHMM)

        // MARK: 行の組み立て（iOS版 HomeStore.rows と同じ）

        /** @param cleaningWorking 清掃中（IN を押して未保存）の部屋数。OUT の押し忘れに気づけるよう清掃記録の行に出す */
        fun rows(s: HomeState, hotel: Hotel, sc: HomeSchedule, fridge: FridgeInput, cleaningWorking: Int = 0): List<HomeRow> {
            val c = cleaningRow(s, hotel)
            val list = mutableListOf(if (cleaningWorking > 0) c.copy(detail = c.detail + "・清掃中 ${cleaningWorking}室") else c)
            if (sc.isOrderDay) list += orderRow(s)
            aquaRow(s, hotel, sc)?.let { list += it }
            // 期限確認の期間（25日〜月末）に加えて、期限切れの品があれば期間外でも出す
            val expiredNow = fridge.hotel == hotel && fridge.synced && fridge.expired > 0
            if (sc.isFridgeWindow || expiredNow) list += fridgeRow(hotel, sc, fridge)
            return list
        }

        private fun cleaningRow(s: HomeState, h: Hotel): HomeRow {
            val failed = HomeTaskKind.CLEANING in s.failed
            if (!s.cleaningLoaded) {
                return HomeRow(
                    HomeTaskKind.CLEANING, if (failed) "電波を確認してください" else "毎日", false,
                    if (failed) "確認できません" else "確認中", HomeRow.Tone.MUTED,
                )
            }
            val note = if (failed) "・前回の結果" else ""
            val n = s.cleaningCount[h] ?: 0
            if (n > 0) {
                val last = s.cleaningLastOut[h]?.let { "・最終 $it" } ?: ""
                return HomeRow(HomeTaskKind.CLEANING, "毎日$last$note", true, "${n}室 記録済み", HomeRow.Tone.DONE)
            }
            return HomeRow(HomeTaskKind.CLEANING, "毎日$note", false, "今日", HomeRow.Tone.NORMAL)
        }

        private fun orderRow(s: HomeState): HomeRow {
            val rule = "毎週 水・日"
            val note = if (HomeTaskKind.ORDER in s.failed) "・前回の結果" else ""
            return when (s.orderState) {
                OrderState.LOADING -> HomeRow(HomeTaskKind.ORDER, rule, false, "確認中", HomeRow.Tone.MUTED)
                // 送信済みかどうか分からないので「未完了」とは表示しない
                OrderState.FAILED -> HomeRow(HomeTaskKind.ORDER, "発注を確認できません", false, "確認できません", HomeRow.Tone.MUTED)
                OrderState.READY -> {
                    val at = s.orderTodayAt
                    if (at != null) HomeRow(HomeTaskKind.ORDER, rule + note, true, "${hhmm(at)} 発注済み", HomeRow.Tone.DONE)
                    else HomeRow(HomeTaskKind.ORDER, rule + note, false, "今日", HomeRow.Tone.NORMAL)
                }
            }
        }

        /**
         * 店舗の全水槽に今週の記録があれば完了。
         * 表示は月〜水。ただし最も古い記録から14日を超えた場合は曜日に関係なく表示して要実施にする。
         */
        private fun aquaRow(s: HomeState, h: Hotel, sc: HomeSchedule): HomeRow? {
            val tanks = AquaConst.rooms.filter { it.hotel == h }
            if (tanks.isEmpty()) return null
            val failed = HomeTaskKind.AQUA in s.failed
            if (!s.aquaLoaded) {
                return if (sc.isAquaWindow) HomeRow(
                    HomeTaskKind.AQUA, if (failed) "電波を確認してください" else "月〜水のうち週1回", false,
                    if (failed) "確認できません" else "確認中", HomeRow.Tone.MUTED,
                ) else null
            }
            val latest = tanks.map { s.aquaLatest[it.id] }
            val monday = sc.mondayOfWeek
            val doneCount = latest.count { it != null && !it.isBefore(monday) }
            val allDone = doneCount == tanks.size
            // 記録のない水槽がある場合は「未実施」とみなす
            val oldest: LocalDate? = if (latest.any { it == null }) null else latest.filterNotNull().minOrNull()
            val since = oldest?.let { sc.daysSince(it) } ?: Int.MAX_VALUE
            // 「14日を超えたら」なので、ちょうど14日目はまだ要実施にしない
            val overLimit = !allDone && since > HomeSchedule.AQUA_LIMIT_DAYS
            if (!sc.isAquaWindow && !overLimit) return null

            var detail = "今週 $doneCount/${tanks.size}槽"
            if (oldest != null && !allDone) detail += "・前回 ${sc.md(oldest)}（${since}日前）"
            else if (!allDone && latest.any { it == null }) detail += "・8週間以上記録なしの水槽あり"
            if (failed) detail += "・前回の結果"

            if (allDone) return HomeRow(HomeTaskKind.AQUA, detail, true, "今週 完了", HomeRow.Tone.DONE)
            if (overLimit) return HomeRow(HomeTaskKind.AQUA, detail, false, "2週間未実施", HomeRow.Tone.URGENT)
            val left = sc.aquaDaysLeft
            return HomeRow(
                HomeTaskKind.AQUA, detail, false,
                if (left == 0) "今日まで" else "残り${left}日",
                if (left == 0) HomeRow.Tone.WARN else HomeRow.Tone.NORMAL,
            )
        }

        private fun fridgeRow(h: Hotel, sc: HomeSchedule, f: FridgeInput): HomeRow {
            if (f.hotel != h || !f.synced) {
                return if (f.offline) HomeRow(HomeTaskKind.FRIDGE, "電波を確認してください", false, "確認できません", HomeRow.Tone.MUTED)
                else HomeRow(HomeTaskKind.FRIDGE, "25日〜月末のうち1回", false, "確認中", HomeRow.Tone.MUTED)
            }
            val note = if (f.offline) "・前回の結果" else ""
            if (f.expired > 0) {
                return HomeRow(
                    HomeTaskKind.FRIDGE, "期限切れ ${f.expired}品・要交換 ${f.red}品$note", false,
                    "期限切れ ${f.expired}", HomeRow.Tone.URGENT,
                )
            }
            if (f.red == 0) return HomeRow(HomeTaskKind.FRIDGE, "要交換の品はありません$note", true, "確認済み", HomeRow.Tone.DONE)
            val left = sc.fridgeDaysLeft
            return HomeRow(
                HomeTaskKind.FRIDGE, "要交換 ${f.red}品$note", false,
                if (left == 0) "今日まで" else "残り${left}日",
                when {
                    left == 0 -> HomeRow.Tone.URGENT
                    left <= 2 -> HomeRow.Tone.WARN
                    else -> HomeRow.Tone.NORMAL
                },
            )
        }

        // MARK: 日報（LINE などに貼る。読み込み済みのデータだけで作る。分からない項目は「—」）

        fun dailyReport(s: HomeState, fridge: FridgeInput, sc: HomeSchedule): String {
            val lines = mutableListOf("【HOTEL ELDIA 日報】${sc.mdw(sc.today)}")
            val order = listOf(Hotel.LUXURY, Hotel.MODERN)
            lines += "■清掃 " + if (!s.cleaningLoaded) "—" else
                order.joinToString(" / ") { "${it.english.uppercase()} ${s.cleaningCount[it] ?: 0}室" }
            lines += "■水槽 " + if (!s.aquaLoaded) "—" else {
                val parts = order.mapNotNull { h ->
                    val tanks = AquaConst.rooms.filter { it.hotel == h }
                    if (tanks.isEmpty()) return@mapNotNull null
                    val pending = tanks.filter { t -> s.aquaLatest[t.id]?.isBefore(sc.mondayOfWeek) ?: true }.map { it.id }
                    var p = "${h.english.uppercase()} ${tanks.size - pending.size}/${tanks.size}"
                    if (pending.isNotEmpty()) p += "（未点検: ${pending.joinToString(", ")}）"
                    p
                }
                if (parts.isEmpty()) "—" else "今週の点検 " + parts.joinToString(" / ")
            }
            lines += "■冷蔵庫 " + if (!fridge.synced) "—" else
                (if (fridge.expired > 0) "期限切れ ${fridge.expired}品・" else "") +
                "要交換 ${fridge.red}品・月末交換 ${fridge.orange}品（${fridge.hotel.english.uppercase()}）"
            lines += "■不具合 " + if (!s.issuesLoaded) "—" else
                "新規 ${s.issueNewToday.values.sum()}件・未対応 ${s.issueOpen.values.sum()}件・対応中 ${s.issueProgress.values.sum()}件"
            lines += "■発注 " + when (s.orderState) {
                OrderState.LOADING, OrderState.FAILED -> "—"
                OrderState.READY -> {
                    val parts = mutableListOf<String>()
                    s.orderTodayAt?.let { parts += "今日 ${hhmm(it)} 発注済み" }
                    parts += "承認待ち ${s.orderPendingCount ?: "—"}件"
                    parts += "未納品 ${s.orderUndeliveredCount ?: "—"}件"
                    parts.joinToString("・")
                }
            }
            return lines.joinToString("\n")
        }
    }
}
