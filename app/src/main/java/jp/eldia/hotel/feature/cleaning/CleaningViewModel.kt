package jp.eldia.hotel.feature.cleaning

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import jp.eldia.hotel.auth.fullName
import jp.eldia.hotel.core.Connectivity
import jp.eldia.hotel.core.DateUtil
import jp.eldia.hotel.core.Hotel
import jp.eldia.hotel.core.Offline
import jp.eldia.hotel.core.SB
import jp.eldia.hotel.core.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 清掃記録（iOS版 CleaningStore と同じ動き）。共通プロジェクトの staff / cleaning_logs を使う。
 * - 担当者はログインの担当者名から自動で選ぶ（名簿にいなければ手動で選ぶ）
 * - 記録は毎回新規追加（同じ部屋でも回数を数える）
 * - 電波がないときは端末に保存し、あとで送る
 */
class CleaningViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val prefs = app.getSharedPreferences("eldia_cleaning", Context.MODE_PRIVATE)
    private val db get() = SB.main

    private val _staffList = MutableStateFlow(loadCachedStaff())
    val staffList: StateFlow<List<Staff>> = _staffList.asStateFlow()

    private val _currentStaff = MutableStateFlow(loadCurrentStaff())
    /** 選んでいる担当者（store は今記録している店舗） */
    val currentStaff: StateFlow<Staff?> = _currentStaff.asStateFlow()

    private val _todayLogs = MutableStateFlow<List<CleaningLog>>(emptyList())
    val todayLogs: StateFlow<List<CleaningLog>> = _todayLogs.asStateFlow()

    private val _pendingCount = MutableStateFlow(pending().size)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val flushLock = Mutex()
    private var todayKey: String? = null

    private val _drafts = MutableStateFlow(loadDrafts())
    /** 清掃中（IN を押して未保存）の部屋。キーは "店舗|部屋" */
    val drafts: StateFlow<Map<String, CleaningDraft>> = _drafts.asStateFlow()

    private val _lastContents = MutableStateFlow(
        prefs.getString(KEY_LAST_CONTENTS, null)?.let {
            runCatching { Offline.json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull()
        } ?: emptyList(),
    )
    /** 前回保存した仕事内容（「前回と同じ」で入れる） */
    val lastContents: StateFlow<List<String>> = _lastContents.asStateFlow()

    init {
        // 電波が戻ったら未送信分を送る
        Connectivity.init(app)
        viewModelScope.launch {
            Connectivity.online.collect { on -> if (on && pending().isNotEmpty()) flushPending() }
        }
    }

    // MARK: 担当者

    fun setCurrentStaff(s: Staff?) {
        _currentStaff.value = s
        if (s == null) prefs.edit().remove(KEY_CURRENT).apply()
        else prefs.edit().putString(KEY_CURRENT, Offline.json.encodeToString(Staff.serializer(), s)).apply()
    }

    fun switchStore(h: Hotel) {
        val s = _currentStaff.value ?: return
        setCurrentStaff(s.copy(store = h.rawValue))
        viewModelScope.launch { loadTodayLogs() }
    }

    suspend fun loadStaff() {
        val rows = db.from("staff").select(Columns.list("name", "store")) {
            filter {
                eq("is_worker", true)
                eq("is_active", true)
            }
            order("name", Order.ASCENDING)
        }.decodeList<Staff>()
        _staffList.value = rows
        prefs.edit().putString(KEY_STAFF, Offline.json.encodeToString(ListSerializer(Staff.serializer()), rows)).apply()
    }

    /** 名簿にない作業者（ログインしない人）を追加する */
    suspend fun addStaff(name: String, store: Hotel): String? = try {
        db.from("staff").upsert(Staff(name.trim(), store.rawValue)) { onConflict = "name" }
        loadStaff()
        null
    } catch (e: Exception) {
        e.userMessage()
    }

    /** ログインの担当者名で自動選択（名簿の作業者にいなければ手動選択にする） */
    suspend fun autoSelect(defaultStore: Hotel) {
        val name = jp.eldia.hotel.core.KnownUser.current()?.fullName
        fun norm(s: String) = s.replace(" ", "").replace("　", "")
        val cur = _currentStaff.value
        if (name != null && cur != null && norm(cur.name) == norm(name)) {
            loadTodayLogs()
            return
        }
        runCatching { loadStaff() }
        val hit = name?.let { n -> _staffList.value.firstOrNull { norm(it.name) == norm(n) } }
        if (hit != null) {
            setCurrentStaff(hit.copy(store = defaultStore.rawValue))
        } else if (cur != null && _staffList.value.isNotEmpty() && _staffList.value.none { it.name == cur.name }) {
            // 名簿にいない担当者（旧表記など）が保存されていたら選び直してもらう
            setCurrentStaff(null)
        }
        loadTodayLogs()
    }

    // MARK: 記録

    suspend fun fetchLogs(store: Hotel, date: String): List<CleaningLog> =
        db.from("cleaning_logs").select {
            filter {
                eq("store", store.rawValue)
                eq("date", date)
            }
            order("time_in", Order.ASCENDING)
        }.decodeList()

    suspend fun fetchLogs(store: Hotel, from: String, to: String): List<CleaningLog> =
        db.from("cleaning_logs").select {
            filter {
                eq("store", store.rawValue)
                // 同じ列に gte と lte を並べると片方しか送られないことがあるため and でまとめる
                and {
                    gte("date", from)
                    lte("date", to)
                }
            }
            order("date", Order.ASCENDING)
        }.decodeList()

    suspend fun loadTodayLogs() {
        val s = _currentStaff.value ?: return
        val today = DateUtil.todayString()
        try {
            val rows = fetchLogs(s.hotel, today)
            if (_currentStaff.value?.store != s.store || DateUtil.todayString() != today) return
            _todayLogs.value = mergePending(rows, today)
            todayKey = "${s.store}|$today"
        } catch (e: Exception) {
            // 通信失敗時は前の表示を残す（日付が変わっていれば前日分は外す）＋未送信分
            _todayLogs.value = mergePending(_todayLogs.value.filter { it.date == today }, today)
        }
    }

    /** 日付か店舗が変わっていたら読み直す（画面に戻ったとき） */
    suspend fun reloadIfNeeded() {
        val s = _currentStaff.value ?: return
        if (todayKey != "${s.store}|${DateUtil.todayString()}") loadTodayLogs()
    }

    /** 直前に保存した記録の id（「元に戻す」で消すため） */
    var lastSavedId: String? = null
        private set

    /** @return エラーメッセージと、端末への一時保存だったか */
    suspend fun saveLog(
        room: String, store: Hotel, date: String, staffName: String,
        contents: List<String>, timeIn: String?, timeOut: String?, memo: String = "",
    ): Pair<String?, Boolean> {
        if (timeIn.isNullOrEmpty() || timeOut.isNullOrEmpty()) return "IN と OUT の時刻を入力してください" to false
        if (contents.isEmpty()) return "仕事内容を選択してください" to false
        if (DateUtil.durationMinutes(timeIn, timeOut) == null) return "OUTがINより前になっています" to false
        val row = CleaningLogWrite(
            id = System.currentTimeMillis().toString(),
            room = room, store = store.rawValue, date = date, staffName = staffName,
            contents = contents, timeIn = timeIn, timeOut = timeOut,
            durationMin = DateUtil.durationMinutes(timeIn, timeOut),
            memo = memo.trim().takeIf { it.isNotEmpty() },
        )
        return try {
            db.from("cleaning_logs").insert(row)
            clearDraft(store, room)
            rememberContents(contents)
            loadTodayLogs()
            lastSavedId = row.id
            null to false
        } catch (e: Exception) {
            if (Offline.isOffline(e)) {
                enqueue(row)
                clearDraft(store, room)
                rememberContents(contents)
                lastSavedId = row.id
                null to true
            } else {
                e.userMessage() to false
            }
        }
    }

    suspend fun deleteLog(id: String): String? {
        // 未送信の記録なら端末から消す（送信中と重ならないよう flushLock 内で）
        val onlyLocal = flushLock.withLock {
            val q = pending()
            if (q.none { it.id == id }) return@withLock false
            val remaining = q.filter { it.id != id }
            Offline.save(ctx, KEY_PENDING, CleaningLogWrite.serializer(), remaining)
            _pendingCount.value = remaining.size
            true
        }
        if (onlyLocal) {
            // サーバーにはまだ無いので送らない
            _todayLogs.value = _todayLogs.value.filter { it.id != id }
            return null
        }
        return try {
            db.from("cleaning_logs").delete { filter { eq("id", id) } }
            loadTodayLogs()
            null
        } catch (e: Exception) {
            e.userMessage()
        }
    }

    /**
     * メモ付きの記録（全スタッフ分）。直近 days 日・新しい順。
     * memo <> '' で null と空文字の両方を除く
     */
    suspend fun fetchMemos(store: Hotel, days: Long = MEMO_DAYS): List<CleaningLog> {
        val today = DateUtil.today()
        val from = DateUtil.ymd(today.minusDays(days - 1))
        val rows: List<CleaningLog> = db.from("cleaning_logs").select {
            filter {
                eq("store", store.rawValue)
                neq("memo", "")
                gte("date", from)
            }
            order("date", Order.DESCENDING)
            order("time_in", Order.DESCENDING)
        }.decodeList()
        val ids = rows.map { it.id }.toSet()
        // 未送信のメモも出す
        val local = pending().filter { it.store == store.rawValue && it.date >= from && it.id !in ids }
            .map { it.toLog() }.filter { it.memoText != null }
        return (rows + local).filter { it.memoText != null }
            .sortedWith(compareByDescending<CleaningLog> { it.date }.thenByDescending { it.timeIn ?: "" })
    }

    /** 本日、部屋ごとのメモ件数（全スタッフ分。忘れ物に気づけるよう部屋の一覧に印を出す） */
    fun roomMemoCounts(logs: List<CleaningLog>, store: Hotel): Map<String, Int> {
        val today = DateUtil.todayString()
        return logs.filter { it.hotel == store && it.date == today && it.memoText != null }
            .groupingBy { it.room }.eachCount()
    }

    /** 本日の部屋ごとの記録回数（ログイン中の担当者の記録のみ。他のスタッフの記録はバッジに出さない） */
    fun roomCounts(logs: List<CleaningLog>, store: Hotel, staffName: String): Map<String, Int> {
        val today = DateUtil.todayString()
        return logs.filter { it.hotel == store && it.date == today && it.staffName == staffName }
            .groupingBy { it.room }.eachCount()
    }

    // MARK: 清掃中（端末だけ）

    fun draft(store: Hotel, room: String): CleaningDraft? = _drafts.value["${store.rawValue}|$room"]

    /** IN が入っていれば清掃中として保存、なければ消す */
    fun updateDraft(d: CleaningDraft) {
        val next = if (d.timeIn.isNullOrEmpty()) _drafts.value - d.key else _drafts.value + (d.key to d)
        if (next != _drafts.value) setDrafts(next)
    }

    fun clearDraft(store: Hotel, room: String) {
        val k = "${store.rawValue}|$room"
        if (k in _drafts.value) setDrafts(_drafts.value - k)
    }

    private fun setDrafts(m: Map<String, CleaningDraft>) {
        _drafts.value = m
        prefs.edit().putString(KEY_DRAFTS, Offline.json.encodeToString(ListSerializer(CleaningDraft.serializer()), m.values.toList())).apply()
    }

    private fun loadDrafts(): Map<String, CleaningDraft> =
        prefs.getString(KEY_DRAFTS, null)?.let {
            runCatching { Offline.json.decodeFromString(ListSerializer(CleaningDraft.serializer()), it) }.getOrNull()
        }.orEmpty().associateBy { it.key }

    private fun rememberContents(c: List<String>) {
        _lastContents.value = c
        prefs.edit().putString(KEY_LAST_CONTENTS, Offline.json.encodeToString(ListSerializer(String.serializer()), c)).apply()
    }

    // MARK: オフライン

    private fun pending(): List<CleaningLogWrite> = Offline.load(ctx, KEY_PENDING, CleaningLogWrite.serializer())

    private fun enqueue(row: CleaningLogWrite) {
        val q = pending().toMutableList()
        if (q.none { it.id == row.id }) q.add(row)
        Offline.save(ctx, KEY_PENDING, CleaningLogWrite.serializer(), q)
        _pendingCount.value = q.size
        if (row.date == DateUtil.todayString() && _todayLogs.value.none { it.id == row.id }) {
            _todayLogs.value = _todayLogs.value + row.toLog()
        }
    }

    /** その日・その店舗の未送信の記録（一覧に「未送信」として出す） */
    fun pendingLogs(store: Hotel, date: String): List<CleaningLog> =
        pending().filter { it.store == store.rawValue && it.date == date }.map { it.toLog() }

    /** サーバーの記録＋未送信の記録（未送信分の id も返す）。通信失敗は例外のまま投げる */
    suspend fun fetchDayLogs(store: Hotel, date: String): DayLogs {
        val rows = fetchLogs(store, date)
        val ids = rows.map { it.id }.toSet()
        val local = pendingLogs(store, date).filter { it.id !in ids }
        return DayLogs((rows + local).sortedBy { it.timeIn ?: "" }, local.map { it.id }.toSet())
    }

    private fun mergePending(logs: List<CleaningLog>, date: String): List<CleaningLog> {
        val ids = logs.map { it.id }.toSet()
        return logs + pending().filter { it.date == date && it.id !in ids }.map { it.toLog() }
    }

    /** 未送信の記録を送る（アプリを開いたとき・電波が戻ったとき） */
    suspend fun flushPending() = flushLock.withLock {
        val q = pending()
        if (q.isEmpty()) return@withLock
        val sent = mutableSetOf<String>()
        for (row in q) {
            try {
                db.from("cleaning_logs").insert(row)
                sent += row.id
            } catch (e: Exception) {
                if (Offline.isDuplicate(e)) sent += row.id
                else if (Offline.isOffline(e)) break
            }
        }
        // 送信中に追加された記録を消さないよう、読み直して送れた分だけ除く
        val remaining = pending().filter { it.id !in sent }
        Offline.save(ctx, KEY_PENDING, CleaningLogWrite.serializer(), remaining)
        _pendingCount.value = remaining.size
        if (sent.isNotEmpty()) loadTodayLogs()
    }

    // MARK: 端末の保存

    private fun loadCachedStaff(): List<Staff> =
        prefs.getString(KEY_STAFF, null)?.let {
            runCatching { Offline.json.decodeFromString(ListSerializer(Staff.serializer()), it) }.getOrNull()
        } ?: emptyList()

    private fun loadCurrentStaff(): Staff? =
        prefs.getString(KEY_CURRENT, null)?.let { runCatching { Offline.json.decodeFromString(Staff.serializer(), it) }.getOrNull() }

    companion object {
        private const val KEY_STAFF = "cleaning_staff_list"
        private const val KEY_CURRENT = "eldia_cleaning_staff"
        private const val KEY_PENDING = "cleaning_pending_v1"
        private const val KEY_DRAFTS = "cleaning_drafts_v1"
        private const val KEY_LAST_CONTENTS = "cleaning_last_contents_v1"
        /** メモ一覧に出す日数 */
        const val MEMO_DAYS = 30L
    }
}
