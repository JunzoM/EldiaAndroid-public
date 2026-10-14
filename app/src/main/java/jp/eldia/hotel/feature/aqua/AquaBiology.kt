package jp.eldia.hotel.feature.aqua

import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import jp.eldia.hotel.core.DateUtil
import jp.eldia.hotel.core.Photos
import jp.eldia.hotel.core.userMessage
import jp.eldia.hotel.feature.cleaning.SubHeader
import jp.eldia.hotel.ui.components.ChipRow
import jp.eldia.hotel.ui.components.DateButton
import jp.eldia.hotel.ui.components.EldiaCard
import jp.eldia.hotel.ui.components.PhotoSourceButtons
import jp.eldia.hotel.ui.components.PhotoViewerDialog
import jp.eldia.hotel.ui.components.rememberToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Period
import java.time.temporal.ChronoUnit

/** 生体（inhabitants） */
@Serializable
data class Inhabitant(
    val id: String? = null,
    @SerialName("room_number") val roomNumber: String = "",
    val name: String = "",
    val category: String = "",
    @SerialName("since_date") val sinceDate: String? = null,
    val cost: Double? = 0.0,
    @SerialName("photo_url") val photoUrl: String? = null,
    /** 匹数（Web版と同じ。0 は全滅） */
    val count: Int = 1,
)

/** 購入記録（purchases） */
@Serializable
data class Purchase(
    val id: String? = null,
    @SerialName("room_number") val roomNumber: String? = null,
    val category: String = "",
    val name: String = "",
    val cost: Double? = 0.0,
    @SerialName("purchased_at") val purchasedAt: String = "",
    val memo: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
)

object AquaBio {
    val bioPresets = listOf("海水魚", "淡水魚", "サンゴ", "甲殻類")
    val purchaseFilters = listOf("すべて", "生体", "エサ", "消耗品", "部材", "その他")
    val purchasePresets = listOf("生体", "エサ", "消耗品", "部材")

    fun purchaseColor(c: String): Color = when (c) {
        "生体" -> AquaConst.teal
        "エサ" -> Color(0xFF43A047)
        "消耗品" -> Color(0xFFFB8C00)
        "部材" -> Color(0xFF8E24AA)
        else -> Color(0xFF8A8A8A)
    }
}

fun formatYen(v: Double?): String = "¥" + "%,d".format(Math.round(v ?: 0.0))

/** 導入日からの経過（年・月・日・通算日数） */
private data class Elapsed(val y: Int, val m: Int, val d: Int, val total: Long)

private fun elapsed(since: String?): Elapsed {
    val s = DateUtil.parse(since) ?: return Elapsed(0, 0, 0, 0)
    val today = DateUtil.today()
    if (s.isAfter(today)) return Elapsed(0, 0, 0, 0)
    val p = Period.between(s, today)
    return Elapsed(p.years, p.months, p.days, ChronoUnit.DAYS.between(s, today))
}

@Composable
private fun Tag2(text: String, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
}

@Composable
private fun Thumb(url: String?, size: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        if (!url.isNullOrBlank()) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(icon, null, tint = tint)
    }
}

// MARK: - 生体（水槽の詳細の中に並べる）

