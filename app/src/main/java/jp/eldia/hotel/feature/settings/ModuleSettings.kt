package jp.eldia.hotel.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import jp.eldia.hotel.core.AppConfig
import jp.eldia.hotel.core.FacilityConst
import jp.eldia.hotel.core.Offline
import jp.eldia.hotel.core.SB
import jp.eldia.hotel.core.freshAccessToken
import jp.eldia.hotel.core.userMessage
import jp.eldia.hotel.feature.facility.FacilityViewModel
import jp.eldia.hotel.feature.facility.IssueTemplate
import jp.eldia.hotel.feature.facility.defaultTemplates
import jp.eldia.hotel.ui.components.ChipRow
import jp.eldia.hotel.ui.components.EldiaCard
import jp.eldia.hotel.ui.components.rememberToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime

// MARK: - 設備管理：テンプレート設定（不具合テンプレート・清掃タスク）。iOS版 TemplateSettingsView に相当

private enum class TemplateKind(val label: String) { ISSUE("📋 不具合テンプレート"), TASK("🧹 清掃タスク") }

@Serializable
private data class TaskSetting(val key: String, val value: List<String>)

private const val KEY_TASKS = "eldia_cleaning_tasks"

/** 成功で null = 行がまだない（デフォルトを使う）。失敗は Result.failure（デフォルトで上書きしないため区別する） */
private suspend fun loadTasks(): Result<List<String>?> = try {
    Result.success(
        SB.main.from("app_settings").select(Columns.list("key", "value")) { filter { eq("key", KEY_TASKS) } }
            .decodeList<TaskSetting>().firstOrNull()?.value,
    )
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/** 保存を1つずつ順番に行う。待っている間に変更が重なったら最新の内容だけ保存する（↑↓の連打で順番が入れ替わらないように） */
private class SerialSaver<T : Any>(private val save: suspend (T) -> String?) {
    private val mutex = Mutex()
    private var latest: T? = null

    suspend fun submit(v: T): String? {
        latest = v
        return mutex.withLock {
            val cur = latest ?: return@withLock null   // 先に保存済み
            latest = null
            save(cur)
        }
    }
}

private suspend fun saveTasks(v: List<String>): String? = try {
    SB.main.from("app_settings").upsert(buildJsonObject {
        put("key", KEY_TASKS)
        put("value", Offline.json.encodeToJsonElement(ListSerializer(String.serializer()), v))
        put("updated_at", OffsetDateTime.now().toString())
    }) { onConflict = "key" }
    null
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    "清掃タスクを保存できませんでした：" + e.userMessage()
}

@Composable
fun TemplateSettingsPage(fvm: FacilityViewModel) {
    val templates by fvm.templates.collectAsState()
    var kind by remember { mutableStateOf(TemplateKind.ISSUE) }
    // 保存待ちの間も連打に追いつくよう、画面側で先に並びを持つ（保存が終われば ViewModel の値に戻す）
    var issueLocal by remember { mutableStateOf<List<IssueTemplate>?>(null) }
    val issues = issueLocal ?: templates
    var tasks by remember { mutableStateOf(FacilityConst.defaultCleaningTasks) }
    var tasksLoaded by remember { mutableStateOf(false) }
    var tasksError by remember { mutableStateOf<String?>(null) }
    var tasksReload by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Int?>(null) }   // -1 = 追加
    var confirmReset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val issueSaver = remember(fvm) { SerialSaver<List<IssueTemplate>> { fvm.saveTemplates(it) } }
    val taskSaver = remember { SerialSaver<List<String>> { saveTasks(it) } }
    LaunchedEffect(tasksReload) {
        tasksLoaded = false
        tasksError = null
        loadTasks()
            .onSuccess { tasks = it ?: FacilityConst.defaultCleaningTasks; tasksLoaded = true }
            .onFailure { tasksError = "清掃タスクを読み込めませんでした：" + it.userMessage() }
    }
    // 清掃タスクは読み込めるまで編集させない（デフォルトで上書きしてしまうため）
    val canEdit = kind == TemplateKind.ISSUE || tasksLoaded

    fun saveIssue(v: List<IssueTemplate>) {
        issueLocal = v
        scope.launch {
            issueSaver.submit(v)?.let(toast)
            if (issueLocal === v) issueLocal = null
        }
    }
    fun saveTask(v: List<String>) {
        if (!tasksLoaded) return
        tasks = v
        scope.launch { taskSaver.submit(v)?.let(toast) }
    }
    fun <T> List<T>.moved(i: Int, d: Int): List<T> {
        val j = i + d
        if (j !in indices) return this
        return toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ChipRow(TemplateKind.entries, kind, { it.label }, MaterialTheme.colorScheme.primary) { kind = it }
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp), modifier = Modifier.weight(1f)) {
                if (kind == TemplateKind.ISSUE) {
                    itemsIndexed(issues) { i, t ->
                        ListRow(
                            title = t.title, tag = "${FacilityConst.areaIcons[t.area] ?: ""}${t.area}",
                            onClick = { editing = i },
                            onUp = { saveIssue(issues.moved(i, -1)) }, onDown = { saveIssue(issues.moved(i, 1)) },
                            onDelete = { saveIssue(issues.filterIndexed { k, _ -> k != i }) },
                        )
                    }
                } else if (tasksError != null) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(tasksError.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { tasksReload++ }) { Text("再読み込み") }
                        }
                    }
                } else if (!tasksLoaded) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                } else {
                    itemsIndexed(tasks) { i, t ->
                        ListRow(
                            title = t, tag = null, onClick = { editing = i },
                            onUp = { saveTask(tasks.moved(i, -1)) }, onDown = { saveTask(tasks.moved(i, 1)) },
                            onDelete = { saveTask(tasks.filterIndexed { k, _ -> k != i }) },
                        )
                    }
                }
                if (canEdit) item {
                    TextButton(onClick = { confirmReset = true }, modifier = Modifier.padding(top = 12.dp)) {
                        Text("デフォルトに戻す", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (canEdit) {
            ExtendedFloatingActionButton(onClick = { editing = -1 }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("追加") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
        }
    }

    editing?.let { i ->
        if (kind == TemplateKind.ISSUE) {
            val cur = issues.getOrNull(i)
            IssueTemplateDialog(cur, onDismiss = { editing = null }) { t ->
                editing = null
                saveIssue(if (cur == null) issues + t else issues.mapIndexed { k, o -> if (k == i) t else o })
            }
        } else if (tasksLoaded) {
            val cur = tasks.getOrNull(i)
            TaskDialog(cur, onDismiss = { editing = null }) { t ->
                editing = null
                saveTask(if (cur == null) tasks + t else tasks.mapIndexed { k, o -> if (k == i) t else o })
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("デフォルトに戻しますか？") },
            text = { Text(if (kind == TemplateKind.ISSUE) "不具合テンプレートを最初の状態に戻します。" else "清掃タスクを最初の状態に戻します。") },
            confirmButton = {
                TextButton(enabled = canEdit, onClick = {
                    confirmReset = false
                    if (kind == TemplateKind.ISSUE) saveIssue(defaultTemplates) else saveTask(FacilityConst.defaultCleaningTasks)
                }) { Text("戻す", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun ListRow(title: String, tag: String?, onClick: () -> Unit, onUp: () -> Unit, onDown: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        tag?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Text(title, modifier = Modifier.weight(1f).padding(start = 8.dp))
        IconButton(onClick = onUp, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.KeyboardArrowUp, "上へ") }
        IconButton(onClick = onDown, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.KeyboardArrowDown, "下へ") }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Delete, "削除", tint = MaterialTheme.colorScheme.error) }
    }
    HorizontalDivider()
}

@Composable
private fun IssueTemplateDialog(cur: IssueTemplate?, onDismiss: () -> Unit, onSave: (IssueTemplate) -> Unit) {
    var title by remember { mutableStateOf(cur?.title.orEmpty()) }
    var area by remember { mutableStateOf(cur?.area ?: FacilityConst.areas.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (cur == null) "テンプレートを追加" else "テンプレートを編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it.take(50) }, label = { Text("タイトル") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FacilityConst.areas.forEach { a ->
                        val on = a == area
                        Text("${FacilityConst.areaIcons[a] ?: ""}$a",
                            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                                .clickable { area = a }.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { onSave(IssueTemplate(title.trim(), area)) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
private fun TaskDialog(cur: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(cur.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (cur == null) "清掃タスクを追加" else "清掃タスクを編集") },
        text = { OutlinedTextField(name, { name = it.take(50) }, label = { Text("タスク名") }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

// MARK: - 設備管理：ユーザー招待（Web版と同じ Vercel API）

private suspend fun sendInvite(email: String): String? = withContext(Dispatchers.IO) {
    val token = freshAccessToken() ?: return@withContext "ログインが必要です"
    val conn = URL("${AppConfig.FACILITY_WEB_ORIGIN}/api/facility/invite").openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        // 招待メールのリンク先（Web版 /facility）を決めるのに使われる
        conn.setRequestProperty("Origin", AppConfig.FACILITY_WEB_ORIGIN)
        conn.outputStream.use { it.write(buildJsonObject { put("email", email) }.toString().toByteArray()) }
        val code = conn.responseCode
        if (code in 200..299) return@withContext null
        val body = conn.errorStream?.bufferedReader()?.readText().orEmpty()
        runCatching { Offline.json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: "エラー ($code)"
    } catch (e: IOException) {
        "通信できませんでした。電波状況を確認してください"
    } finally {
        conn.disconnect()
    }
}

@Composable
fun InviteDialog(onDismiss: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val valid = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ユーザーを招待") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("招待メールが届きます。メールのリンクからパスワードを決めると、共通ログインで使えるようになります。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(email, { email = it; error = null }, label = { Text("メールアドレス") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !sending, onClick = {
                sending = true
                scope.launch {
                    val e = sendInvite(email.trim())
                    sending = false
                    if (e == null) { toast("招待メールを送信しました"); onDismiss() } else error = e
                }
            }) { Text(if (sending) "送信中…" else "招待する") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/** 設定画面の1行（タップで開く） */
@Composable
fun SettingsLink(title: String, sub: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
    EldiaCard {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        content()
    }
}
