package jp.eldia.hotel.feature.facility

import jp.eldia.hotel.core.UndoBar
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.jan.supabase.auth.auth
import jp.eldia.hotel.auth.displayName
import jp.eldia.hotel.core.DateUtil
import jp.eldia.hotel.core.FacilityConst
import jp.eldia.hotel.core.Hotel
import jp.eldia.hotel.core.SB
import jp.eldia.hotel.core.StatusColor
import jp.eldia.hotel.feature.cleaning.SubHeader
import jp.eldia.hotel.ui.components.ChipRow
import jp.eldia.hotel.ui.components.DateButton
import jp.eldia.hotel.ui.components.EldiaCard
import jp.eldia.hotel.ui.components.HotelSegmented
import jp.eldia.hotel.ui.components.PhotoEditor
import jp.eldia.hotel.ui.components.rememberSaveablePhotoUploadState
import jp.eldia.hotel.ui.components.rememberToast
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

private fun myName() = jp.eldia.hotel.core.KnownUser.current()?.displayName.orEmpty()

private const val PHOTO_ERROR_MESSAGE = "送信に失敗した写真があります。再送するか削除してください"

@Composable
private fun Badge(text: String, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
}

/** 不具合1件の行 */
@Composable
fun IssueRow(issue: Issue, showLocation: Boolean = false, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(issue.issueStatus.label, issue.issueStatus.color)
                issue.issuePriority?.let { Badge("優先度 ${it.label}", it.color) }
                if (issue.isOverdue) Badge("延滞", StatusColor.overdue)
                Spacer(Modifier.weight(1f))
                Text("${FacilityConst.areaIcons[issue.area] ?: ""}${issue.area}", style = MaterialTheme.typography.labelSmall)
            }
            Text(issue.title, fontWeight = FontWeight.SemiBold)
            val meta = buildList {
                if (showLocation) add("${issue.site.shortName} ${locationLabel(issue.roomId, issue.site)}")
                issue.reportDate?.takeIf { it.isNotBlank() }?.let { add("📅 $it") }
                issue.assignee?.takeIf { it.isNotBlank() }?.let { add("👤 $it") }
                if (issue.commentList.isNotEmpty()) add("💬 ${issue.commentList.size}")
                if (issue.photoList.isNotEmpty()) add("📷 ${issue.photoList.size}")
            }.joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// MARK: - 部屋の詳細

