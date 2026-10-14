package jp.eldia.hotel.feature.order

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import jp.eldia.hotel.core.DateUtil
import jp.eldia.hotel.core.StatusColor
import jp.eldia.hotel.core.TabRequests
import jp.eldia.hotel.ui.components.ChipRow
import jp.eldia.hotel.ui.components.EldiaCard
import jp.eldia.hotel.ui.components.rememberToast
import kotlinx.coroutines.launch

/** 発注タブの切り替え */
enum class OrderSegment(val label: String, val color: Color) {
    ORDER("発注する", Color(0xFF7CB342)),
    HISTORY("発注履歴", Color(0xFF1E88E5)),
    STOCK("在庫", Color(0xFF8E24AA)),
    ADMIN("管理", Color(0xFF6D4C41)),
}

@Composable
fun OrderTab(modifier: Modifier = Modifier, vm: OrderViewModel = viewModel()) {
    var segment by rememberSaveable { mutableStateOf(OrderSegment.ORDER) }
    val isAdmin by vm.isAdmin.collectAsState()
    // ホーム・ショートカットなど外から区分を指定されたら切り替えて、指定を消す
    val requested by TabRequests.orderSegment.collectAsState()
    LaunchedEffect(requested) {
        val name = requested ?: return@LaunchedEffect
        OrderSegment.entries.firstOrNull { it.name == name }?.let { segment = it }
        TabRequests.orderSegment.value = null
    }
    LaunchedEffect(Unit) { vm.start() }
    // アプリに戻ったら読み直す（30秒に1回まで）
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshIfStale() }
    // 「管理」は発注の管理者だけ
    val segments = OrderSegment.entries.filter { it != OrderSegment.ADMIN || isAdmin }
    if (segment !in segments) segment = OrderSegment.ORDER
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            segments.forEach { s ->
                val on = s == segment
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (on) s.color else Color.Transparent)
                        .clickable { segment = s }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(s.label, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        SyncStatusRow(vm)
        Box(Modifier.fillMaxSize()) {
            when (segment) {
                OrderSegment.ORDER -> OrderScreen(vm) { segment = OrderSegment.HISTORY }
                OrderSegment.HISTORY -> HistoryScreen(vm)
                OrderSegment.STOCK -> StockScreen(vm)
                OrderSegment.ADMIN -> AdminScreen(vm)
            }
        }
    }
}

