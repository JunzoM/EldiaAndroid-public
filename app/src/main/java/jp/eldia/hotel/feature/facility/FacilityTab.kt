package jp.eldia.hotel.feature.facility

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import jp.eldia.hotel.core.AppPrefs
import jp.eldia.hotel.core.FacilityConst
import jp.eldia.hotel.core.Room
import jp.eldia.hotel.core.StatusColor
import jp.eldia.hotel.core.TabRequests
import jp.eldia.hotel.ui.components.PillFab
import jp.eldia.hotel.ui.components.ChipRow
import jp.eldia.hotel.ui.components.rememberToast
import kotlinx.coroutines.launch

/**
 * 設備管理のルート。画面の切り替えは文字列で持つ（回転しても戻る）
 * home / room:<site>:<id> / new:<site>:<id> / edit:<issueId> / issue:<issueId> / thread:<filter> / pick
 */
@Composable
fun FacilityTab(modifier: Modifier = Modifier, vm: FacilityViewModel = viewModel()) {
    val hotel by AppPrefs.hotel.collectAsState()
    var site by rememberSaveable { mutableStateOf(IssueSite.of(hotel)) }
    var route by rememberSaveable { mutableStateOf("home") }
    var back by rememberSaveable { mutableStateOf(listOf<String>()) }

    fun go(r: String) { back = back + route; route = r }
    fun pop() { route = back.lastOrNull() ?: "home"; back = back.dropLast(1) }

    LaunchedEffect(Unit) { vm.start() }
    // アプリに戻ったときに読み直す（30秒以内は読み直さない）
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    LaunchedEffect(hotel) { if (site != IssueSite.EXTERIOR) site = IssueSite.of(hotel) }
    BackHandler(enabled = route != "home") { pop() }

    // ショートカット「不具合を登録」：部屋の選択から始める（入力中のフォームは閉じない）
    val newRequested by TabRequests.facilityNew.collectAsState()
    LaunchedEffect(newRequested) {
        if (!newRequested) return@LaunchedEffect
        TabRequests.facilityNew.value = false
        if (!route.startsWith("new") && !route.startsWith("edit") && route != "pick") {
            back = listOf("home")
            route = "pick"
        }
    }

    // 通知「新しい不具合」のタップ：その不具合を開く（入力中のフォームは閉じない）
    val issueRequested by TabRequests.facilityIssue.collectAsState()
    LaunchedEffect(issueRequested) {
        val id = issueRequested ?: return@LaunchedEffect
        TabRequests.facilityIssue.value = null
        if (!route.startsWith("new") && !route.startsWith("edit")) {
            back = listOf("home")
            route = "issue:$id"
            vm.loadIssues(silent = true)
        }
    }

    RouteTransition(route, back.size, modifier) { r ->
        val parts = r.split(":")
        when (parts[0]) {
            "room" -> RoomDetail(vm, IssueSite.from(parts[1]), parts[2], onBack = ::pop,
                onNew = { go("new:${parts[1]}:${parts[2]}") }, onIssue = { go("issue:$it") })
            "new" -> IssueForm(vm, existing = null, site = IssueSite.from(parts[1]), roomId = parts[2], onDone = ::pop,
                // 重複の確認で「既存の不具合を開く」：登録フォームを閉じて、その不具合の詳細へ
                onOpenIssue = { route = "issue:$it" })
            "edit" -> {
                val issue = vm.issues.collectAsState().value.firstOrNull { it.id == parts[1].toLongOrNull() }
                if (issue != null) IssueForm(vm, existing = issue, site = issue.site, roomId = issue.roomId, onDone = ::pop)
                else LaunchedEffect(Unit) { pop() }
            }
            "issue" -> IssueDetail(vm, parts[1].toLong(), onBack = ::pop, onEdit = { go("edit:$it") },
                onRoom = { s, r -> go("room:${s.raw}:$r") })
            "thread" -> ThreadList(vm, parts.getOrNull(1)?.takeIf { it.isNotEmpty() }, onBack = ::pop, onIssue = { go("issue:$it") })
            "pick" -> RoomPicker(site.hotel ?: hotel, onBack = ::pop) { s, r -> route = "new:${s.raw}:$r" }
            else -> FacilityHome(vm, site, onSite = { s ->
                site = s
                s.hotel?.let { if (it != hotel) AppPrefs.setHotel(it) }
            }, onRoom = { s, r -> go("room:${s.raw}:$r") }, onThread = { go("thread:${it ?: ""}") }, onReport = { go("pick") })
        }
    }
}

