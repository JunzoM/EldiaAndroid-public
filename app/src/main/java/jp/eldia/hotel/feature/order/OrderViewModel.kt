package jp.eldia.hotel.feature.order

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import jp.eldia.hotel.core.AppConfig
import jp.eldia.hotel.core.DateUtil
import jp.eldia.hotel.core.Offline
import jp.eldia.hotel.core.SB
import jp.eldia.hotel.core.freshAccessToken
import jp.eldia.hotel.core.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime

// MARK: - 型（共通プロジェクトの order_* / orders / order_items）

@Serializable
data class OrderCategory(val id: String, val name: String, @SerialName("parent_id") val parentId: String? = null, @SerialName("sort_order") val sortOrder: Int = 0)

@Serializable
data class OrderUnit(val id: String, val name: String, @SerialName("sort_order") val sortOrder: Int = 0)

@Serializable
data class OrderProduct(
    val id: String,
    val name: String,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("unit_id") val unitId: String? = null,
    val stock: Int = 0,
    @SerialName("order_point") val orderPoint: Int = 0,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("image_url") val imageUrl: String? = null,
) {
    /** 在庫切れ（0 で発注点あり）／在庫少（発注点以下） */
    val stockLevel: StockLevel
        get() = when {
            stock <= orderPoint && stock == 0 -> StockLevel.OUT
            stock <= orderPoint -> StockLevel.LOW
            else -> StockLevel.OK
        }
}

enum class StockLevel(val label: String, val color: Color) {
    OUT("在庫切れ", Color(0xFFE53935)),
    LOW("在庫少", Color(0xFFFB8C00)),
    OK("", Color.Unspecified),
}

@Serializable
data class OrderItemRow(
    val id: String,
    @SerialName("product_id") val productId: String? = null,
    val quantity: Int,
    @SerialName("delivered_at") val deliveredAt: String? = null,
)

@Serializable
data class OrderRow(
    val id: String,
    @SerialName("orderer_name") val ordererName: String = "",
    val status: String = "draft",
    @SerialName("ordered_at") val orderedAt: String = "",
    @SerialName("delivered_at") val deliveredAt: String? = null,
    val note: String? = null,
    @SerialName("notify_error") val notifyError: String? = null,
    @SerialName("order_items") val items: List<OrderItemRow> = emptyList(),
) {
    val orderStatus get() = OrderStatus.from(status)
    val failed get() = status == "draft" || (!notifyError.isNullOrBlank() && status != "delivered")
}

enum class OrderStatus(val raw: String, val label: String, val color: Color) {
    DRAFT("draft", "送信待ち", Color(0xFFE53935)),
    PENDING("pending", "承認待ち", Color(0xFFF9A825)),
    SENT("sent", "発注済", Color(0xFF1E88E5)),
    DELIVERED("delivered", "納品済", Color(0xFF43A047)),
    CANCELLED("cancelled", "取消", Color(0xFF8A8A8A));

    companion object { fun from(raw: String) = entries.firstOrNull { it.raw == raw } ?: DRAFT }
}

/** 商品の編集フォーム */
data class ProductInput(
    val name: String = "",
    val categoryId: String? = null,
    val unitId: String? = null,
    val stock: Int = 0,
    val orderPoint: Int = 0,
    val sortOrder: Int = 0,
    val isActive: Boolean = true,
    val imageUrl: String = "",
)

@Serializable
data class CartItem(val productId: String, val quantity: Int)

@Serializable
private data class SettingRow(val key: String, val value: String? = null)

/** 取り消し（画面を閉じた・ログアウト等）は握りつぶさずに投げ直す。それ以外の失敗だけ Result にする */
private inline fun <T> catching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/** 発注した直後の送信状況（画面の上に出す） */
data class SendTrack(val orderId: String, val state: String, val message: String)

