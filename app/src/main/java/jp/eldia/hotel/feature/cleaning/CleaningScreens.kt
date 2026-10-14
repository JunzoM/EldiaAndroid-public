package jp.eldia.hotel.feature.cleaning

import jp.eldia.hotel.core.UndoBar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.eldia.hotel.core.AppPrefs
import jp.eldia.hotel.core.DateUtil
import jp.eldia.hotel.core.Hotel
import jp.eldia.hotel.core.StatusColor
import jp.eldia.hotel.core.userMessage
import jp.eldia.hotel.ui.components.DateButton
import jp.eldia.hotel.ui.components.EldiaCard
import jp.eldia.hotel.ui.components.HotelSegmented
import jp.eldia.hotel.ui.components.TimePickerDialog
import jp.eldia.hotel.ui.components.rememberToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 戻るボタン付きの見出し */
@Composable
fun SubHeader(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** 仕事内容のタグ */
@Composable
fun ContentTag(text: String) {
    val c = CleaningConst.contentColor(text)
    Text(
        text,
        color = c,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.background(c.copy(alpha = 0.14f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun CleaningLogRow(log: CleaningLog, showStaff: Boolean = false, pending: Boolean = false, onDelete: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(log.room, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (showStaff) Text("  ${log.staffName}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text(log.timeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                log.contents.forEach { ContentTag(it) }
                if (pending) PendingTag()
            }
            log.memoText?.let { MemoLine(it) }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "削除", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

/** メモの表示（忘れ物など。目立つよう色付きの帯） */
@Composable
fun MemoLine(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MemoColor.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.AutoMirrored.Outlined.StickyNote2, null, tint = MemoColor, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Text(text, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
    }
}

/** メモの色（アンバー） */
val MemoColor = Color(0xFFE08E0B)

/** 端末にだけある記録の印 */
@Composable
fun PendingTag() {
    Text(
        "未送信",
        color = StatusColor.progress,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.background(StatusColor.progress.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** 読み込み失敗の表示（「記録なし」と区別する） */
@Composable
fun LoadErrorRetry(message: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("記録を読み込めませんでした：$message", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onRetry) { Text("再読み込み") }
    }
}

/** 記録の削除（確認してから消す）。消したら onDeleted を呼ぶ */
@Composable
fun rememberDeleteLog(vm: CleaningViewModel, onDeleted: (String) -> Unit = {}): (CleaningLog) -> Unit {
    var target by remember { mutableStateOf<CleaningLog?>(null) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    target?.let { t ->
        AlertDialog(
            onDismissRequest = { target = null },
            title = { Text("${t.room} の記録を削除しますか？") },
            text = { Text("${t.staffName}・${t.timeLabel}") },
            confirmButton = {
                TextButton(onClick = {
                    target = null
                    scope.launch {
                        val err = vm.deleteLog(t.id)
                        if (err != null) toast("削除できませんでした：$err") else { onDeleted(t.id); toast("削除しました") }
                    }
                }) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { target = null }) { Text("キャンセル") } },
        )
    }
    return { target = it }
}

// MARK: - 記録フォーム

@Composable
fun CleaningForm(vm: CleaningViewModel, room: String, staff: Staff, onDone: () -> Unit) {
    val todayLogs by vm.todayLogs.collectAsState()
    val lastContents by vm.lastContents.collectAsState()
    val hotel = staff.hotel
    // 清掃中（IN を押したまま閉じた）なら、その入力から続ける
    val draft = remember(hotel, room) { vm.draft(hotel, room) }
    var date by rememberSaveable { mutableStateOf(draft?.date ?: DateUtil.todayString()) }
    var contents by rememberSaveable { mutableStateOf(draft?.contents ?: listOf()) }
    var timeIn by rememberSaveable { mutableStateOf(draft?.timeIn) }
    var timeOut by rememberSaveable { mutableStateOf(draft?.timeOut) }
    var memo by rememberSaveable { mutableStateOf(draft?.memo ?: "") }
    var confirmDiscard by remember { mutableStateOf(false) }
    // 入力が変わるたびに清掃中として保存（IN がなければ清掃中ではない）
    LaunchedEffect(date, contents, timeIn, timeOut, memo) {
        vm.updateDraft(CleaningDraft(hotel.rawValue, room, date, staff.name, contents, timeIn, timeOut, memo))
    }
    // この部屋のメモ（全スタッフ分・直近30日）。忘れ物の問い合わせに答えられるよう誰の記録でも出す
    var roomMemos by remember { mutableStateOf<List<CleaningLog>>(emptyList()) }
    LaunchedEffect(hotel, room, todayLogs) {
        roomMemos = try {
            vm.fetchMemos(hotel).filter { it.room == room }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 取得できないときは本日分（手元にある分）だけ出す
            todayLogs.filter { it.room == room && it.hotel == hotel && it.memoText != null }
        }
    }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var earlier by remember { mutableStateOf<List<CleaningLog>>(emptyList()) }
    var earlierPending by remember { mutableStateOf<Set<String>>(emptySet()) }
    var earlierError by remember { mutableStateOf(false) }
    var editTime by remember { mutableStateOf<String?>(null) } // "in" / "out"
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val askDelete = rememberDeleteLog(vm) { id -> earlier = earlier.filter { it.id != id } }

    // この部屋のその日の記録（全担当者）。保存は常に新規追加
    LaunchedEffect(date, todayLogs) {
        earlierError = false
        val day: DayLogs = if (date == DateUtil.todayString()) {
            DayLogs(todayLogs, vm.pendingLogs(hotel, date).map { it.id }.toSet())
        } else {
            try {
                vm.fetchDayLogs(hotel, date)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 取得できないときは未送信分だけ出す
                earlierError = true
                vm.pendingLogs(hotel, date).let { l -> DayLogs(l, l.map { it.id }.toSet()) }
            }
        }
        earlier = day.logs.filter { it.room == room && it.hotel == hotel && it.staffName == staff.name }
        earlierPending = day.pendingIds
    }

    // OUT が IN より前なら保存させない
    val outBeforeIn = !timeIn.isNullOrEmpty() && !timeOut.isNullOrEmpty() && DateUtil.durationMinutes(timeIn, timeOut) == null
    val canSave = !saving && contents.isNotEmpty() && !timeIn.isNullOrEmpty() && !timeOut.isNullOrEmpty() && !outBeforeIn

    Column(Modifier.fillMaxSize()) {
        SubHeader("$room 清掃", onDone)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(hotel.accent.copy(alpha = 0.12f)).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(room, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Text("${hotel.label} 神戸店", style = MaterialTheme.typography.bodySmall)
            }
            if (draft != null && draft.date != DateUtil.todayString() && date == draft.date) {
                Text("${DateUtil.parse(draft.date)?.let { DateUtil.label(it) } ?: draft.date} の作業が保存されていません。OUT を入れて保存するか、取り消してください",
                    color = StatusColor.open, style = MaterialTheme.typography.bodySmall)
            }
            if (earlierError) {
                Text("この日の記録を取得できませんでした（回数が正しくない場合があります）",
                    color = StatusColor.progress, style = MaterialTheme.typography.bodySmall)
            }
            if (roomMemos.isNotEmpty()) {
                EldiaCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Outlined.StickyNote2, null, tint = MemoColor, modifier = Modifier.size(18.dp))
                        Text("この部屋のメモ ${roomMemos.size}件（直近${CleaningViewModel.MEMO_DAYS}日・全員）",
                            Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge)
                    }
                    roomMemos.take(5).forEach { m ->
                        Column(Modifier.padding(top = 6.dp)) {
                            Text("${DateUtil.parse(m.date)?.let { DateUtil.md(it) } ?: m.date} ${m.timeIn ?: ""}・${m.staffName}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MemoLine(m.memoText ?: "", Modifier.padding(top = 2.dp))
                        }
                    }
                    if (roomMemos.size > 5) {
                        Text("ほか ${roomMemos.size - 5}件はメモ一覧で確認できます", Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (earlier.isNotEmpty()) {
                EldiaCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, null, tint = hotel.accent, modifier = Modifier.size(18.dp))
                        Text("この日 ${earlier.size} 件記録済み（今回は ${earlier.size + 1} 回目）",
                            Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge)
                    }
                    earlier.forEach { e ->
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${e.staffName}・${e.contents.joinToString(" / ")}", style = MaterialTheme.typography.bodySmall)
                                Text(e.timeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                e.memoText?.let { MemoLine(it, Modifier.padding(top = 2.dp)) }
                            }
                            if (e.id in earlierPending) PendingTag()
                            if (e.staffName == staff.name) {
                                IconButton(onClick = { askDelete(e) }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "削除", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
            EldiaCard {
                Text("日付", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                DateButton(DateUtil.parse(date) ?: DateUtil.today(), { date = DateUtil.ymd(it) })
            }
            EldiaCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("仕事内容", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    // 前回保存した内容をワンタップで入れる
                    if (lastContents.isNotEmpty() && lastContents.toSet() != contents.toSet()) {
                        TextButton(onClick = { contents = CleaningConst.contentOptions.filter { it in lastContents } }) {
                            Text("前回と同じ（${lastContents.joinToString("・")}）", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CleaningConst.contentOptions.forEach { opt ->
                        val on = opt in contents
                        val c = CleaningConst.contentColor(opt)
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (on) c else c.copy(alpha = 0.10f))
                                .clickable { contents = if (on) contents - opt else contents + opt }
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(opt, color = if (on) Color.White else c, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            EldiaCard {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TimeStamp("IN", timeIn, CleaningConst.inColor, Modifier.weight(1f),
                        onStamp = { timeIn = DateUtil.hhmm() }, onEdit = { editTime = "in" })
                    TimeStamp("OUT", timeOut, CleaningConst.outColor, Modifier.weight(1f),
                        onStamp = { timeOut = DateUtil.hhmm() }, onEdit = { editTime = "out" })
                }
                Spacer(Modifier.height(8.dp))
                val d = DateUtil.durationMinutes(timeIn, timeOut)
                if (d != null && d > 0) Text("所要時間 ${d}分", style = MaterialTheme.typography.bodyMedium)
                if (outBeforeIn) Text("OUTがINより前になっています", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
                if (timeIn.isNullOrEmpty() || timeOut.isNullOrEmpty()) {
                    Text("IN と OUT の両方を入力すると保存できます（時刻をタップで手入力）",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            EldiaCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Outlined.StickyNote2, null, tint = MemoColor, modifier = Modifier.size(18.dp))
                    Text("メモ（任意）", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = memo,
                    onValueChange = { if (it.length <= MEMO_MAX) memo = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("例：忘れ物 黒い充電器（ベッド横）→ フロントへ") },
                    minLines = 2,
                    maxLines = 5,
                )
                Text("お客様の忘れ物などを残せます。メモはスタッフ全員に表示されます",
                    Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!timeIn.isNullOrEmpty()) {
                TextButton(onClick = { confirmDiscard = true }) {
                    Text("この作業を取り消す（清掃中を解除）", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = {
                saving = true
                error = null
                scope.launch {
                    val (err, queued) = vm.saveLog(room, hotel, date, staff.name, contents, timeIn, timeOut, memo)
                    saving = false
                    if (err != null) error = err
                    else {
                        val msg = if (queued) "電波がないため端末に保存しました（あとで自動送信）" else "記録を保存しました"
                        val savedId = vm.lastSavedId
                        // 保存のあと 5 秒だけ「元に戻す」（押したらこの記録を消す）
                        if (savedId != null) UndoBar.offer(msg) { vm.deleteLog(savedId) } else toast(msg)
                        onDone()
                    }
                }
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
        ) {
            if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            else Text("記録する", fontWeight = FontWeight.Bold)
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("$room の作業を取り消しますか？") },
            text = { Text("入力中の IN・OUT・仕事内容・メモを消して、清掃中を解除します（保存済みの記録は消えません）") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    contents = emptyList(); timeIn = null; timeOut = null; memo = ""
                    vm.clearDraft(hotel, room)
                    onDone()
                }) { Text("取り消す", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("続ける") } },
        )
    }

    editTime?.let { which ->
        TimePickerDialog(
            initial = if (which == "in") timeIn else timeOut,
            title = if (which == "in") "IN の時刻" else "OUT の時刻",
            onDismiss = { editTime = null },
            onConfirm = { v -> if (which == "in") timeIn = v else timeOut = v; editTime = null },
        )
    }
}

@Composable
private fun TimeStamp(title: String, value: String?, tint: Color, modifier: Modifier, onStamp: () -> Unit, onEdit: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            value ?: "--:--",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clickable(onClick = onEdit),
        )
        Button(onClick = onStamp, colors = ButtonDefaults.buttonColors(containerColor = tint), modifier = Modifier.fillMaxWidth()) {
            Text("$title を記録", fontWeight = FontWeight.Bold)
        }
    }
}

// MARK: - 自分の記録

@Composable
fun MyRecords(vm: CleaningViewModel, staff: Staff, onBack: () -> Unit) {
    var date by rememberSaveable { mutableStateOf(DateUtil.todayString()) }
    var day by remember { mutableStateOf<DayLogs?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val pendingCount by vm.pendingCount.collectAsState()
    val askDelete = rememberDeleteLog(vm) { id -> day = day?.let { d -> d.copy(logs = d.logs.filter { it.id != id }) } }

    // 未送信分が送られたら読み直す
    LaunchedEffect(date, reload, pendingCount) {
        day = null
        loadError = null
        day = loadDayLogs(vm, staff.hotel, date) { loadError = it }
    }
    val logs = day?.logs
    val mine = logs.orEmpty().filter { it.staffName == staff.name }

    Column(Modifier.fillMaxSize()) {
        SubHeader("自分の記録", onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { DateButton(DateUtil.parse(date) ?: DateUtil.today(), { date = DateUtil.ymd(it) }) }
            when {
                loadError != null -> item { LoadErrorRetry(loadError!!) { reload++ } }
                logs == null -> item { CircularProgressIndicator() }
                mine.isEmpty() -> item { Text("この日の記録はありません", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> {
                    item {
                        Text("${mine.size}室 / ${mine.sumOf { it.durationMin ?: 0 }}分",
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    item {
                        EldiaCard {
                            mine.forEachIndexed { i, log ->
                                if (i > 0) HorizontalDivider()
                                CleaningLogRow(log, pending = log.id in day?.pendingIds.orEmpty(), onDelete = { askDelete(log) })
                            }
                        }
                    }
                }
            }
        }
    }

}

// MARK: - 全体一覧

@Composable
fun RecordList(vm: CleaningViewModel, hotel: Hotel, myName: String, onBack: () -> Unit) {
    var date by rememberSaveable { mutableStateOf(DateUtil.todayString()) }
    var day by remember { mutableStateOf<DayLogs?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val pendingCount by vm.pendingCount.collectAsState()
    val askDelete = rememberDeleteLog(vm) { id -> day = day?.let { d -> d.copy(logs = d.logs.filter { it.id != id }) } }
    LaunchedEffect(date, reload, pendingCount) {
        day = null
        loadError = null
        day = loadDayLogs(vm, hotel, date) { loadError = it }
    }
    val logs = day?.logs
    val pendingIds = day?.pendingIds.orEmpty()
    val groups = logs.orEmpty().groupBy { it.staffName }.toSortedMap()

    Column(Modifier.fillMaxSize()) {
        SubHeader("全体一覧（${hotel.label}）", onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { DateButton(DateUtil.parse(date) ?: DateUtil.today(), { date = DateUtil.ymd(it) }) }
            when {
                loadError != null -> item { LoadErrorRetry(loadError!!) { reload++ } }
                logs == null -> item { CircularProgressIndicator() }
                logs.isEmpty() -> item { Text("この日の記録はありません", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> {
                    item { Text("合計 ${logs.size} 室", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    groups.forEach { (name, list) ->
                        item(key = name) {
                            EldiaCard {
                                Row {
                                    Text(name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    Text("${list.size}室")
                                }
                                list.forEach { log ->
                                    HorizontalDivider()
                                    CleaningLogRow(log, pending = log.id in pendingIds,
                                        onDelete = if (log.staffName == myName) ({ askDelete(log) }) else null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - メモ一覧（全スタッフ分）

@Composable
fun MemoList(vm: CleaningViewModel, initialHotel: Hotel, onBack: () -> Unit, onOpenRoom: (Hotel, String) -> Unit) {
    var hotel by rememberSaveable { mutableStateOf(initialHotel) }
    var memos by remember { mutableStateOf<List<CleaningLog>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val pendingCount by vm.pendingCount.collectAsState()
    LaunchedEffect(hotel, reload, pendingCount) {
        memos = null
        loadError = null
        memos = try {
            vm.fetchMemos(hotel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.userMessage()
            null
        }
    }
    val groups = memos.orEmpty().groupBy { it.date }

    Column(Modifier.fillMaxSize()) {
        SubHeader("メモ一覧", onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { HotelSegmented(hotel, { hotel = it }) }
            item {
                Text("直近${CleaningViewModel.MEMO_DAYS}日のメモ（スタッフ全員分）。部屋をタップすると記録画面を開きます",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                loadError != null -> item { LoadErrorRetry(loadError!!) { reload++ } }
                memos == null -> item { CircularProgressIndicator() }
                memos!!.isEmpty() -> item { Text("メモはありません", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> groups.forEach { (d, list) ->
                    item(key = "${hotel.rawValue}-$d") {
                        EldiaCard {
                            Text(DateUtil.parse(d)?.let { DateUtil.label(it) } ?: d, fontWeight = FontWeight.Bold)
                            list.forEach { m ->
                                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                                Column(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onOpenRoom(hotel, m.room) },
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(m.room, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Text("  ${m.staffName}", style = MaterialTheme.typography.bodySmall)
                                        Spacer(Modifier.weight(1f))
                                        Text(m.timeLabel, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    MemoLine(m.memoText ?: "")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** メモの最大文字数 */
const val MEMO_MAX = 300

// MARK: - 集計・分析

private enum class Period(val label: String) { TODAY("今日"), WEEK("今週"), MONTH("今月") }

@Composable
fun CleaningStats(vm: CleaningViewModel, onBack: () -> Unit) {
    var period by rememberSaveable { mutableStateOf(Period.TODAY) }
    // 初期値は選択中の店舗（上部で店舗を切り替えたらそちらに合わせる）
    val appHotel by AppPrefs.hotel.collectAsState()
    var hotel by rememberSaveable(appHotel) { mutableStateOf(appHotel) }
    var logs by remember { mutableStateOf<List<CleaningLog>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(period, hotel, reload) {
        logs = null
        loadError = null
        val today = DateUtil.today()
        val from: LocalDate = when (period) {
            Period.TODAY -> today
            Period.WEEK -> DateUtil.startOfWeekSunday(today) // 日曜始まり（Web版と同じ）
            Period.MONTH -> today.withDayOfMonth(1)
        }
        logs = try {
            vm.fetchLogs(hotel, DateUtil.ymd(from), DateUtil.ymd(today))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.userMessage()
            null
        }
    }
    val list = logs.orEmpty()
    val stats = list.staffStats()
    val totalMin = list.sumOf { it.durationMin ?: 0 }
    val avg = if (list.isEmpty()) 0 else Math.round(totalMin.toDouble() / list.size).toInt()

    Column(Modifier.fillMaxSize()) {
        SubHeader("集計・分析", onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Period.entries.forEach { p ->
                        val on = p == period
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(50))
                                .background(if (on) hotel.accent else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { period = p }.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(p.label, color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            item { HotelSegmented(hotel, { hotel = it }) }
            if (loadError != null) {
                item { LoadErrorRetry(loadError!!) { reload++ } }
            } else if (logs == null) {
                item { CircularProgressIndicator() }
            } else {
                item {
                    EldiaCard {
                        Row {
                            Summary("${list.size}", "総清掃室", Modifier.weight(1f))
                            Summary("$totalMin", "総分数", Modifier.weight(1f))
                            Summary("$avg", "平均分/室", Modifier.weight(1f))
                        }
                    }
                }
                item {
                    EldiaCard {
                        Text("スタッフ別集計", fontWeight = FontWeight.Bold)
                        if (stats.isEmpty()) Text("この期間の記録はありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        stats.forEachIndexed { i, s ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${i + 1}", Modifier.width(20.dp), fontWeight = FontWeight.Bold)
                                    Text(s.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                }
                                WorkKind.entries.filter { it != WorkKind.OTHER || s.count(it) > 0 }.chunked(2).forEach { pair ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        pair.forEach { k ->
                                            val n = s.count(k)
                                            Row(
                                                Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                                                    .background(if (n > 0) hotel.accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant)
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                val c = if (n > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                                Text(k.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c, maxLines = 1)
                                                Text("${n}室", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = c)
                                            }
                                        }
                                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                            if (i < stats.lastIndex) HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/** その日の記録（未送信分を含む）。失敗したら onError を呼んで null */
private suspend fun loadDayLogs(vm: CleaningViewModel, hotel: Hotel, date: String, onError: (String) -> Unit): DayLogs? =
    try {
        vm.fetchDayLogs(hotel, date)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(e.userMessage())
        null
    }

@Composable
private fun Summary(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