// MARK: - ホーム（部屋グリッド）

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FacilityHome(
    vm: FacilityViewModel, site: IssueSite,
    onSite: (IssueSite) -> Unit, onRoom: (IssueSite, String) -> Unit, onThread: (String?) -> Unit, onReport: () -> Unit,
) {
    val issues by vm.issues.collectAsState()
    val activeIndex = remember(issues) { ActiveIssueIndex(issues) }
    val ranks by vm.ranks.collectAsState()
    val loading by vm.loading.collectAsState()
    val live by vm.live.collectAsState()
    val error by vm.error.collectAsState()
    var floor by rememberSaveable(site) { mutableStateOf("all") }
    var sortByIssues by rememberSaveable(site) { mutableStateOf(false) }
    val s = vm.stats(issues, site)
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = loading, onRefresh = { scope.launch { vm.refresh() } }, modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                GridCells.Adaptive(96.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("設備管理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            // リアルタイム同期の印（緑＝同期中）
                            Box(Modifier.size(8.dp).background(if (live) StatusColor.done else MaterialTheme.colorScheme.outline, CircleShape))
                            TextButton(onClick = { onThread(null) }) {
                                Icon(Icons.Outlined.Forum, null, Modifier.size(18.dp)); Text("一覧", Modifier.padding(start = 4.dp))
                            }
                        }
                        SiteSegmented(site, onSite)
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        PendingIssuesCard(vm)
                        if (loading && issues.isEmpty()) CircularProgressIndicator()
                        if (site != IssueSite.EXTERIOR) {
                            val total = site.hotel?.let { FacilityConst.rooms(it).size } ?: 0
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { StatChip("総部屋数", total, MaterialTheme.colorScheme.onSurfaceVariant, null) }
                                item { StatChip("未対応", s.open, StatusColor.open) { onThread("open") } }
                                item { StatChip("対応中", s.progress, StatusColor.progress) { onThread("progress") } }
                                item { StatChip("完了(今月)", s.doneThisMonth, StatusColor.done) { onThread("done") } }
                                if (s.overdue > 0) item { StatChip("延滞", s.overdue, StatusColor.overdue) { onThread("overdue") } }
                                item { StatChip("問題部屋", s.affectedRooms, Color(0xFF8E24AA)) { sortByIssues = true } }
                            }
                        }
                    }
                }

                if (site == IssueSite.EXTERIOR) {
                    items(FacilityConst.exteriors, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { e ->
                        LocationCard(e.name, "外構部", Icons.Outlined.Park, activeIndex, site, e.id) { onRoom(site, e.id) }
                    }
                } else {
                    val hotel = site.hotel!!
                    val floors = FacilityConst.floors(hotel)
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                ChipRow(listOf("all") + floors, floor, { f ->
                                    if (f == "all") "全て" else activeIndex.floorCount(site, f).let { n -> if (n > 0) "$f $n" else f }
                                }, hotel.accent) { floor = it; sortByIssues = false }
                            }
                            FilterChip(
                                selected = sortByIssues, onClick = { sortByIssues = !sortByIssues }, label = { Text("問題順") },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = StatusColor.open.copy(alpha = 0.18f)),
                            )
                        }
                    }
                    if (sortByIssues) {
                        val rooms = FacilityConst.rooms(hotel)
                            .map { r -> r to activeIndex.count(site, r.id) }
                            .filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
                        if (rooms.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text("未対応・対応中の問題はありません", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                            }
                        }
                        items(rooms, key = { it.id }) { r -> RoomCard(r, vm.rank(ranks, site, r), activeIndex.active(site, r.id)) { onRoom(site, r.id) } }
                    } else {
                        floors.filter { floor == "all" || it == floor }.forEach { f ->
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                val n = activeIndex.floorCount(site, f)
                                Row(Modifier.padding(top = 8.dp)) {
                                    Text(f, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                    if (n > 0) Text("${n}件", style = MaterialTheme.typography.labelMedium, color = StatusColor.open)
                                }
                            }
                            items(FacilityConst.rooms(hotel, f), key = { it.id }) { r ->
                                RoomCard(r, vm.rank(ranks, site, r), activeIndex.active(site, r.id)) { onRoom(site, r.id) }
                            }
                            FacilityConst.commons(hotel).firstOrNull { it.floor == f }?.let { c ->
                                item(key = c.id, span = { GridItemSpan(maxLineSpan) }) {
                                    LocationCard(c.name, c.floor, Icons.Outlined.Apartment, activeIndex, site, c.id) { onRoom(site, c.id) }
                                }
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(80.dp)) }
            }
        }
        PillFab("不具合を報告", Icons.Outlined.Add, site.accent, onClick = onReport,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
}

