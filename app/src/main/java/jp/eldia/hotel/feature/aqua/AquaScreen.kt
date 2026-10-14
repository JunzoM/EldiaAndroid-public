package jp.eldia.hotel.feature.aqua

import jp.eldia.hotel.ui.components.RouteTransition
import jp.eldia.hotel.ui.components.PendingSendCard
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import android.net.Uri
import androidx.compose.material3.TextButton
import jp.eldia.hotel.core.Photos
import jp.eldia.hotel.ui.components.PhotoSourceButtons
import coil3.compose.AsyncImage
import jp.eldia.hotel.core.AppPrefs
import jp.eldia.hotel.core.DateUtil
import jp.eldia.hotel.core.StatusColor
import jp.eldia.hotel.core.userMessage
import jp.eldia.hotel.feature.cleaning.SubHeader
import jp.eldia.hotel.ui.components.DateButton
import jp.eldia.hotel.ui.components.EldiaCard
import jp.eldia.hotel.ui.components.HotelSegmented
import jp.eldia.hotel.ui.components.rememberToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Period
import java.time.temporal.ChronoUnit
import java.time.format.TextStyle
import java.util.Locale

/** 水槽（一覧 → 水槽の詳細／点検の記録）。iOS版 AquaRoot に相当 */
@Composable
fun AquaScreen(vm: AquaViewModel = viewModel()) {
    // "tanks" / "room:503" / "room:503:bio" / "log:503" / "bio:503:<id|new>" / "purchases" / "buy"
    var route by rememberSaveable { mutableStateOf("tanks") }
    LaunchedEffect(Unit) {
        vm.refresh(force = false)
        vm.flushPending()
    }
    // アプリに戻ってきたときも未送信分を送る
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    LaunchedEffect(resumeTick) { if (resumeTick > 0) vm.flushPending() }
    fun parent(r: String) = when {
        r.startsWith("bio:") -> "room:" + r.split(":")[1] + ":bio"
        r == "buy" -> "purchases"
        else -> "tanks"
    }
    BackHandler(enabled = route != "tanks") { route = parent(route) }
    // 階層：水槽一覧 0 → 水槽・購入記録など 1 → 生体・購入の入力 2
    val depth = when (route.substringBefore(":")) {
        "tanks" -> 0
        "bio", "buy" -> 2
        else -> 1
    }
    RouteTransition(route, depth) { r ->
    val parts = r.split(":")
    when (parts[0]) {
        "room" -> TankDetail(vm, parts[1], initialBio = parts.getOrNull(2) == "bio",
            onBack = { route = "tanks" }, onLog = { route = "log:$it" },
            onBio = { id -> route = "bio:${parts[1]}:${id ?: "new"}" })
        "log" -> MaintenanceForm(vm, parts[1], onDone = { route = "tanks" })
        "bio" -> BiologyForm(vm, parts[1], parts.getOrNull(2)?.takeIf { it != "new" }, onDone = { route = parent(r) })
        "purchases" -> PurchasesScreen(vm, onBack = { route = "tanks" }, onAdd = { route = "buy" })
        "buy" -> PurchaseForm(vm, onDone = { route = "purchases" })
        else -> TankList(vm, onOpen = { route = "room:$it" }, onLog = { route = "log:$it" }, onPurchases = { route = "purchases" })
    }
    }
}

