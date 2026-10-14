package jp.eldia.hotel.feature.order

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import jp.eldia.hotel.core.userMessage
import jp.eldia.hotel.ui.components.EldiaCard
import jp.eldia.hotel.ui.components.PhotoEditor
import jp.eldia.hotel.ui.components.rememberPhotoUploadState
import jp.eldia.hotel.ui.components.rememberToast
import kotlinx.coroutines.launch

/** 管理（管理者のみ）：商品・カテゴリ・単位・通知設定。Web版 ProductsView / SettingsView に相当 */
enum class AdminSection(val label: String) { PRODUCTS("商品"), CATEGORIES("カテゴリ"), UNITS("単位"), SETTINGS("通知設定") }

private val adminTint = Color(0xFF6D4C41)

@Composable
fun AdminScreen(vm: OrderViewModel, initial: AdminSection = AdminSection.PRODUCTS) {
    var section by rememberSaveable(initial) { mutableStateOf(initial) }
    Column(Modifier.fillMaxSize()) {
        // 4つの切り替え（発注タブ上部と同じ形）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AdminSection.entries.forEach { s ->
                val on = s == section
                Box(
                    Modifier.weight(1f).background(if (on) adminTint else Color.Transparent, RoundedCornerShape(50))
                        .clickable { section = s }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(s.label, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
                        color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Box(Modifier.weight(1f)) {
            when (section) {
                AdminSection.PRODUCTS -> ProductsAdmin(vm)
                AdminSection.CATEGORIES -> CategoriesAdmin(vm)
                AdminSection.UNITS -> UnitsAdmin(vm)
                AdminSection.SETTINGS -> SettingsAdmin(vm)
            }
        }
    }
}

private fun String.toIntInput(allowMinus: Boolean = false): String =
    filterIndexed { i, c -> c.isDigit() || (allowMinus && i == 0 && c == '-') }.take(6)

// MARK: - 商品

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductsAdmin(vm: OrderViewModel) {
    val products by vm.products.collectAsState()
    val loading by vm.loading.collectAsState()
    var search by rememberSaveable { mutableStateOf("") }
    var showHidden by rememberSaveable { mutableStateOf(true) }
    var editing by remember { mutableStateOf<OrderProduct?>(null) }
    var creating by remember { mutableStateOf(false) }
    val groups = products
        .filter { showHidden || it.isActive }
        .filter { search.isBlank() || it.name.contains(search.trim(), ignoreCase = true) }
        .groupBy { vm.categoryLabel(it.categoryId) }.toSortedMap()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(search, { search = it }, placeholder = { Text("商品名で検索") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = showHidden, onClick = { showHidden = !showHidden }, label = { Text("非表示の商品も出す") })
                Spacer(Modifier.weight(1f))
                Text("${products.size}品", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PullToRefreshBox(isRefreshing = loading, onRefresh = { vm.refresh() }, modifier = Modifier.weight(1f)) {
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                    groups.forEach { (cat, list) ->
                        item(key = "h-$cat") {
                            Text(cat, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                        }
                        items(list.sortedWith(compareBy({ it.sortOrder }, { it.name })), key = { it.id }) { p ->
                            Card(onClick = { editing = p }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(p.name, fontWeight = FontWeight.SemiBold,
                                            color = if (p.isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("在庫 ${p.stock} ／ 発注点 ${p.orderPoint} ${vm.unitName(p)} · 並び順 ${p.sortOrder}",
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (!p.isActive) Text("非表示", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp))
                                    else StockBadge(p)
                                }
                            }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("商品を追加") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
    if (creating) ProductSheet(vm, null) { creating = false }
    editing?.let { p -> ProductSheet(vm, p) { editing = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductSheet(vm: OrderViewModel, p: OrderProduct?, onDismiss: () -> Unit) {
    val categories by vm.categories.collectAsState()
    val unitList by vm.unitList.collectAsState()
    var name by remember { mutableStateOf(p?.name.orEmpty()) }
    var categoryId by remember { mutableStateOf(p?.categoryId) }
    var unitId by remember { mutableStateOf(p?.unitId) }
    var stock by remember { mutableStateOf((p?.stock ?: 0).toString()) }
    var point by remember { mutableStateOf((p?.orderPoint ?: 0).toString()) }
    var sort by remember { mutableStateOf((p?.sortOrder ?: 0).toString()) }
    var active by remember { mutableStateOf(p?.isActive ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val photo = rememberPhotoUploadState("photos", "order-")
    LaunchedEffect(p?.id) { photo.setExisting(listOfNotNull(p?.imageUrl?.takeIf { it.isNotBlank() })) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    // 子カテゴリがあれば子だけ、なければ親も選べる
    val catOptions = categories.filter { c -> categories.none { it.parentId == c.id } }
        .sortedBy { vm.categoryLabel(it.id) }

    fun save() {
        saving = true
        error = null
        scope.launch {
            val e = vm.saveProduct(p?.id, ProductInput(
                name = name, categoryId = categoryId, unitId = unitId,
                stock = stock.toIntOrNull() ?: 0, orderPoint = point.toIntOrNull() ?: 0, sortOrder = sort.toIntOrNull() ?: 0,
                isActive = active, imageUrl = photo.uploadedUrls.firstOrNull().orEmpty(),
            ))
            saving = false
            if (e == null) { toast(if (p == null) "商品を追加しました" else "保存しました"); onDismiss() } else error = e
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (p == null) "商品を追加" else "商品を編集", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(name, { name = it.take(100) }, label = { Text("商品名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Picker("カテゴリ", catOptions, categoryId, { vm.categoryLabel(it.id) }, { it.id }) { categoryId = it }
            Picker("単位", unitList, unitId, { it.name }, { it.id }) { unitId = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(stock, { stock = it.toIntInput() }, label = { Text("在庫") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                OutlinedTextField(point, { point = it.toIntInput() }, label = { Text("発注点") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                OutlinedTextField(sort, { sort = it.toIntInput(allowMinus = true) }, label = { Text("並び順") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("発注画面に表示する", fontWeight = FontWeight.SemiBold)
                    Text("オフにすると発注画面・在庫から隠れます（履歴は残ります）", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(active, { active = it })
            }
            PhotoEditor(photo, max = 1, title = "商品写真（任意）")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { save() }, enabled = !saving && !photo.isUploading && !photo.hasError,
                colors = ButtonDefaults.buttonColors(containerColor = adminTint, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(50.dp)) {
                if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                else Text("保存する", fontWeight = FontWeight.Bold)
            }
            if (p != null) {
                TextButton(onClick = { confirmDelete = true }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                    Text("この商品を削除", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (confirmDelete && p != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("「${p.name}」を削除しますか？") },
            text = { Text("過去の発注履歴では「削除された商品」と表示されます。発注画面から隠すだけなら「発注画面に表示する」をオフにしてください。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    saving = true
                    scope.launch {
                        val e = vm.deleteProduct(p.id)
                        saving = false
                        if (e == null) { toast("削除しました"); onDismiss() } else error = e
                    }
                }) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") } },
        )
    }
}

/** ドロップダウンで1つ選ぶ（「未設定」も選べる） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Picker(label: String, options: List<T>, selectedId: String?, labelOf: (T) -> String, idOf: (T) -> String, onSelect: (String?) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val current = options.firstOrNull { idOf(it) == selectedId }?.let { labelOf(it) } ?: "未設定"
    ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { menuOpen = it }) {
        OutlinedTextField(current, {}, readOnly = true, label = { Text(label) }, singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menuOpen) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("未設定") }, onClick = { onSelect(null); menuOpen = false })
            options.forEach { o -> DropdownMenuItem(text = { Text(labelOf(o)) }, onClick = { onSelect(idOf(o)); menuOpen = false }) }
        }
    }
}

// MARK: - カテゴリ

@Composable
private fun CategoriesAdmin(vm: OrderViewModel) {
    val categories by vm.categories.collectAsState()
    val products by vm.products.collectAsState()
    var editing by remember { mutableStateOf<OrderCategory?>(null) }
    var creating by remember { mutableStateOf(false) }
    val tops = categories.filter { it.parentId == null }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
            items(tops, key = { it.id }) { t ->
                val children = categories.filter { it.parentId == t.id }
                EldiaCard {
                    AdminRow(t.name, "${products.count { it.categoryId == t.id }}品 · 並び順 ${t.sortOrder}", bold = true) { editing = t }
                    children.forEach { c ->
                        HorizontalDivider()
                        AdminRow("　└ ${c.name}", "${products.count { it.categoryId == c.id }}品 · 並び順 ${c.sortOrder}") { editing = c }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("カテゴリを追加") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
    if (creating) CategoryDialog(vm, null) { creating = false }
    editing?.let { c -> CategoryDialog(vm, c) { editing = null } }
}

@Composable
private fun AdminRow(title: String, sub: String, bold: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun CategoryDialog(vm: OrderViewModel, c: OrderCategory?, onDismiss: () -> Unit) {
    val categories by vm.categories.collectAsState()
    var name by remember { mutableStateOf(c?.name.orEmpty()) }
    var parentId by remember { mutableStateOf(c?.parentId) }
    var sort by remember { mutableStateOf((c?.sortOrder ?: 0).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val hasChildren = c != null && categories.any { it.parentId == c.id }
    val parents = categories.filter { it.parentId == null && it.id != c?.id }
    fun run(block: suspend () -> String?, ok: String) {
        saving = true
        error = null
        scope.launch {
            val e = block()
            saving = false
            if (e == null) { toast(ok); onDismiss() } else error = e
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (c == null) "カテゴリを追加" else "カテゴリを編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(50) }, label = { Text("名前") }, singleLine = true)
                if (hasChildren) {
                    Text("子カテゴリがあるため大分類のままです", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Picker("親カテゴリ（未設定なら大分類）", parents, parentId, { it.name }, { it.id }) { parentId = it }
                }
                OutlinedTextField(sort, { sort = it.toIntInput(allowMinus = true) }, label = { Text("並び順") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (c != null) {
                    TextButton(onClick = { run({ vm.deleteCategory(c.id) }, "削除しました") }, enabled = !saving) {
                        Text("このカテゴリを削除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                run({ vm.saveCategory(c?.id, name, if (hasChildren) null else parentId, sort.toIntOrNull() ?: 0) }, "保存しました")
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

// MARK: - 単位

@Composable
private fun UnitsAdmin(vm: OrderViewModel) {
    val units by vm.unitList.collectAsState()
    val products by vm.products.collectAsState()
    var editing by remember { mutableStateOf<OrderUnit?>(null) }
    var creating by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
            items(units, key = { it.id }) { u ->
                EldiaCard { AdminRow(u.name, "${products.count { it.unitId == u.id }}品 · 並び順 ${u.sortOrder}", bold = true) { editing = u } }
            }
        }
        ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("単位を追加") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
    if (creating) UnitDialog(vm, null) { creating = false }
    editing?.let { u -> UnitDialog(vm, u) { editing = null } }
}

@Composable
private fun UnitDialog(vm: OrderViewModel, u: OrderUnit?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(u?.name.orEmpty()) }
    var sort by remember { mutableStateOf((u?.sortOrder ?: 0).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    fun run(block: suspend () -> String?, ok: String) {
        saving = true
        error = null
        scope.launch {
            val e = block()
            saving = false
            if (e == null) { toast(ok); onDismiss() } else error = e
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (u == null) "単位を追加" else "単位を編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(20) }, label = { Text("名前（例：個・箱・kg）") }, singleLine = true)
                OutlinedTextField(sort, { sort = it.toIntInput(allowMinus = true) }, label = { Text("並び順") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (u != null) {
                    TextButton(onClick = { run({ vm.deleteUnit(u.id) }, "削除しました") }, enabled = !saving) {
                        Text("この単位を削除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !saving, onClick = { run({ vm.saveUnit(u?.id, name, sort.toIntOrNull() ?: 0) }, "保存しました") }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

// MARK: - 通知設定

@Composable
private fun SettingsAdmin(vm: OrderViewModel) {
    var s by remember { mutableStateOf<Map<String, String>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    LaunchedEffect(Unit) {
        runCatching { vm.loadSettings() }.onSuccess { s = it }.onFailure { loadError = it.userMessage() }
    }
    val v = s
    if (v == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loadError != null) Text(loadError.orEmpty(), color = MaterialTheme.colorScheme.error) else CircularProgressIndicator()
        }
        return
    }
    fun get(k: String, def: String = "") = v[k]?.takeIf { it.isNotEmpty() } ?: def
    fun set(k: String, value: String) { s = v + (k to value) }
    // 入力中は空欄のままにする（465 は保存するときだけ補う）
    val port = v["mail_port"] ?: "465"

    fun save(then: (suspend () -> Unit)? = null) {
        saving = true
        val savePort = port.trim().ifEmpty { "465" }
        scope.launch {
            val values = v + mapOf(
                "line_send_mode" to (if (get("line_send_mode") == "approval") "approval" else "auto"),
                "line_type" to (if (get("line_type") == "user") "user" else "group"),
                "line_group_id" to get("line_group_id").trim(),
                "line_user_id" to get("line_user_id").trim(),
                "mail_port" to savePort,
                "mail_secure" to (savePort == "465").toString(),
            )
            val e = vm.saveSettings(values)
            saving = false
            // 空欄で保存したら補った 465 を表示に戻す
            if (e == null && s?.get("mail_port").isNullOrBlank()) s = s?.plus("mail_port" to savePort)
            if (e != null) toast(e) else if (then == null) toast("設定を保存しました") else then()
        }
    }

    fun test(action: String) {
        testing = action
        save {
            val e = vm.notify(action, null)
            testing = null
            toast(e ?: if (action == "test_line") "LINEにテスト送信しました" else "テストメールを送信しました")
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EldiaCard {
            ToggleRow("LINE通知", get("line_enabled") == "true") { set("line_enabled", it.toString()) }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("送信のしかた", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Choice(listOf("auto" to "すぐ送信", "approval" to "承認してから"), get("line_send_mode", "auto")) { set("line_send_mode", it) }
            Text(if (get("line_send_mode") == "approval") "発注は「承認待ち」になり、管理者が承認すると LINE・メールで送信されます。"
                else "発注するとすぐに LINE・メールで送信されます。",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text("送信先", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Choice(listOf("group" to "グループ（業者）", "user" to "個人（テスト用）"), get("line_type", "group")) { set("line_type", it) }
            if (get("line_type", "group") == "group") {
                OutlinedTextField(get("line_group_id"), { set("line_group_id", it) }, label = { Text("グループID") }, placeholder = { Text("C から始まる ID") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            } else {
                OutlinedTextField(get("line_user_id"), { set("line_user_id", it) }, label = { Text("ユーザーID") }, placeholder = { Text("U から始まる ID") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("※ 個人宛ては業者に届きません。テストが終わったらグループに戻してください。", style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFE65100))
            }
            OutlinedButton(onClick = { test("test_line") }, enabled = !saving && testing == null, modifier = Modifier.padding(top = 6.dp)) {
                Text(if (testing == "test_line") "送信中…" else "保存してLINEテスト")
            }
        }
        EldiaCard {
            ToggleRow("メール通知", get("mail_enabled") == "true") { set("mail_enabled", it.toString()) }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(get("mail_host"), { set("mail_host", it) }, label = { Text("SMTPホスト") }, placeholder = { Text("smtp.gmail.com") },
                    singleLine = true, modifier = Modifier.weight(2f))
                OutlinedTextField(port, { set("mail_port", it.toIntInput()) }, label = { Text("ポート") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            }
            if (port.trim() == "25" || port.trim() == "587") {
                Text("ポート ${port.trim()} は使えません（Supabase の制限）。465 にしてください。", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall)
            }
            OutlinedTextField(get("mail_user"), { set("mail_user", it) }, label = { Text("SMTPユーザー") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(get("mail_from"), { set("mail_from", it) }, label = { Text("差出人") }, placeholder = { Text("ELDIA 発注 <example@gmail.com>") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(get("mail_to"), { set("mail_to", it) }, label = { Text("送信先") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("SMTPパスワードはここでは保存しません（Supabase の Edge Functions → Secrets の MAIL_PASS）。",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { test("test_mail") }, enabled = !saving && testing == null, modifier = Modifier.padding(top = 6.dp)) {
                Text(if (testing == "test_mail") "送信中…" else "保存してメールテスト")
            }
        }
        Button(onClick = { save() }, enabled = !saving, colors = ButtonDefaults.buttonColors(containerColor = adminTint, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(50.dp)) {
            if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            else Text("設定を保存", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Switch(on, onChange)
    }
}

@Composable
private fun Choice(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (id, label) ->
            val on = id == selected
            Box(Modifier.weight(1f).background(if (on) adminTint else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                .clickable { onSelect(id) }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(label, color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
