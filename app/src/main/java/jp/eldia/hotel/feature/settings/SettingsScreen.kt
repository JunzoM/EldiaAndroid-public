package jp.eldia.hotel.feature.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import jp.eldia.hotel.auth.AuthState
import jp.eldia.hotel.auth.AuthViewModel
import jp.eldia.hotel.auth.displayName
import jp.eldia.hotel.auth.fullName
import jp.eldia.hotel.core.AppPrefs
import jp.eldia.hotel.core.Appearance
import jp.eldia.hotel.core.Push
import jp.eldia.hotel.core.Reminders
import jp.eldia.hotel.feature.facility.FacilityViewModel
import jp.eldia.hotel.feature.order.AdminScreen
import jp.eldia.hotel.feature.order.AdminSection
import jp.eldia.hotel.feature.order.OrderViewModel
import jp.eldia.hotel.ui.components.EldiaCard
import jp.eldia.hotel.ui.components.HotelSegmented
import jp.eldia.hotel.ui.components.TimePickerDialog
import kotlinx.coroutines.launch

/** 設定の中の画面 */
private enum class SettingsPage(val title: String, val admin: AdminSection? = null) {
    TEMPLATES("テンプレート設定"),
    ORDER_PRODUCTS("商品管理", AdminSection.PRODUCTS),
    ORDER_CATEGORIES("カテゴリ管理", AdminSection.CATEGORIES),
    ORDER_UNITS("単位管理", AdminSection.UNITS),
    ORDER_NOTIFY("LINE・メール通知設定", AdminSection.SETTINGS),
}

/** 設定（アカウント・担当者名・店舗・外観・設備管理・発注・サインアウト） */
@Composable
fun SettingsScreen(vm: AuthViewModel, modifier: Modifier = Modifier) {
    val facilityVm: FacilityViewModel = viewModel()
    val orderVm: OrderViewModel = viewModel()
    val isOrderAdmin by orderVm.isAdmin.collectAsState()
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    var showInvite by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { orderVm.start() }

    page?.let { pg ->
        BackHandler { page = null }
        Column(modifier) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                IconButton(onClick = { page = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") }
                Text(pg.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.fillMaxSize()) {
                val admin = pg.admin
                if (admin != null) AdminScreen(orderVm, admin) else TemplateSettingsPage(facilityVm)
            }
        }
        return
    }
    val state by vm.state.collectAsState()
    val user = (state as? AuthState.SignedIn)?.user
    val hotel by AppPrefs.hotel.collectAsState()
    val appearance by AppPrefs.appearance.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    var name by remember(user?.id) { mutableStateOf(user?.fullName.orEmpty()) }
    var nameMsg by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        EldiaCard {
            Text("アカウント", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(8.dp))
            Text(user?.displayName.orEmpty(), style = MaterialTheme.typography.titleMedium)
            Text(user?.email.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        EldiaCard {
            Text("担当者名", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "清掃記録や不具合の担当者として記録される名前です。スタッフ名簿にも反映されます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; nameMsg = null },
                singleLine = true,
                placeholder = { Text("例：村井") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        saving = true
                        scope.launch {
                            nameMsg = vm.updateDisplayName(name) ?: "保存しました"
                            saving = false
                        }
                    },
                    enabled = !saving && name.isNotBlank() && name.trim() != user?.fullName,
                ) { Text(if (saving) "保存中…" else "保存") }
                nameMsg?.let { Text(it, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodySmall) }
            }
        }

        EldiaCard {
            Text("店舗", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(8.dp))
            HotelSegmented(hotel, { AppPrefs.setHotel(it) })
        }

        EldiaCard {
            Text("外観", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Appearance.entries.forEach { a ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = appearance == a, onClick = { AppPrefs.setAppearance(a) })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = appearance == a, onClick = null)
                    Text(a.label, Modifier.padding(start = 8.dp))
                }
            }
        }

        ReminderSettings()

        PushSettings()

        SettingsSection("設備管理") {
            SettingsLink("テンプレート設定", "不具合テンプレート・清掃タスク") { page = SettingsPage.TEMPLATES }
            HorizontalDivider()
            SettingsLink("ユーザーを招待", "メールで共通ログインに招待します") { showInvite = true }
        }

        SettingsSection("発注") {
            Text(if (isOrderAdmin) "共通ログインで利用中（管理者）" else "共通ログインで利用中（スタッフ）",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (isOrderAdmin) {
                SettingsLink("商品管理", "商品の追加・編集・表示／非表示") { page = SettingsPage.ORDER_PRODUCTS }
                HorizontalDivider()
                SettingsLink("カテゴリ管理") { page = SettingsPage.ORDER_CATEGORIES }
                HorizontalDivider()
                SettingsLink("単位管理") { page = SettingsPage.ORDER_UNITS }
                HorizontalDivider()
                SettingsLink("LINE・メール通知設定", "送信先・送信のしかた・テスト送信") { page = SettingsPage.ORDER_NOTIFY }
            } else {
                Text("商品や通知の設定は発注の管理者だけが変更できます。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }

        OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) {
            Text("サインアウト", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.size(24.dp))
    }

    if (showInvite) InviteDialog { showInvite = false }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("サインアウトしますか？") },
            text = { Text("すべての機能からログアウトします。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    scope.launch {
                        // この端末に通知が届かないようにしてからサインアウトする
                        Push.unregister(ctx)
                        vm.signOut()
                    }
                }) { Text("サインアウト", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("キャンセル") } },
        )
    }
}

