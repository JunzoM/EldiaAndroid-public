package jp.eldia.hotel.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import jp.eldia.hotel.core.UndoBar
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import jp.eldia.hotel.auth.AuthViewModel
import jp.eldia.hotel.core.AppPrefs
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import jp.eldia.hotel.core.Push
import jp.eldia.hotel.core.Reminders
import jp.eldia.hotel.core.TabRequests
import jp.eldia.hotel.feature.cleaning.CleaningTab
import jp.eldia.hotel.feature.facility.FacilityTab
import jp.eldia.hotel.feature.fridge.FridgeTab
import jp.eldia.hotel.feature.home.HomeTab
import jp.eldia.hotel.feature.order.OrderTab
import jp.eldia.hotel.feature.settings.SettingsScreen
import jp.eldia.hotel.ui.components.HotelMenuButton
import jp.eldia.hotel.ui.theme.LocalEldia

/** 下のタブ（iOS版と同じ5つ。設定は右上のボタンから） */
enum class AppTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("ホーム", Icons.Outlined.Home, Icons.Filled.Home),
    ORDER("発注", Icons.Outlined.ListAlt, Icons.Filled.ListAlt),
    CLEANING("清掃", Icons.Outlined.CleaningServices, Icons.Filled.CleaningServices),
    FACILITY("設備", Icons.Outlined.Build, Icons.Filled.Build),
    FRIDGE("冷蔵庫", Icons.Outlined.Kitchen, Icons.Filled.Kitchen),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(authVm: AuthViewModel) {
    var tab by rememberSaveable { mutableStateOf(AppTab.HOME) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val hotel by AppPrefs.hotel.collectAsState()
    val eldia = LocalEldia.current
    val stateHolder = rememberSaveableStateHolder()

    BackHandler(enabled = showSettings) { showSettings = false }
    // ホーム以外のタブで戻るを押したら、アプリを閉じずにホームへ戻る（各タブの中の画面は各タブ側で先に戻る）
    BackHandler(enabled = !showSettings && tab != AppTab.HOME) { tab = AppTab.HOME }

    // プッシュ通知：ログイン後と店舗の切り替えで端末を登録し直す。初回だけ通知の許可を求める（iOS版と同じ）
    val ctx = LocalContext.current
    val pushPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Push.registerLater(ctx) }
    LaunchedEffect(hotel) {
        if (!Push.configured) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Reminders.hasPermission(ctx) && !Push.askedPermission(ctx)) {
            Push.markAskedPermission(ctx)
            pushPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        Push.registerLater(ctx)
    }

    // 保存のあとの「元に戻す」（新しい保存が来たら前の表示は閉じる）
    val snackbar = remember { SnackbarHostState() }
    val undoScope = rememberCoroutineScope()
    val view = LocalView.current
    // 保存できたときに軽く振動させる（iOS版のトーストと同じ手応え）
    fun confirmHaptic() {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP,
        )
    }
    LaunchedEffect(Unit) {
        UndoBar.events.collect { e ->
            confirmHaptic()
            snackbar.currentSnackbarData?.dismiss()
            undoScope.launch {
                val result = withTimeoutOrNull(UndoBar.SHOW_MILLIS) {
                    snackbar.showSnackbar(e.message, actionLabel = "元に戻す", duration = SnackbarDuration.Indefinite)
                }
                if (result == null) snackbar.currentSnackbarData?.dismiss()
                if (result == SnackbarResult.ActionPerformed) {
                    val err = e.undo()
                    if (err == null) confirmHaptic()
                    snackbar.showSnackbar(if (err == null) "元に戻しました" else "元に戻せませんでした：$err", duration = SnackbarDuration.Short)
                }
            }
        }
    }

    // ランチャーのショートカットなど外からタブを指定されたら切り替えて、指定を消す
    val requestedTab by TabRequests.mainTab.collectAsState()
    LaunchedEffect(requestedTab) {
        val name = requestedTab ?: return@LaunchedEffect
        AppTab.entries.firstOrNull { it.name == name }?.let {
            tab = it
            showSettings = false
        }
        TabRequests.mainTab.value = null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (showSettings) {
                        IconButton(onClick = { showSettings = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                        }
                    }
                },
                title = {
                    if (showSettings) Text("設定")
                    else Text("ELDIA", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = eldia.accentText)
                },
                actions = {
                    if (!showSettings) {
                        HotelMenuButton(hotel) { AppPrefs.setHotel(it) }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "設定")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (!showSettings) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    AppTab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(if (tab == t) t.selectedIcon else t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                            // 選択中の印は店舗の色にする（既定の紫を使わない）
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = eldia.accentText,
                                selectedTextColor = eldia.accentText,
                            ),
                        )
                    }
                }
            }
        },
    ) { inner ->
        val m = Modifier.fillMaxSize().padding(inner)
        if (showSettings) {
            SettingsScreen(authVm, m)
        } else {
            // タブを切り替えても・設定から戻っても、各タブの表示状態（区分・絞り込み・検索など）を残す
            stateHolder.SaveableStateProvider(tab.name) {
                when (tab) {
                    // ホームの行をタップしたら該当のタブへ
                    AppTab.HOME -> HomeTab(m, onOpen = { tab = it })
                    AppTab.ORDER -> OrderTab(m)
                    AppTab.CLEANING -> CleaningTab(m)
                    AppTab.FACILITY -> FacilityTab(m)
                    AppTab.FRIDGE -> FridgeTab(m)
                }
            }
        }
    }
}

