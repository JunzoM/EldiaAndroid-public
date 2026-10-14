package jp.eldia.hotel.feature.cleaning

import jp.eldia.hotel.ui.components.RouteTransition
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import jp.eldia.hotel.auth.fullName
import jp.eldia.hotel.core.AppPrefs
import jp.eldia.hotel.core.DateUtil
import jp.eldia.hotel.core.Hotel
import jp.eldia.hotel.core.StatusColor
import jp.eldia.hotel.ui.components.ChipRow
import jp.eldia.hotel.ui.components.EldiaCard
import jp.eldia.hotel.ui.components.HotelSegmented
import jp.eldia.hotel.ui.components.PendingSendCard
import kotlinx.coroutines.launch

/** 清掃記録の中の画面 */
private sealed interface Route {
    data object Rooms : Route
    data class Form(val room: String) : Route
    data object Mine : Route
    data object Records : Route
    data object Stats : Route
    data object Memos : Route
}

/** 清掃記録（担当者の選択 → 部屋の選択 → 記録）。iOS版 CleaningLogRoot に相当 */
@Composable
fun CleaningLogScreen(vm: CleaningViewModel = viewModel()) {
    val staff by vm.currentStaff.collectAsState()
    val appHotel by AppPrefs.hotel.collectAsState()
    var routeKey by rememberSaveable { mutableStateOf("rooms") }
    fun routeOf(key: String): Route = when {
        key.startsWith("form:") -> Route.Form(key.removePrefix("form:"))
        key == "mine" -> Route.Mine
        key == "list" -> Route.Records
        key == "stats" -> Route.Stats
        key == "memos" -> Route.Memos
        else -> Route.Rooms
    }
    val route: Route = routeOf(routeKey)

    // 担当者はログインの担当者名で自動選択。電波が戻ったときのために未送信分も送る
    LaunchedEffect(Unit) {
        vm.autoSelect(appHotel)
        vm.flushPending()
    }
    // 日付をまたいで開いたままでも、アプリに戻ってきたら本日の記録に読み直す
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    LaunchedEffect(resumeTick) { vm.reloadIfNeeded(); vm.flushPending() }

    BackHandler(enabled = route != Route.Rooms) { routeKey = "rooms" }

    val s = staff
    if (s == null) {
        StaffSelector(vm)
        return
    }
    RouteTransition(routeKey, if (route == Route.Rooms) 0 else 1) { key ->
        when (val r = routeOf(key)) {
            Route.Rooms -> RoomSelector(vm, s, onOpen = { routeKey = it })
            is Route.Form -> CleaningForm(vm, r.room, s, onDone = { routeKey = "rooms" })
            Route.Mine -> MyRecords(vm, s, onBack = { routeKey = "rooms" })
            Route.Records -> RecordList(vm, s.hotel, s.name, onBack = { routeKey = "rooms" })
            Route.Stats -> CleaningStats(vm, onBack = { routeKey = "rooms" })
            Route.Memos -> MemoList(vm, s.hotel, onBack = { routeKey = "rooms" }, onOpenRoom = { h, room ->
                // 別の店舗のメモなら記録する店舗も切り替えてから開く
                if (h != s.hotel) vm.switchStore(h)
                routeKey = "form:$room"
            })
        }
    }
}

// MARK: - 担当者の選択

