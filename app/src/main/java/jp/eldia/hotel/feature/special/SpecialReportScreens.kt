package jp.eldia.hotel.feature.special

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import jp.eldia.hotel.auth.displayName
import jp.eldia.hotel.core.AppPrefs
import jp.eldia.hotel.core.DateUtil
import jp.eldia.hotel.core.FacilityConst
import jp.eldia.hotel.core.Hotel
import jp.eldia.hotel.core.StatusColor
import jp.eldia.hotel.feature.cleaning.SubHeader
import jp.eldia.hotel.ui.components.ChipRow
import jp.eldia.hotel.ui.components.DateButton
import jp.eldia.hotel.ui.components.EldiaCard
import jp.eldia.hotel.ui.components.HotelSegmented

/** 報告者名（ログインの表示名、なければ前回入力した担当者名） */
fun reporterName(vm: SpecialCleaningViewModel): String =
    jp.eldia.hotel.core.KnownUser.current()?.displayName?.takeIf { it.isNotBlank() } ?: vm.savedName

/** 「LINEで報告しますか？」（設備の不具合報告と同じ形。報告文を見せてから共有シートを開く） */
@Composable
fun SpecialLineDialog(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("LINEで報告しますか？") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, style = MaterialTheme.typography.bodySmall)
                Text("文章が届かなかったときは、LINEの入力欄に貼り付けてください（コピー済み）",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); shareSpecialReport(context, text) }) { Text("LINEで報告") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("あとで") } },
    )
}