/** 通知（今日やることのお知らせ）：全体のオン／オフ・対象（発注・水槽・冷蔵庫）・時刻 */
@Composable
private fun ReminderSettings() {
    val ctx = LocalContext.current
    var rs by remember { mutableStateOf(Reminders.load(ctx)) }
    var allowed by remember { mutableStateOf(Reminders.notificationsEnabled(ctx)) }
    var denied by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    // 端末の設定から戻ったときに許可の状態を取り直す
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = Reminders.notificationsEnabled(ctx) }

    fun update(n: Reminders.Settings) {
        rs = n
        Reminders.save(ctx, n)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = Reminders.notificationsEnabled(ctx)
        denied = !granted
        if (granted) update(rs.copy(enabled = true))
    }

    fun setEnabled(on: Boolean) {
        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Reminders.hasPermission(ctx)) {
            // Android 13 以降はオンにするときに通知の許可を求める
            permLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            denied = false
            update(rs.copy(enabled = on))
        }
    }

    SettingsSection("通知") {
        Text(
            "毎日決まった時刻に、今日が対象日の作業（発注日・水槽チェック・冷蔵庫の期限確認）をお知らせします。端末内の通知なので電波がなくても届きます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ReminderSwitchRow("お知らせを受け取る", null, rs.enabled, true) { setEnabled(it) }
        HorizontalDivider()
        ReminderSwitchRow("発注", "毎週 水曜・日曜（両店舗まとめて）", rs.order, rs.enabled) { update(rs.copy(order = it)) }
        ReminderSwitchRow("水槽", "月〜水のうちに点検", rs.aqua, rs.enabled) { update(rs.copy(aqua = it)) }
        ReminderSwitchRow("冷蔵庫", "毎月25日〜月末に期限確認", rs.fridge, rs.enabled) { update(rs.copy(fridge = it)) }
        HorizontalDivider()
        SettingsLink("通知の時刻", rs.hhmm) { showTime = true }
        if (rs.enabled && !allowed || denied) {
            Text(
                "通知が許可されていません。端末の設定でこのアプリの通知をオンにしてください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
            TextButton(onClick = {
                val i = Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(i) }
            }) { Text("通知の設定を開く") }
        }
    }

    if (showTime) {
        TimePickerDialog(rs.hhmm, "通知の時刻", onDismiss = { showTime = false }) { hhmm ->
            val parts = hhmm.split(":").mapNotNull { it.toIntOrNull() }
            if (parts.size == 2) update(rs.copy(hour = parts[0], minute = parts[1]))
            showTime = false
        }
    }
}

/** プッシュ通知（新しい不具合・発注の承認待ち）。iOS版の「プッシュ通知」と同じ */
@Composable
private fun PushSettings() {
    val ctx = LocalContext.current
    var issue by remember { mutableStateOf(Push.notifyIssue(ctx)) }
    var order by remember { mutableStateOf(Push.notifyOrder(ctx)) }
    var allowed by remember { mutableStateOf(Reminders.notificationsEnabled(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = Reminders.notificationsEnabled(ctx) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = Reminders.notificationsEnabled(ctx)
    }
    fun save(i: Boolean, o: Boolean) {
        issue = i; order = o
        Push.setPrefs(ctx, i, o)
        if ((i || o) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Reminders.hasPermission(ctx)) {
            permLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    SettingsSection("プッシュ通知") {
        if (!Push.configured) {
            Text(
                "このビルドにはプッシュ通知の設定（Firebase）が入っていません。設定ファイルを入れたビルドで使えるようになります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SettingsSection
        }
        Text(
            "ほかの人が登録した不具合や、承認待ちの発注をすぐにお知らせします。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ReminderSwitchRow("新しい不具合", "設備管理に不具合が登録されたとき", issue, true) { save(it, order) }
        ReminderSwitchRow("発注の承認待ち", "承認制のとき、発注が承認を待っているとき", order, true) { save(issue, it) }
        if ((issue || order) && !allowed) {
            Text(
                "通知が許可されていません。端末の設定でこのアプリの通知をオンにしてください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
            TextButton(onClick = {
                val i = Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(i) }
            }) { Text("通知の設定を開く") }
        }
    }
}

@Composable
private fun ReminderSwitchRow(title: String, sub: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