@Composable
private fun StaffSelector(vm: CleaningViewModel) {
    val list by vm.staffList.collectAsState()
    // 初期値は選択中の店舗（上部で店舗を切り替えたらそちらに合わせる）
    val appHotel by AppPrefs.hotel.collectAsState()
    var active by rememberSaveable(appHotel) { mutableStateOf(appHotel) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching { vm.loadStaff() }.onFailure { error = "スタッフ一覧を取得できませんでした（保存済みの一覧を表示中）" }
        loading = false
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Who is cleaning?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("担当者を選択してください", color = MaterialTheme.colorScheme.onSurfaceVariant)
        HotelSegmented(active, { active = it })
        if (loading && list.isEmpty()) CircularProgressIndicator()
        error?.let { Text(it, color = StatusColor.progress, style = MaterialTheme.typography.bodySmall) }
        // 名簿は1人1件。どちらの店舗のタブでも全員を出し、選んだタブの店舗で記録する
        LazyVerticalGrid(
            GridCells.Adaptive(140.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            items(list, key = { it.name }) { st ->
                Card(
                    onClick = { vm.setCurrentStaff(st.copy(store = active.rawValue)); scope.launch { vm.loadTodayLogs() } },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = BorderStroke(1.dp, active.accent.copy(alpha = 0.4f)),
                ) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
                        Text(st.name, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        if (list.isEmpty() && !loading) Text("スタッフが登録されていません", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { adding = true }) { Text("スタッフを追加") }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false; newName = "" },
            title = { Text("スタッフ追加") },
            text = {
                Column {
                    Text("${active.label} 神戸店に追加します", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(newName, { newName = it }, singleLine = true, placeholder = { Text("名前を入力") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = newName.trim()
                    adding = false
                    newName = ""
                    if (n.isNotEmpty()) scope.launch { error = vm.addStaff(n, active) }
                }) { Text("追加") }
            },
            dismissButton = { TextButton(onClick = { adding = false; newName = "" }) { Text("キャンセル") } },
        )
    }
}

// MARK: - 部屋の選択

@Composable
private fun RoomSelector(vm: CleaningViewModel, staff: Staff, onOpen: (String) -> Unit) {
    val logs by vm.todayLogs.collectAsState()
    val pending by vm.pendingCount.collectAsState()
    val drafts by vm.drafts.collectAsState()
    val canChangeStaff = jp.eldia.hotel.core.KnownUser.current()?.fullName == null
    val hotel = staff.hotel
    val counts = vm.roomCounts(logs, hotel, staff.name)
    // メモは全スタッフ分（忘れ物に気づけるよう）
    val memoCounts = vm.roomMemoCounts(logs, hotel)
    val floors = CleaningConst.floors(hotel)
    var floor by rememberSaveable(hotel) { mutableStateOf("all") }
    var menu by remember { mutableStateOf(false) }
    val total = floors.sumOf { it.rooms.size }
    // 部屋バッジと同じく、自分の記録だけを数える
    val doneRooms = counts.keys.size
    val times = counts.values.sum()
    val today = DateUtil.todayString()
    val todayLabel = DateUtil.md(DateUtil.today())
    // この店舗で清掃中（IN を押して未保存）の部屋
    val working = drafts.values.filter { it.store == hotel.rawValue }.sortedBy { it.room }

    LazyVerticalGrid(
        GridCells.Adaptive(76.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("清掃記録", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { menu = true }) {
                            Icon(Icons.Outlined.Person, null, Modifier.size(18.dp))
                            Text(staff.name, Modifier.padding(start = 4.dp))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("自分の記録") }, onClick = { menu = false; onOpen("mine") })
                            DropdownMenuItem(text = { Text("集計・分析") }, onClick = { menu = false; onOpen("stats") })
                            DropdownMenuItem(text = { Text("メモ一覧") }, onClick = { menu = false; onOpen("memos") })
                            if (canChangeStaff) {
                                DropdownMenuItem(text = { Text("担当者を変更", color = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; vm.setCurrentStaff(null) })
                            }
                        }
                    }
                }
                HotelSegmented(hotel, { vm.switchStore(it) })
                EldiaCard {
                    Text("本日 自分の記録 ${doneRooms}室", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${staff.name}・${times}件記録", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { if (total == 0) 0f else doneRooms.toFloat() / total },
                        color = CleaningSegment.CLEANING.color,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (working.isNotEmpty()) {
                    // OUT の押し忘れに気づけるよう、清掃中の部屋をまとめて出す（タップで続きから）
                    EldiaCard {
                        Text("清掃中 ${working.size}室（OUT 未入力）", fontWeight = FontWeight.Bold, color = StatusColor.progress)
                        Text("保存するまで「清掃中」のままです。タップして OUT を記録してください", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            working.take(6).forEach { d ->
                                val old = d.date != today
                                Text(
                                    "${d.room} ${d.timeIn ?: ""}〜" + if (old) "（${DateUtil.parse(d.date)?.let { DateUtil.md(it) } ?: d.date}）" else "",
                                    color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clip(RoundedCornerShape(50))
                                        .background(if (old) StatusColor.open else StatusColor.progress)
                                        .clickable { onOpen("form:${d.room}") }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
                if (pending > 0) PendingSendCard(cleaningVm = vm)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onOpen("mine") }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.History, null, Modifier.size(18.dp)); Text("履歴", Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(onClick = { onOpen("stats") }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.BarChart, null, Modifier.size(18.dp)); Text("集計", Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(onClick = { onOpen("memos") }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Outlined.StickyNote2, null, Modifier.size(18.dp)); Text("メモ", Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            val options = listOf("all") + floors.map { it.floor }
            Box(Modifier.padding(vertical = 4.dp)) {
                ChipRow(options, floor, label = { f ->
                    if (f == "all") "全フロア"
                    else {
                        val n = floors.first { it.floor == f }.rooms.sumOf { counts[it] ?: 0 }
                        if (n > 0) "$f $n" else f
                    }
                }, tint = hotel.accent) { floor = it }
            }
        }
        floors.filter { floor == "all" || it.floor == floor }.forEach { f ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(f.floor, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            items(f.rooms, key = { "${f.floor}-$it" }) { room ->
                val count = counts[room] ?: 0
                RoomTile(room, count, todayLabel, drafts["${hotel.rawValue}|$room"]?.timeIn, memoCounts[room] ?: 0) { onOpen("form:$room") }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun RoomTile(room: String, count: Int, todayLabel: String, workingSince: String?, memoCount: Int, onClick: () -> Unit) {
    val done = count > 0
    val working = workingSince != null
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = when {
                working -> StatusColor.progress.copy(alpha = 0.14f)
                done -> StatusColor.done.copy(alpha = 0.12f)
                else -> MaterialTheme.colorScheme.surfaceContainer
            },
        ),
        border = when {
            working -> BorderStroke(2.dp, StatusColor.progress)
            done -> BorderStroke(1.dp, StatusColor.done.copy(alpha = 0.5f))
            else -> null
        },
    ) {
        Box {
        if (memoCount > 0) {
            // 本日メモ（忘れ物など）がある部屋の印
            Icon(Icons.AutoMirrored.Outlined.StickyNote2, contentDescription = "メモあり", tint = MemoColor,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(14.dp))
        }
        Column(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(room, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (working) {
                Text("清掃中 $workingSince〜", color = StatusColor.progress, style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold, maxLines = 1)
            } else if (done) {
                Text(
                    "$count",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(StatusColor.done, RoundedCornerShape(50))
                        .padding(horizontal = 7.dp, vertical = 1.dp),
                )
            } else {
                Text(todayLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        }
    }
}