@Composable
fun SiteSegmented(selected: IssueSite, onSelect: (IssueSite) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IssueSite.entries.forEach { s ->
            val on = s == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (on) s.accent else Color.Transparent)
                    .clickable { onSelect(s) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(s.hotel?.english?.uppercase() ?: s.label, fontWeight = FontWeight.Bold,
                    color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: Int, color: Color, onClick: (() -> Unit)?) {
    Card(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$value", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

/** 部屋カード（左の線＝ランクの色。未完了の不具合は場所ごとのバッジ） */
@Composable
fun RoomCard(room: Room, rank: String, active: List<Issue>, onClick: () -> Unit) {
    val openN = active.count { it.issueStatus == IssueStatus.OPEN }
    val tint = when {
        openN > 0 -> StatusColor.open
        active.isNotEmpty() -> StatusColor.progress
        else -> null
    }
    val rankColor = FacilityConst.rankColors[rank] ?: Color.Gray
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = tint?.copy(alpha = 0.10f) ?: MaterialTheme.colorScheme.surfaceContainer),
        border = tint?.let { BorderStroke(1.dp, it.copy(alpha = 0.5f)) },
    ) {
        Row(Modifier.heightIn(min = 78.dp)) {
            Box(Modifier.padding(vertical = 12.dp).width(3.dp).fillMaxHeight().background(rankColor, RoundedCornerShape(2.dp)))
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(room.num, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(rank, style = MaterialTheme.typography.labelSmall, color = rankColor, maxLines = 1)
                if (active.isEmpty()) {
                    Text("異常なし", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        FacilityConst.areas.forEach { a ->
                            val n = active.count { it.area == a }
                            if (n > 0) {
                                val c = FacilityConst.areaBadgeColors[a] ?: Color.Gray
                                Text("${FacilityConst.areaIcons[a]}$n", color = c, style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.background(c.copy(alpha = 0.16f), RoundedCornerShape(50)).padding(horizontal = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 共用部・外構部のカード */
@Composable
private fun LocationCard(
    name: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    index: ActiveIssueIndex, site: IssueSite, id: String, onClick: () -> Unit,
) {
    val n = index.count(site, id)
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = if (n > 0) StatusColor.open.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = site.accent)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(name, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (n > 0) "${n}件" else "異常なし", color = if (n > 0) StatusColor.open else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 未送信の不具合（電波がなくて端末に保存したもの）。1件ごとに「今すぐ送信」「破棄」 */
@Composable
private fun PendingIssuesCard(vm: FacilityViewModel) {
    val pending by vm.pendingIssues.collectAsState()
    val sending by vm.sendingPending.collectAsState()
    var discardTarget by remember { mutableStateOf<PendingIssue?>(null) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    if (pending.isEmpty()) return

    Card(
        colors = CardDefaults.cardColors(containerColor = StatusColor.progress.copy(alpha = 0.10f)),
        border = BorderStroke(1.dp, StatusColor.progress.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudOff, null, Modifier.size(18.dp), tint = StatusColor.progress)
                Text("未送信 ${pending.size}件", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 6.dp))
                if (sending) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Text("電波が戻ると自動で送信します", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            pending.forEach { p ->
                val site = p.issue.site
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(p.issue.title, fontWeight = FontWeight.SemiBold)
                    val meta = buildList {
                        add("${site.shortName} ${locationLabel(p.issue.roomId, site)}")
                        if (p.photoCount > 0) add("📷 ${p.photoCount}")
                        if (p.failCount > 0) add("送信失敗 ${p.failCount}回")
                    }.joinToString(" · ")
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    p.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !sending, onClick = {
                            scope.launch {
                                val err = vm.flushPendingIssues(p.id)
                                if (err != null) toast("送信できませんでした：$err") else toast("送信しました")
                            }
                        }) { Text("今すぐ送信") }
                        TextButton(enabled = !sending, onClick = { discardTarget = p }) {
                            Text("破棄", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    discardTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { discardTarget = null },
            title = { Text("未送信の報告を破棄しますか？") },
            text = { Text("「${t.issue.title}」は送信されずに削除されます。") },
            confirmButton = {
                TextButton(onClick = {
                    discardTarget = null
                    vm.discardPending(t.id)
                    toast("破棄しました")
                }) { Text("破棄", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { discardTarget = null }) { Text("キャンセル") } },
        )
    }
}
