package jp.eldia.hotel.feature.aqua

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import jp.eldia.hotel.core.Connectivity
import jp.eldia.hotel.core.DateUtil
import jp.eldia.hotel.core.Hotel
import jp.eldia.hotel.core.Offline
import jp.eldia.hotel.core.Photos
import jp.eldia.hotel.core.SB
import jp.eldia.hotel.core.StatusColor
import jp.eldia.hotel.core.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 水槽のある部屋（iOS版 AquaStore.rooms と同じ） */
data class TankRoom(val id: String, val hotel: Hotel)

object AquaConst {
    val rooms = listOf(
        TankRoom("503", Hotel.LUXURY), TankRoom("420", Hotel.LUXURY), TankRoom("421", Hotel.LUXURY),
        TankRoom("318", Hotel.LUXURY), TankRoom("320", Hotel.LUXURY),
        TankRoom("508", Hotel.MODERN), TankRoom("413", Hotel.MODERN), TankRoom("312", Hotel.MODERN),
    )

    data class Item(val key: String, val label: String, val short: String)

    val maintenanceItems = listOf(
        Item("water_change", "水換え実施", "水換え"),
        Item("glass_lid_clean", "ガラス・フタ清掃", "ガラス・フタ"),
        Item("gravel_clean", "底砂清掃", "底砂清掃"),
        Item("filter_change", "フィルター交換", "フィルター交換"),
        Item("decor_clean", "人口水草・その他清掃", "デコレーション"),
        Item("additive_input", "バクテリア・ビタミン投入", "バクテリア投入"),
        Item("food_refill", "エサの補充", "エサ補充"),
    )

    val teal = Color(0xFF009688)

    /** 正常範囲（境界ちょうども範囲外。iOS版 AquaLimits と同じ） */
    fun phOut(v: Double?) = v != null && v > 0 && (v <= 6.0 || v >= 8.5)
    fun tempOut(v: Double?) = v != null && v > 0 && (v <= 20.0 || v >= 30.0)

    /** 経過日数による状態（14日以上 WARNING / 21日以上 ALERT） */
    fun health(lastDate: String?, today: java.time.LocalDate = DateUtil.today()): Pair<String, Color> {
        val d = DateUtil.parse(lastDate)?.let { ChronoUnit.DAYS.between(it, today) } ?: return "ALERT" to StatusColor.open
        return when {
            d >= 21 -> "ALERT" to StatusColor.open
            d >= 14 -> "WARNING" to StatusColor.progress
            else -> "STABLE" to StatusColor.done
        }
    }
}

/** 点検記録（maintenance_logs）。id は端末で採番した uuid（再送しても二重登録にならない） */
@Serializable
data class MaintenanceLog(
    val id: String? = null,
    @SerialName("room_number") val roomNumber: String = "",
    val date: String = "",
    @SerialName("ph_value") val ph: Double? = null,
    @SerialName("water_temp") val temp: Double? = null,
    @SerialName("water_change") val waterChange: Boolean = false,
    @SerialName("glass_lid_clean") val glassLidClean: Boolean = false,
    @SerialName("gravel_clean") val gravelClean: Boolean = false,
    @SerialName("filter_change") val filterChange: Boolean = false,
    @SerialName("decor_clean") val decorClean: Boolean = false,
    @SerialName("additive_input") val additiveInput: Boolean = false,
    @SerialName("food_refill") val foodRefill: Boolean = false,
    val memo: String? = null,
    val photo: String? = null,
) {
    fun flag(key: String): Boolean = when (key) {
        "water_change" -> waterChange
        "glass_lid_clean" -> glassLidClean
        "gravel_clean" -> gravelClean
        "filter_change" -> filterChange
        "decor_clean" -> decorClean
        "additive_input" -> additiveInput
        "food_refill" -> foodRefill
        else -> false
    }

    companion object {
        fun create(room: String, date: String, ph: Double?, temp: Double?, flags: Set<String>, memo: String?) = MaintenanceLog(
            id = UUID.randomUUID().toString(), roomNumber = room, date = date, ph = ph, temp = temp,
            waterChange = "water_change" in flags, glassLidClean = "glass_lid_clean" in flags,
            gravelClean = "gravel_clean" in flags, filterChange = "filter_change" in flags,
            decorClean = "decor_clean" in flags, additiveInput = "additive_input" in flags,
            foodRefill = "food_refill" in flags, memo = memo,
        )
    }
}

