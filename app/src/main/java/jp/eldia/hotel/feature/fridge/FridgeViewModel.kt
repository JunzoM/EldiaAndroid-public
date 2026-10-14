package jp.eldia.hotel.feature.fridge

import jp.eldia.hotel.core.UndoBar
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import jp.eldia.hotel.core.AppPrefs
import jp.eldia.hotel.core.Connectivity
import jp.eldia.hotel.core.DateUtil
import jp.eldia.hotel.core.Hotel
import jp.eldia.hotel.core.Offline
import jp.eldia.hotel.core.SB
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 冷蔵庫在庫管理（iOS版 FridgeStore と同じ仕様）
 * - 共通プロジェクトの fridge_items / fridge_removed を使用
 * - 各部屋 16 スロット。行が無いスロットはデフォルト品目名・期限なし
 * - 品目削除は行削除ではなく name="" / expiry_date="" で上書き
 * - 端末内にデータと未送信キューを保持し、電波がなくても操作できる（次の同期で再送）
 */
/** 強制送信の進み具合 */
data class ForcePushState(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val result: String? = null)

class FridgeViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("eldia_fridge", Context.MODE_PRIVATE)
    private val db get() = SB.main
    private val json = Offline.json

    private val dataSerializer = MapSerializer(String.serializer(), ListSerializer(Slot.serializer()))
    private val keysSerializer = ListSerializer(String.serializer())

    private val _hotel = MutableStateFlow(AppPrefs.hotel.value)
    val hotel: StateFlow<Hotel> = _hotel.asStateFlow()

    private val _data = MutableStateFlow<Map<String, List<Slot>>>(emptyMap())
    val data: StateFlow<Map<String, List<Slot>>> = _data.asStateFlow()

    private val _removed = MutableStateFlow(loadList("elrv7_keys", String.serializer()).toSet())
    /** 取り出し中のマス（"部屋-スロット"・両店舗分） */
    val removed: StateFlow<Set<String>> = _removed.asStateFlow()

    private val _noDrink = MutableStateFlow(loadList("eldia_ndv1_keys", String.serializer()).toSet())
    /** ブラックアウト（未使用）の部屋・端末内のみ */
    val noDrink: StateFlow<Set<String>> = _noDrink.asStateFlow()

    private val _history = MutableStateFlow(loadList("elhv7", FridgeHistory.serializer()))
    val history: StateFlow<List<FridgeHistory>> = _history.asStateFlow()

    private val _pending = MutableStateFlow(loadList("eldia_pv1", FridgePending.serializer()))
    val pending: StateFlow<List<FridgePending>> = _pending.asStateFlow()

    private val _removedOps = MutableStateFlow(loadList("eldia_rpv1", RemovedOp.serializer()))
    val removedOps: StateFlow<List<RemovedOp>> = _removedOps.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _online = MutableStateFlow<Boolean?>(null)
    /** 直近の通信が届いたか（null = まだ通信していない） */
    val online: StateFlow<Boolean?> = _online.asStateFlow()

    private val _lastSync = MutableStateFlow<Long?>(null)
    val lastSync: StateFlow<Long?> = _lastSync.asStateFlow()

    private val _live = MutableStateFlow(false)
    /** リアルタイム同期がつながっているか（LIVE 表示用） */
    val live: StateFlow<Boolean> = _live.asStateFlow()

    private var channel: RealtimeChannel? = null
    /** リアルタイムの変更通知（連続した通知はまとめて1回の同期にする） */
    private val realtimeTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // タブ非表示中のお知らせも取りこぼさないよう Channel に溜める（溢れたら古いものから捨てる）
    private val _messages = Channel<String>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** 画面下に出す短いお知らせ */
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val lastSyncByHotel = mutableMapOf<Hotel, Long>()
    private val syncMutex = Mutex()
    /** 端末で編集した時刻（ms）。同期中の編集を古いサーバー値で戻さないために使う */
    private val slotEditTs = mutableMapOf<String, Double>()
    private val removedEditTs = mutableMapOf<String, Double>()
    private var lastBackgroundError = 0L

    private val _forcePush = MutableStateFlow(ForcePushState())
    val forcePush: StateFlow<ForcePushState> = _forcePush.asStateFlow()

    /** サーバーへの書き込みは 1 本の列で順番に送る（同じマスへの書き込みが前後しないように） */
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        loadLocal()
        viewModelScope.launch {
            for (w in writes) {
                try {
                    w()
                } catch (e: Exception) {
                    // 個々の失敗は各処理で扱う（画面終了によるキャンセルのときだけ止める）
                    ensureActive()
                }
            }
        }
        // 電波が戻ったら未送信分を送る（同期で新旧を確かめてから送る）
        Connectivity.init(app)
        viewModelScope.launch {
            var prev = Connectivity.online.value
            Connectivity.online.collect { on ->
                if (on && !prev && (_pending.value.isNotEmpty() || _removedOps.value.isNotEmpty())) {
                    syncMutex.withLock { performSync(silent = true) }
                }
                prev = on
            }
        }
        subscribe()
    }

    /** 他の端末の変更をすぐ反映する（fridge_items / fridge_removed の変更通知 → 少し待って同期） */
    @OptIn(FlowPreview::class)
    private fun subscribe() {
        runCatching {
            val ch = db.channel("fridge-android")
            listOf("fridge_items", "fridge_removed").forEach { t ->
                ch.postgresChangeFlow<PostgresAction>(schema = "public") { table = t }
                    .onEach { realtimeTrigger.tryEmit(Unit) }
                    .launchIn(viewModelScope)
            }
            // 一括操作では通知がまとめて届くので、落ち着いてから1回だけ同期（未送信の編集は通常の同期と同じく守る）
            realtimeTrigger
                .debounce(800)
                .onEach { runCatching { syncMutex.withLock { performSync(silent = true, showLoading = false) } } }
                .launchIn(viewModelScope)
            // 接続とチャンネルの状態を LIVE 表示に使う。つなぎ直したら取りこぼし分を同期
            var subscribedOnce = false
            combine(db.realtime.status, ch.status) { conn, st ->
                conn == Realtime.Status.CONNECTED && st == RealtimeChannel.Status.SUBSCRIBED
            }
                .onEach { on ->
                    val wasLive = _live.value
                    _live.value = on
                    if (on && !wasLive) {
                        if (subscribedOnce) realtimeTrigger.tryEmit(Unit)
                        subscribedOnce = true
                    }
                }
                .launchIn(viewModelScope)
            viewModelScope.launch {
                runCatching { ch.subscribe() }.onFailure { _live.value = false }
            }
            channel = ch
        }.onFailure { _live.value = false }
    }

    override fun onCleared() {
        val ch = channel
        channel = null
        // viewModelScope はもう止まっているため、別のスコープで後片付けする
        if (ch != null) CoroutineScope(Dispatchers.IO + NonCancellable).launch { runCatching { db.realtime.removeChannel(ch) } }
        super.onCleared()
    }

    /** 書き込みを列の最後に積む（結果は待たない） */
    private fun enqueueWrite(block: suspend () -> Unit) {
        writes.trySend(block)
    }

    /** 書き込みを列の最後に積み、終わるまで待つ */
    private suspend fun <T> awaitWrite(block: suspend () -> T): T {
        val d = CompletableDeferred<T>()
        writes.send {
            try {
                d.complete(block())
            } catch (e: Throwable) {
                d.completeExceptionally(e)
                throw e
            }
        }
        return d.await()
    }

    private fun say(msg: String) { _messages.trySend(msg) }
    private fun nowMs() = System.currentTimeMillis().toDouble()
    private fun nowIso() = OffsetDateTime.now(ZoneOffset.UTC).toString()

    // MARK: 端末への保存

    private fun <T> loadList(key: String, s: kotlinx.serialization.KSerializer<T>): List<T> =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString(ListSerializer(s), it) }.getOrNull() } ?: emptyList()

    private fun <T> saveList(key: String, s: kotlinx.serialization.KSerializer<T>, v: List<T>) {
        prefs.edit().putString(key, json.encodeToString(ListSerializer(s), v)).apply()
    }

    private fun dataKey(h: Hotel = _hotel.value) = "elv7_${FridgeConst.code(h)}"

    private fun initData(h: Hotel) = FridgeConst.rooms(h).associateWith { FridgeConst.defaultSlots() }

    private fun loadLocal() {
        val h = _hotel.value
        _data.value = prefs.getString(dataKey(h), null)
            ?.let { runCatching { json.decodeFromString(dataSerializer, it) }.getOrNull() }
            ?: initData(h)
    }

    private fun persist() { prefs.edit().putString(dataKey(), json.encodeToString(dataSerializer, _data.value)).apply() }
    private fun persistRemoved() = saveList("elrv7_keys", String.serializer(), _removed.value.toList())
    private fun persistHistory() = saveList("elhv7", FridgeHistory.serializer(), _history.value)

    private fun setPending(q: List<FridgePending>) {
        _pending.value = q
        saveList("eldia_pv1", FridgePending.serializer(), q)
    }

    private fun setRemovedOps(q: List<RemovedOp>) {
        _removedOps.value = q
        saveList("eldia_rpv1", RemovedOp.serializer(), q)
    }

    /** 未送信キューに積むマス（部屋・マス番号・品目名・期限） */
    private data class PendingEdit(val rn: String, val idx: Int, val nm: String, val exp: String)

    private fun addPending(rn: String, idx: Int, nm: String, exp: String) = addPendings(listOf(PendingEdit(rn, idx, nm, exp)))

    /**
     * 複数のマスをまとめて未送信キューに積む（同じマスの古い分は置き換え）。
     * 1 件ずつ積むのと結果は同じで、端末への保存は 1 回だけ
     */
    private fun addPendings(edits: List<PendingEdit>) {
        if (edits.isEmpty()) return
        val keys = edits.map { Triple(FridgeConst.codeOf(it.rn), it.rn, it.idx) }.toSet()
        val now = nowMs()
        // 同じマスが重なったら後の方だけ（1 件ずつ積んだときと同じ並び）
        val lastIndex = HashMap<Pair<String, Int>, Int>()
        edits.forEachIndexed { i, e -> lastIndex[e.rn to e.idx] = i }
        val added = edits.filterIndexed { i, e -> lastIndex[e.rn to e.idx] == i }
            .map { FridgePending(FridgeConst.codeOf(it.rn), it.rn, it.idx, it.nm, it.exp, now) }
        setPending(_pending.value.filterNot { Triple(it.st, it.rn, it.idx) in keys } + added)
        edits.forEach { slotEditTs["${it.rn}-${it.idx}"] = now }
    }

    private fun removePending(rn: String, idx: Int) = removePendings(listOf(rn to idx))

    /** 複数のマスを未送信キューから除く（保存は 1 回だけ） */
    private fun removePendings(slots: List<Pair<String, Int>>) {
        if (slots.isEmpty()) return
        val keys = slots.map { (rn, idx) -> Triple(FridgeConst.codeOf(rn), rn, idx) }.toSet()
        val q = _pending.value.filterNot { Triple(it.st, it.rn, it.idx) in keys }
        if (q.size != _pending.value.size) setPending(q)
    }

    /** 送った内容と同じ未送信分だけを消す（送信中にさらに編集されたものは残す）。保存は 1 回だけ */
    private fun removePendingIfMatches(rows: List<ItemWrite>) {
        if (rows.isEmpty()) return
        val sent = rows.map { SentKey(FridgeConst.codeOf(it.roomNo), it.roomNo, it.slotIdx, it.name, it.expiryDate) }.toSet()
        val q = _pending.value.filterNot { SentKey(it.st, it.rn, it.idx, it.nm, it.exp) in sent }
        if (q.size != _pending.value.size) setPending(q)
    }

    private data class SentKey(val st: String, val rn: String, val idx: Int, val nm: String, val exp: String)

    private fun pushHistory(e: FridgeHistory) {
        _history.value = (listOf(e) + _history.value).take(300)
    }

    // MARK: 参照

    fun slots(rn: String): List<Slot> = _data.value[rn] ?: FridgeConst.defaultSlots()

    private fun Map<String, List<Slot>>.with(rn: String, idx: Int, s: Slot): Map<String, List<Slot>> {
        val list = (this[rn] ?: FridgeConst.defaultSlots()).toMutableList()
        if (idx !in list.indices) return this
        list[idx] = s
        return this + (rn to list)
    }

    // MARK: 店舗切替・同期

    fun switchHotel(h: Hotel) {
        if (h != _hotel.value || _data.value.isEmpty()) {
            _hotel.value = h
            loadLocal()
            _lastSync.value = lastSyncByHotel[h]
        }
        sync(silent = true)
    }

    fun sync(silent: Boolean) {
        viewModelScope.launch { syncNow(silent) }
    }

    /** 自動の同期（silent）は直前 60 秒以内に済んでいれば省く（手動の更新は常に同期）。変更はリアルタイム通知でも同期する */
    suspend fun syncNow(silent: Boolean) = syncMutex.withLock {
        val last = lastSyncByHotel[_hotel.value]
        if (silent && last != null && System.currentTimeMillis() - last < 60_000 && _data.value.isNotEmpty()) return@withLock
        performSync(silent)
    }

    /** showLoading = false はリアルタイム通知からの同期（引っぱって更新の表示を出さない） */
    private suspend fun performSync(silent: Boolean, showLoading: Boolean = true) {
        val h = _hotel.value
        val c = FridgeConst.code(h)
        val startedAt = nowMs()
        if (showLoading) _loading.value = true
        try {
            // 取り出し中の未送信分を先に送る
            awaitWrite { flushRemovedOps() }
            val luxury = h == Hotel.LUXURY
            val items = fetchAll<ItemRow>("fridge_items", "room_no,slot_idx,name,expiry_date,updated_at", "room_no", luxury)
            val remRows = fetchAll<RemovedRow>("fridge_removed", "key", "key", luxury)
            if (_hotel.value != h) return

            if (items.isEmpty()) {
                // サーバーに行がなくても未送信分は送る
                pushPendingIfCurrent(_pending.value.filter { it.st == c })
            } else {
                val (built, srvTs) = withContext(Dispatchers.Default) { buildFromServer(items, h) }
                if (_hotel.value != h) return
                var nd = built
                val local = _data.value
                val toPush = mutableListOf<FridgePending>()
                val resolved = mutableListOf<Pair<String, Int>>()   // キューから除くマス（最後にまとめて除く）
                // 未送信キューの解決（端末側が新しければ端末の値を残して再送）
                for (p in _pending.value.filter { it.st == c }) {
                    val s = nd[p.rn]?.getOrNull(p.idx)
                    if (s != null && s.name == p.nm && s.exp == p.exp) { resolved += p.rn to p.idx; continue }
                    if (p.ts > (srvTs["${p.rn}-${p.idx}"] ?: 0.0)) {
                        local[p.rn]?.getOrNull(p.idx)?.let { nd = nd.with(p.rn, p.idx, it) }
                        toPush += p
                    } else {
                        resolved += p.rn to p.idx
                        say("他の端末の変更を優先しました：${p.rn} ${FridgeConst.slotLabel(p.idx)}")
                    }
                }
                removePendings(resolved)
                // 取得開始後に端末で編集したマスは端末の値を残す
                for ((k, ts) in slotEditTs) {
                    if (ts < startedAt) continue
                    val rn = k.substringBeforeLast("-")
                    val idx = k.substringAfterLast("-").toIntOrNull() ?: continue
                    local[rn]?.getOrNull(idx)?.let { nd = nd.with(rn, idx, it) }
                }
                if (nd != _data.value) { _data.value = nd; persist() }
                pushPendingIfCurrent(toPush)
            }
            if (_hotel.value != h) return
            // 取得したのは現在の店舗分だけなので、現在の店舗のキーだけを置き換える
            val rem = remRows.map { it.key }.filter { FridgeConst.codeOf(it) == c }.toMutableSet()
            for (op in _removedOps.value) {
                if (FridgeConst.codeOf(op.key) != c) continue
                if (op.add) rem += op.key else rem -= op.key
            }
            for ((k, ts) in removedEditTs) {
                if (ts < startedAt || FridgeConst.codeOf(k) != c) continue
                if (k in _removed.value) rem += k else rem -= k
            }
            rem += _removed.value.filter { FridgeConst.codeOf(it) != c }
            if (rem != _removed.value) { _removed.value = rem; persistRemoved() }
            slotEditTs.entries.removeAll { it.value < startedAt }
            removedEditTs.entries.removeAll { it.value < startedAt }
            val now = System.currentTimeMillis()
            lastSyncByHotel[h] = now
            _lastSync.value = now
            _online.value = true
            if (!silent) say("同期完了")
        } catch (e: Exception) {
            _online.value = !Offline.isOffline(e)
            if (!silent) say("Supabase取得失敗")
        } finally {
            if (showLoading) _loading.value = false
        }
    }

    /** 未送信分を送る。列の順番が来た時点でまだ同じ内容のもの（その後に編集されていないもの）だけ送る */
    private suspend fun pushPendingIfCurrent(list: List<FridgePending>) {
        if (list.isEmpty()) return
        awaitWrite {
            val cur = _pending.value.toSet()
            val rows = list.filter { it in cur }.map { ItemWrite(it.rn, it.idx, it.nm, it.exp, nowIso()) }
            saveRows(rows)
        }
    }

    /** サーバーの行 → 部屋ごとのスロットと更新時刻 */
    private fun buildFromServer(items: List<ItemRow>, h: Hotel): Pair<Map<String, List<Slot>>, Map<String, Double>> {
        val nd = FridgeConst.rooms(h).associateWith { FridgeConst.defaultSlots().toMutableList() }
        val srvTs = mutableMapOf<String, Double>()
        for (row in items) {
            val list = nd[row.roomNo] ?: continue
            if (row.slotIdx !in 0..15) continue
            val raw = row.expiryDate ?: ""
            val norm = DateUtil.parse(raw)?.let { d -> DateUtil.ymd(d) } ?: raw
            list[row.slotIdx] = Slot(row.name ?: "", norm)
            row.updatedAt?.let { u ->
                runCatching { OffsetDateTime.parse(u).toInstant().toEpochMilli().toDouble() }.getOrNull()
                    ?.let { ms -> srvTs["${row.roomNo}-${row.slotIdx}"] = ms }
            }
        }
        return nd to srvTs
    }

    /** 1 店舗分の行をページ分けで取得（luxury → "L" で始まる行 / modern → それ以外） */
    private suspend inline fun <reified T : Any> fetchAll(table: String, columns: String, storeColumn: String, luxury: Boolean): List<T> {
        val acc = mutableListOf<T>()
        var offset = 0L
        val page = 1000L
        while (true) {
            val rows = db.from(table).select(Columns.raw(columns)) {
                filter {
                    if (luxury) like(storeColumn, "L%") else filterNot(storeColumn, FilterOperator.LIKE, "L%")
                }
                order("id", Order.ASCENDING)
                range(offset, offset + page - 1)
            }.decodeList<T>()
            acc += rows
            if (rows.size < page) break
            offset += page
        }
        return acc
    }

    /** 複数スロットを 1 回の upsert で保存。成功で未送信キューから除く（失敗分は次回再送） */
    private suspend fun saveRows(rows: List<ItemWrite>): Boolean {
        if (rows.isEmpty()) return true
        return try {
            db.from("fridge_items").upsert(rows) { onConflict = "room_no,slot_idx" }
            removePendingIfMatches(rows)
            _online.value = true
            true
        } catch (e: Exception) {
            if (Offline.isOffline(e)) _online.value = false
            false
        }
    }

    private suspend fun save(rn: String, idx: Int, nm: String, exp: String) =
        saveRows(listOf(ItemWrite(rn, idx, nm, exp, nowIso())))

    /** 取り出し中の追加・解除をキューに積む（最新の操作だけを残す） */
    private fun queueRemoved(keys: List<String>, add: Boolean) {
        if (keys.isEmpty()) return
        val set = keys.toSet()
        setRemovedOps(_removedOps.value.filterNot { it.key in set } + keys.map { RemovedOp(it, add) })
        val now = nowMs()
        keys.forEach { removedEditTs[it] = now }
    }

    private suspend fun sendRemoved(keys: List<String>, add: Boolean): Boolean {
        if (keys.isEmpty()) return true
        return try {
            if (add) {
                db.from("fridge_removed").upsert(keys.map { RemovedRow(it) }) {
                    onConflict = "key"
                    ignoreDuplicates = true
                }
            } else {
                db.from("fridge_removed").delete { filter { isIn("key", keys) } }
            }
            val set = keys.toSet()
            val q = _removedOps.value.filterNot { it.key in set && it.add == add }
            if (q.size != _removedOps.value.size) setRemovedOps(q)
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun flushRemovedOps() {
        val q = _removedOps.value
        if (q.isEmpty()) return
        sendRemoved(q.filter { it.add }.map { it.key }, true)
        sendRemoved(q.filterNot { it.add }.map { it.key }, false)
    }

    private val saveFailed = "SB保存失敗（端末に保存済み・次回同期で再送）"

    // MARK: 操作

    /** 期限を端末に反映して未送信キューに積む。戻り値は品目名 */
    private fun applyExpiryLocally(rn: String, idx: Int, exp: String): String {
        val prev = slots(rn)[idx]
        _data.value = _data.value.with(rn, idx, prev.copy(exp = exp))
        persist()
        pushHistory(FridgeHistory(nowIso(), rn, idx, prev.name, prev.exp, exp, "exp"))
        persistHistory()
        addPending(rn, idx, prev.name, exp)
        return prev.name
    }

    /** 期限を更新（本家 upC） */
    fun updateExpiry(rn: String, idx: Int, exp: String) {
        val prevExp = slots(rn).getOrNull(idx)?.exp.orEmpty()
        val name = applyExpiryLocally(rn, idx, exp)
        enqueueWrite {
            when {
                !save(rn, idx, name, exp) -> say(saveFailed)
                // 変わったときだけ 5 秒間「元に戻す」（押したら前の期限に戻す）
                prevExp != exp -> UndoBar.offer("消費期限を更新しました") { revertExpiry(rn, idx, prevExp) }
                else -> say("消費期限を更新しました")
            }
        }
    }

    /** 「元に戻す」：期限を前の値に戻す（履歴にも残る）。@return エラーメッセージ */
    private suspend fun revertExpiry(rn: String, idx: Int, exp: String): String? {
        val name = applyExpiryLocally(rn, idx, exp)
        return if (awaitWrite { save(rn, idx, name, exp) }) null else saveFailed
    }

    /** 期限を端末に保存してすぐ戻る（連続入力用）。送信失敗の知らせは30秒に1回まで */
    fun updateExpiryQuiet(rn: String, idx: Int, exp: String) {
        val name = applyExpiryLocally(rn, idx, exp)
        enqueueWrite {
            if (!save(rn, idx, name, exp) && System.currentTimeMillis() - lastBackgroundError > 30_000) {
                lastBackgroundError = System.currentTimeMillis()
                say(saveFailed)
            }
        }
    }

    /** 品目名を変更（空なら削除）。all = 現在の店舗の全室（本家 upN） */
    fun updateName(rn: String, idx: Int, name: String, all: Boolean) {
        val targets = if (all) FridgeConst.rooms(_hotel.value) else listOf(rn)
        val now = nowIso()
        var nd = _data.value
        val rem = _removed.value.toMutableSet()
        val unremoved = mutableListOf<String>()
        val edits = mutableListOf<PendingEdit>()
        for (r in targets) {
            val prev = (nd[r] ?: FridgeConst.defaultSlots())[idx]
            val newExp = if (name.isEmpty()) "" else prev.exp
            nd = nd.with(r, idx, Slot(name, newExp))
            edits += PendingEdit(r, idx, name, newExp)
            val k = "$r-$idx"
            if (rem.remove(k)) unremoved += k
            if (prev.name != name) {
                pushHistory(FridgeHistory(now, r, idx, name.ifEmpty { prev.name }, prev.name, name, if (name.isEmpty()) "delete" else "rename"))
            }
        }
        addPendings(edits)
        _data.value = nd
        _removed.value = rem
        queueRemoved(unremoved, false)
        persist()
        persistRemoved()
        persistHistory()
        say(if (name.isEmpty()) "品目を削除しました" else "品目を変更しました")
        val rows = targets.map { r -> slots(r)[idx].let { s -> ItemWrite(r, idx, s.name, s.exp, now) } }
        enqueueWrite {
            val ok = saveRows(rows)
            val okRemoved = sendRemoved(unremoved, false)
            if (!ok) say("SB保存失敗 ${targets.size}件（端末に保存済み・次回同期で再送）")
            else if (!okRemoved) say(saveFailed)
        }
    }

    /** 複数スロットをまとめて削除（本家 bulkDelSlots） */
    fun bulkDelete(rn: String, idxs: List<Int>) {
        val now = nowIso()
        var nd = _data.value
        val rem = _removed.value.toMutableSet()
        val unremoved = mutableListOf<String>()
        val edits = mutableListOf<PendingEdit>()
        for (idx in idxs) {
            val prev = slots(rn)[idx]
            nd = nd.with(rn, idx, Slot("", ""))
            edits += PendingEdit(rn, idx, "", "")
            val k = "$rn-$idx"
            if (rem.remove(k)) unremoved += k
            if (prev.name.isNotEmpty() || prev.exp.isNotEmpty()) {
                pushHistory(FridgeHistory(now, rn, idx, prev.name, prev.exp, "", "delete"))
            }
        }
        addPendings(edits)
        _data.value = nd
        _removed.value = rem
        queueRemoved(unremoved, false)
        persist()
        persistRemoved()
        persistHistory()
        say("${idxs.size}マスを削除しました")
        enqueueWrite {
            val ok = saveRows(idxs.map { ItemWrite(rn, it, "", "", now) })
            val okRemoved = sendRemoved(unremoved, false)
            if (!ok) say("SB保存失敗 ${idxs.size}件（端末に保存済み・次回同期で再送）")
            else if (!okRemoved) say(saveFailed)
        }
    }

    /** 取り出し中 ⇄ 戻した（本家 togR） */
    fun toggleRemoved(rn: String, idx: Int) {
        val k = "$rn-$idx"
        val add = k !in _removed.value
        _removed.value = if (add) _removed.value + k else _removed.value - k
        persistRemoved()
        queueRemoved(listOf(k), add)
        say(if (add) "取り出し中にしました" else "元の位置に戻しました")
        enqueueWrite { if (!sendRemoved(listOf(k), add)) say(saveFailed) }
    }

    fun bulkRemove(items: List<FridgeAlert>) {
        val keys = items.map { it.key }.filter { it !in _removed.value }
        if (keys.isEmpty()) return
        _removed.value = _removed.value + keys
        persistRemoved()
        queueRemoved(keys, true)
        say("一括取り出し中にしました")
        enqueueWrite { if (!sendRemoved(keys, true)) say(saveFailed) }
    }

    /** ブラックアウト（未使用）切替 — 端末内のみ */
    fun toggleNoDrink(rn: String) {
        val on = rn !in _noDrink.value
        _noDrink.value = if (on) _noDrink.value + rn else _noDrink.value - rn
        saveList("eldia_ndv1_keys", String.serializer(), _noDrink.value.toList())
        say(if (on) "ブラックアウトに設定しました" else "ブラックアウトを解除しました")
    }

    /**
     * この端末のデータを Supabase へ強制送信（本家 pushAllLocalToSb）。100件ずつ送る。
     * 画面を離れても止まらないよう viewModelScope で動かす（パスワード確認は画面側）
     */
    fun startForcePush() {
        if (_forcePush.value.running) return
        viewModelScope.launch {
            val now = nowIso()
            val rows = FridgeConst.rooms(_hotel.value).flatMap { r ->
                slots(r).mapIndexed { i, s -> ItemWrite(r, i, s.name, s.exp, now) }
            }
            _forcePush.value = ForcePushState(running = true, total = rows.size)
            var ok = 0
            var err = 0
            try {
                rows.chunked(100).forEach { chunk ->
                    if (awaitWrite { saveRows(chunk) }) ok += chunk.size else err += chunk.size
                    _forcePush.value = _forcePush.value.copy(done = ok + err)
                }
            } finally {
                _forcePush.value = ForcePushState(
                    running = false, done = ok + err, total = rows.size,
                    result = if (err > 0) "送信完了 成功: ${ok}件 / 失敗: ${err}件" else "✅ 送信完了 ${ok}件のデータを保存しました",
                )
            }
        }
    }
}
