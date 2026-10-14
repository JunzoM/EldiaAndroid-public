package jp.eldia.hotel.feature.home

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import jp.eldia.hotel.core.AppPrefs
import jp.eldia.hotel.core.DateUtil
import jp.eldia.hotel.core.DeviceStatus
import jp.eldia.hotel.core.Hotel
import jp.eldia.hotel.core.StatusColor
import jp.eldia.hotel.core.TabRequests
import jp.eldia.hotel.feature.aqua.AquaViewModel
import jp.eldia.hotel.feature.cleaning.CleaningSegment
import jp.eldia.hotel.feature.cleaning.CleaningViewModel
import jp.eldia.hotel.feature.order.OrderSegment
import jp.eldia.hotel.feature.widget.WidgetSnapshot
import jp.eldia.hotel.feature.fridge.FridgeSummary
import jp.eldia.hotel.feature.facility.FacilityViewModel
import jp.eldia.hotel.feature.fridge.FridgeViewModel
import jp.eldia.hotel.ui.AppTab
import jp.eldia.hotel.ui.components.EldiaCard
import jp.eldia.hotel.ui.components.HotelSegmented
import jp.eldia.hotel.ui.components.PendingSendCard
import jp.eldia.hotel.ui.theme.LocalEldia
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ホーム「今日やること」（チェックリスト型・iOS版 HomeView に相当）。
 * 各モジュールの記録を読み、記録があれば自動で完了にする（手動チェックはしない）。
 * 行をタップすると該当のタブを開く。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTab(
    modifier: Modifier = Modifier,
    vm: HomeViewModel = viewModel(),
    cleaningVm: CleaningViewModel = viewModel(),
    aquaVm: AquaViewModel = viewModel(),
    fridgeVm: FridgeViewModel = viewModel(),
    facilityVm: FacilityViewModel = viewModel(),
    onOpen: (AppTab) -> Unit = {},
) {
    val hotel by AppPrefs.hotel.collectAsState()
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pulling by remember { mutableStateOf(false) }
    // 日付が変わったら判定し直すため、画面に戻るたびに今日を取り直す
    var today by remember { mutableStateOf(DateUtil.today()) }

    // 未送信の件数（各 ViewModel は作成時に端末の保存分から数えている）
    val cleaningPending by cleaningVm.pendingCount.collectAsState()
    val aquaPending by aquaVm.pendingCount.collectAsState()
    val fridgePending by fridgeVm.pending.collectAsState()
    val fridgeRemovedOps by fridgeVm.removedOps.collectAsState()
    val facilityPending by facilityVm.pendingCount.collectAsState()
    val pendingTotal = cleaningPending + aquaPending + fridgePending.size + fridgeRemovedOps.size + facilityPending

    // 未送信の件数をサーバーへ知らせる（月1回の点検で「送れずに残っている記録」を見つけるため）
    var resumeTick by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    LaunchedEffect(cleaningPending, aquaPending, fridgePending.size, fridgeRemovedOps.size, facilityPending, resumeTick) {
        delay(3000)
        DeviceStatus.report(
            context.applicationContext, AppPrefs.hotel.value,
            mapOf(
                "cleaning" to cleaningPending,
                "aqua" to aquaPending,
                "fridge" to fridgePending.size + fridgeRemovedOps.size,
                "facility" to facilityPending,
            ),
        )
    }

    // 冷蔵庫は冷蔵庫タブと同じ ViewModel のデータを使う
    val fHotel by fridgeVm.hotel.collectAsState()
    val fData by fridgeVm.data.collectAsState()
    val fRemoved by fridgeVm.removed.collectAsState()
    val fLastSync by fridgeVm.lastSync.collectAsState()
    val fOnline by fridgeVm.online.collectAsState()
    val fSum = remember(fHotel, fData, fRemoved, today) { FridgeSummary.build(fHotel, fData, fRemoved) }
    val fridge = FridgeInput(fHotel, fLastSync != null, fOnline == false, fSum.red, fSum.orange, fSum.expired)

    val local = remember(cleaningVm, aquaVm) {
        HomeLocal(
            cleaningPending = { h, d -> cleaningVm.pendingLogs(h, d) },
            aquaLogs = { aquaVm.logs.value },
        )
    }

    LaunchedEffect(Unit) { vm.reload(local) }
    // 店舗を切り替えたら冷蔵庫だけ読み直す（ほかは両店舗分を読んでいる）
    LaunchedEffect(hotel) { fridgeVm.switchHotel(hotel) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        today = DateUtil.today()
        vm.reload(local)
    }

    val schedule = remember(today) { HomeSchedule(today) }
    val drafts by cleaningVm.drafts.collectAsState()
    val working = drafts.values.count { it.store == hotel.rawValue }
    val rows = HomeViewModel.rows(state, hotel, schedule, fridge, working)
    val accent = LocalEldia.current.accentText

    // ホーム画面のウィジェット用に今日の結果を保存する（読み込みが終わってから・続けて変わるときはまとめる）
    val readyForWidget = !state.loading && (state.cleaningLoaded || state.failed.isNotEmpty())
    LaunchedEffect(rows, hotel, pendingTotal, readyForWidget) {
        if (!readyForWidget) return@LaunchedEffect
        delay(600)
        WidgetSnapshot.saveAndUpdate(context.applicationContext, rows, hotel, pendingTotal)
    }

    // 長押しメニュー（iOS版 HomeQuickActions に相当。記録画面の区分まで指定して開く）
    fun quickActions(kind: HomeTaskKind): List<HomeQuickAction> = when (kind) {
        HomeTaskKind.CLEANING -> listOf(
            HomeQuickAction("清掃を記録", Icons.Outlined.CleaningServices) {
                TabRequests.cleaningSegment.value = CleaningSegment.CLEANING.name
                onOpen(AppTab.CLEANING)
            },
        )
        HomeTaskKind.AQUA -> listOf(
            HomeQuickAction("水槽を記録", Icons.Outlined.WaterDrop) {
                TabRequests.cleaningSegment.value = CleaningSegment.AQUA.name
                onOpen(AppTab.CLEANING)
            },
        )
        HomeTaskKind.ORDER -> listOf(
            HomeQuickAction("発注する", Icons.Outlined.ShoppingCart) {
                TabRequests.orderSegment.value = OrderSegment.ORDER.name
                onOpen(AppTab.ORDER)
            },
            HomeQuickAction("発注履歴", Icons.Outlined.History) {
                TabRequests.orderSegment.value = OrderSegment.HISTORY.name
                onOpen(AppTab.ORDER)
            },
        )
        HomeTaskKind.FRIDGE -> listOf(
            HomeQuickAction("冷蔵庫を開く", Icons.Outlined.Kitchen) { onOpen(AppTab.FRIDGE) },
        )
        HomeTaskKind.FACILITY -> emptyList()
    }

    fun refreshAll() {
        scope.launch {
            pulling = true
            try {
                fridgeVm.sync(silent = false)
                vm.reload(local, force = true).join()
            } finally {
                pulling = false
            }
        }
    }

    PullToRefreshBox(isRefreshing = pulling, onRefresh = { refreshAll() }, modifier = modifier) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                // タブレットでは読みやすい幅に収める
                Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 日付と見出し・日報の共有
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(schedule.dateLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("今日やること", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                    }
                    if (state.loading && !pulling) {
                        CircularProgressIndicator(Modifier.padding(bottom = 10.dp).size(20.dp), strokeWidth = 2.dp)
                    }
                    IconButton(onClick = {
                        val text = HomeViewModel.dailyReport(state, fridge, schedule)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, "今日の日報を共有"))
                    }) {
                        Icon(Icons.Outlined.Share, contentDescription = "今日の日報を共有")
                    }
                }

                HotelSegmented(hotel, { AppPrefs.setHotel(it) })

                SummaryCard(rows, hotel.accent)

                PendingSendCard(cleaningVm = cleaningVm, aquaVm = aquaVm, fridgeVm = fridgeVm, facilityVm = facilityVm,
                    onSent = { vm.reload(local, force = true) })

                if (state.failed.isNotEmpty()) {
                    EldiaCard(Modifier.clickable { refreshAll() }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.WarningAmber, null, tint = StatusColor.progress)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("一部の記録を読み込めませんでした", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    "前回の結果を表示しています・タップで再読み込み",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Section("今日") {
                    EldiaCard {
                        rows.forEachIndexed { i, row ->
                            TaskRow(row, accent, actions = quickActions(row.kind)) {
                                // 清掃タブは該当の区分（清掃記録／水槽）を開く
                                when (row.kind) {
                                    HomeTaskKind.AQUA -> TabRequests.cleaningSegment.value = CleaningSegment.AQUA.name
                                    HomeTaskKind.CLEANING -> TabRequests.cleaningSegment.value = CleaningSegment.CLEANING.name
                                    else -> {}
                                }
                                onOpen(row.kind.tab)
                            }
                            if (i < rows.size - 1) HorizontalDivider(Modifier.padding(start = 40.dp))
                        }
                    }
                    Text(
                        "記録が入ると自動で完了になります（行をタップすると記録画面・長押しでメニューを開きます）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                Section("お知らせ") {
                    EldiaCard {
                        val orderRow = orderInfoRow(state)
                        TaskRow(orderRow, accent, icon = Icons.Outlined.ListAlt, title = "発注の状況") { onOpen(AppTab.ORDER) }
                        HorizontalDivider(Modifier.padding(start = 40.dp))
                        TaskRow(facilityInfoRow(state, hotel), accent, icon = Icons.Outlined.Build) { onOpen(AppTab.FACILITY) }
                    }
                }

                Section("今後の予定") {
                    EldiaCard {
                        val items = schedule.upcoming()
                        items.forEachIndexed { i, u ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    u.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = accent,
                                    modifier = Modifier.widthIn(min = 130.dp),
                                )
                                Text(u.title, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (i < items.size - 1) HorizontalDivider()
                        }
                    }
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}

/** 発注の 承認待ち・未納品（直近31日分・両店舗共通） */
private fun orderInfoRow(s: HomeState): HomeRow {
    val failed = HomeTaskKind.ORDER in s.failed
    if (s.orderState != OrderState.READY) {
        return HomeRow(
            HomeTaskKind.ORDER, if (failed) "電波を確認してください" else "承認待ち・未納品", false,
            if (failed) "確認できません" else "確認中", HomeRow.Tone.MUTED,
        )
    }
    val pending = s.orderPendingCount ?: 0
    val undelivered = s.orderUndeliveredCount ?: 0
    val note = if (failed) "・前回の結果" else ""
    val detail = "承認待ち ${pending}件・未納品 ${undelivered}件$note"
    return when {
        pending > 0 -> HomeRow(HomeTaskKind.ORDER, detail, false, "承認待ち ${pending}", HomeRow.Tone.WARN)
        undelivered > 0 -> HomeRow(HomeTaskKind.ORDER, detail, false, "未納品 ${undelivered}", HomeRow.Tone.NORMAL)
        else -> HomeRow(HomeTaskKind.ORDER, detail, true, "なし", HomeRow.Tone.DONE)
    }
}

/** 選択中の店舗＋外構部の、未完了の不具合 */
private fun facilityInfoRow(s: HomeState, h: Hotel): HomeRow {
    val failed = HomeTaskKind.FACILITY in s.failed
    if (!s.issuesLoaded) {
        return HomeRow(
            HomeTaskKind.FACILITY, if (failed) "電波を確認してください" else "未対応・対応中", false,
            if (failed) "確認できません" else "確認中", HomeRow.Tone.MUTED,
        )
    }
    val keys = listOf(h.rawValue, "exterior")
    val openCount = keys.sumOf { s.issueOpen[it] ?: 0 }
    val progress = keys.sumOf { s.issueProgress[it] ?: 0 }
    val attention = keys.sumOf { s.issueAttention[it] ?: 0 }
    val note = if (failed) "・前回の結果" else ""
    val detail = "未対応 ${openCount}件・対応中 ${progress}件（${h.english.uppercase()}＋外構部）$note"
    // 赤く出すのは「要確認」（7日以内の報告・優先度 高）だけ。古い未対応は件数だけ控えめに出す
    return when {
        attention > 0 -> HomeRow(HomeTaskKind.FACILITY, detail, false, "要確認 ${attention}", HomeRow.Tone.URGENT)
        openCount + progress > 0 -> HomeRow(HomeTaskKind.FACILITY, detail, false, "未完了 ${openCount + progress}", HomeRow.Tone.NORMAL)
        else -> HomeRow(HomeTaskKind.FACILITY, detail, true, "なし", HomeRow.Tone.DONE)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        content()
    }
}

/** 進捗サマリー（完了数のリング＋残りの項目） */
@Composable
private fun SummaryCard(rows: List<HomeRow>, accent: Color) {
    val total = rows.size
    val doneCount = rows.count { it.done }
    val rest = rows.filter { !it.done }.map { it.kind.title }
    EldiaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { if (total == 0) 0f else doneCount.toFloat() / total },
                    modifier = Modifier.size(64.dp),
                    color = accent,
                    strokeWidth = 7.dp,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                )
                Text("$doneCount/$total", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(if (rest.isEmpty()) "すべて完了しました" else "残り${rest.size}件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (rest.isEmpty()) "記録が入ると自動で完了になります" else rest.joinToString("・") + " — 記録すると自動で完了",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 電波がなく端末にためている記録の件数と「今すぐ送信」 */
private fun iconOf(kind: HomeTaskKind): ImageVector = when (kind) {
    HomeTaskKind.CLEANING -> Icons.Outlined.CleaningServices
    HomeTaskKind.ORDER -> Icons.Outlined.ListAlt
    HomeTaskKind.AQUA -> Icons.Outlined.WaterDrop
    HomeTaskKind.FRIDGE -> Icons.Outlined.Kitchen
    HomeTaskKind.FACILITY -> Icons.Outlined.Build
}

/** ホームの行を長押ししたときのメニュー項目 */
private class HomeQuickAction(val title: String, val icon: ImageVector, val run: () -> Unit)

/** チェックリストの1行（完了は緑のチェック、未完了は点線の丸）。actions があれば長押しでメニューを出す */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskRow(
    row: HomeRow,
    accent: Color,
    icon: ImageVector? = null,
    title: String = row.kind.title,
    actions: List<HomeQuickAction> = emptyList(),
    onClick: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onLongClick = if (actions.isEmpty()) null else ({ menu = true }),
                    onClick = onClick,
                )
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                when {
                    icon != null -> Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    row.done -> {
                        Box(Modifier.size(26.dp).background(StatusColor.done, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = Color.White)
                        }
                    }
                    else -> Canvas(Modifier.size(26.dp)) {
                        val w = 2.dp.toPx()
                        drawCircle(
                            color = accent,
                            radius = size.minDimension / 2 - w / 2,
                            center = Offset(size.width / 2, size.height / 2),
                            style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()), 0f)),
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon == null) {
                        Icon(iconOf(row.kind), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (row.done) FontWeight.Medium else FontWeight.Bold,
                        color = if (row.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    if (row.kind.isShared && icon == null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "両店舗",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f), RoundedCornerShape(50))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(row.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            HomeBadge(row.badge, row.tone)
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            actions.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a.title) },
                    leadingIcon = { Icon(a.icon, null) },
                    onClick = {
                        menu = false
                        a.run()
                    },
                )
            }
        }
    }
}

@Composable
private fun HomeBadge(text: String, tone: HomeRow.Tone) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val (fg, bg) = when (tone) {
        HomeRow.Tone.URGENT -> StatusColor.open to StatusColor.open.copy(alpha = 0.13f)
        HomeRow.Tone.WARN -> StatusColor.progress to StatusColor.progress.copy(alpha = 0.15f)
        HomeRow.Tone.DONE -> StatusColor.done to Color.Transparent
        HomeRow.Tone.MUTED -> muted to Color.Transparent
        HomeRow.Tone.NORMAL -> muted to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    }
    val padH = if (tone == HomeRow.Tone.DONE || tone == HomeRow.Tone.MUTED) 0.dp else 9.dp
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = fg,
        maxLines = 1,
        modifier = Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = padH, vertical = 4.dp),
    )
}
