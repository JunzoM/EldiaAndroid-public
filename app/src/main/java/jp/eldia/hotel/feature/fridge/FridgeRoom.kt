package jp.eldia.hotel.feature.fridge

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Outbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import jp.eldia.hotel.core.DateUtil
import jp.eldia.hotel.feature.cleaning.SubHeader
import jp.eldia.hotel.ui.components.DateButton
import jp.eldia.hotel.ui.components.EldiaCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 部屋詳細 4×4 グリッド（本家 RV） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FridgeRoomScreen(vm: FridgeViewModel, rn: String, onBack: () -> Unit, onScan: () -> Unit) {
    val data by vm.data.collectAsState()
    val removed by vm.removed.collectAsState()
    val pending by vm.pending.collectAsState()
    val slots = data[rn] ?: FridgeConst.defaultSlots()
    val removedSlots = slots.indices.filter { "$rn-$it" in removed }
    val pendingKeys = remember(pending) { pending.map { "${it.rn}-${it.idx}" }.toSet() }
    var menuTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    var expiryTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    var nameTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    /** 管理用パスワードが必要な操作（"delete" / "rename:<idx>"） */
    var pinRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var selectMode by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(setOf<Int>()) }
    var confirmBulk by remember { mutableStateOf(false) }
    val nme = FridgeConst.nextMonthEnd()

    // 要交換マスの点滅（画面全体で1つのアニメーションを共有する）
    val blink by rememberInfiniteTransition(label = "blink").animateFloat(
        1f, 0.55f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "blinkAlpha",
    )

    Column(Modifier.fillMaxSize()) {
        SubHeader("${rn}号室", onBack) {
            if (!selectMode) {
                TextButton(onClick = onScan) {
                    Icon(Icons.Outlined.DocumentScanner, null, Modifier.size(16.dp))
                    Text("連続読み取り", Modifier.padding(start = 4.dp))
                }
                TextButton(onClick = { pinRequest = "delete" }) {
                    Icon(Icons.Outlined.Lock, null, Modifier.size(16.dp))
                    Text("削除", Modifier.padding(start = 4.dp))
                }
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EldiaCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("次回月末交換", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    Text(FridgeConst.mdw(nme), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
            if (removedSlots.isNotEmpty()) {
                Surface(color = FridgeStatus.ORANGE.color.copy(alpha = 0.10f), shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, FridgeStatus.ORANGE.color.copy(alpha = 0.5f))) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("取り出し中 - タップして「元の場所に戻した」を選択", color = FridgeStatus.ORANGE.color,
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            removedSlots.forEach { i ->
                                Text("${FridgeConst.slotLabel(i)} ${slots[i].name}", style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).background(FridgeStatus.ORANGE.color.copy(alpha = 0.15f))
                                        .clickable { menuTarget = i }.padding(horizontal = 10.dp, vertical = 4.dp))
                            }
                        }
                    }
                }
            }
            Text(if (selectMode) "${selected.size}マス選択中" else "マスをタップしてメニューを開く",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // 4×4 グリッド
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Spacer(Modifier.width(16.dp))
                    (1..4).forEach { n ->
                        Text("$n", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                (0 until 4).forEach { ri ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(listOf("A", "B", "C", "D")[ri], Modifier.width(16.dp), style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        (0 until 4).forEach { ci ->
                            val idx = ri * 4 + ci
                            SlotCell(
                                slot = slots[idx], idx = idx, gone = "$rn-$idx" in removed, selectMode = selectMode,
                                isSel = idx in selected, pendingMark = "$rn-$idx" in pendingKeys, blink = blink,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (selectMode) selected = if (idx in selected) selected - idx else selected + idx
                                else menuTarget = idx
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        if (selectMode) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { selected = if (selected.size == 16) emptySet() else (0 until 16).toSet() }) {
                    Text(if (selected.size == 16) "全解除" else "全選択")
                }
                Button(
                    onClick = { confirmBulk = true }, enabled = selected.isNotEmpty(), modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = FridgeStatus.RED.color, contentColor = Color.White),
                ) { Text(if (selected.isEmpty()) "マスを削除" else "${selected.size}マスを削除") }
                OutlinedButton(onClick = { selectMode = false; selected = emptySet() }) { Text("キャンセル") }
            }
        }
    }

    menuTarget?.let { idx ->
        SlotMenuSheet(rn, idx, slots[idx].name, "$rn-$idx" in removed, onDismiss = { menuTarget = null }) { action ->
            menuTarget = null
            when (action) {
                SlotAction.EXPIRY -> expiryTarget = idx
                SlotAction.NAME -> pinRequest = "rename:$idx"
                SlotAction.TOGGLE -> vm.toggleRemoved(rn, idx)
            }
        }
    }
    expiryTarget?.let { idx ->
        ExpirySheet(rn, idx, slots[idx], onDismiss = { expiryTarget = null }) { v ->
            expiryTarget = null
            vm.updateExpiry(rn, idx, v)
        }
    }
    nameTarget?.let { idx ->
        ItemNameSheet(rn, idx, slots[idx].name, onDismiss = { nameTarget = null }) { name, all ->
            nameTarget = null
            vm.updateName(rn, idx, name, all)
        }
    }
    pinRequest?.let { req ->
        val message = if (req == "delete") "マスを削除するにはパスワードを入力してください"
        else "${FridgeConst.slotLabel(req.substringAfter(":").toIntOrNull() ?: 0)} の品目を変更・削除するにはパスワードを入力してください"
        AdminPinDialog(message, onDismiss = { pinRequest = null }) {
            pinRequest = null
            if (req == "delete") { selected = emptySet(); selectMode = true }
            else nameTarget = req.substringAfter(":").toIntOrNull()
        }
    }
    if (confirmBulk) {
        AlertDialog(
            onDismissRequest = { confirmBulk = false },
            title = { Text("${selected.size}マスを削除しますか？") },
            confirmButton = {
                TextButton(onClick = {
                    val idxs = selected.sorted()
                    confirmBulk = false
                    selectMode = false
                    selected = emptySet()
                    vm.bulkDelete(rn, idxs)
                }) { Text("削除", color = FridgeStatus.RED.color) }
            },
            dismissButton = { TextButton(onClick = { confirmBulk = false }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun SlotCell(
    slot: Slot, idx: Int, gone: Boolean, selectMode: Boolean, isSel: Boolean, pendingMark: Boolean, blink: Float,
    modifier: Modifier, onClick: () -> Unit,
) {
    val st = FridgeConst.status(slot.exp)
    val orange = FridgeStatus.ORANGE.color
    val empty = slot.name.isEmpty() && !gone && !selectMode
    val lamp0 = if (gone) orange else if (st == FridgeStatus.NONE) FridgeStatus.NONE.color.copy(alpha = 0.4f) else st.color
    val expired = st == FridgeStatus.RED && FridgeConst.isExpired(slot.exp)
    val statusText = if (gone && !selectMode) "取り出し中" else when (st) {
        FridgeStatus.RED -> if (expired) "期限切れ" else "要交換"
        FridgeStatus.ORANGE -> "月末交換"
        FridgeStatus.OK -> "販売中"
        FridgeStatus.NONE -> "未入力"
    }
    val lamp = if (expired && !gone) FridgeConst.expiredColor else lamp0
    val borderColor = when {
        selectMode -> FridgeStatus.RED.color.copy(alpha = if (isSel) 1f else 0.4f)
        gone -> orange
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val alpha = when {
        empty -> 0.35f
        st == FridgeStatus.RED && !gone && !selectMode -> blink
        else -> 1f
    }
    Box(modifier.alpha(alpha)) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(if (selectMode && isSel || gone) 2.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .background(if (gone && !selectMode) orange.copy(alpha = 0.15f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(statusText, color = lamp, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Clip, modifier = Modifier.weight(1f))
                Text(FridgeConst.slotLabel(idx), fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!selectMode) Box(Modifier.padding(start = 2.dp).size(6.dp).background(lamp, CircleShape))
            }
            Column(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(3.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                if (gone && !selectMode) Icon(Icons.Outlined.Inbox, null, Modifier.size(14.dp), tint = orange)
                Text(slot.name, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    maxLines = 3, lineHeight = 12.sp, overflow = TextOverflow.Ellipsis)
            }
            if (!selectMode) {
                val bg = if ((st == FridgeStatus.RED || st == FridgeStatus.ORANGE) && !gone) st.color.copy(alpha = 0.15f) else Color.Transparent
                Column(Modifier.fillMaxWidth().background(bg).padding(vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        gone -> Text("ここに戻す", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = orange)
                        slot.exp.isNotEmpty() -> {
                            val c = if (st == FridgeStatus.OK) MaterialTheme.colorScheme.onSurfaceVariant else st.color
                            Text(FridgeConst.md(slot.exp), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = c)
                            Text(FridgeConst.daysLabel(slot.exp), fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = c)
                        }
                        else -> Text("——", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
        if (selectMode && isSel) {
            Icon(Icons.Outlined.CheckCircle, null, Modifier.align(Alignment.TopStart).padding(3.dp).size(16.dp), tint = FridgeStatus.RED.color)
        }
        // 未送信の変更があるマス（次の同期で自動送信）
        if (pendingMark && !selectMode) {
            Icon(Icons.Outlined.CloudUpload, "未送信", Modifier.align(Alignment.BottomEnd).padding(2.dp).size(10.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

enum class SlotAction { EXPIRY, NAME, TOGGLE }

/** スロットメニュー（本家 MM） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotMenuSheet(rn: String, idx: Int, name: String, removed: Boolean, onDismiss: () -> Unit, onAction: (SlotAction) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center) {
                    Text(FridgeConst.slotLabel(idx), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace)
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text(name.ifEmpty { "（空き）" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${rn}号室", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (removed) {
                MenuButton("元の場所に戻した", Icons.Outlined.Check, FridgeStatus.OK.color, true) { onAction(SlotAction.TOGGLE) }
                MenuButton("期限を更新する", Icons.Outlined.Edit, MaterialTheme.colorScheme.primary, false) { onAction(SlotAction.EXPIRY) }
            } else {
                MenuButton("期限を更新する", Icons.Outlined.Edit, MaterialTheme.colorScheme.primary, true) { onAction(SlotAction.EXPIRY) }
                MenuButton("品目を変更・削除する", Icons.Outlined.Lock, Color(0xFF1E88E5), false) { onAction(SlotAction.NAME) }
                MenuButton("取り出し中にする", Icons.Outlined.Outbox, FridgeStatus.ORANGE.color, false) { onAction(SlotAction.TOGGLE) }
            }
        }
    }
}

@Composable
private fun MenuButton(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, prominent: Boolean, onClick: () -> Unit) {
    val m = Modifier.fillMaxWidth().height(52.dp)
    if (prominent) {
        Button(onClick = onClick, modifier = m, colors = ButtonDefaults.buttonColors(containerColor = tint, contentColor = Color.White)) {
            Icon(icon, null, Modifier.size(18.dp)); Text(title, Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = m, border = BorderStroke(1.dp, tint.copy(alpha = 0.6f))) {
            Icon(icon, null, Modifier.size(18.dp), tint = tint); Text(title, Modifier.padding(start = 8.dp), color = tint, fontWeight = FontWeight.Bold)
        }
    }
}

/** 期限入力（本家 DM） */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ExpirySheet(rn: String, idx: Int, slot: Slot, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val parts = slot.exp.split("-")
    var year by rememberSaveable { mutableStateOf(parts.getOrNull(0).orEmpty()) }
    var month by rememberSaveable { mutableStateOf(parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..12 }?.let { "%02d".format(it) } ?: "") }
    var day by rememberSaveable { mutableStateOf(parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..31 }?.let { "%02d".format(it) } ?: "") }
    val today = DateUtil.today()

    val value: String = run {
        val y = year.toIntOrNull()
        val m = month.toIntOrNull()
        val d = day.toIntOrNull()
        if (year.length != 4 || y == null || m == null || d == null || y !in 2020..2099 || m !in 1..12 || d < 1) ""
        else if (d > LocalDate.of(y, m, 1).lengthOfMonth()) "" else "%04d-%02d-%02d".format(y, m, d)
    }
    val invalid = !(year.isEmpty() && month.isEmpty() && day.isEmpty()) && value.isEmpty()
    val st = FridgeConst.status(value)
    fun pick(d: LocalDate) { year = "${d.year}"; month = "%02d".format(d.monthValue); day = "%02d".format(d.dayOfMonth) }
    val tme = FridgeConst.thisMonthEnd(today)
    val nme = FridgeConst.nextMonthEnd(today)
    val quick = listOf(
        "今月末 ${DateUtil.md(tme)}" to tme, "来月末 ${DateUtil.md(nme)}" to nme,
        "3ヶ月後" to today.plusMonths(3), "6ヶ月後" to today.plusMonths(6),
    )

    var showScanner by remember { mutableStateOf(false) }
    if (showScanner) ExpiryScannerDialog(onDismiss = { showScanner = false }) { pick(it) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("期限を更新  ${FridgeConst.slotLabel(idx)}  ${rn}号室", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(slot.name.ifEmpty { "（空き）" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (value.isNotEmpty()) {
                    Text(FridgeConst.daysLabel(value), fontWeight = FontWeight.Bold,
                        color = if (st == FridgeStatus.OK) MaterialTheme.colorScheme.primary else st.color)
                }
            }
            FilledTonalButton(onClick = { showScanner = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Outlined.DocumentScanner, null, Modifier.size(20.dp))
                Text("カメラで期限を読み取る", Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold)
            }
            Text("パッケージの賞味期限・消費期限にカメラを向けると、日付を自動で読み取ります。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("クイック選択", style = MaterialTheme.typography.labelLarge)
            quick.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (label, d) ->
                        FilledTonalButton(onClick = { pick(d) }, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
                    }
                }
            }
            Text("日付を直接入力", style = MaterialTheme.typography.labelLarge)
            Text("年", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (today.year..today.year + 3).forEach { y -> Choice("${y}年", year == "$y", Modifier.weight(1f)) { year = "$y" } }
            }
            Text("月", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 6) {
                (1..12).forEach { m ->
                    val v = "%02d".format(m)
                    Choice("${m}月", month == v, Modifier.weight(1f)) { month = v }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    day, { v -> day = v.filter { it.isDigit() }.take(2) }, label = { Text("日") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(96.dp),
                )
                Column {
                    Text("カレンダーから選択", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    DateButton(DateUtil.parse(value) ?: today, { pick(it) })
                }
            }
            if (invalid) Text("⚠ 日付が無効です（2020〜2099年・実在する月日）", color = FridgeStatus.RED.color, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { onSave(value) }, enabled = value.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (st == FridgeStatus.OK || st == FridgeStatus.NONE) MaterialTheme.colorScheme.primary else st.color,
                    contentColor = Color.White,
                ),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (value.isEmpty()) "日付を選択してください" else "${FridgeConst.slotLabel(idx)} → ${FridgeConst.md(value)}",
                    fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Choice(text: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(text, textAlign = TextAlign.Center, maxLines = 1, style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.clip(RoundedCornerShape(50))
            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).padding(vertical = 8.dp))
}

/** 品目名の変更・削除（本家 NM） */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ItemNameSheet(rn: String, idx: Int, original: String, onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf(original) }
    var allRooms by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val trimmed = name.trim()
    val empty = trimmed.isEmpty()
    val lbl = FridgeConst.slotLabel(idx)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("$lbl  ${rn}号室", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("品目名", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FridgeConst.nameChoices.forEach { n ->
                    Choice(n, name == n, Modifier) { name = n }
                }
                Text("ー", modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (empty && original.isNotEmpty()) FridgeStatus.RED.color.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { name = "" }.padding(horizontal = 14.dp, vertical = 8.dp))
            }
            OutlinedTextField(name, { name = it }, label = { Text("品目名を入力（空欄で削除）") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            if (empty && original.isNotEmpty()) {
                Text("⚠ 空欄にすると品目が削除されます", color = FridgeStatus.RED.color, style = MaterialTheme.typography.bodySmall)
            }
            Text("適用範囲", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false to "この部屋のみ", true to "全室に適用").forEachIndexed { i, (v, label) ->
                    SegmentedButton(selected = allRooms == v, onClick = { allRooms = v },
                        shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                }
            }
            if (allRooms) Text("全部屋の $lbl スロットを変更します", color = FridgeStatus.ORANGE.color, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { if (empty || (allRooms && trimmed != original)) confirm = true else onSave(trimmed, allRooms) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (empty) FridgeStatus.RED.color else MaterialTheme.colorScheme.primary, contentColor = Color.White,
                ),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (empty) "この品目を削除する" else if (allRooms) "全室に変更を適用する" else "この部屋に変更を適用する",
                    fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (confirm) {
        val msg = if (empty) {
            if (allRooms) "全室の $lbl を削除します。本当によろしいですか？" else "この部屋の $lbl（$original）を削除します。よろしいですか？"
        } else "全室の $lbl を「$trimmed」に変更します。よろしいですか？"
        AlertDialog(
            onDismissRequest = { confirm = false },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { confirm = false; onSave(trimmed, allRooms) }) {
                    Text(if (empty) "削除する" else "変更する", color = if (empty) FridgeStatus.RED.color else MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("キャンセル") } },
        )
    }
}

/** 管理用パスワード入力（マス削除・品目変更/削除の前に表示） */
@Composable
internal fun AdminPinDialog(message: String, onDismiss: () -> Unit, onSuccess: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun tap(k: String) {
        if (checking) return
        if (k == "del") { pin = pin.dropLast(1); error = null; return }
        if (pin.length >= 4) return
        pin += k
        if (pin.length < 4) return
        checking = true
        val input = pin
        scope.launch {
            val r = FridgePin.verify(ctx, input)
            checking = false
            if (r == PinResult.OK) {
                onSuccess()
            } else {
                error = r.message
                delay(if (r == PinResult.WRONG) 800 else 2500)
                pin = ""
                error = null
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("管理用パスワード", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    (0 until 4).forEach { i ->
                        val c = when {
                            pin.length <= i -> MaterialTheme.colorScheme.outlineVariant
                            error != null -> FridgeStatus.RED.color
                            else -> MaterialTheme.colorScheme.primary
                        }
                        Box(Modifier.size(16.dp).background(c, CircleShape))
                    }
                }
                Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) {
                    if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else error?.let { Text(it, color = FridgeStatus.RED.color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                }
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "del")).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        row.forEach { k ->
                            if (k.isEmpty()) {
                                Spacer(Modifier.size(64.dp))
                            } else {
                                Box(
                                    Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { tap(k) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (k == "del") Icon(Icons.AutoMirrored.Outlined.Backspace, "1文字消す")
                                    else Text(k, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss) { Text("キャンセル") }
            }
        }
    }
}