/** 発注（共通プロジェクト）。iOS版 OrderSession に相当（Render を通さず Supabase に直接つなぐ） */
class OrderViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("eldia_order", Context.MODE_PRIVATE)
    private val db get() = SB.main

    private val _categories = MutableStateFlow<List<OrderCategory>>(emptyList())
    val categories: StateFlow<List<OrderCategory>> = _categories.asStateFlow()
    private val _units = MutableStateFlow<Map<String, String>>(emptyMap())
    val units: StateFlow<Map<String, String>> = _units.asStateFlow()
    private val _unitList = MutableStateFlow<List<OrderUnit>>(emptyList())
    val unitList: StateFlow<List<OrderUnit>> = _unitList.asStateFlow()
    private val _products = MutableStateFlow<List<OrderProduct>>(emptyList())
    val products: StateFlow<List<OrderProduct>> = _products.asStateFlow()
    private val _orders = MutableStateFlow<List<OrderRow>>(emptyList())
    val orders: StateFlow<List<OrderRow>> = _orders.asStateFlow()
    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()
    private val _sendMode = MutableStateFlow("auto")
    val sendMode: StateFlow<String> = _sendMode.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _live = MutableStateFlow(false)
    val live: StateFlow<Boolean> = _live.asStateFlow()
    private val _track = MutableStateFlow<SendTrack?>(null)
    val track: StateFlow<SendTrack?> = _track.asStateFlow()
    // 発注の送信中フラグとエラー（シートは見るだけ。処理は ViewModel 側で続く）
    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting.asStateFlow()
    private val _submitError = MutableStateFlow<String?>(null)
    val submitError: StateFlow<String?> = _submitError.asStateFlow()
    private val _submitted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** 発注が登録できたら流れる */
    val submitted: SharedFlow<Unit> = _submitted.asSharedFlow()

    private val _cart = MutableStateFlow(
        prefs.getString(KEY_CART, null)?.let {
            runCatching { Offline.json.decodeFromString(ListSerializer(CartItem.serializer()), it) }.getOrNull()
        } ?: emptyList(),
    )
    val cart: StateFlow<List<CartItem>> = _cart.asStateFlow()

    private var started = false
    private var channel: RealtimeChannel? = null
    private var trackJob: Job? = null
    private var refreshJob: Job? = null
    private var lastRefresh = 0L

    fun start() {
        if (started) return
        started = true
        refreshJob = viewModelScope.launch {
            load()
            subscribe()
        }
    }

    /** 引っぱって更新など。読み込み中なら何もしない */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch { load() }
    }

    /** 画面に戻ったとき。30秒以内に読んでいれば何もしない */
    fun refreshIfStale() {
        if (!started) return
        if (SystemClock.elapsedRealtime() - lastRefresh < 30_000) return
        refresh()
    }

    private suspend fun load() {
        lastRefresh = SystemClock.elapsedRealtime()
        _loading.value = true
        try {
            loadCatalog()
            _sendMode.value = db.from("order_settings").select { filter { eq("key", "line_send_mode") } }
                .decodeList<SettingRow>().firstOrNull()?.value ?: "auto"
            _isAdmin.value = catching { db.postgrest.rpc("is_order_admin").decodeAs<Boolean>() }.getOrDefault(false)
            loadOrders()
            _error.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = "読み込みエラー：" + e.userMessage()
        } finally {
            _loading.value = false
        }
    }

    /** 商品・カテゴリ・単位を読み直す */
    private suspend fun loadCatalog() {
        _categories.value = db.from("order_categories").select().decodeList<OrderCategory>()
            .sortedWith(compareBy({ it.sortOrder }, { it.name }))
        val u = db.from("order_units").select().decodeList<OrderUnit>().sortedWith(compareBy({ it.sortOrder }, { it.name }))
        _unitList.value = u
        _units.value = u.associate { it.id to it.name }
        _products.value = db.from("order_products").select { order("sort_order", Order.ASCENDING) }.decodeList<OrderProduct>()
    }

    /** 一覧の読み直しは1つずつ順番に（古い応答が新しい一覧を上書きしないように） */
    private val ordersLock = Mutex()
    /** リアルタイムの変更通知（連続した通知はまとめて1回の読み直しにする） */
    private val reloadTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private suspend fun loadOrders() = ordersLock.withLock {
        _orders.value = db.from("orders").select(Columns.raw("id, orderer_name, status, ordered_at, delivered_at, note, notify_error, order_items(id, product_id, quantity, delivered_at)")) {
            order("ordered_at", Order.DESCENDING)
            limit(300)
        }.decodeList<OrderRow>()
    }

    @OptIn(FlowPreview::class)
    private suspend fun subscribe() {
        catching {
            val ch = db.channel("orders-android")
            listOf("orders", "order_items").forEach { t ->
                ch.postgresChangeFlow<PostgresAction>(schema = "public") { table = t }
                    .onEach { reloadTrigger.tryEmit(Unit) }
                    .launchIn(viewModelScope)
            }
            // 1件の発注で orders + order_items が何件も届くので、落ち着いてから1回だけ読み直す
            reloadTrigger
                .debounce(500)
                .onEach { catching { loadOrders() } }
                .launchIn(viewModelScope)
            // 接続状態をそのまま LIVE 表示に使う（切断・再接続も反映される）。つなぎ直したら取りこぼし分を読み直す
            var subscribedOnce = false
            ch.status
                .onEach { st ->
                    val on = st == RealtimeChannel.Status.SUBSCRIBED
                    _live.value = on
                    if (on) {
                        if (subscribedOnce) reloadTrigger.tryEmit(Unit)
                        subscribedOnce = true
                    }
                }
                .launchIn(viewModelScope)
            ch.subscribe()
            channel = ch
        }.onFailure { _live.value = false }
    }

    override fun onCleared() {
        // viewModelScope はもう止まっているので、外のスコープでチャンネルを外す（冷蔵庫と同じ書き方）
        val ch = channel
        channel = null
        if (ch != null) CoroutineScope(Dispatchers.IO + NonCancellable).launch { runCatching { db.realtime.removeChannel(ch) } }
        super.onCleared()
    }

    // MARK: 参照

    fun product(id: String?) = _products.value.firstOrNull { it.id == id }
    fun unitName(p: OrderProduct?) = p?.unitId?.let { _units.value[it] }.orEmpty()
    fun category(id: String?) = _categories.value.firstOrNull { it.id == id }

    /** 「親 › 子」のカテゴリ名 */
    fun categoryLabel(id: String?): String {
        val c = category(id) ?: return "その他"
        val parent = c.parentId?.let { category(it) }
        return if (parent != null) "${parent.name} › ${c.name}" else c.name
    }

    /** 大分類（親）の ID（親がなければ自分） */
    fun topCategoryId(id: String?): String? {
        val c = category(id) ?: return null
        return c.parentId ?: c.id
    }

    // MARK: カート（端末に保存）

    private fun saveCart(c: List<CartItem>) {
        _cart.value = c
        prefs.edit().putString(KEY_CART, Offline.json.encodeToString(ListSerializer(CartItem.serializer()), c)).apply()
    }

    fun setCart(productId: String, qty: Int) {
        val rest = _cart.value.filterNot { it.productId == productId }
        saveCart(if (qty <= 0) rest else rest + CartItem(productId, qty.coerceAtMost(9999)))
    }

    fun clearCart() = saveCart(emptyList())

    // MARK: 発注

    fun clearSubmitError() { _submitError.value = null }

    /**
     * カートを発注する。画面（シート）を閉じても ViewModel 側で最後まで続ける。
     * 送信中は二重に押しても無視する。結果は submitting / submitError / submitted で見る
     */
    fun submit(note: String) {
        if (_submitting.value) return
        val items = _cart.value.filter { product(it.productId)?.isActive == true }
        if (items.isEmpty()) { _submitError.value = "商品を選んでください"; return }
        _submitting.value = true
        _submitError.value = null
        val requestId = requestIdFor(items, note)
        viewModelScope.launch {
            try {
                // 登録とカートを空にするところまでは途中で止めない（二重発注を防ぐ）
                val res = withContext(NonCancellable) {
                    val r = createOrder(items, note, requestId)
                    clearCart()
                    prefs.edit().remove(KEY_REQUEST).apply()
                    r
                }
                _submitting.value = false
                val id = res["id"]?.jsonPrimitive?.contentOrNull
                if (id == null) {
                    _submitError.value = "発注の登録に失敗しました"
                    return@launch
                }
                val status = res["status"]?.jsonPrimitive?.contentOrNull ?: "draft"
                watch(id, status)
                _submitted.tryEmit(Unit)
                catching { loadOrders() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _submitError.value = e.userMessage()
            } finally {
                _submitting.value = false
            }
        }
    }

    /**
     * 発注の受付番号。同じ内容で送り直したときは同じ番号を使い、サーバー側で二重登録を防ぐ
     * （返事が届かずにもう一度押した場合）。内容が変わったら新しい番号にする
     */
    private fun requestIdFor(items: List<CartItem>, note: String): String {
        val sig = items.sortedBy { it.productId }.joinToString(",") { "${it.productId}:${it.quantity}" } + "|" + note.trim()
        val saved = prefs.getString(KEY_REQUEST, null)?.split("\n", limit = 2)
        if (saved != null && saved.size == 2 && saved[1] == sig) return saved[0]
        val id = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_REQUEST, id + "\n" + sig).apply()
        return id
    }

    private suspend fun createOrder(items: List<CartItem>, note: String, requestId: String): JsonObject {
        fun body(withId: Boolean) = buildJsonObject {
            put("p_items", buildJsonArray {
                items.forEach { c -> addJsonObject { put("product_id", c.productId); put("quantity", c.quantity) } }
            })
            put("p_note", note.trim().takeIf { it.isNotEmpty() })
            if (withId) put("p_client_id", requestId)
        }
        return try {
            db.postgrest.rpc("order_create", body(true)).decodeAs<JsonObject>()
        } catch (e: Exception) {
            // データベースがまだ古い関数（受付番号なし）のとき。関数が見つからない＝何も登録されていないので送り直してよい
            val m = e.message.orEmpty()
            if (e !is CancellationException && m.contains("order_create") && (m.contains("PGRST202") || m.contains("Could not find"))) {
                db.postgrest.rpc("order_create", body(false)).decodeAs<JsonObject>()
            } else throw e
        }
    }

    /** 送信が終わるまで数秒ごとに確認（データベースが自動で LINE を送る） */
    private fun watch(id: String, initial: String) {
        trackJob?.cancel()
        if (initial == "pending") {
            _track.value = SendTrack(id, "pending", "承認待ちです（管理者が承認すると送信されます）")
            return
        }
        _track.value = SendTrack(id, "sending", "送信中…")
        trackJob = viewModelScope.launch {
            repeat(10) {
                delay(2000)
                val o = catching {
                    db.from("orders").select(Columns.raw("id, status, notify_error")) { filter { eq("id", id) } }.decodeSingle<OrderRow>()
                }.getOrNull() ?: return@repeat
                if (o.status != "draft") {
                    _track.value = SendTrack(id, "sent", "LINEに送信しました")
                    return@launch
                }
                if (!o.notifyError.isNullOrBlank()) {
                    _track.value = SendTrack(id, "error", "送信できませんでした：${o.notifyError}")
                    return@launch
                }
            }
            _track.value = SendTrack(id, "error", "送信を確認できませんでした。「もう一度送る」を押してください")
        }
    }

    fun dismissTrack() { trackJob?.cancel(); _track.value = null }

    /** Edge Function order-notify を呼ぶ（notify / approve / resend） */
    suspend fun notify(action: String, orderId: String?): String? = withContext(Dispatchers.IO) {
        // スリープ明けでも期限切れのトークンを送らない
        val token = freshAccessToken() ?: return@withContext "ログインが必要です"
        val conn = URL("${AppConfig.SUPABASE_URL}/functions/v1/order-notify").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("apikey", AppConfig.SUPABASE_ANON_KEY)
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { out ->
                out.write(buildJsonObject { put("action", action); if (orderId != null) put("order_id", orderId) }.toString().toByteArray())
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            val obj = runCatching { Offline.json.parseToJsonElement(body).jsonObject }.getOrNull()
            val err = obj?.get("error")?.jsonPrimitive?.contentOrNull
            val sent = obj?.get("sent")?.jsonPrimitive?.booleanOrNull
            if (orderId != null) catching { loadOrders() }
            when {
                err != null -> err
                code !in 200..299 -> "送信に失敗しました ($code)"
                sent == false -> "送信できませんでした（LINE・メールの設定を確認してください）"
                else -> null
            }
        } catch (e: IOException) {
            "通信できませんでした。電波状況を確認してください"
        } finally {
            conn.disconnect()
        }
    }

    fun retryTrack() {
        val t = _track.value ?: return
        if (t.state == "sending") return
        trackJob?.cancel()
        _track.value = t.copy(state = "sending", message = "送信中…")
        trackJob = viewModelScope.launch {
            val err = notify("notify", t.orderId)
            _track.value = if (err == null) t.copy(state = "sent", message = "LINEに送信しました") else t.copy(state = "error", message = err)
        }
    }

    // MARK: 納品

    suspend fun setDelivered(orderId: String, itemId: String, delivered: Boolean): String? = try {
        db.postgrest.rpc("order_set_item_delivered", buildJsonObject {
            put("p_order", orderId); put("p_item", itemId); put("p_delivered", delivered)
        })
        // 画面をすぐ更新（リアルタイムでも届く）
        _orders.value = _orders.value.map { o ->
            if (o.id != orderId) o else o.copy(items = o.items.map {
                if (it.id == itemId) it.copy(deliveredAt = if (delivered) (it.deliveredAt ?: OffsetDateTime.now().toString()) else null) else it
            })
        }
        catching { loadOrders() }
        // 納品すると在庫が自動で増える（データベースのトリガー）ので、在庫の表示も読み直す
        catching { loadCatalog() }
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.userMessage()
    }

    suspend fun markAllDelivered(orderId: String): String? = try {
        db.postgrest.rpc("order_mark_all_delivered", buildJsonObject { put("p_order", orderId) })
        catching { loadOrders() }
        catching { loadCatalog() }
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.userMessage()
    }

    /**
     * 長く届かない発注を締める（管理者のみ）。1品も届いていなければ取消、
     * 一部届いていれば納品済みにして未着の品数を備考に残す。未着の品は在庫に加算しない
     */
    suspend fun closeOrder(orderId: String): String? = try {
        db.postgrest.rpc("order_close", buildJsonObject { put("p_order", orderId) })
        catching { loadOrders() }
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.userMessage()
    }

    suspend fun deleteOrder(orderId: String): String? = try {
        // 消えた行を返してもらう。空なら RLS で弾かれた（管理者以外）
        val rows = db.from("orders").delete { select(); filter { eq("id", orderId) } }.decodeList<JsonObject>()
        if (rows.isEmpty()) "削除できませんでした（管理者のみ削除できます）"
        else {
            _orders.value = _orders.value.filterNot { it.id == orderId }
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.userMessage()
    }

    // MARK: 在庫（管理者のみ）

    suspend fun updateStock(p: OrderProduct, stock: Int, orderPoint: Int): String? = try {
        val rows = db.from("order_products").update(buildJsonObject { put("stock", stock); put("order_point", orderPoint) }) {
            select()
            filter { eq("id", p.id) }
        }.decodeList<OrderProduct>()
        if (rows.isEmpty()) "変更できませんでした（管理者のみ変更できます）"
        else {
            _products.value = _products.value.map { if (it.id == p.id) rows.first() else it }
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.userMessage()
    }

    // MARK: 商品・カテゴリ・単位の管理（管理者のみ）

    /** 書き込み後に読み直す。行が返らなければ権限なし（RLS で弾かれた） */
    private suspend fun <T> adminWrite(rows: suspend () -> List<T>): String? = try {
        if (rows().isEmpty()) "変更できませんでした（管理者のみ変更できます）"
        else { catching { loadCatalog() }; null }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.userMessage()
    }

    suspend fun saveProduct(id: String?, f: ProductInput): String? {
        if (f.name.isBlank()) return "商品名を入力してください"
        val body = buildJsonObject {
            put("name", f.name.trim())
            put("category_id", f.categoryId)
            put("unit_id", f.unitId)
            put("stock", f.stock)
            put("order_point", f.orderPoint)
            put("sort_order", f.sortOrder)
            put("is_active", f.isActive)
            put("image_url", f.imageUrl.trim().takeIf { it.isNotEmpty() })
        }
        return adminWrite {
            if (id == null) db.from("order_products").insert(body) { select() }.decodeList<OrderProduct>()
            else db.from("order_products").update(body) { select(); filter { eq("id", id) } }.decodeList<OrderProduct>()
        }
    }

    suspend fun deleteProduct(id: String): String? =
        adminWrite { db.from("order_products").delete { select(); filter { eq("id", id) } }.decodeList<OrderProduct>() }

    suspend fun saveCategory(id: String?, name: String, parentId: String?, sortOrder: Int): String? {
        if (name.isBlank()) return "カテゴリ名を入力してください"
        val hasChildren = id != null && _categories.value.any { it.parentId == id }
        val body = buildJsonObject {
            put("name", name.trim())
            put("parent_id", if (hasChildren || parentId == id) null else parentId)
            put("sort_order", sortOrder)
        }
        return adminWrite {
            if (id == null) db.from("order_categories").insert(body) { select() }.decodeList<OrderCategory>()
            else db.from("order_categories").update(body) { select(); filter { eq("id", id) } }.decodeList<OrderCategory>()
        }
    }

    suspend fun deleteCategory(id: String): String? {
        val products = _products.value.count { it.categoryId == id }
        val children = _categories.value.count { it.parentId == id }
        if (products > 0 || children > 0) {
            val parts = listOfNotNull(if (products > 0) "商品 ${products}件" else null, if (children > 0) "子カテゴリ ${children}件" else null)
            return "このカテゴリは${parts.joinToString("・")}で使われているため削除できません。先に移動または削除してください。"
        }
        return adminWrite { db.from("order_categories").delete { select(); filter { eq("id", id) } }.decodeList<OrderCategory>() }
    }

    suspend fun saveUnit(id: String?, name: String, sortOrder: Int): String? {
        if (name.isBlank()) return "単位名を入力してください"
        val body = buildJsonObject { put("name", name.trim()); put("sort_order", sortOrder) }
        return adminWrite {
            if (id == null) db.from("order_units").insert(body) { select() }.decodeList<OrderUnit>()
            else db.from("order_units").update(body) { select(); filter { eq("id", id) } }.decodeList<OrderUnit>()
        }
    }

    suspend fun deleteUnit(id: String): String? {
        val n = _products.value.count { it.unitId == id }
        if (n > 0) return "この単位は商品 ${n}件で使われているため削除できません。"
        return adminWrite { db.from("order_units").delete { select(); filter { eq("id", id) } }.decodeList<OrderUnit>() }
    }

    // MARK: 通知設定（管理者のみ。メールのパスワードは Edge Function の Secrets にあり、ここでは扱わない）

    suspend fun loadSettings(): Map<String, String> =
        db.from("order_settings").select().decodeList<SettingRow>().associate { it.key to (it.value ?: "") }

    suspend fun saveSettings(values: Map<String, String>): String? = try {
        val now = OffsetDateTime.now().toString()
        val rows: List<JsonObject> = values.filterKeys { it in SETTING_KEYS }.map { (k, v) ->
            buildJsonObject { put("key", k); put("value", v); put("updated_at", now) }
        }
        val saved = db.from("order_settings").upsert(rows) { onConflict = "key"; select() }.decodeList<SettingRow>()
        if (saved.isEmpty()) "保存できませんでした（管理者のみ変更できます）"
        else { _sendMode.value = values["line_send_mode"] ?: _sendMode.value; null }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.userMessage()
    }

    companion object {
        /** 保存してよい設定（mail_pass は含めない） */
        val SETTING_KEYS = setOf(
            "line_enabled", "line_send_mode", "line_type", "line_group_id", "line_user_id",
            "mail_enabled", "mail_host", "mail_port", "mail_secure", "mail_user", "mail_from", "mail_to",
        )
        private const val KEY_REQUEST = "eldia_order_request_v1"
        private const val KEY_CART = "eldia_order_cart_v2"
    }
}

/** "2026-10-02T11:38:47+00:00" → "10/2(金) 20:38" */
/** 発注から何日たったか（日本時間の日付で数える）。読めなければ null */
fun orderAgeDays(iso: String?): Long? = runCatching {
    val d = OffsetDateTime.parse(iso).atZoneSameInstant(DateUtil.TZ).toLocalDate()
    java.time.temporal.ChronoUnit.DAYS.between(d, DateUtil.today())
}.getOrNull()

/** 発注からこの日数を過ぎて未着の品が残っていたら「要確認」にする */
const val ORDER_STALE_DAYS = 7L

fun orderDateLabel(iso: String?): String = runCatching {
    val d = OffsetDateTime.parse(iso).atZoneSameInstant(DateUtil.TZ)
    val w = listOf("月", "火", "水", "木", "金", "土", "日")[d.dayOfWeek.value - 1]
    "%d/%d(%s) %02d:%02d".format(d.monthValue, d.dayOfMonth, w, d.hour, d.minute)
}.getOrDefault(iso.orEmpty().take(16))