/** 本日の記録の件数と「LINEで日報を送る」（部屋一覧の上に出す） */
@Composable
fun TodayReportCard(vm: SpecialCleaningViewModel, records: List<SpecialRecord>, hotel: Hotel, onOpenReport: () -> Unit) {
    val today = DateUtil.todayString()
    val recs = SpecialReport.dayRecords(records, today, hotel)
    var dialog by remember { mutableStateOf<String?>(null) }
    EldiaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("本日 ${recs.size}室 記録済み", fontWeight = FontWeight.Bold)
                Text(
                    if (recs.isEmpty()) "記録するとまとめてLINEで報告できます"
                    else recs.joinToString("・") { FacilityConst.roomNum(it.room, it.hotel) },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                )
            }
            TextButton(onClick = onOpenReport) { Text("日報") }
            if (recs.isNotEmpty()) {
                Button(
                    onClick = { dialog = SpecialReport.dayText(records, today, hotel, reporterName(vm)) },
                    colors = ButtonDefaults.buttonColors(containerColor = LINE_GREEN, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Send, null, Modifier.size(16.dp))
                    Text("LINE", Modifier.padding(start = 4.dp), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    dialog?.let { SpecialLineDialog(it) { dialog = null } }
}

private val LINE_GREEN = Color(0xFF06C755)

// MARK: - 日報（日ごとの LINE 報告）

@Composable
fun SpecialDayReport(vm: SpecialCleaningViewModel, onBack: () -> Unit) {
    val records by vm.records.collectAsState()
    val appHotel by AppPrefs.hotel.collectAsState()
    // "luxury" / "modern" / "all"
    var storeKey by rememberSaveable { mutableStateOf(appHotel.rawValue) }
    val store = Hotel.from(storeKey)
    var date by rememberSaveable { mutableStateOf(DateUtil.todayString()) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val recs = SpecialReport.dayRecords(records, date, store)
    val dates = SpecialReport.recordedDates(records, store).take(14)
    val counts = records.filter { store == null || it.hotel == store }.groupingBy { it.date.take(10) }.eachCount()
    val tint = store?.accent ?: MaterialTheme.colorScheme.primary

    Column(Modifier.fillMaxSize()) {
        SubHeader("日報（LINE報告）", onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "両店舗", "luxury" to "Luxury", "modern" to "Modern").forEach { (v, l) ->
                        val on = storeKey == v
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(50))
                                .background(if (on) tint else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { storeKey = v }.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(l, color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            item {
                EldiaCard {
                    DateButton(DateUtil.parse(date) ?: DateUtil.today(), { date = DateUtil.ymd(it) })
                    if (dates.isNotEmpty()) {
                        Text("記録のある日", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    }
                }
            }
            if (dates.isNotEmpty()) {
                item {
                    ChipRow(dates, date, { d -> (DateUtil.parse(d)?.let(DateUtil::md) ?: d) + " ${counts[d] ?: 0}室" }, tint) { date = it }
                }
            }
            item {
                EldiaCard {
                    val total = recs.sumOf { it.durationMin ?: 0 }
                    Text("${DateUtil.parse(date)?.let(DateUtil::label) ?: date}　${recs.size}室", fontWeight = FontWeight.Bold)
                    Text(
                        "作業${recs.sumOf { it.tasks.size }}件" + if (total > 0) "・合計${SpecialLogic.durationLabel(total)}" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (recs.isEmpty()) {
                        Text("この日の記録はありません", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                    recs.forEachIndexed { i, r ->
                        if (i == 0) Spacer(Modifier.height(4.dp))
                        RecordRow(r, showRoom = true, onDelete = null)
                        if (i < recs.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
        Button(
            onClick = { dialog = SpecialReport.dayText(records, date, store, reporterName(vm)) },
            enabled = recs.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = LINE_GREEN, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.Send, null, Modifier.size(18.dp))
            Text("この日の分をLINEで報告", Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold)
        }
    }
    dialog?.let { SpecialLineDialog(it) { dialog = null } }
}

// MARK: - 集計・分析

@Composable
fun SpecialStats(vm: SpecialCleaningViewModel, onBack: () -> Unit) {
    val records by vm.records.collectAsState()
    val allTasks by vm.tasks.collectAsState()
    val appHotel by AppPrefs.hotel.collectAsState()
    var hotel by rememberSaveable(appHotel) { mutableStateOf(appHotel) }
    var period by rememberSaveable { mutableStateOf(SpecialReport.Period.MONTH) }

    // 集計は記録・期間・店舗が変わったときだけ計算し直す（再描画のたびに全件を数えない）
    val list = remember(records, period, hotel) { SpecialReport.inPeriod(records, period, hotel) }
    val sum = remember(list) { SpecialReport.summary(list) }
    val taskCounts = remember(list, allTasks) { SpecialReport.taskCounts(list, allTasks) }
    val staff = remember(list) { SpecialReport.staffStats(list) }
    val monthly = remember(records, hotel) { SpecialReport.monthly(records, hotel) }
    val fresh = remember(records, hotel) { SpecialReport.freshness(records, hotel) }
    val overdue = remember(records, hotel) { SpecialReport.overdueRooms(records, hotel) }
    val totalRooms = FacilityConst.rooms(hotel).size

    Column(Modifier.fillMaxSize()) {
        SubHeader("集計・分析", onBack)
        LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ChipRow(SpecialReport.Period.entries.toList(), period, { it.label }, hotel.accent) { period = it } }
            item { HotelSegmented(hotel, { hotel = it }, Modifier.padding(horizontal = 16.dp)) }
            item {
                EldiaCard(Modifier.padding(horizontal = 16.dp)) {
                    Row {
                        Summary("${sum.rooms}", "清掃室数", Modifier.weight(1f))
                        Summary("${sum.days}", "実施日数", Modifier.weight(1f))
                        Summary("${sum.tasks}", "作業件数", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row {
                        Summary(if (sum.minutes > 0) SpecialLogic.durationLabel(sum.minutes) else "-", "合計時間", Modifier.weight(1f))
                        Summary(sum.avg?.let { "${it}分" } ?: "-", "平均/室", Modifier.weight(1f))
                        Summary("${sum.uniqueRooms}/$totalRooms", "実施した部屋", Modifier.weight(1f))
                    }
                }
            }
            item {
                // 期間に関係なく「今の状態」
                EldiaCard(Modifier.padding(horizontal = 16.dp)) {
                    Text("部屋の状況（現在）", fontWeight = FontWeight.Bold)
                    val colors = listOf(StatusColor.done, StatusColor.progress, StatusColor.open, MaterialTheme.colorScheme.outlineVariant)
                    val labels = listOf("7日以内", "30日以内", "30日超", "未記録")
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(14.dp).clip(RoundedCornerShape(50))) {
                        fresh.forEachIndexed { i, n -> if (n > 0) Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(colors[i])) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        fresh.forEachIndexed { i, n ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(colors[i], CircleShape))
                                Text("${labels[i]} $n", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            item {
                EldiaCard(Modifier.padding(horizontal = 16.dp)) {
                    Text("月別の清掃室数", fontWeight = FontWeight.Bold)
                    val max = (monthly.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
                    Row(Modifier.fillMaxWidth().height(130.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Bottom) {
                        monthly.forEach { (label, n) ->
                            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom) {
                                Text("$n", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Box(
                                    Modifier.fillMaxWidth(0.7f).height((80f * n / max).coerceAtLeast(2f).dp)
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(if (n > 0) hotel.accent else MaterialTheme.colorScheme.outlineVariant),
                                )
                                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            item {
                EldiaCard(Modifier.padding(horizontal = 16.dp)) {
                    Text("作業別の件数", fontWeight = FontWeight.Bold)
                    val max = (taskCounts.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
                    taskCounts.forEach { (task, n) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(task, Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                color = if (n > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                if (n > 0) Box(Modifier.fillMaxWidth(n.toFloat() / max).fillMaxHeight().background(StatusColor.done))
                            }
                            Text("$n", Modifier.width(36.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            item {
                EldiaCard(Modifier.padding(horizontal = 16.dp)) {
                    Text("スタッフ別集計", fontWeight = FontWeight.Bold)
                    if (staff.isEmpty()) Text("この期間の記録はありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    staff.forEachIndexed { i, s ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", Modifier.width(20.dp), fontWeight = FontWeight.Bold)
                            Column(Modifier.weight(1f)) {
                                Text(s.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(
                                    "作業${s.tasks}件" + (if (s.minutes > 0) "・合計${SpecialLogic.durationLabel(s.minutes)}" else "") +
                                        (s.avg?.let { "・平均${it}分" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text("${s.rooms}室", fontWeight = FontWeight.Bold, color = hotel.accent)
                        }
                        if (i < staff.lastIndex) HorizontalDivider()
                    }
                }
            }
            item {
                EldiaCard(Modifier.padding(horizontal = 16.dp)) {
                    Text("清掃から日数が経っている部屋", fontWeight = FontWeight.Bold)
                    overdue.forEach { (num, days) ->
                        val c = SpecialLogic.color(days) ?: MaterialTheme.colorScheme.onSurfaceVariant
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${num}号室", Modifier.weight(1f))
                            Text(days?.let { "${it}日前" } ?: "未記録", color = c, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun Summary(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