/** 水槽の詳細の「生体」タブの中身 */
fun LazyListScope.biologyItems(list: List<Inhabitant>, loading: Boolean, error: String?, onOpen: (Inhabitant) -> Unit) {
    when {
        loading -> item { CircularProgressIndicator() }
        error != null -> item { Text(error, color = MaterialTheme.colorScheme.error) }
        list.isEmpty() -> item { Text("生体が登録されていません", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    items(list, key = { "bio-" + (it.id ?: it.name) }) { fish ->
        val e = elapsed(fish.sinceDate)
        // 全滅（0匹）は薄く表示する（Web版と同じ）
        Card(onClick = { onOpen(fish) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth().alpha(if (fish.count <= 0) 0.55f else 1f)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumb(fish.photoUrl, 72, Icons.Outlined.Pets, AquaConst.teal)
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(fish.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                        if (fish.category.isNotBlank()) Tag2(fish.category, AquaConst.teal)
                        if (fish.count <= 0) Tag2("全滅", MaterialTheme.colorScheme.onSurfaceVariant)
                        else Tag2("${fish.count}匹", Color(0xFF1E88E5))
                    }
                    Text(fish.sinceDate ?: "----.--.--", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${e.y}y ${e.m}m ${e.d}d", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f))
                        Text("${e.total}d", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatYen(fish.cost), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

/** 生体の詳細（Web版 InhabitantDetailModal） */
@Composable
fun BiologyDetailDialog(
    fish: Inhabitant,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    /** 「1匹死亡」（匹数を1つ減らす）。成功したら null、失敗したらエラーメッセージ */
    onDeath: (suspend () -> String?)? = null,
) {
    val e = elapsed(fish.sinceDate)
    var viewer by remember { mutableStateOf(false) }
    var confirmDeath by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var deathError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(fish.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!fish.photoUrl.isNullOrBlank()) {
                    AsyncImage(fish.photoUrl, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp))
                            .background(AquaConst.teal.copy(alpha = 0.1f)).clickable { viewer = true })
                }
                Text("カテゴリー：${fish.category}")
                Text("導入日：${fish.sinceDate ?: "----.--.--"}")
                Text("費用：${formatYen(fish.cost)}")
                // 匹数と死亡記録（Web版 InhabitantDetailModal と同じ）
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("現在の匹数", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (fish.count <= 0) "0匹（全滅）" else "${fish.count}匹",
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    if (onDeath != null) {
                        OutlinedButton(onClick = { confirmDeath = true }, enabled = fish.count > 0 && !recording) {
                            if (recording) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text("1匹死亡", color = Color(0xFFE11D48), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                deathError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("飼育期間", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                Text("${e.y}年 ${e.m}ヶ月 ${e.d}日", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = { TextButton(onClick = onEdit) { Text("編集する") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
    if (viewer) fish.photoUrl?.let { PhotoViewerDialog(it) { viewer = false } }
    if (confirmDeath && onDeath != null) {
        AlertDialog(
            onDismissRequest = { confirmDeath = false },
            title = { Text("1匹死亡を記録しますか？") },
            text = { Text("${fish.name}：${fish.count}匹 → ${maxOf(0, fish.count - 1)}匹") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeath = false
                    recording = true
                    deathError = null
                    scope.launch {
                        try {
                            deathError = onDeath()?.let { "記録できませんでした：$it" }
                        } finally {
                            recording = false
                        }
                    }
                }) { Text("記録する", color = Color(0xFFE11D48)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeath = false }) { Text("キャンセル") } },
        )
    }
}

/** 生体の登録・編集（Web版 BiologyForm） */
@Composable
fun BiologyForm(vm: AquaViewModel, room: String, existingId: String?, onDone: () -> Unit) {
    val inhabitants by vm.inhabitants.collectAsState()
    val existing = existingId?.let { id -> inhabitants.firstOrNull { it.id == id } }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var category by rememberSaveable {
        mutableStateOf(existing?.category?.let { if (it in AquaBio.bioPresets) it else "その他" } ?: AquaBio.bioPresets.first())
    }
    var custom by rememberSaveable { mutableStateOf(existing?.category?.takeIf { it !in AquaBio.bioPresets }.orEmpty()) }
    val initialToday = rememberSaveable { DateUtil.todayString() }
    var since by rememberSaveable { mutableStateOf(existing?.sinceDate ?: initialToday) }
    var cost by rememberSaveable { mutableStateOf(existing?.cost?.let { Math.round(it).toString() } ?: "0") }
    var count by rememberSaveable { mutableStateOf((existing?.count ?: 1).toString()) }
    var photo by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val ctx = LocalContext.current

    // 編集なのに一覧に無い（アプリが再起動された後など）ときは読み込んでから入力欄を埋める
    var loaded by remember { mutableStateOf(existingId == null || existing != null) }
    var filled by rememberSaveable { mutableStateOf(existingId == null || existing != null) }
    LaunchedEffect(existingId) {
        if (!loaded) {
            try {
                vm.loadInhabitants()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "読み込めませんでした：" + e.userMessage()
            }
            loaded = true
        }
    }
    LaunchedEffect(existing) {
        val ex = existing
        if (!filled && ex != null) {
            name = ex.name
            category = ex.category.let { if (it in AquaBio.bioPresets) it else "その他" }
            custom = ex.category.takeIf { it !in AquaBio.bioPresets }.orEmpty()
            since = ex.sinceDate ?: initialToday
            cost = ex.cost?.let { Math.round(it).toString() } ?: "0"
            count = ex.count.toString()
            filled = true
        }
    }
    val notFound = existingId != null && existing == null
    // 入力途中で戻るときは確認する（編集は元の値と比べる）
    val baseCategory = existing?.category?.let { if (it in AquaBio.bioPresets) it else "その他" } ?: AquaBio.bioPresets.first()
    val dirty = !notFound && filled && (
        name != existing?.name.orEmpty() ||
            category != baseCategory ||
            custom != existing?.category?.takeIf { it !in AquaBio.bioPresets }.orEmpty() ||
            since != (existing?.sinceDate ?: initialToday) ||
            cost != (existing?.cost?.let { Math.round(it).toString() } ?: "0") ||
            count != (existing?.count ?: 1).toString() ||
            photo != null
        )
    val leave = rememberDiscardGuard(dirty && !saving, onDone)
    if (notFound) {
        Column(Modifier.fillMaxSize()) {
            SubHeader("生体を編集", onDone)
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                if (!loaded) CircularProgressIndicator(color = AquaConst.teal)
                else Text(error ?: "見つかりませんでした", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        SubHeader(if (existing == null) "生体を登録" else "生体を編集", leave)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Room $room", color = AquaConst.teal, fontWeight = FontWeight.Bold)
            EldiaCard {
                Text("写真", style = MaterialTheme.typography.labelLarge)
                val shown: Any? = photo?.let { Uri.parse(it) } ?: existing?.photoUrl?.takeIf { it.isNotBlank() }
                if (shown != null) {
                    AsyncImage(shown, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)))
                }
                Box(Modifier.padding(top = 8.dp)) { PhotoSourceButtons(max = 1) { photo = it.firstOrNull()?.toString() } }
            }
            EldiaCard {
                OutlinedTextField(name, { name = it; error = null }, label = { Text("生体名（例: カクレクマノミ）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Text("カテゴリー", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                ChipRow(AquaBio.bioPresets + "その他", category, { it }, AquaConst.teal) { category = it }
                if (category == "その他") {
                    OutlinedTextField(custom, { custom = it }, label = { Text("カテゴリーを入力（例: ヤドカリ）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                Text("導入日", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                DateButton(DateUtil.parse(since) ?: DateUtil.today(), { d ->
                    if (isFutureDate(d)) toast(FUTURE_DATE_MESSAGE) else since = DateUtil.ymd(d)
                })
                OutlinedTextField(cost, { v -> cost = v.filter { it.isDigit() }.take(9) }, label = { Text("費用 (¥)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                // 匹数（新規は1匹以上・編集は0＝全滅も可。Web版と同じ）
                OutlinedTextField(count, { v -> count = v.filter { it.isDigit() }.take(4) }, label = { Text("匹数") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = {
                val cat = if (category == "その他") custom.trim() else category
                val n = count.toIntOrNull()
                if (name.isBlank()) { error = "生体名を入力してください"; return@Button }
                if (n == null || n < (if (existing == null) 1 else 0)) {
                    error = if (existing == null) "匹数は1以上で入力してください" else "匹数を入力してください"; return@Button
                }
                if (isFutureDate(DateUtil.parse(since))) { error = FUTURE_DATE_MESSAGE; return@Button }
                if (cat.isEmpty()) { error = "カテゴリーを入力してください"; return@Button }
                saving = true
                scope.launch {
                    try {
                        val bytes = photo?.let { Photos.jpeg(ctx, Uri.parse(it)) }
                        vm.saveInhabitant(existing, room, name.trim(), cat, since, cost.toDoubleOrNull() ?: 0.0, n, bytes)
                        toast(if (existing == null) "生体を登録しました" else "保存しました")
                        onDone()
                    } catch (e: Exception) {
                        error = (if (existing == null) "登録に失敗しました：" else "保存に失敗しました：") + e.userMessage()
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
            else Text(if (existing == null) "登録する" else "保存する", fontWeight = FontWeight.Bold)
        }
    }
}

// MARK: - 購入記録（Web版 PurchaseView）

@Composable
fun PurchasesScreen(vm: AquaViewModel, onBack: () -> Unit, onAdd: () -> Unit) {
    val purchases by vm.purchases.collectAsState()
    var filter by rememberSaveable { mutableStateOf("すべて") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var viewer by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { vm.loadPurchases(); error = null } catch (e: Exception) { error = e.userMessage() }
        loading = false
    }
    val filtered = when (filter) {
        "すべて" -> purchases
        "その他" -> purchases.filter { it.category !in AquaBio.purchasePresets }
        else -> purchases.filter { it.category == filter }
    }
    val total = filtered.sumOf { it.cost ?: 0.0 }

    Column(Modifier.fillMaxSize()) {
        SubHeader("購入記録", onBack) {
            Button(onClick = onAdd, colors = ButtonDefaults.buttonColors(containerColor = AquaConst.teal)) { Text("記録") }
        }
        ChipRow(AquaBio.purchaseFilters, filter, { it }, AquaConst.teal) { filter = it }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                EldiaCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ShoppingCart, null, tint = AquaConst.teal)
                        Text("${if (filter == "すべて") "合計" else filter} — ${filtered.size}件", Modifier.weight(1f).padding(start = 8.dp))
                        Text(formatYen(total), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
            when {
                loading -> item { CircularProgressIndicator() }
                error != null -> item { Text(error ?: "", color = MaterialTheme.colorScheme.error) }
                filtered.isEmpty() -> item { Text("記録がありません", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(filtered, key = { it.id ?: (it.name + it.purchasedAt) }) { p ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.clickable(enabled = !p.photoUrl.isNullOrBlank()) { viewer = p.photoUrl }) {
                            Thumb(p.photoUrl, 56, Icons.Outlined.ShoppingCart, MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Tag2(p.category, AquaBio.purchaseColor(p.category))
                                p.roomNumber?.takeIf { it.isNotBlank() }?.let { Tag2("Room $it", Color(0xFF8A8A8A)) }
                            }
                            p.memo?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(formatYen(p.cost), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text(p.purchasedAt.replace("-", "/"), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    viewer?.let { PhotoViewerDialog(it) { viewer = null } }
}

/** 購入を記録（Web版 PurchaseForm） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PurchaseForm(vm: AquaViewModel, onDone: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("消耗品") }
    var custom by rememberSaveable { mutableStateOf("") }
    var cost by rememberSaveable { mutableStateOf("0") }
    val initialDate = rememberSaveable { DateUtil.todayString() }
    var date by rememberSaveable { mutableStateOf(initialDate) }
    var room by rememberSaveable { mutableStateOf<String?>(null) }
    var memo by rememberSaveable { mutableStateOf("") }
    var photo by rememberSaveable { mutableStateOf<String?>(null) }
    var roomMenu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val ctx = LocalContext.current

    // 入力途中で戻るときは確認する
    val dirty = name.isNotBlank() || category != "消耗品" || custom.isNotBlank() || cost != "0" || date != initialDate ||
        room != null || memo.isNotBlank() || photo != null
    val leave = rememberDiscardGuard(dirty && !saving, onDone)

    Column(Modifier.fillMaxSize()) {
        SubHeader("購入を記録", leave)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EldiaCard {
                OutlinedTextField(name, { name = it; error = null }, label = { Text("品名（例: 人工海水 20kg）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Text("カテゴリー", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                ChipRow(AquaBio.purchasePresets + "その他", category, { it }, AquaConst.teal) { category = it }
                if (category == "その他") {
                    OutlinedTextField(custom, { custom = it }, label = { Text("カテゴリーを入力") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                OutlinedTextField(cost, { v -> cost = v.filter { it.isDigit() }.take(9) }, label = { Text("費用 (¥)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Text("購入日", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                DateButton(DateUtil.parse(date) ?: DateUtil.today(), { d ->
                    if (isFutureDate(d)) toast(FUTURE_DATE_MESSAGE) else date = DateUtil.ymd(d)
                })
                Text("部屋（任意）", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                Box {
                    OutlinedButton(onClick = { roomMenu = true }) { Text(room?.let { "Room $it" } ?: "なし（共通）") }
                    DropdownMenu(expanded = roomMenu, onDismissRequest = { roomMenu = false }) {
                        DropdownMenuItem(text = { Text("なし（共通）") }, onClick = { room = null; roomMenu = false })
                        AquaConst.rooms.forEach { r ->
                            DropdownMenuItem(text = { Text("Room ${r.id}（${r.hotel.english}）") }, onClick = { room = r.id; roomMenu = false })
                        }
                    }
                }
            }
            EldiaCard {
                OutlinedTextField(memo, { memo = it }, label = { Text("備考") }, placeholder = { Text("特記事項があれば入力") },
                    minLines = 2, modifier = Modifier.fillMaxWidth())
            }
            EldiaCard {
                Text("写真", style = MaterialTheme.typography.labelLarge)
                photo?.let {
                    AsyncImage(Uri.parse(it), null, contentScale = ContentScale.Crop,
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp)))
                }
                FlowRow(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.Center) {
                    PhotoSourceButtons(max = 1) { photo = it.firstOrNull()?.toString() }
                    if (photo != null) TextButton(onClick = { photo = null }) { Text("外す") }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = {
                val cat = if (category == "その他") custom.trim() else category
                if (name.isBlank()) { error = "品名を入力してください"; return@Button }
                if (isFutureDate(DateUtil.parse(date))) { error = FUTURE_DATE_MESSAGE; return@Button }
                if (cat.isEmpty()) { error = "カテゴリーを入力してください"; return@Button }
                saving = true
                scope.launch {
                    try {
                        val bytes = photo?.let { Photos.jpeg(ctx, Uri.parse(it)) }
                        vm.addPurchase(room, cat, name.trim(), cost.toDoubleOrNull() ?: 0.0, date, memo.trim(), bytes)
                        toast("購入を記録しました")
                        onDone()
                    } catch (e: Exception) {
                        error = "登録に失敗しました：" + e.userMessage()
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
            else Text("記録する", fontWeight = FontWeight.Bold)
        }
    }
}