@Composable
fun RoomDetail(vm: FacilityViewModel, site: IssueSite, roomId: String, onBack: () -> Unit, onNew: () -> Unit, onIssue: (Long) -> Unit) {
    val issues by vm.issues.collectAsState()
    val ranks by vm.ranks.collectAsState()
    val list = vm.roomIssues(roomId, site, issues).sortedWith(compareBy({ it.issueStatus.ordinal }, { -(it.id ?: 0) }))
    val room = site.hotel?.let { h -> FacilityConst.rooms(h).firstOrNull { it.id == roomId } }
    var rankMenu by remember { mutableStateOf(false) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()

    Column(Modifier.fillMaxSize()) {
        SubHeader(locationLabel(roomId, site), onBack) { Button(onClick = onNew) { Text("報告") } }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (room != null) {
                item {
                    val rank = vm.rank(ranks, site, room)
                    EldiaCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("ランク", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Box {
                                TextButton(onClick = { rankMenu = true }) {
                                    Text(rank, color = FacilityConst.rankColors[rank] ?: Color.Gray, fontWeight = FontWeight.Bold)
                                }
                                DropdownMenu(expanded = rankMenu, onDismissRequest = { rankMenu = false }) {
                                    FacilityConst.ranks.forEach { r ->
                                        DropdownMenuItem(text = { Text(r) }, onClick = {
                                            rankMenu = false
                                            scope.launch { vm.saveRank(site, roomId, r)?.let { toast("ランクを保存できませんでした：$it") } }
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val active = list.filter { it.issueStatus != IssueStatus.DONE }
            val done = list.filter { it.issueStatus == IssueStatus.DONE }
            if (active.isEmpty()) item { Text("未対応・対応中の不具合はありません", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(active, key = { it.id ?: 0 }) { i -> IssueRow(i) { i.id?.let(onIssue) } }
            if (done.isNotEmpty()) {
                item {
                    TextButton(onClick = { showDone = !showDone }) { Text(if (showDone) "完了を隠す" else "完了 ${done.size}件を表示") }
                }
                if (showDone) items(done, key = { it.id ?: 0 }) { i -> IssueRow(i) { i.id?.let(onIssue) } }
            }
        }
    }
}

// MARK: - 報告・編集フォーム

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IssueForm(
    vm: FacilityViewModel, existing: Issue?, site: IssueSite, roomId: String, onDone: () -> Unit,
    onOpenIssue: (Long) -> Unit = {},
) {
    val templates by vm.templates.collectAsState()
    val allIssues by vm.issues.collectAsState()
    val roster by vm.roster.collectAsState()
    val areas = FacilityConst.areas(roomId)
    var title by rememberSaveable { mutableStateOf(existing?.title.orEmpty()) }
    var area by rememberSaveable { mutableStateOf(existing?.area?.takeIf { it in areas } ?: areas.firstOrNull() ?: "部屋") }
    // 編集開始時の状態（変えたときだけ保存する。他の人の状態変更を戻さないため）
    val initialStatus = rememberSaveable { existing?.issueStatus ?: IssueStatus.OPEN }
    var status by rememberSaveable { mutableStateOf(initialStatus) }
    var priority by rememberSaveable { mutableStateOf(existing?.issuePriority) }
    var occur by rememberSaveable { mutableStateOf(existing?.occurDate?.takeIf { it.isNotBlank() } ?: DateUtil.todayString()) }
    var report by rememberSaveable { mutableStateOf(existing?.reportDate?.takeIf { it.isNotBlank() } ?: DateUtil.todayString()) }
    var assignee by rememberSaveable { mutableStateOf(existing?.assignee ?: myName()) }
    var memo by rememberSaveable { mutableStateOf(existing?.memo.orEmpty()) }
    var assigneeMenu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val usable = templates.filter { it.area in areas }
    val photos = rememberSaveablePhotoUploadState("issue-photos")
    // 既存の写真を入れるのは最初の1回だけ（回転・復元後は保存しておいた写真を使う）
    var seeded by rememberSaveable(existing?.id) { mutableStateOf(false) }
    LaunchedEffect(existing?.id) {
        if (!seeded) {
            photos.setExisting(existing?.photoList.orEmpty())
            seeded = true
        }
    }

    // 保存せずに閉じるときは、この入力中にアップロードした写真をバケットから消す（回転では呼ばれない）
    fun cancel() {
        photos.discardSessionUploads()
        onDone()
    }
    BackHandler(enabled = !saving) { cancel() }
    // 新規で、失敗した写真がすべて電波なしによるもの → 端末に保存できる
    val canQueue = existing == null && photos.failedOffline
    // 重複登録の防止：同じ部屋・同じ場所の未完了の不具合（新規登録のときだけ）
    val duplicates = if (existing != null) emptyList() else allIssues.filter {
        it.site == site && it.roomId == roomId && it.area == area && it.issueStatus != IssueStatus.DONE
    }
    var confirmDuplicate by remember { mutableStateOf(false) }
    // 新規保存後の「LINEで報告」確認（報告文が入っていれば表示）
    var lineReport by remember { mutableStateOf<IssueShare?>(null) }
    var sharing by remember { mutableStateOf(false) }
    val context = LocalContext.current

    /** 保存（新規で同じ場所に未完了の不具合があれば、先に確認する） */
    fun submit(skipDuplicateCheck: Boolean = false) {
        if (title.isBlank()) { error = "内容を入力してください"; return }
        if (photos.isUploading) { error = "写真のアップロードが終わるまでお待ちください"; return }
        if (photos.hasError && !canQueue) { error = PHOTO_ERROR_MESSAGE; return }
        // 同じ場所に未完了の不具合があれば、登録してよいか確認する
        if (duplicates.isNotEmpty() && !skipDuplicateCheck) { confirmDuplicate = true; return }
        confirmDuplicate = false
        saving = true
        val base = existing ?: Issue(store = site.raw, roomId = roomId)
        val issue = base.copy(
            store = site.raw, roomId = roomId, area = area, title = title.trim(), status = status.raw,
            priority = priority?.raw, occurDate = occur, reportDate = report,
            assignee = assignee.trim(), memo = memo.trim(),
            photos = if (seeded) photos.uploadedUrls else base.photoList,
        )
        // 編集では、状態はフォームで変えたときだけ（変更の記録を残して）反映する
        val newStatus = if (existing != null && status != initialStatus) status else null
        if (existing == null) {
            // 新規：電波がなければ端末に保存して、あとで送る
            val local = if (canQueue) photos.localUris else emptyList()
            scope.launch {
                when (val r = vm.createIssue(issue, local)) {
                    is CreateResult.Saved -> {
                        photos.keepSessionUploads(); saving = false; toast("保存しました")
                        lineReport = IssueShare(issueReportText(r.issue, myName()), r.issue.photoList)
                    }
                    CreateResult.Queued -> {
                        photos.keepSessionUploads()
                        saving = false
                        toast("電波がないため端末に保存しました。電波が戻ったら送信します")
                        // 電波なし：送れた写真は URL、送れなかった写真は端末内のものを添付
                        lineReport = IssueShare(issueReportText(issue, myName()), issue.photoList, local)
                    }
                    is CreateResult.Failed -> { saving = false; error = "保存できませんでした：${r.message}" }
                }
            }
            return
        }
        scope.launch {
            val (saved, err) = vm.saveIssue(issue, newStatus, myName())
            saving = false
            when {
                saved == null -> error = "保存できませんでした：$err"
                err != null -> { photos.keepSessionUploads(); toast("保存しましたが、$err"); onDone() }
                else -> { photos.keepSessionUploads(); toast("保存しました"); onDone() }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        SubHeader(if (existing == null) "不具合を報告" else "不具合を編集", { if (!saving) cancel() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${site.shortName} · ${locationLabel(roomId, site)}", color = site.accent, fontWeight = FontWeight.Bold)
            if (duplicates.isNotEmpty()) {
                // 同じ場所にまだ対応が終わっていない不具合がある（同じ内容なら、新しく登録せずにコメントを追加）
                EldiaCard {
                    Text("この場所（$area）に未完了の不具合が${duplicates.size}件あります", color = StatusColor.progress,
                        fontWeight = FontWeight.Bold)
                    Text("同じ内容なら、新しく登録せずに既存の不具合を開いてコメントを追加してください",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    duplicates.take(5).forEach { d ->
                        Row(
                            Modifier.fillMaxWidth().clickable { d.id?.let { photos.discardSessionUploads(); onOpenIssue(it) } }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(d.title, fontWeight = FontWeight.SemiBold,
                                    color = if (d.title.trim() == title.trim()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                                Text("${d.issueStatus.label}・報告 ${d.reportDate ?: "—"}", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("開く →", color = site.accent, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            if (existing == null && usable.isNotEmpty()) {
                EldiaCard {
                    Text("テンプレート", style = MaterialTheme.typography.labelLarge)
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        usable.forEach { t ->
                            Text(t.title, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { title = t.title; area = t.area }.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                }
            }
            EldiaCard {
                OutlinedTextField(title, { title = it; error = null }, label = { Text("内容") }, modifier = Modifier.fillMaxWidth())
                Text("場所", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    areas.forEach { a -> SelectPill("${FacilityConst.areaIcons[a] ?: ""}$a", a == area, site.accent) { area = a } }
                }
                Text("状態", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IssueStatus.entries.forEach { s -> SelectPill(s.label, s == status, s.color) { status = s } }
                }
                Text("優先度", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectPill("なし", priority == null, MaterialTheme.colorScheme.outline) { priority = null }
                    IssuePriority.entries.forEach { p -> SelectPill(p.label, p == priority, p.color) { priority = p } }
                }
            }
            EldiaCard {
                Text("発生日", style = MaterialTheme.typography.labelLarge)
                DateButton(DateUtil.parse(occur) ?: DateUtil.today(), { occur = DateUtil.ymd(it) })
                Text("報告日", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                DateButton(DateUtil.parse(report) ?: DateUtil.today(), { report = DateUtil.ymd(it) })
            }
            EldiaCard {
                Box {
                    OutlinedTextField(assignee, { assignee = it }, label = { Text("担当者") }, singleLine = true,
                        trailingIcon = { if (roster.isNotEmpty()) TextButton(onClick = { assigneeMenu = true }) { Text("選択") } },
                        modifier = Modifier.fillMaxWidth())
                    DropdownMenu(expanded = assigneeMenu, onDismissRequest = { assigneeMenu = false }) {
                        roster.forEach { n -> DropdownMenuItem(text = { Text(n) }, onClick = { assignee = n; assigneeMenu = false }) }
                    }
                }
                OutlinedTextField(memo, { memo = it }, label = { Text("メモ") }, minLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            EldiaCard { PhotoEditor(photos, max = 10, title = "写真") }
            if (canQueue) {
                Text("電波がないため送れなかった写真があります。このまま保存すると端末に保存し、電波が戻ったら送信します",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else if (photos.hasError) Text(PHOTO_ERROR_MESSAGE, color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = { submit() },
            enabled = !saving && !photos.isUploading && (!photos.hasError || canQueue),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
        ) {
            if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            else Text(
                when {
                    photos.isUploading -> "写真をアップロード中…"
                    canQueue -> "端末に保存（電波が戻ったら送信）"
                    else -> "保存"
                },
                fontWeight = FontWeight.Bold,
            )
        }
    }
    lineReport?.let { share ->
        val count = share.photoUrls.size + share.localPhotos.size
        AlertDialog(
            onDismissRequest = { if (!sharing) { lineReport = null; onDone() } },
            title = { Text("LINEで報告しますか？") },
            text = {
                Column {
                    Text(share.text, style = MaterialTheme.typography.bodySmall)
                    if (count > 0) {
                        Text("写真${count}枚を画像で添付します。文章が届かなかったときは、LINEの入力欄に貼り付けてください（コピー済み）",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !sharing, onClick = {
                    sharing = true
                    scope.launch {
                        shareIssueReport(context, share)
                        sharing = false
                        lineReport = null
                        onDone()
                    }
                }) {
                    if (sharing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("LINEで報告")
                }
            },
            dismissButton = {
                TextButton(enabled = !sharing, onClick = { lineReport = null; onDone() }) { Text("あとで") }
            },
        )
    }
    if (confirmDuplicate && !saving) {
        AlertDialog(
            onDismissRequest = { confirmDuplicate = false },
            title = { Text("同じ場所に未完了の不具合があります") },
            text = {
                Text("${locationLabel(roomId, site)}（$area）に未完了の不具合が${duplicates.size}件あります。\n" +
                    duplicates.take(3).joinToString("\n") { "・${it.title}" } +
                    "\n\n別の不具合として登録しますか？")
            },
            confirmButton = {
                TextButton(onClick = { submit(skipDuplicateCheck = true) }) { Text("登録する") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDuplicate = false }) { Text("やめる") }
            },
        )
    }
}

@Composable
private fun SelectPill(text: String, on: Boolean, color: Color, onClick: () -> Unit) {
    Text(text, color = if (on) Color.White else MaterialTheme.colorScheme.onSurface, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) color else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}

// MARK: - 不具合の詳細（状態の変更・コメント）

@Composable
fun IssueDetail(vm: FacilityViewModel, issueId: Long, onBack: () -> Unit, onEdit: (Long) -> Unit, onRoom: (IssueSite, String) -> Unit) {
    val issues by vm.issues.collectAsState()
    val issue = issues.firstOrNull { it.id == issueId }
    var comment by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteComment by remember { mutableStateOf<IssueComment?>(null) }
    var viewer by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val commentPhotos = rememberSaveablePhotoUploadState("issue-photos")
    var showCommentPhotos by rememberSaveable { mutableStateOf(false) }

    // 画面を離れるときは、送信していないコメント用の写真をバケットから消す（回転では呼ばれない）
    fun leave(go: () -> Unit) {
        commentPhotos.discardSessionUploads()
        go()
    }
    BackHandler { leave(onBack) }

    if (issue == null) {
        Column(Modifier.fillMaxSize()) {
            SubHeader("不具合", onBack)
            Text("この不具合は削除されたか、まだ読み込まれていません", Modifier.padding(16.dp))
        }
        return
    }

    fun act(block: suspend () -> String?) {
        busy = true
        scope.launch { block()?.let { toast("保存できませんでした：$it") }; busy = false }
    }

    Column(Modifier.fillMaxSize()) {
        SubHeader(locationLabel(issue.roomId, issue.site), { leave(onBack) }) {
            IconButton(onClick = { leave { onEdit(issueId) } }) { Icon(Icons.Outlined.Edit, "編集") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "削除", tint = MaterialTheme.colorScheme.error) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                EldiaCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Badge(issue.issueStatus.label, issue.issueStatus.color)
                        issue.issuePriority?.let { Badge("優先度 ${it.label}", it.color) }
                        if (issue.isOverdue) Badge("延滞", StatusColor.overdue)
                    }
                    Text(issue.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    Text("${issue.site.shortName} · ${locationLabel(issue.roomId, issue.site)} · ${FacilityConst.areaIcons[issue.area] ?: ""}${issue.area}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { leave { onRoom(issue.site, issue.roomId) } })
                    Spacer(Modifier.height(8.dp))
                    Text("発生日 ${issue.occurDate ?: "-"} ／ 報告日 ${issue.reportDate ?: "-"}", style = MaterialTheme.typography.bodySmall)
                    Text("担当者 ${issue.assignee?.takeIf { it.isNotBlank() } ?: "未設定"}", style = MaterialTheme.typography.bodySmall)
                    issue.memo?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.padding(top = 8.dp)) }
                    if (issue.photoList.isNotEmpty()) {
                        Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            issue.photoList.forEach { url ->
                                AsyncImage(url, "写真（タップで拡大）", contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)).clickable { viewer = url })
                            }
                        }
                    }
                }
            }
            item {
                EldiaCard {
                    Text("状態を変更", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IssueStatus.entries.forEach { s ->
                            SelectPill(s.label, s == issue.issueStatus, s.color) {
                                if (s != issue.issueStatus && !busy) act {
                                    val prev = issue.issueStatus
                                    vm.setStatus(issue, s, myName()).also { err ->
                                        // 5 秒間「元に戻す」（押したら前の状態に戻す。コメントにも記録が残る）
                                        if (err == null) UndoBar.offer("「${s.label}」にしました") { vm.setStatus(issue, prev, myName()) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { Text("コメント ${issue.commentList.size}件", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
            items(issue.commentList.reversed(), key = { it.key }) { c ->
                EldiaCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.author.ifBlank { "（名前なし）" }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(commentDate(c.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (c.author == myName()) {
                            IconButton(onClick = { deleteComment = c }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.Delete, "削除", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    c.statusChange?.let { ch ->
                        Text("状態：${IssueStatus.from(ch.from).label} → ${IssueStatus.from(ch.to).label}",
                            color = IssueStatus.from(ch.to).color, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                    if (c.text.isNotBlank()) Text(c.text, Modifier.padding(top = 4.dp))
                    if (c.photos.isNotEmpty()) {
                        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            c.photos.forEach { url ->
                                AsyncImage(url, "写真（タップで拡大）", contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).clickable { viewer = url })
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        // 失敗した写真があるときは、再送・削除できるように必ず表示する
        if (showCommentPhotos || commentPhotos.hasError) {
            Box(Modifier.padding(horizontal = 12.dp).padding(top = 8.dp)) { PhotoEditor(commentPhotos, max = 5, title = "コメントに添付する写真") }
        }
        if (commentPhotos.hasError) {
            Text(PHOTO_ERROR_MESSAGE, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp).padding(top = 4.dp))
        }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { showCommentPhotos = !showCommentPhotos }) {
                Icon(Icons.Outlined.PhotoCamera, "写真を添付",
                    tint = if (showCommentPhotos || commentPhotos.items.isNotEmpty()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(comment, { comment = it }, placeholder = { Text("コメントを入力") }, modifier = Modifier.weight(1f), maxLines = 4)
            Button(
                onClick = {
                    val t = comment.trim()
                    val urls = commentPhotos.uploadedUrls
                    if (t.isEmpty() && urls.isEmpty()) return@Button
                    if (commentPhotos.isUploading || commentPhotos.hasError) return@Button
                    act {
                        vm.appendComment(issueId, myName(), t, urls, null).also {
                            if (it == null) {
                                comment = ""
                                commentPhotos.removeUploaded(urls)
                                if (commentPhotos.items.isEmpty()) showCommentPhotos = false
                            }
                        }
                    }
                },
                enabled = !busy && !commentPhotos.isUploading && !commentPhotos.hasError &&
                    (comment.isNotBlank() || commentPhotos.uploadedUrls.isNotEmpty()),
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("送信") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("この不具合を削除しますか？") },
            text = { Text("コメントと写真の記録も消えます。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        val err = vm.deleteIssue(issueId)
                        if (err == null) { toast("削除しました"); leave(onBack) } else toast("削除できませんでした：$err")
                    }
                }) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") } },
        )
    }
    deleteComment?.let { c ->
        AlertDialog(
            onDismissRequest = { deleteComment = null },
            title = { Text("このコメントを削除しますか？") },
            confirmButton = {
                TextButton(onClick = { deleteComment = null; act { vm.deleteComment(issueId, c) } }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteComment = null }) { Text("キャンセル") } },
        )
    }
    viewer?.let { url ->
        AlertDialog(
            onDismissRequest = { viewer = null },
            confirmButton = { TextButton(onClick = { viewer = null }) { Text("閉じる") } },
            text = { AsyncImage(url, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth()) },
        )
    }
}

private fun commentDate(iso: String): String = runCatching {
    val d = OffsetDateTime.parse(iso).atZoneSameInstant(DateUtil.TZ)
    "%d/%d %02d:%02d".format(d.monthValue, d.dayOfMonth, d.hour, d.minute)
}.getOrDefault(iso.take(16))

// MARK: - 一覧（スレッド）

@Composable
fun ThreadList(vm: FacilityViewModel, initialFilter: String?, onBack: () -> Unit, onIssue: (Long) -> Unit) {
    val issues by vm.issues.collectAsState()
    var filter by rememberSaveable { mutableStateOf(initialFilter ?: "active") }
    var siteFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val filters = listOf("active", "open", "progress", "overdue", "done", "all")
    fun label(f: String) = when (f) {
        "active" -> "未完了"; "open" -> "未対応"; "progress" -> "対応中"; "overdue" -> "延滞"; "done" -> "完了"; else -> "すべて"
    }
    val list = issues
        .filter { siteFilter == null || it.store == siteFilter }
        .filter {
            when (filter) {
                "active" -> it.issueStatus != IssueStatus.DONE
                "open" -> it.issueStatus == IssueStatus.OPEN
                "progress" -> it.issueStatus == IssueStatus.PROGRESS
                "overdue" -> it.isOverdue
                "done" -> it.issueStatus == IssueStatus.DONE
                else -> true
            }
        }
        .sortedByDescending { it.updatedAt ?: "" }

    Column(Modifier.fillMaxSize()) {
        SubHeader("不具合一覧", onBack)
        Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ChipRow(filters, filter, ::label, MaterialTheme.colorScheme.primary) { filter = it }
            ChipRow(listOf<String?>(null) + IssueSite.entries.map { it.raw }, siteFilter,
                { r -> r?.let { IssueSite.from(it).shortName } ?: "全施設" }, MaterialTheme.colorScheme.primary) { siteFilter = it }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("${list.size}件", style = MaterialTheme.typography.labelLarge) }
            if (list.isEmpty()) item { Text("該当する不具合はありません", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(list, key = { it.id ?: 0 }) { i -> IssueRow(i, showLocation = true) { i.id?.let(onIssue) } }
        }
    }
}

// MARK: - 報告する部屋を選ぶ

@Composable
fun RoomPicker(initial: Hotel, onBack: () -> Unit, onPick: (IssueSite, String) -> Unit) {
    var site by rememberSaveable { mutableStateOf(IssueSite.of(initial)) }
    var floor by rememberSaveable(site) { mutableStateOf("all") }
    Column(Modifier.fillMaxSize()) {
        SubHeader("報告する場所を選択", onBack)
        LazyVerticalGrid(
            GridCells.Adaptive(84.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { SiteSegmented(site) { site = it } }
            val h = site.hotel
            if (h == null) {
                items(FacilityConst.exteriors, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { e ->
                    OutlinedButton(onClick = { onPick(site, e.id) }, modifier = Modifier.fillMaxWidth()) { Text(e.name) }
                }
            } else {
                val floors = FacilityConst.floors(h)
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ChipRow(listOf("all") + floors, floor, { if (it == "all") "全て" else it }, h.accent) { floor = it }
                }
                floors.filter { floor == "all" || it == floor }.forEach { f ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(f, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(FacilityConst.rooms(h, f), key = { it.id }) { r ->
                        OutlinedButton(onClick = { onPick(site, r.id) }) { Text(r.num) }
                    }
                    FacilityConst.commons(h).firstOrNull { it.floor == f }?.let { c ->
                        item(key = c.id, span = { GridItemSpan(maxLineSpan) }) {
                            OutlinedButton(onClick = { onPick(site, c.id) }, modifier = Modifier.fillMaxWidth()) { Text(c.name) }
                        }
                    }
                }
            }
        }
    }
}
