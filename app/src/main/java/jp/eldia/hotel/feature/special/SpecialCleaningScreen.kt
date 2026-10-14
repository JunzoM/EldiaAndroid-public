package jp.eldia.hotel.feature.special

import jp.eldia.hotel.ui.components.RouteTransition
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import jp.eldia.hotel.auth.displayName
import jp.eldia.hotel.core.AppPrefs
import jp.eldia.hotel.core.DateUtil
import jp.eldia.hotel.core.FacilityConst
import jp.eldia.hotel.core.Hotel
import jp.eldia.hotel.core.Room
import jp.eldia.hotel.core.StatusColor
import jp.eldia.hotel.feature.cleaning.SubHeader
import jp.eldia.hotel.ui.components.PillFab
import jp.eldia.hotel.ui.components.ChipRow
import jp.eldia.hotel.ui.components.DateButton
import jp.eldia.hotel.ui.components.EldiaCard
import jp.eldia.hotel.ui.components.HotelSegmented
import jp.eldia.hotel.ui.components.TimePickerDialog
import jp.eldia.hotel.ui.components.rememberToast
import kotlinx.coroutines.launch

/** 特別清掃（部屋一覧 → 部屋の履歴／記録）。iOS版 SpecialCleaningRoot に相当 */
@Composable
fun SpecialCleaningScreen(vm: SpecialCleaningViewModel = viewModel()) {
    val hotel by AppPrefs.hotel.collectAsState()
    val records by vm.records.collectAsState()
    val error by vm.error.collectAsState()
    var loading by remember { mutableStateOf(true) }
    // 画面："rooms" / "room:L301" / "history" / "report" / "stats" / "form:" / "form:L301"
    var route by rememberSaveable { mutableStateOf("rooms") }

    LaunchedEffect(Unit) { vm.load(); loading = false }
    BackHandler(enabled = route != "rooms") {
        route = if (route.startsWith("form:") && route.length > 5) "room:" + route.removePrefix("form:") else "rooms"
    }

    // 階層：部屋一覧 0 → 部屋の履歴など 1 → 部屋の履歴から開いた記録 2
    val depth = when {
        route == "rooms" -> 0
        route.startsWith("form:") && route.length > 5 -> 2
        else -> 1
    }
    RouteTransition(route, depth) { r -> when {
        r.startsWith("room:") -> RoomHistory(vm, r.removePrefix("room:"),
            onBack = { route = "rooms" }, onRecord = { route = "form:$it" })
        r == "history" -> AllHistory(vm, onBack = { route = "rooms" })
        r == "report" -> SpecialDayReport(vm, onBack = { route = "rooms" })
        r == "stats" -> SpecialStats(vm, onBack = { route = "rooms" })
        r.startsWith("form:") -> SpecialForm(
            vm, initialRoomId = r.removePrefix("form:").ifEmpty { null }, initialHotel = hotel,
            onDone = { route = "rooms" },
        )
        else -> Box(Modifier.fillMaxSize()) {
            RoomGrid(
                vm, hotel, records, loading, error, onRoom = { route = "room:${it.id}" },
                onOpen = { route = it },
            )
            PillFab("清掃を記録", Icons.Outlined.AutoAwesome, hotel.accent, onClick = { route = "form:" },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
        }
    } }
}

@Composable
private fun RoomGrid(
    vm: SpecialCleaningViewModel, hotel: Hotel, records: List<SpecialRecord>, loading: Boolean, error: String?,
    onRoom: (Room) -> Unit, onOpen: (String) -> Unit,
) {
    var floor by rememberSaveable(hotel) { mutableStateOf("all") }
    val floors = FacilityConst.floors(hotel).reversed() // 最上階から
    val latestByRoom = remember(records) { SpecialLogic.latestByRoom(records) }

    LazyVerticalGrid(
        GridCells.Adaptive(84.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("特別清掃", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                HotelSegmented(hotel, { AppPrefs.setHotel(it) })
                // 日ごとの LINE 報告（部屋ごとではなく、その日の分をまとめて送る）
                TodayReportCard(vm, records, hotel, onOpenReport = { onOpen("report") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onOpen("history") }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.History, null, Modifier.size(18.dp)); Text("履歴", Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(onClick = { onOpen("stats") }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.BarChart, null, Modifier.size(18.dp)); Text("集計", Modifier.padding(start = 6.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Legend(StatusColor.done, "7日以内"); Legend(StatusColor.progress, "30日以内")
                    Legend(StatusColor.open, "30日超"); Legend(MaterialTheme.colorScheme.outline, "未記録")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (loading && records.isEmpty()) CircularProgressIndicator()
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.padding(vertical = 4.dp)) {
                ChipRow(listOf("all") + floors, floor, { if (it == "all") "全て" else it }, hotel.accent) { floor = it }
            }
        }
        floors.filter { floor == "all" || it == floor }.forEach { f ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(f, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            items(FacilityConst.rooms(hotel, f), key = { it.id }) { room ->
                SpecialRoomCard(room, latestByRoom[SpecialLogic.key(room.id, hotel)]) { onRoom(room) }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun Legend(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(c, CircleShape))
        Text(label, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 部屋カード（最終清掃からの日数で色分け。右上は前回の作業数） */
@Composable
fun SpecialRoomCard(room: Room, latest: SpecialRecord?, onClick: () -> Unit) {
    val days = SpecialLogic.daysSince(latest?.date)
    val color = SpecialLogic.color(days)
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = color?.copy(alpha = 0.12f) ?: MaterialTheme.colorScheme.surfaceContainer),
        border = color?.let { BorderStroke(1.dp, it.copy(alpha = 0.5f)) },
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 58.dp)) {
            Column(Modifier.align(Alignment.Center).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(room.num, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(days?.let { "${it}日前" } ?: "未記録", style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold, color = color ?: MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (latest != null && color != null) {
                Text("${latest.tasks.size}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                        .background(color.copy(alpha = 0.18f), RoundedCornerShape(50)).padding(horizontal = 5.dp))
            }
        }
    }
}

/** 記録1件（日付・担当・時間・作業・メモ） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordRow(rec: SpecialRecord, showRoom: Boolean, onDelete: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (showRoom) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${FacilityConst.roomNum(rec.room, rec.hotel)}号室", fontWeight = FontWeight.Bold)
                    Text(rec.hotel.english, color = rec.hotel.accent, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 8.dp))
                }
            }
            val meta = buildList {
                add("📅 ${rec.date}")
                rec.staffName?.takeIf { it.isNotBlank() }?.let { add("👤 $it") }
                if (!rec.timeIn.isNullOrEmpty() && !rec.timeOut.isNullOrEmpty()) {
                    val d = rec.durationMin?.let { "（${SpecialLogic.durationLabel(it)}）" } ?: ""
                    add("⏱ ${rec.timeIn.take(5)}〜${rec.timeOut.take(5)}$d")
                }
            }.joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (rec.tasks.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    rec.tasks.forEach {
                        Text(it, color = StatusColor.done, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                            modifier = Modifier.background(StatusColor.done.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
            rec.memo?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        // 削除は記録した本人だけ（清掃記録と同じ）
        if (onDelete != null) {
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "削除", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

/** 削除できる記録（自分の記録）なら削除の操作を返す */
private fun deleteAction(vm: SpecialCleaningViewModel, rec: SpecialRecord, askDelete: (SpecialRecord) -> Unit): (() -> Unit)? {
    val me = jp.eldia.hotel.core.KnownUser.current()?.displayName
    return if (vm.isMine(rec, me)) ({ askDelete(rec) }) else null
}

@Composable
private fun rememberDelete(vm: SpecialCleaningViewModel): (SpecialRecord) -> Unit {
    var target by remember { mutableStateOf<SpecialRecord?>(null) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    target?.let { t ->
        AlertDialog(
            onDismissRequest = { target = null },
            title = { Text("この記録を削除しますか？") },
            text = { Text("${FacilityConst.roomNum(t.room, t.hotel)}号室・${t.date}") },
            confirmButton = {
                TextButton(onClick = {
                    target = null
                    scope.launch { vm.delete(t.id)?.let { toast("削除できませんでした：$it") } ?: toast("削除しました") }
                }) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { target = null }) { Text("キャンセル") } },
        )
    }
    return { target = it }
}

// MARK: - 部屋の履歴

@Composable
private fun RoomHistory(vm: SpecialCleaningViewModel, roomId: String, onBack: () -> Unit, onRecord: (String) -> Unit) {
    val records by vm.records.collectAsState()
    val hotel = if (roomId.startsWith("M")) Hotel.MODERN else Hotel.LUXURY
    val recs = records.filter { it.room == roomId }.sortedByDescending { it.date }
    val askDelete = rememberDelete(vm)
    Column(Modifier.fillMaxSize()) {
        SubHeader("${FacilityConst.roomNum(roomId, hotel)}号室 清掃履歴", onBack) {
            Button(onClick = { onRecord(roomId) }) { Text("記録") }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            if (recs.isEmpty()) item {
                // 空のときは次の一手（この部屋を記録する）をその場に出す
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("この部屋の清掃記録はありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { onRecord(roomId) }, modifier = Modifier.padding(top = 12.dp)) { Text("この部屋を記録する") }
                }
            }
            items(recs, key = { it.id }) { r -> RecordRow(r, showRoom = false, onDelete = deleteAction(vm, r, askDelete)); HorizontalDivider() }
        }
    }
}

// MARK: - 全体履歴

@Composable
private fun AllHistory(vm: SpecialCleaningViewModel, onBack: () -> Unit) {
    val records by vm.records.collectAsState()
    var storeFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val askDelete = rememberDelete(vm)
    val filtered = records
        .filter { storeFilter == null || it.store == storeFilter }
        .filter { query.isBlank() || FacilityConst.roomNum(it.room, it.hotel).contains(query.trim()) }
        .sortedByDescending { it.date }
    Column(Modifier.fillMaxSize()) {
        SubHeader("特別清掃 履歴", onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "全て", "luxury" to "Luxury", "modern" to "Modern").forEach { (v, l) ->
                    val on = storeFilter == v
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(50))
                            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { storeFilter = v }.padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(l, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("部屋番号で検索…") },
                modifier = Modifier.fillMaxWidth())
        }
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            if (filtered.isEmpty()) item { Text("記録がありません", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(filtered, key = { it.id }) { r -> RecordRow(r, showRoom = true, onDelete = deleteAction(vm, r, askDelete)); HorizontalDivider() }
        }
    }
}

// MARK: - 記録フォーム

@Composable
private fun SpecialForm(vm: SpecialCleaningViewModel, initialRoomId: String?, initialHotel: Hotel, onDone: () -> Unit) {
    val records by vm.records.collectAsState()
    val allTasks by vm.tasks.collectAsState()
    var hotel by rememberSaveable {
        mutableStateOf(initialRoomId?.let { if (it.startsWith("M")) Hotel.MODERN else Hotel.LUXURY } ?: initialHotel)
    }
    var roomId by rememberSaveable { mutableStateOf(initialRoomId) }
    var floor by rememberSaveable(hotel) { mutableStateOf("all") }
    var date by rememberSaveable { mutableStateOf(DateUtil.todayString()) }
    var staffName by rememberSaveable {
        mutableStateOf(jp.eldia.hotel.core.KnownUser.current()?.displayName?.takeIf { it.isNotBlank() } ?: vm.savedName)
    }
    var timeIn by rememberSaveable { mutableStateOf<String?>(null) }
    var timeOut by rememberSaveable { mutableStateOf<String?>(null) }
    var tasks by rememberSaveable { mutableStateOf(listOf<String>()) }
    var memo by rememberSaveable { mutableStateOf("") }
    var editTime by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val latestByRoom = remember(records) { SpecialLogic.latestByRoom(records) }

    val room = roomId?.let { id -> FacilityConst.rooms(hotel).firstOrNull { it.id == id } }

    if (room == null) {
        // 部屋の選択
        val floors = FacilityConst.floors(hotel).reversed()
        Column(Modifier.fillMaxSize()) {
            SubHeader("部屋を選択", onDone)
            LazyVerticalGrid(
                GridCells.Adaptive(84.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { HotelSegmented(hotel, { hotel = it }) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ChipRow(listOf("all") + floors, floor, { if (it == "all") "全て" else it }, hotel.accent) { floor = it }
                }
                floors.filter { floor == "all" || it == floor }.forEach { f ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(f, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(FacilityConst.rooms(hotel, f), key = { it.id }) { r ->
                        SpecialRoomCard(r, latestByRoom[SpecialLogic.key(r.id, hotel)]) {
                            roomId = r.id; tasks = emptyList(); memo = ""; timeIn = null; timeOut = null
                        }
                    }
                }
            }
        }
        return
    }

    val last = latestByRoom[SpecialLogic.key(room.id, hotel)]
    fun lastDate(task: String) = records.filter { it.hotel == hotel && it.room == room.id && task in it.tasks }.maxOfOrNull { it.date }

    Column(Modifier.fillMaxSize()) {
        SubHeader("${room.num}号室", onBack = { if (initialRoomId == null) roomId = null else onDone() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EldiaCard {
                DateButton(DateUtil.parse(date) ?: DateUtil.today(), { date = DateUtil.ymd(it) })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(staffName, { staffName = it }, label = { Text("担当者（氏名）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Text("前回: ${last?.date ?: "未記録"}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            EldiaCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editTime = "in" }, modifier = Modifier.weight(1f)) { Text("開始 ${timeIn ?: "--:--"}") }
                    OutlinedButton(onClick = { editTime = "out" }, modifier = Modifier.weight(1f)) { Text("終了 ${timeOut ?: "--:--"}") }
                }
                DateUtil.durationMinutes(timeIn, timeOut)?.let {
                    Text("所要時間 ${SpecialLogic.durationLabel(it)}", Modifier.padding(top = 6.dp))
                }
            }
            EldiaCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("作業内容", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { tasks = allTasks }) { Text("全選択") }
                    TextButton(onClick = { tasks = emptyList() }) { Text("全解除") }
                }
                allTasks.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        pair.forEach { task ->
                            val on = task in tasks
                            val ld = lastDate(task)
                            val lc = SpecialLogic.taskColor(ld)
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                    .background(if (on) StatusColor.done else StatusColor.done.copy(alpha = 0.08f))
                                    .clickable { tasks = if (on) tasks - task else tasks + task }
                                    .padding(10.dp),
                            ) {
                                Text(task, color = if (on) Color.White else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                Text("前回: ${ld?.let { DateUtil.parse(it)?.let(DateUtil::md) } ?: "未実施"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (on) Color.White.copy(alpha = 0.85f) else lc ?: MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            EldiaCard {
                OutlinedTextField(memo, { memo = it }, label = { Text("メモ") }, placeholder = { Text("特記事項など") },
                    minLines = 3, modifier = Modifier.fillMaxWidth())
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = {
                // 入力チェック（作業内容なし・OUT が IN より前は保存しない）
                if (tasks.isEmpty()) { error = "作業内容を1つ以上選んでください"; return@Button }
                if (!timeIn.isNullOrEmpty() && !timeOut.isNullOrEmpty() && DateUtil.durationMinutes(timeIn, timeOut) == null) {
                    error = "OUTがINより前になっています"; return@Button
                }
                saving = true
                error = null
                val name = staffName.trim()
                val rec = SpecialRecord(
                    id = "${hotel.rawValue}-${room.id}-${System.currentTimeMillis()}",
                    date = date, room = room.id, store = hotel.rawValue, tasks = tasks,
                    memo = memo.trim().ifEmpty { null }, staffName = name.ifEmpty { null },
                    timeIn = timeIn, timeOut = timeOut, durationMin = DateUtil.durationMinutes(timeIn, timeOut),
                )
                scope.launch {
                    val err = vm.save(rec)
                    saving = false
                    if (err != null) error = "保存できませんでした：$err"
                    else {
                        if (name.isNotEmpty()) vm.savedName = name
                        toast("清掃記録を保存しました")
                        onDone()
                    }
                }
            },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
        ) {
            if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            else Text("保存", fontWeight = FontWeight.Bold)
        }
    }

    editTime?.let { which ->
        TimePickerDialog(
            initial = if (which == "in") timeIn else timeOut,
            title = if (which == "in") "開始時刻" else "終了時刻",
            onDismiss = { editTime = null },
            onConfirm = { v -> if (which == "in") timeIn = v else timeOut = v; editTime = null },
        )
    }
}