/** 区分の下の小さな行：リアルタイム同期の印（緑＝同期中）と最終更新時刻 */
@Composable
private fun SyncStatusRow(vm: OrderViewModel) {
    val live by vm.live.collectAsState()
    val orders by vm.orders.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    // ViewModel に読み込み時刻がないので、一覧が変わった・読み直しが終わった時刻をここで覚える
    var updatedAt by rememberSaveable { mutableStateOf<String?>(null) }
    var seenHash by rememberSaveable { mutableStateOf<Int?>(null) }
    var wasLoading by remember { mutableStateOf(false) }
    LaunchedEffect(orders, loading) {
        val finished = wasLoading && !loading
        wasLoading = loading
        val h = orders.hashCode()
        if (orders.isNotEmpty() && (h != seenHash || (finished && error == null))) {
            seenHash = h
            updatedAt = DateUtil.hhmm()
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        Box(Modifier.size(8.dp).background(if (live) StatusColor.done else MaterialTheme.colorScheme.outline, CircleShape))
        Text(
            if (live) "LIVE" else "オフライン",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (live) StatusColor.done else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        updatedAt?.let {
            Text(
                "最終更新 $it",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
fun StockBadge(p: OrderProduct) {
    val lv = p.stockLevel
    if (lv == StockLevel.OK) return
    Text(lv.label, color = lv.color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(lv.color.copy(alpha = 0.15f), RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp))
}

@Composable
private fun ProductThumb(p: OrderProduct) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (!p.imageUrl.isNullOrBlank()) AsyncImage(p.imageUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text("📦")
    }
}

// MARK: - 発注する

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrderScreen(vm: OrderViewModel, onHistory: () -> Unit) {
    val products by vm.products.collectAsState()
    val categories by vm.categories.collectAsState()
    val cart by vm.cart.collectAsState()
    val track by vm.track.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    var search by rememberSaveable { mutableStateOf("") }
    var top by rememberSaveable { mutableStateOf<String?>(null) }
    var qtys by remember { mutableStateOf(mapOf<String, Int>()) }
    var showCart by remember { mutableStateOf(false) }

    val tops = categories.filter { it.parentId == null }
    val list = products.filter { it.isActive }
        .filter { top == null || vm.topCategoryId(it.categoryId) == top }
        .filter { search.isBlank() || it.name.contains(search.trim(), ignoreCase = true) }
        .sortedWith(compareBy({ vm.categoryLabel(it.categoryId) }, { it.sortOrder }, { it.name }))
    val cartMap = cart.associate { it.productId to it.quantity }

    Column(Modifier.fillMaxSize()) {
        track?.let { t ->
            val c = when (t.state) { "sent" -> Color(0xFF43A047); "error" -> Color(0xFFE53935); "pending" -> Color(0xFFF9A825); else -> MaterialTheme.colorScheme.primary }
            Surface(color = c.copy(alpha = 0.12f), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, c.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (t.state == "sending") CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(t.message, color = c, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    if (t.state == "error") TextButton(onClick = { vm.retryTrack() }) { Text("もう一度送る") }
                    if (t.state == "sent" || t.state == "pending") TextButton(onClick = { vm.dismissTrack(); onHistory() }) { Text("履歴へ") }
                    IconButton(onClick = { vm.dismissTrack() }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.Close, "閉じる", Modifier.size(16.dp)) }
                }
            }
        }
        OutlinedTextField(search, { search = it }, placeholder = { Text("商品名で検索") }, singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        ChipRow(listOf<String?>(null) + tops.map { it.id }, top, { id -> id?.let { i -> tops.first { it.id == i }.name } ?: "すべて" },
            OrderSegment.ORDER.color) { top = it }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        PullToRefreshBox(isRefreshing = loading, onRefresh = { vm.refresh() }, modifier = Modifier.weight(1f)) {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()) {
                if (!loading && list.isEmpty()) item { Text("商品がありません", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp)) }
                items(list, key = { it.id }) { p ->
                    val inCart = cartMap[p.id]
                    val q = inCart ?: qtys[p.id] ?: 1
                    Card(colors = CardDefaults.cardColors(containerColor = if (inCart != null) OrderSegment.ORDER.color.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainer)) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProductThumb(p)
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(p.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                                    StockBadge(p)
                                }
                                Text("在庫 ${p.stock} ／ 発注点 ${p.orderPoint} ${vm.unitName(p)}", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            QtyStepper(q) { n -> if (inCart != null) vm.setCart(p.id, n) else qtys = qtys + (p.id to n) }
                            Text(vm.unitName(p), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(28.dp).padding(start = 2.dp))
                            if (inCart != null) {
                                IconButton(onClick = { vm.setCart(p.id, 0) }) { Icon(Icons.Outlined.Check, "追加済み（タップで外す）", tint = OrderSegment.ORDER.color) }
                            } else {
                                IconButton(onClick = { vm.setCart(p.id, q) }) { Icon(Icons.Outlined.Add, "カートに追加") }
                            }
                        }
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Button(
            onClick = { showCart = true }, enabled = cart.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = OrderSegment.ORDER.color, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
        ) {
            Icon(Icons.Outlined.ShoppingCart, null)
            Text(if (cart.isEmpty()) "商品を追加してください" else "発注リスト ${cart.size}品目 を確認", fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp))
        }
    }
    if (showCart) CartSheet(vm, onDismiss = { showCart = false })
}

@Composable
private fun QtyStepper(value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onChange(maxOf(1, value - 1)) },
            contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Remove, "減らす", Modifier.size(16.dp)) }
        Text("$value", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.width(34.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Box(Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onChange(minOf(9999, value + 1)) },
            contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Add, "増やす", Modifier.size(16.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CartSheet(vm: OrderViewModel, onDismiss: () -> Unit) {
    val cart by vm.cart.collectAsState()
    val sendMode by vm.sendMode.collectAsState()
    val sending by vm.submitting.collectAsState()
    val error by vm.submitError.collectAsState()
    var note by rememberSaveable { mutableStateOf("") }
    val toast = rememberToast()
    // 実際に発注される品目数（非表示・削除された商品は除く）
    val orderable = cart.count { vm.product(it.productId)?.isActive == true }
    // 前回のエラーは開き直したら消す（送信中ならそのまま見せる）
    LaunchedEffect(Unit) { if (!vm.submitting.value) vm.clearSubmitError() }
    // 登録できたら閉じる（閉じた後に終わっても上の送信状況バナーで分かる）
    LaunchedEffect(Unit) { vm.submitted.collect { toast("発注しました"); onDismiss() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("発注内容を確認", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            cart.forEach { c ->
                val p = vm.product(c.productId)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p?.name ?: "（削除された商品）", fontWeight = FontWeight.SemiBold)
                        // 管理者が非表示・削除にした商品は発注されない（送信時に外す）ので、ここで知らせる
                        if (p?.isActive != true) {
                            Text("現在は発注できない商品です（発注には含まれません）", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error)
                        } else {
                            Text(vm.categoryLabel(p.categoryId), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    QtyStepper(c.quantity) { vm.setCart(c.productId, it) }
                    Text(vm.unitName(p), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(28.dp).padding(start = 2.dp))
                    IconButton(onClick = { vm.setCart(c.productId, 0) }) { Icon(Icons.Outlined.Close, "外す") }
                }
                HorizontalDivider()
            }
            OutlinedTextField(note, { if (it.length <= 1000) note = it }, label = { Text("備考（任意）") }, placeholder = { Text("納期・特記事項など") },
                minLines = 2, modifier = Modifier.fillMaxWidth())
            if (sendMode == "approval") Text("承認制です。管理者が承認するとLINEに送信されます。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { vm.submit(note) },
                enabled = !sending && orderable > 0,
                colors = ButtonDefaults.buttonColors(containerColor = OrderSegment.ORDER.color, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                else Text("${orderable}品目を発注する", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// MARK: - 発注履歴

private enum class HistoryFilter(val label: String) { ACTIVE("未納品"), PENDING("承認待ち"), FAILED("送信失敗"), DONE("納品済み"), ALL("すべて") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryScreen(vm: OrderViewModel) {
    val orders by vm.orders.collectAsState()
    val loading by vm.loading.collectAsState()
    val isAdmin by vm.isAdmin.collectAsState()
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ACTIVE) }
    val list = orders.filter {
        when (filter) {
            HistoryFilter.ACTIVE -> it.status == "sent"
            HistoryFilter.PENDING -> it.status == "pending"
            HistoryFilter.FAILED -> it.failed
            HistoryFilter.DONE -> it.status == "delivered"
            HistoryFilter.ALL -> true
        }
    }
    Column(Modifier.fillMaxSize()) {
        ChipRow(HistoryFilter.entries, filter, { f ->
            val n = when (f) {
                HistoryFilter.ACTIVE -> orders.count { it.status == "sent" }
                HistoryFilter.PENDING -> orders.count { it.status == "pending" }
                HistoryFilter.FAILED -> orders.count { it.failed }
                else -> 0
            }
            if (n > 0) "${f.label} $n" else f.label
        }, OrderSegment.HISTORY.color) { filter = it }
        PullToRefreshBox(isRefreshing = loading, onRefresh = { vm.refresh() }, modifier = Modifier.weight(1f)) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                if (list.isEmpty()) item { Text("該当する発注はありません", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp)) }
                items(list, key = { it.id }) { o -> OrderCard(vm, o, isAdmin) }
            }
        }
    }
}

@Composable
private fun OrderCard(vm: OrderViewModel, o: OrderRow, isAdmin: Boolean) {
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    val st = o.orderStatus
    fun act(block: suspend () -> String?, ok: String) {
        busy = true
        scope.launch { val e = block(); busy = false; toast(e ?: ok) }
    }
    EldiaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(orderDateLabel(o.orderedAt), fontWeight = FontWeight.Bold)
                Text(o.ordererName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(st.label, color = st.color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(st.color.copy(alpha = 0.15f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp))
        }
        o.note?.takeIf { it.isNotBlank() }?.let {
            Text("備考：$it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)
                .background(Color(0xFFFFF8E1).copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(8.dp).fillMaxWidth(), color = Color(0xFF6D4C00))
        }
        if (!o.notifyError.isNullOrBlank() && o.status != "delivered") {
            Text(o.notifyError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(6.dp))
        o.items.sortedBy { vm.categoryLabel(vm.product(it.productId)?.categoryId) }.forEach { item ->
            val p = vm.product(item.productId)
            val done = item.deliveredAt != null
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(enabled = !busy) {
                act({ vm.setDelivered(o.id, item.id, !done) }, if (done) "納品を取り消しました（在庫から戻しました）" else "納品済みにしました（在庫に加算）")
            }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = done, onCheckedChange = null)
                Column(Modifier.weight(1f)) {
                    Text(p?.name ?: "（削除された商品）", color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    if (done) Text("納品 ${orderDateLabel(item.deliveredAt)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF43A047))
                }
                Text("${item.quantity} ${vm.unitName(p)}", fontWeight = FontWeight.Bold)
            }
        }
        val remaining = o.items.count { it.deliveredAt == null }
        // 発注から日数が経っても届いていない品がある（業者に確認するか、締める）
        val age = orderAgeDays(o.orderedAt)
        val stale = o.status == "sent" && remaining > 0 && age != null && age >= ORDER_STALE_DAYS
        if (stale) {
            Text("⚠ 発注から${age}日・未着 ${remaining}品。業者に確認するか、届かない分を締めてください",
                color = StatusColor.progress, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp))
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (remaining > 0 && o.status != "pending") {
                OutlinedButton(onClick = { act({ vm.markAllDelivered(o.id) }, "すべて納品済みにしました（在庫に加算）") }, enabled = !busy) { Text("残り${remaining}品を納品済みに") }
            }
            if (o.status == "draft") {
                Button(onClick = { act({ vm.notify("notify", o.id) }, "送信しました") }, enabled = !busy) { Text("送信をやり直す") }
            }
        }
        if (isAdmin) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (o.status == "pending") TextButton(onClick = { act({ vm.notify("approve", o.id) }, "承認して送信しました") }, enabled = !busy) { Text("承認して送信") }
                if (o.status == "sent") TextButton(onClick = { act({ vm.notify("resend", o.id) }, "再送信しました") }, enabled = !busy) { Text("再送信") }
                if (stale) TextButton(onClick = { confirmClose = true }, enabled = !busy) { Text("締める") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { confirmDelete = true }, enabled = !busy) { Text("削除", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirmClose) {
        val delivered = o.items.size - o.items.count { it.deliveredAt == null }
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("この発注を締めますか？") },
            text = {
                Text(
                    if (delivered == 0) "1品も届いていないので「取消」にします。在庫は変わりません。"
                    else "届いた${delivered}品はそのまま、未着の${o.items.size - delivered}品は取消として「納品済み」にします。未着の分は在庫に加算しません。",
                )
            },
            confirmButton = { TextButton(onClick = { confirmClose = false; act({ vm.closeOrder(o.id) }, "締めました") }) { Text("締める") } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("キャンセル") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("この発注を削除しますか？") },
            text = { Text("${orderDateLabel(o.orderedAt)}・${o.items.size}品目") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; act({ vm.deleteOrder(o.id) }, "削除しました") }) { Text("削除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") } },
        )
    }
}

// MARK: - 在庫

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StockScreen(vm: OrderViewModel) {
    val products by vm.products.collectAsState()
    val isAdmin by vm.isAdmin.collectAsState()
    val loading by vm.loading.collectAsState()
    var lowOnly by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<OrderProduct?>(null) }
    val groups = products.filter { it.isActive && (!lowOnly || it.stockLevel != StockLevel.OK) }
        .groupBy { vm.categoryLabel(it.categoryId) }.toSortedMap()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = lowOnly, onClick = { lowOnly = !lowOnly }, label = { Text("在庫少のみ") })
            Spacer(Modifier.weight(1f))
            if (!isAdmin) Text("変更は管理者のみ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PullToRefreshBox(isRefreshing = loading, onRefresh = { vm.refresh() }, modifier = Modifier.weight(1f)) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                groups.forEach { (cat, list) ->
                    item(key = "h-$cat") {
                        Text(cat, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(list.sortedWith(compareBy({ it.sortOrder }, { it.name })), key = { it.id }) { p ->
                        Card(onClick = { if (isAdmin) editing = p }, enabled = isAdmin,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer, disabledContentColor = MaterialTheme.colorScheme.onSurface)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(p.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                StockBadge(p)
                                Text("${p.stock}", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = p.stockLevel.color.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(start = 10.dp))
                                Text(" / ${p.orderPoint} ${vm.unitName(p)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { p -> StockEditDialog(vm, p) { editing = null } }
}

@Composable
private fun StockEditDialog(vm: OrderViewModel, p: OrderProduct, onDismiss: () -> Unit) {
    var stock by remember { mutableStateOf(p.stock.toString()) }
    var point by remember { mutableStateOf(p.orderPoint.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(p.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(stock, { v -> stock = v.filter { it.isDigit() }.take(6) }, label = { Text("在庫（${vm.unitName(p)}）") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(point, { v -> point = v.filter { it.isDigit() }.take(6) }, label = { Text("発注点") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    val e = vm.updateStock(p, stock.toIntOrNull() ?: 0, point.toIntOrNull() ?: 0)
                    saving = false
                    if (e == null) onDismiss() else error = e
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