/** 未送信の記録で、送信に失敗したもの（サーバーへは送らない端末だけの情報） */
@Serializable
data class PendingFail(
    val id: String,
    val room: String = "",
    val date: String = "",
    val count: Int = 0,
    val lastError: String = "",
)

@Serializable
private data class TankRow(
    val id: String,
    @SerialName("setup_date") val setupDate: String? = null,
    @SerialName("tank_photo_url") val tankPhotoUrl: String? = null,
)

/** 水槽（共通プロジェクトの maintenance_logs / rooms）。iOS版 AquaStore に相当 */
class AquaViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val db get() = SB.main

    private val _logs = MutableStateFlow<List<MaintenanceLog>>(emptyList())
    /** 読み込み済みの記録（日付降順）。直近8週間＋期間内に記録のない水槽の最新1件＋詳細で読んだ全履歴 */
    val logs: StateFlow<List<MaintenanceLog>> = _logs.asStateFlow()

    private val _photos = MutableStateFlow<Map<String, String>>(emptyMap())
    val photos: StateFlow<Map<String, String>> = _photos.asStateFlow()

    private val _setupDates = MutableStateFlow<Map<String, String>>(emptyMap())
    val setupDates: StateFlow<Map<String, String>> = _setupDates.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _pendingCount = MutableStateFlow(pending().size)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val _pendingFails = MutableStateFlow(loadFails())
    /** 送信に失敗している未送信の記録（エラー内容つき） */
    val pendingFails: StateFlow<List<PendingFail>> = _pendingFails.asStateFlow()

    private val fullHistory = mutableSetOf<String>()
    private var lastLoadedAt = 0L
    private val flushLock = Mutex()
    /** アップロード済みの写真（記録 id → 写真の内容のハッシュ と URL）。再試行で二重アップロードしない */
    private val uploadedPhotos = mutableMapOf<String, Pair<Int, String>>()

    init {
        // 電波が戻ったら未送信分を送る
        Connectivity.init(app)
        viewModelScope.launch {
            Connectivity.online.collect { on -> if (on && pending().isNotEmpty()) flushPending() }
        }
    }

    /** 失敗しても null を返す（キャンセルは握りつぶさない） */
    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    fun latestByRoom(logs: List<MaintenanceLog>): Map<String, MaintenanceLog> =
        logs.groupBy { it.roomNumber }.mapValues { (_, l) -> l.maxBy { it.date } }

    /** @param force false なら直前60秒以内に読み込み済みのとき省く（タブに戻ったとき） */
    suspend fun refresh(force: Boolean = true) {
        if (!force && System.currentTimeMillis() - lastLoadedAt < 60_000) return
        _loading.value = true
        try {
            coroutineScope {
                val a = async { loadLogs() }
                val b = async { loadRooms() }
                a.await(); b.await()
            }
        } finally {
            _loading.value = false
        }
    }

    private suspend fun loadLogs() {
        val since = DateUtil.ymd(DateUtil.today().minusDays(56))
        val recent = safe {
            db.from("maintenance_logs").select {
                filter { gte("date", since) }
                order("date", Order.DESCENDING)
            }.decodeList<MaintenanceLog>()
        } ?: return
        val present = recent.map { it.roomNumber }.toSet()
        val missing = AquaConst.rooms.map { it.id }.filter { it !in present }
        // 期間内に記録のない水槽は最新1件だけ読む（最終点検日を正しく出すため）
        val older = coroutineScope {
            missing.map { r ->
                async {
                    safe {
                        db.from("maintenance_logs").select {
                            filter { eq("room_number", r) }
                            order("date", Order.DESCENDING)
                            limit(1)
                        }.decodeList<MaintenanceLog>().firstOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val kept = _logs.value.filter { it.date < since }
        _logs.value = merge(recent + older + pendingLogs(), kept)
        lastLoadedAt = System.currentTimeMillis()
    }

    private suspend fun loadRooms() {
        safe {
            db.from("rooms").select(Columns.list("id", "setup_date", "tank_photo_url")).decodeList<TankRow>()
        }?.let { rows ->
            _photos.value = rows.mapNotNull { r -> r.tankPhotoUrl?.takeIf { it.isNotBlank() }?.let { r.id to it } }.toMap()
            _setupDates.value = rows.mapNotNull { r -> r.setupDate?.takeIf { it.isNotBlank() }?.let { r.id to it } }.toMap()
        }
    }

    /** 部屋の全期間の記録（詳細を開いたとき）。読み込み済みなら省く */
    suspend fun loadHistory(room: String) {
        if (room in fullHistory) return
        val rows = safe {
            db.from("maintenance_logs").select {
                filter { eq("room_number", room) }
                order("date", Order.DESCENDING)
            }.decodeList<MaintenanceLog>()
        } ?: return
        _logs.value = merge(rows, _logs.value)
        fullHistory += room
    }

    private fun merge(primary: List<MaintenanceLog>, secondary: List<MaintenanceLog>): List<MaintenanceLog> =
        (primary + secondary).distinctBy { it.id ?: "${it.roomNumber}|${it.date}" }.sortedByDescending { it.date }

    /**
     * 記録を保存（写真があれば先にアップロード）。
     * @return true なら電波がないため端末に一時保存した（写真も端末に残し、電波が戻ったら送る）
     */
    suspend fun save(base: MaintenanceLog, photo: ByteArray? = null): Boolean {
        var log = base
        return try {
            if (photo != null && log.photo == null) {
                // 同じ記録・同じ写真の再試行ならアップロード済みの URL を使う
                val key = log.id ?: ""
                val hash = photo.contentHashCode()
                val url = uploadedPhotos[key]?.takeIf { it.first == hash }?.second
                    ?: uploadPhoto(photo).also { uploadedPhotos[key] = hash to it }
                log = log.copy(photo = url)
            }
            insert(log)
            log.id?.let { uploadedPhotos.remove(it) }
            loadLogs()
            false
        } catch (e: Exception) {
            if (!Offline.isOffline(e)) throw e
            val q = pending().toMutableList()
            if (q.none { it.id == log.id }) q.add(log)
            Offline.save(ctx, KEY_PENDING, MaintenanceLog.serializer(), q)
            if (log.photo == null && photo != null) {
                val id = log.id ?: ""
                runCatching { photoFile(id).apply { parentFile?.mkdirs() }.writeBytes(photo) }
            }
            _pendingCount.value = q.size
            _logs.value = merge(listOf(log), _logs.value)
            true
        }
    }

    /** 未送信の記録の写真（id ごとに1枚） */
    private fun photoFile(id: String) = java.io.File(java.io.File(ctx.filesDir, "aqua_pending_photos"), "$id.jpg")

    suspend fun uploadPhoto(bytes: ByteArray): String =
        Photos.upload(bytes, "photos", "aquarium_${System.currentTimeMillis()}.jpg")

    /** 水槽の写真（一覧に出る写真）を変更 */
    suspend fun updateRoomPhoto(room: String, bytes: ByteArray) {
        val url = uploadPhoto(bytes)
        db.from("rooms").update(buildJsonObject { put("tank_photo_url", url) }) { filter { eq("id", room) } }
        _photos.value = _photos.value + (room to url)
    }

    /** 同じ id が既にあれば何もしない（再送で二重登録しない） */
    private suspend fun insert(log: MaintenanceLog) {
        db.from("maintenance_logs").upsert(log) {
            onConflict = "id"
            ignoreDuplicates = true
        }
    }

    private fun pending(): List<MaintenanceLog> = Offline.load(ctx, KEY_PENDING, MaintenanceLog.serializer())
    private fun pendingLogs() = pending()

    suspend fun flushPending() = flushLock.withLock {
        val q = pending()
        if (q.isEmpty()) return@withLock
        val sent = mutableSetOf<String?>()
        val uploaded = mutableMapOf<String, String>()
        val failed = mutableMapOf<String, String>()
        for (p in q) {
            var log = p
            val f = log.id?.let { photoFile(it) }
            try {
                if (log.photo == null && f != null && f.exists()) {
                    val url = uploadPhoto(f.readBytes())
                    log = log.copy(photo = url)
                    uploaded[log.id ?: ""] = url
                }
                insert(log); sent += log.id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (Offline.isDuplicate(e)) sent += log.id
                else if (Offline.isOffline(e)) break
                else failed[log.id ?: ""] = e.userMessage()
            }
        }
        // 写真だけ送れた記録は URL を残して次回の二重アップロードを防ぐ
        val remaining = pending().filter { it.id !in sent }.map { l -> uploaded[l.id ?: ""]?.let { l.copy(photo = it) } ?: l }
        sent.forEach { id -> id?.let { photoFile(it).delete() } }
        Offline.save(ctx, KEY_PENDING, MaintenanceLog.serializer(), remaining)
        _pendingCount.value = remaining.size
        // 失敗の回数と最後のエラーを記録（送れた・消えた分は外す）
        val ids = remaining.mapNotNull { it.id }.toSet()
        val old = _pendingFails.value.associateBy { it.id }
        val fails = remaining.mapNotNull { l ->
            val id = l.id ?: return@mapNotNull null
            val prev = old[id]
            val err = failed[id]
            when {
                err != null -> PendingFail(id, l.roomNumber, l.date, (prev?.count ?: 0) + 1, err)
                else -> prev
            }
        }.filter { it.id in ids }
        saveFails(fails)
        if (sent.isNotEmpty()) loadLogs()
    }

    /** 送れない未送信の記録を破棄する（端末の写真も消す） */
    suspend fun discardPending(id: String) = flushLock.withLock {
        val remaining = pending().filter { it.id != id }
        Offline.save(ctx, KEY_PENDING, MaintenanceLog.serializer(), remaining)
        runCatching { photoFile(id).delete() }
        _pendingCount.value = remaining.size
        saveFails(_pendingFails.value.filter { it.id != id })
        _logs.value = _logs.value.filter { it.id != id }
    }

    private fun loadFails(): List<PendingFail> = Offline.load(ctx, KEY_FAILS, PendingFail.serializer())

    private fun saveFails(list: List<PendingFail>) {
        Offline.save(ctx, KEY_FAILS, PendingFail.serializer(), list)
        _pendingFails.value = list
    }

    // MARK: 生体・購入記録

    private val _inhabitants = MutableStateFlow<List<Inhabitant>>(emptyList())
    val inhabitants: StateFlow<List<Inhabitant>> = _inhabitants.asStateFlow()

    private val _purchases = MutableStateFlow<List<Purchase>>(emptyList())
    val purchases: StateFlow<List<Purchase>> = _purchases.asStateFlow()

    suspend fun loadInhabitants() {
        _inhabitants.value = db.from("inhabitants").select { order("since_date", Order.DESCENDING) }.decodeList<Inhabitant>()
    }

    suspend fun loadPurchases() {
        _purchases.value = db.from("purchases").select { order("purchased_at", Order.DESCENDING) }.decodeList<Purchase>()
    }

    /** 生体の登録（existing が null）または編集。写真があれば先にアップロード */
    suspend fun saveInhabitant(
        existing: Inhabitant?, room: String, name: String, category: String, sinceDate: String?, cost: Double, count: Int, photo: ByteArray?,
    ) {
        val url = photo?.let { uploadPhoto(it) } ?: existing?.photoUrl?.takeIf { it.isNotBlank() }
        val body = buildJsonObject {
            put("name", name)
            put("category", category)
            put("since_date", sinceDate?.takeIf { it.isNotBlank() })
            put("cost", cost)
            put("photo_url", url)
            put("count", count)
            if (existing == null) put("room_number", room)
        }
        val id = existing?.id
        if (id == null) db.from("inhabitants").insert(body)
        else db.from("inhabitants").update(body) { filter { eq("id", id) } }
        runCatching { loadInhabitants() }
    }

    /** 1匹死亡（匹数を1つ減らす。0 未満にはしない）。@return 更新後の生体（失敗は例外） */
    suspend fun recordDeath(fish: Inhabitant): Inhabitant {
        val id = fish.id ?: error("保存されていない生体です")
        // 他の端末の更新と重ならないよう、最新の匹数から減らす
        val latest = db.from("inhabitants").select(Columns.list("count")) { filter { eq("id", id) } }
            .decodeList<CountRow>().firstOrNull()?.count ?: fish.count
        val next = maxOf(0, latest - 1)
        db.from("inhabitants").update(buildJsonObject { put("count", next) }) { filter { eq("id", id) } }
        val updated = fish.copy(count = next)
        _inhabitants.value = _inhabitants.value.map { if (it.id == id) updated else it }
        runCatching { loadInhabitants() }
        return updated
    }

    @Serializable
    private data class CountRow(val count: Int = 1)

    suspend fun addPurchase(
        room: String?, category: String, name: String, cost: Double, purchasedAt: String, memo: String?, photo: ByteArray?,
    ) {
        val url = photo?.let { uploadPhoto(it) }
        db.from("purchases").insert(buildJsonObject {
            put("room_number", room)
            put("category", category)
            put("name", name)
            put("cost", cost)
            put("purchased_at", purchasedAt)
            put("memo", memo?.takeIf { it.isNotBlank() })
            put("photo_url", url)
        })
        runCatching { loadPurchases() }
    }

    companion object {
        private const val KEY_PENDING = "aqua_pending_v1"
        private const val KEY_FAILS = "aqua_pending_fails_v1"
    }
}
