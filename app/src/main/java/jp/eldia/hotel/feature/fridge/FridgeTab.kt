package jp.eldia.hotel.feature.fridge

import jp.eldia.hotel.ui.components.RouteTransition
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import jp.eldia.hotel.core.AppConfig
import jp.eldia.hotel.core.AppPrefs
import jp.eldia.hotel.core.DateUtil
import jp.eldia.hotel.feature.cleaning.SubHeader
import jp.eldia.hotel.ui.components.ChipRow
import jp.eldia.hotel.ui.components.EldiaCard
import jp.eldia.hotel.ui.components.HotelSegmented
import jp.eldia.hotel.ui.components.PendingSendCard
import jp.eldia.hotel.ui.components.LabeledRow
import jp.eldia.hotel.ui.components.rememberToast
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime

/**
 * 冷蔵庫のルート。画面は文字列で持つ（回転しても戻る）
 * home / room:<部屋> / alerts / restock / history / settings
 */
@Composable
fun FridgeTab(modifier: Modifier = Modifier, vm: FridgeViewModel = viewModel()) {
    val appHotel by AppPrefs.hotel.collectAsState()
    var route by rememberSaveable { mutableStateOf("home") }
    var back by rememberSaveable { mutableStateOf(listOf<String>()) }
    var shownHotel by rememberSaveable { mutableStateOf("") }
    val toast = rememberToast()

    fun go(r: String) { back = back + route; route = r }
    fun pop() { route = back.lastOrNull() ?: "home"; back = back.dropLast(1) }

    LaunchedEffect(appHotel) {
        // 店舗を切り替えたら最初の画面に戻る（回転では戻さない）
        if (shownHotel != appHotel.rawValue) {
            if (shownHotel.isNotEmpty()) { route = "home"; back = emptyList() }
            shownHotel = appHotel.rawValue
        }
        vm.switchHotel(appHotel)
    }
    LaunchedEffect(Unit) { vm.messages.collect { toast(it) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.sync(silent = true) }
    BackHandler(enabled = route != "home") { pop() }

    RouteTransition(route, back.size, modifier) { r ->
        when {
            r.startsWith("room:") -> FridgeRoomScreen(vm, r.removePrefix("room:"), onBack = ::pop,
                onScan = { go("scan:" + r.removePrefix("room:")) })
            r.startsWith("scan:") -> ContinuousExpiryScanScreen(vm, r.removePrefix("scan:"), onClose = ::pop)
            r == "alerts" -> AlertsScreen(vm, onBack = ::pop, onRoom = { go("room:$it") })
            r == "restock" -> RestockScreen(vm, onBack = ::pop)
            r == "history" -> HistoryScreen(vm, onBack = ::pop)
            r == "settings" -> FridgeSettingsScreen(vm, onBack = ::pop)
            else -> FridgeHome(vm, onRoom = { go("room:$it") }, onOpen = { go(it) })
        }
    }
}

// MARK: - フロア一覧

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FridgeHome(vm: FridgeViewModel, onRoom: (String) -> Unit, onOpen: (String) -> Unit) {
    val hotel by vm.hotel.collectAsState()
    val data by vm.data.collectAsState()
    val removed by vm.removed.collectAsState()
    val noDrink by vm.noDrink.collectAsState()
    val loading by vm.loading.collectAsState()
    val online by vm.online.collectAsState()
    val live by vm.live.collectAsState()
    val pendingN = vm.pending.collectAsState().value.size + vm.removedOps.collectAsState().value.size
    val today = DateUtil.today()
    val sum = remember(hotel, data, removed, today) { FridgeSummary.build(hotel, data, removed) }
    var floor by rememberSaveable(hotel) { mutableStateOf("all") }
    var boMode by rememberSaveable(hotel) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val floors = FridgeConst.floors(hotel)

    PullToRefreshBox(isRefreshing = loading, onRefresh = { vm.sync(silent = false) }, modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            GridCells.Adaptive(80.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("冷蔵庫 棚卸管理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        // リアルタイム同期の印（緑＝同期中）
                        Box(Modifier.size(8.dp).background(if (live) FridgeStatus.OK.color else MaterialTheme.colorScheme.outline, CircleShape))
                        IconButton(onClick = { onOpen("alerts") }) {
                            BadgedBox(badge = { if (sum.alerts.isNotEmpty()) Badge { Text("${sum.alerts.size}") } }) {
                                Icon(Icons.Outlined.WarningAmber, "アラート")
                            }
                        }
                        IconButton(onClick = { onOpen("restock") }) {
                            BadgedBox(badge = {
                                if (sum.totalRemoved > 0) Badge(containerColor = FridgeStatus.ORANGE.color) { Text("${sum.totalRemoved}") }
                            }) { Icon(Icons.Outlined.Inventory2, "補充リスト") }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "その他") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("履歴") }, leadingIcon = { Icon(Icons.Outlined.History, null) },
                                    onClick = { menu = false; onOpen("history") })
                                DropdownMenuItem(text = { Text("設定") }, leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                                    onClick = { menu = false; onOpen("settings") })
                            }
                        }
                    }
                    HotelSegmented(hotel, { AppPrefs.setHotel(it) })
                    if (pendingN > 0) PendingSendCard(fridgeVm = vm)
                    if (FridgeConst.isCheckWindow(today)) {
                        val left = FridgeConst.checkDaysLeft(today)
                        Card(colors = CardDefaults.cardColors(containerColor = FridgeStatus.ORANGE.color.copy(alpha = 0.12f))) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Schedule, null, tint = FridgeStatus.ORANGE.color)
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text("期限確認の期間です", fontWeight = FontWeight.Bold)
                                    Text("25日〜月末のうち1回", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(if (left == 0) "今日まで" else "残り${left}日", fontWeight = FontWeight.Bold,
                                    color = if (left <= 2) FridgeStatus.RED.color else FridgeStatus.ORANGE.color)
                            }
                        }
                    }
                    // 期限切れは要交換の中でも特に急ぐので、上に目立たせて出す
                    if (sum.expired > 0) {
                        Card(
                            onClick = { onOpen("alerts") },
                            colors = CardDefaults.cardColors(containerColor = FridgeConst.expiredColor.copy(alpha = 0.12f)),
                            border = BorderStroke(1.dp, FridgeConst.expiredColor.copy(alpha = 0.5f)),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("期限切れ ${sum.expired}品", color = FridgeConst.expiredColor, fontWeight = FontWeight.Bold)
                                    Text("期限の日を過ぎています。すぐに取り出してください", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("一覧 →", color = FridgeConst.expiredColor, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("要交換", sum.red, "品", FridgeStatus.RED.color, Modifier.weight(1f))
                        StatTile("月末交換", sum.orange, "品", FridgeStatus.ORANGE.color, Modifier.weight(1f))
                        StatTile("取り出し中", sum.totalRemoved, "本", MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
                    }
                    val nme = FridgeConst.nextMonthEnd(today)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Legend(FridgeStatus.RED.color, "要即交換")
                        Legend(FridgeStatus.ORANGE.color, "月末交換～${nme.monthValue}/${nme.dayOfMonth}")
                        Legend(FridgeStatus.OK.color, "正常")
                        Legend(FridgeStatus.NONE.color, "未入力")
                        if (noDrink.isNotEmpty() && !boMode) Legend(Color.Gray.copy(alpha = 0.4f), "未使用")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val on = online != false
                        Box(Modifier.size(8.dp).background(if (on) FridgeStatus.OK.color else FridgeStatus.RED.color, CircleShape))
                        Text(if (on) "オンライン" else "オフライン", style = MaterialTheme.typography.labelSmall,
                            color = if (on) FridgeStatus.OK.color else FridgeStatus.RED.color)
                        if (sum.totalRemoved > 0) Pill("${sum.totalRemoved}本取出中", FridgeStatus.ORANGE.color)
                        if (loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(
                            onClick = { boMode = !boMode },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            border = BorderStroke(1.dp, if (boMode) FridgeStatus.ORANGE.color else MaterialTheme.colorScheme.outline),
                        ) {
                            Text(if (boMode) "✕ 完了" else "⊘ BO設定" + if (noDrink.isEmpty()) "" else " (${noDrink.size})",
                                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                                color = if (boMode) FridgeStatus.ORANGE.color else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (boMode) {
                        Text("部屋をタップしてブラックアウトを ON / OFF", color = FridgeStatus.ORANGE.color,
                            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.padding(horizontal = 0.dp)) {
                    ChipRow(listOf("all") + floors.map { it.floor }, floor, { if (it == "all") "全て" else it }, hotel.accent) { floor = it }
                }
            }
            floors.filter { floor == "all" || it.floor == floor }.forEach { f ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(f.floor, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
                items(f.rooms, key = { it }) { r ->
                    RoomCard(r, sum.roomStatus[r] ?: FridgeStatus.NONE, sum.removedCount[r] ?: 0, r in noDrink, boMode) {
                        if (boMode) vm.toggleNoDrink(r) else onRoom(r)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun StatTile(label: String, value: Int, unit: String, color: Color, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$value", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color,
                    fontFamily = FontFamily.Monospace)
                Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
            }
        }
    }
}

@Composable
private fun Legend(c: Color, t: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(c, RoundedCornerShape(2.dp)))
        Text(t, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
fun Pill(text: String, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
}

@Composable
private fun RoomCard(rn: String, st: FridgeStatus, removedN: Int, noDrink: Boolean, boMode: Boolean, onClick: () -> Unit) {
    val tint = if (noDrink || st == FridgeStatus.NONE) null else st.color
    Box {
        Card(
            onClick = onClick,
            colors = CardDefaults.cardColors(containerColor = tint?.copy(alpha = 0.10f) ?: MaterialTheme.colorScheme.surfaceContainer),
            border = when {
                boMode -> BorderStroke(2.dp, FridgeStatus.ORANGE.color)
                tint != null -> BorderStroke(1.dp, tint.copy(alpha = 0.5f))
                else -> null
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).alpha(if (noDrink) 0.5f else 1f),
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(rn, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(if (noDrink) "未使用" else st.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    color = if (noDrink) MaterialTheme.colorScheme.onSurfaceVariant else st.color)
            }
        }
        if (!noDrink && removedN > 0) {
            Badge(containerColor = FridgeStatus.ORANGE.color, modifier = Modifier.align(Alignment.TopEnd).padding(2.dp)) {
                Text("$removedN")
            }
        }
    }
}

// MARK: - アラート（本家 AV）

@Composable
private fun AlertsScreen(vm: FridgeViewModel, onBack: () -> Unit, onRoom: (String) -> Unit) {
    val hotel by vm.hotel.collectAsState()
    val data by vm.data.collectAsState()
    val removed by vm.removed.collectAsState()
    val sum = remember(hotel, data, removed) { FridgeSummary.build(hotel, data, removed) }
    val expired = sum.alerts.filter { it.expired }
    val red = sum.alerts.filter { it.status == FridgeStatus.RED && !it.expired }
    val orange = sum.alerts.filter { it.status == FridgeStatus.ORANGE }
    val summary = sum.alerts.groupBy { it.name.ifEmpty { "?" } }.toSortedMap()

    Column(Modifier.fillMaxSize()) {
        SubHeader("アラート", onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (sum.alerts.isEmpty()) {
                item { Text("問題ありません", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp)) }
            } else {
                item {
                    EldiaCard {
                        Row {
                            Text("SUMMARY", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            Text("合計 ${sum.alerts.size}本", style = MaterialTheme.typography.labelLarge)
                        }
                        summary.forEach { (name, list) ->
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(name, modifier = Modifier.weight(1f))
                                val r = list.count { it.status == FridgeStatus.RED }
                                val o = list.size - r
                                if (r > 0) Pill("$r", FridgeStatus.RED.color)
                                if (o > 0) Pill("$o", FridgeStatus.ORANGE.color)
                            }
                        }
                    }
                }
                alertSection("⛔ 期限切れ（すぐに取り出す）", expired, FridgeConst.expiredColor, removed, vm, onRoom)
                alertSection("⚠ 要即交換（今月末以前）", red, FridgeStatus.RED.color, removed, vm, onRoom)
                alertSection("📅 月末交換対象（来月末まで）", orange, FridgeStatus.ORANGE.color, removed, vm, onRoom)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.alertSection(
    title: String, list: List<FridgeAlert>, color: Color, removed: Set<String>, vm: FridgeViewModel, onRoom: (String) -> Unit,
) {
    if (list.isEmpty()) return
    val notYet = list.filter { it.key !in removed }
    item {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$title(${list.size})", color = color, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (notYet.isNotEmpty()) TextButton(onClick = { vm.bulkRemove(notYet) }) { Text("一括取り出し中 (${notYet.size})") }
        }
    }
    items(list, key = { "${title.first()}${it.key}" }) { a ->
        val gone = a.key in removed
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth().alpha(if (gone) 0.7f else 1f)) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(44.dp).background(if (gone) FridgeStatus.ORANGE.color else color, RoundedCornerShape(2.dp)))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(FridgeConst.slotLabel(a.idx), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace)
                        Text("${a.rn}号室", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (gone) Pill("取り出し中", FridgeStatus.ORANGE.color)
                    }
                    Text(a.name, fontWeight = FontWeight.SemiBold)
                    Text(FridgeConst.md(a.exp), style = MaterialTheme.typography.bodySmall, color = color, fontFamily = FontFamily.Monospace)
                }
                OutlinedButton(onClick = { onRoom(a.rn) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("部屋へ →") }
            }
        }
    }
}

// MARK: - 補充リスト（本家 RestockV）

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RestockScreen(vm: FridgeViewModel, onBack: () -> Unit) {
    val hotel by vm.hotel.collectAsState()
    val data by vm.data.collectAsState()
    val removed by vm.removed.collectAsState()
    val groups = remember(hotel, data, removed) {
        val g = sortedMapOf<String, MutableList<Pair<String, Int>>>()
        for (r in FridgeConst.rooms(hotel)) {
            (data[r] ?: FridgeConst.defaultSlots()).forEachIndexed { i, s ->
                if ("$r-$i" in removed) g.getOrPut(s.name.ifEmpty { "?" }) { mutableListOf() }.add(r to i)
            }
        }
        g
    }
    val total = groups.values.sumOf { it.size }
    Column(Modifier.fillMaxSize()) {
        SubHeader("補充リスト", onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (total == 0) {
                item { Text("取り出し中の商品はありません", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp)) }
            } else {
                item { EldiaCard { LabeledRow("合計", "${total}本") } }
                groups.forEach { (name, list) ->
                    item(key = name) {
                        EldiaCard {
                            Row {
                                Text(name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text("${list.size}本", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                list.forEach { (rn, idx) -> Pill("${FridgeConst.slotLabel(idx)} ${rn}号室", FridgeStatus.ORANGE.color) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - 履歴（端末内）

@Composable
private fun HistoryScreen(vm: FridgeViewModel, onBack: () -> Unit) {
    val history by vm.history.collectAsState()
    Column(Modifier.fillMaxSize()) {
        SubHeader("履歴", onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (history.isEmpty()) item {
                Text("履歴がありません（この端末での操作だけを記録します）", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp))
            }
            items(history, key = { it.key }) { e ->
                val op = e.op ?: "exp"
                val (tag, color) = when (op) {
                    "rename" -> "品目変更" to Color(0xFF1E88E5)
                    "delete" -> "品目削除" to FridgeStatus.RED.color
                    else -> "期限変更" to FridgeStatus.OK.color
                }
                EldiaCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(tag, color)
                        Text("${e.rn}号室" + (e.idx?.let { " / ${FridgeConst.slotLabel(it)}" } ?: ""),
                            style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        Text(mdhm(e.dt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when (op) {
                        "exp" -> {
                            Text(e.name.ifEmpty { "(品目なし)" }, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                            Text("${FridgeConst.md(e.from).ifEmpty { "未設定" }} → ${FridgeConst.md(e.to).ifEmpty { "未設定" }}",
                                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                        "rename" -> Text("${e.from.orEmpty().ifEmpty { "(空)" }} → ${e.to.orEmpty().ifEmpty { "(空)" }}",
                            modifier = Modifier.padding(top = 4.dp))
                        else -> Text(e.from?.takeIf { it.isNotEmpty() } ?: e.name.ifEmpty { "(空)" }, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

private fun mdhm(iso: String): String = runCatching {
    val d = OffsetDateTime.parse(iso).atZoneSameInstant(DateUtil.TZ)
    "%d/%d %02d:%02d".format(d.monthValue, d.dayOfMonth, d.hour, d.minute)
}.getOrDefault(iso.take(16))

// MARK: - 設定（本家 SV）

@Composable
private fun FridgeSettingsScreen(vm: FridgeViewModel, onBack: () -> Unit) {
    val hotel by vm.hotel.collectAsState()
    val data by vm.data.collectAsState()
    val pending by vm.pending.collectAsState()
    val removedOps by vm.removedOps.collectAsState()
    val loading by vm.loading.collectAsState()
    val lastSync by vm.lastSync.collectAsState()
    var confirmPush by remember { mutableStateOf(false) }
    var askPin by remember { mutableStateOf(false) }
    val push by vm.forcePush.collectAsState()
    val pushing = push.running
    val progress = push.done to push.total
    val result = push.result
    val code = FridgeConst.code(hotel)
    val pendingN = pending.count { it.st == code } + removedOps.count { FridgeConst.codeOf(it.key) == code }
    val filled = data.values.sumOf { l -> l.count { it.exp.isNotEmpty() } }

    Column(Modifier.fillMaxSize()) {
        SubHeader("冷蔵庫の設定", onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                EldiaCard {
                    Text("SUPABASE 接続状態", style = MaterialTheme.typography.labelLarge)
                    Text("Supabase接続済", color = FridgeStatus.OK.color, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    Text(AppConfig.SUPABASE_URL.removePrefix("https://"), style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                EldiaCard {
                    Text("このデバイスのローカルデータ", style = MaterialTheme.typography.labelLarge)
                    LabeledRow("店舗", hotel.label)
                    LabeledRow("期限入力済スロット", "${filled}件")
                    LabeledRow("未送信(pending)", "${pendingN}件")
                }
            }
            item {
                EldiaCard {
                    Text("Supabase から取得", style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = { vm.sync(silent = false) }, enabled = !loading, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                        Text(if (loading) "取得中..." else "今すぐ Supabase から取得", Modifier.padding(start = 6.dp))
                    }
                    lastSync?.let {
                        val t = Instant.ofEpochMilli(it).atZone(DateUtil.TZ)
                        Text("最終同期: %02d:%02d".format(t.hour, t.minute), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("最新データを Supabase から取り直します。他デバイスでの変更を即座に反映したいときに使用。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
            item {
                EldiaCard {
                    Text("⚠ 強制送信（他デバイスのデータを上書きする可能性あり）", style = MaterialTheme.typography.labelLarge,
                        color = FridgeStatus.RED.color)
                    if (pushing) {
                        Text("送信中... ${progress.first} / ${progress.second}", modifier = Modifier.padding(top = 6.dp))
                        LinearProgressIndicator(
                            progress = { if (progress.second == 0) 0f else progress.first.toFloat() / progress.second },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    } else {
                        OutlinedButton(onClick = { confirmPush = true }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            border = BorderStroke(1.dp, FridgeStatus.RED.color)) {
                            Icon(Icons.Outlined.CloudUpload, null, Modifier.size(18.dp), tint = FridgeStatus.RED.color)
                            Text("このデバイスのデータを Supabase へ強制送信", Modifier.padding(start = 6.dp), color = FridgeStatus.RED.color)
                        }
                    }
                    result?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                    Text("このデバイスが最新であることが確実な場合のみ使用してください。他デバイスで行われた新しい変更は上書きされます。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
            item {
                EldiaCard {
                    Text("スロット番号ラベルの貼り方", style = MaterialTheme.typography.labelLarge)
                    Text("冷蔵庫の各スロット枠に A1〜D4 のシールを貼ると、取り出した商品の返却場所が一目でわかります。",
                        modifier = Modifier.padding(top = 4.dp))
                    Text("横列： 1・2・3・4（左から）")
                    Text("縦行： A（上段）→ B → C → D（下段）")
                }
            }
        }
    }

    if (confirmPush) {
        AlertDialog(
            onDismissRequest = { confirmPush = false },
            title = { Text("強制送信しますか？") },
            text = { Text("このデバイス（${hotel.label}）の全データを Supabase に上書き保存します。他のデバイスで行われた新しい変更は失われる可能性があります。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmPush = false
                    askPin = true
                }) { Text("強制送信する", color = FridgeStatus.RED.color) }
            },
            dismissButton = { TextButton(onClick = { confirmPush = false }) { Text("キャンセル") } },
        )
    }
    // 強制送信は管理用パスワードを確認してから
    if (askPin) {
        AdminPinDialog("強制送信するにはパスワードを入力してください", onDismiss = { askPin = false }) {
            askPin = false
            vm.startForcePush()
        }
    }
}