private fun fmt1(v: Double?): String = v?.takeIf { it > 0 }?.let { "%.1f".format(it) } ?: "--"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TankList(vm: AquaViewModel, onOpen: (String) -> Unit, onLog: (String) -> Unit, onPurchases: () -> Unit) {
    val hotel by AppPrefs.hotel.collectAsState()
    val logs by vm.logs.collectAsState()
    val photos by vm.photos.collectAsState()
    val loading by vm.loading.collectAsState()
    val pending by vm.pendingCount.collectAsState()
    val fails by vm.pendingFails.collectAsState()
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val latest = vm.latestByRoom(logs)
    val tanks = AquaConst.rooms.filter { it.hotel == hotel }
    // 「今日」は画面に戻るたびに取り直す（日付をまたいでも週の集計・状態が更新されるように）
    var today by remember { mutableStateOf(DateUtil.today()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { today = DateUtil.today() }
    // 今週（月曜以降）に点検した水槽の数。ホームの「水槽チェック」と同じ基準
    val monday = DateUtil.startOfWeekMonday(today)
    val doneN = tanks.count { t -> DateUtil.parse(latest[t.id]?.date)?.let { !it.isBefore(monday) } == true }

    // 引っ張って更新
    PullToRefreshBox(
        isRefreshing = loading,
        onRefresh = {
            today = DateUtil.today()
            scope.launch {
                try {
                    vm.refresh(force = true)
                    vm.flushPending()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toast("更新できませんでした：" + e.userMessage())
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
    LazyVerticalGrid(
        GridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("AQUA管理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onPurchases) {
                        Icon(Icons.Outlined.ShoppingCart, null, Modifier.size(18.dp), tint = AquaConst.teal)
                        Text("購入記録", Modifier.padding(start = 4.dp), color = AquaConst.teal)
                    }
                }
                HotelSegmented(hotel, { AppPrefs.setHotel(it) })
                EldiaCard {
                    Text("今週 $doneN/${tanks.size}槽 点検済み", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("月〜水のうち週1回", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { if (tanks.isEmpty()) 0f else doneN.toFloat() / tanks.size },
                        color = AquaConst.teal, modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (pending > 0) PendingSendCard(aquaVm = vm)
                if (fails.isNotEmpty()) {
                    EldiaCard {
                        Text("送れない点検記録", fontWeight = FontWeight.Bold, color = StatusColor.open)
                        // 電波以外の理由で送れない記録（エラー内容と破棄ボタン）
                        fails.forEach { f ->
                            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${f.room}・${f.date}（${f.count}回失敗）", style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold)
                                    Text(f.lastError, style = MaterialTheme.typography.bodySmall, color = StatusColor.open)
                                }
                                TextButton(onClick = { scope.launch { vm.discardPending(f.id) } }) {
                                    Text("破棄", color = StatusColor.open)
                                }
                            }
                        }
                    }
                }
                if (loading && logs.isEmpty()) CircularProgressIndicator()
            }
        }
        items(tanks, key = { it.id }) { t ->
            val last = latest[t.id]
            val (label, color) = AquaConst.health(last?.date, today)
            Card(
                onClick = { onOpen(t.id) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = if (label == "STABLE") null else BorderStroke(1.5.dp, color.copy(alpha = 0.6f)),
            ) {
                Box(Modifier.fillMaxWidth().height(96.dp).background(AquaConst.teal.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    val p = photos[t.id]
                    if (p != null) {
                        AsyncImage(model = p, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Icon(Icons.Outlined.WaterDrop, null, tint = AquaConst.teal.copy(alpha = 0.5f), modifier = Modifier.size(36.dp))
                    }
                }
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.hotel.english, color = t.hotel.accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Text(t.id, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                        FilledIconButton(onClick = { onLog(t.id) }, modifier = Modifier.size(34.dp),
                            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(containerColor = AquaConst.teal)) {
                            Icon(Icons.Filled.Add, contentDescription = "記録", tint = Color.White)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("pH ${fmt1(last?.ph)}", style = MaterialTheme.typography.bodySmall,
                            color = if (AquaConst.phOut(last?.ph)) StatusColor.open else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${fmt1(last?.temp)}℃", style = MaterialTheme.typography.bodySmall,
                            color = if (AquaConst.tempOut(last?.temp)) StatusColor.open else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Last ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(DateUtil.parse(last?.date)?.let(DateUtil::md) ?: "未点検", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        Text(label, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(24.dp)) }
    }
    }
}

// MARK: - 水槽の詳細

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TankDetail(
    vm: AquaViewModel, room: String, initialBio: Boolean, onBack: () -> Unit, onLog: (String) -> Unit, onBio: (String?) -> Unit,
) {
    val logs by vm.logs.collectAsState()
    val setupDates by vm.setupDates.collectAsState()
    var loadingHistory by remember { mutableStateOf(true) }
    LaunchedEffect(room) { vm.loadHistory(room); loadingHistory = false }

    val list = logs.filter { it.roomNumber == room }.sortedByDescending { it.date }
    val setup = setupDates[room]
    val setupDate = DateUtil.parse(setup)
    var today by remember { mutableStateOf(DateUtil.today()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { today = DateUtil.today() }
    val (label, color) = AquaConst.health(list.firstOrNull()?.date, today)
    val photos by vm.photos.collectAsState()
    var changingPhoto by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val ctx = LocalContext.current
    var bioTab by rememberSaveable { mutableStateOf(initialBio) }
    val inhabitants by vm.inhabitants.collectAsState()
    var bioLoading by remember { mutableStateOf(false) }
    var bioError by remember { mutableStateOf<String?>(null) }
    var selectedBio by remember { mutableStateOf<Inhabitant?>(null) }
    LaunchedEffect(bioTab) {
        if (!bioTab) return@LaunchedEffect
        bioLoading = inhabitants.isEmpty()
        try { vm.loadInhabitants(); bioError = null } catch (e: Exception) { if (inhabitants.isEmpty()) bioError = e.userMessage() }
        bioLoading = false
    }

    Column(Modifier.fillMaxSize()) {
        SubHeader("Room $room", onBack) {
            Button(onClick = { if (bioTab) onBio(null) else onLog(room) },
                colors = ButtonDefaults.buttonColors(containerColor = AquaConst.teal)) { Text(if (bioTab) "登録" else "記録") }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            listOf(false to "メンテナンス", true to "生体").forEachIndexed { i, (v, label) ->
                SegmentedButton(selected = bioTab == v, onClick = { bioTab = v }, shape = SegmentedButtonDefaults.itemShape(i, 2),
                    colors = SegmentedButtonDefaults.colors(activeContainerColor = AquaConst.teal.copy(alpha = 0.18f))) { Text(label) }
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                EldiaCard {
                    Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)).background(AquaConst.teal.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center) {
                        val p = photos[room]
                        if (p != null) AsyncImage(model = p, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Icon(Icons.Outlined.WaterDrop, null, tint = AquaConst.teal.copy(alpha = 0.5f), modifier = Modifier.size(40.dp))
                        if (changingPhoto) CircularProgressIndicator(color = Color.White)
                    }
                    Row(Modifier.padding(top = 8.dp)) {
                        PhotoSourceButtons(max = 1, enabled = !changingPhoto) { uris ->
                            val uri = uris.firstOrNull() ?: return@PhotoSourceButtons
                            changingPhoto = true
                            scope.launch {
                                try {
                                    vm.updateRoomPhoto(room, Photos.jpeg(ctx, uri))
                                    toast("水槽の写真を変更しました")
                                } catch (e: Exception) {
                                    toast("写真を変更できませんでした：" + e.userMessage())
                                } finally {
                                    changingPhoto = false
                                }
                            }
                        }
                    }
                }
            }
            item {
                EldiaCard {
                    Text("Setup: ${setup ?: "Pending"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (setupDate != null) {
                        val p = Period.between(setupDate, today)
                        val total = ChronoUnit.DAYS.between(setupDate, today)
                        Row(Modifier.padding(top = 8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Running Time", style = MaterialTheme.typography.labelSmall)
                                Text("${p.years}y ${p.months}m ${p.days}d", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Total Days", style = MaterialTheme.typography.labelSmall)
                                Text("$total days", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Health Check", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        Text(label, color = color, fontWeight = FontWeight.Black)
                    }
                }
            }
            if (bioTab) {
                item { Text("生体 (Biology)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                biologyItems(inhabitants.filter { it.roomNumber == room }, bioLoading, bioError) { selectedBio = it }
                return@LazyColumn
            }
            item { Text("Maintenance Logs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            if (list.isEmpty()) {
                item {
                    if (loadingHistory) CircularProgressIndicator()
                    else Text("記録がありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(list, key = { it.id ?: "${it.roomNumber}|${it.date}" }) { log ->
                EldiaCard {
                    val d = DateUtil.parse(log.date)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (d != null) {
                            Text("${d.dayOfMonth}", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.JAPANESE), style = MaterialTheme.typography.labelLarge)
                                Text("${d.year}, ${d.monthValue}月", style = MaterialTheme.typography.labelSmall)
                            }
                        } else Text(log.date)
                    }
                    log.photo?.takeIf { it.isNotBlank() }?.let { url ->
                        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp)))
                    }
                    Text(log.memo?.takeIf { it.isNotBlank() } ?: "本日の定期メンテナンスを完了しました。",
                        Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AquaConst.maintenanceItems.filter { log.flag(it.key) }.forEach { Tag(it.short, AquaConst.teal) }
                        Tag("pH ${fmt1(log.ph)}", if (AquaConst.phOut(log.ph)) StatusColor.open else Color(0xFF1E88E5))
                        Tag("${fmt1(log.temp)}℃", if (AquaConst.tempOut(log.temp)) StatusColor.open else StatusColor.progress)
                    }
                }
            }
        }
    }
    selectedBio?.let { fish ->
        BiologyDetailDialog(
            fish,
            onDismiss = { selectedBio = null },
            onEdit = {
                selectedBio = null
                onBio(fish.id)
            },
            onDeath = {
                try {
                    selectedBio = vm.recordDeath(fish)
                    toast("1匹死亡を記録しました")
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.userMessage()
                }
            },
        )
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
}

// MARK: - 点検の記録

@Composable
private fun MaintenanceForm(vm: AquaViewModel, room: String, onDone: () -> Unit) {
    val initialDate = rememberSaveable { DateUtil.todayString() }
    var date by rememberSaveable { mutableStateOf(initialDate) }
    // 未測定の値を実測値として保存しないよう、空（未入力）から始める
    var ph by rememberSaveable { mutableStateOf<Double?>(null) }
    var temp by rememberSaveable { mutableStateOf<Double?>(null) }
    var flags by rememberSaveable { mutableStateOf(setOf<String>()) }
    var memo by rememberSaveable { mutableStateOf("") }
    var photo by rememberSaveable { mutableStateOf<String?>(null) }
    // 記録の id は画面ごとに固定（保存の再試行で二重登録・写真の二重アップロードをしない）
    val logId by rememberSaveable { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val ctx = LocalContext.current
    // 入力途中で戻るときは確認する
    val dirty = date != initialDate || ph != null || temp != null || flags.isNotEmpty() || memo.isNotBlank() || photo != null
    val leave = rememberDiscardGuard(dirty && !saving, onDone)

    Column(Modifier.fillMaxSize()) {
        SubHeader("ROOM $room", leave)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EldiaCard {
                Text("清掃日", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                DateButton(DateUtil.parse(date) ?: DateUtil.today(), { d ->
                    if (isFutureDate(d)) toast(FUTURE_DATE_MESSAGE) else date = DateUtil.ymd(d)
                })
                Stepper("PH LEVEL", ph, "", 0.0, 14.0, 7.0, AquaConst.phOut(ph)) { ph = it }
                Stepper("温度", temp, "℃", 0.0, 40.0, 26.0, AquaConst.tempOut(temp)) { temp = it }
                Text("測っていない値は「--」のまま保存できます（未測定として記録）",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
            }
            EldiaCard {
                AquaConst.maintenanceItems.forEachIndexed { i, item ->
                    if (i > 0) HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable { flags = if (item.key in flags) flags - item.key else flags + item.key }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.label, Modifier.weight(1f))
                        Switch(
                            checked = item.key in flags,
                            onCheckedChange = { on -> flags = if (on) flags + item.key else flags - item.key },
                            colors = SwitchDefaults.colors(checkedTrackColor = AquaConst.teal),
                        )
                    }
                }
            }
            EldiaCard {
                OutlinedTextField(memo, { memo = it }, label = { Text("備考 (memo)") }, placeholder = { Text("特記事項があれば入力") },
                    minLines = 2, modifier = Modifier.fillMaxWidth())
            }
            EldiaCard {
                Text("現場写真 (Photo)", style = MaterialTheme.typography.labelLarge)
                photo?.let { p ->
                    AsyncImage(model = Uri.parse(p), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)))
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PhotoSourceButtons(max = 1) { photo = it.firstOrNull()?.toString() }
                    if (photo != null) TextButton(onClick = { photo = null }) { Text("外す") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = {
                if (isFutureDate(DateUtil.parse(date))) { toast(FUTURE_DATE_MESSAGE); return@Button }
                saving = true
                scope.launch {
                    try {
                        val log = MaintenanceLog.create(room, date, ph?.let { Math.round(it * 10) / 10.0 }, temp?.let { Math.round(it * 10) / 10.0 },
                            flags, memo.trim().ifEmpty { null }).copy(id = logId)
                        val bytes = photo?.let { Photos.jpeg(ctx, Uri.parse(it)) }
                        val queued = vm.save(log, bytes)
                        toast(if (queued) "電波がないため端末に保存しました（あとで自動送信）" else "記録を保存しました")
                        onDone()
                    } catch (e: Exception) {
                        toast("保存できませんでした：" + e.userMessage())
                    } finally {
                        saving = false
                    }
                }
            },
            enabled = !saving,
            colors = ButtonDefaults.buttonColors(containerColor = AquaConst.teal),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
        ) {
            if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            else Text("記録を保存", fontWeight = FontWeight.Bold)
        }
    }
}

internal const val FUTURE_DATE_MESSAGE = "未来の日付は選べません"

/** 今日より後の日付か（共通の DateButton は未来日を止められないので保存時にも確認する） */
internal fun isFutureDate(d: LocalDate?): Boolean = d != null && d.isAfter(DateUtil.today())

/**
 * 入力途中の画面から離れるときの確認。
 * dirty のときは端末の戻る操作も止めて「破棄しますか？」を出す。戻り値は「戻る」ボタン用の処理。
 */
@Composable
internal fun rememberDiscardGuard(dirty: Boolean, onLeave: () -> Unit): () -> Unit {
    var asking by remember { mutableStateOf(false) }
    val leaveNow by rememberUpdatedState(onLeave)
    BackHandler(enabled = dirty) { asking = true }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("入力中の内容を破棄しますか？") },
            confirmButton = {
                TextButton(onClick = { asking = false; leaveNow() }) { Text("破棄", color = StatusColor.open) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("続ける") } },
        )
    }
    return { if (dirty) asking = true else leaveNow() }
}

/** 0.1 刻みで増減する入力（範囲外は赤）。未入力（null）は「--」、最初の +/- で base から始める */
@Composable
private fun Stepper(
    label: String, value: Double?, unit: String, min: Double, max: Double, base: Double, out: Boolean,
    onChange: (Double?) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        if (value != null) TextButton(onClick = { onChange(null) }) { Text("未測定") }
        IconButton(onClick = {
            onChange(if (value == null) base else maxOf(min, Math.round((value - 0.1) * 10) / 10.0))
        }) { Icon(Icons.Filled.Remove, "減らす") }
        Text(value?.let { "%.1f%s".format(it, unit) } ?: "--", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = when {
                value == null -> MaterialTheme.colorScheme.onSurfaceVariant
                out -> StatusColor.open
                else -> MaterialTheme.colorScheme.onSurface
            })
        IconButton(onClick = {
            onChange(if (value == null) base else minOf(max, Math.round((value + 0.1) * 10) / 10.0))
        }) { Icon(Icons.Filled.Add, "増やす") }
    }
}
