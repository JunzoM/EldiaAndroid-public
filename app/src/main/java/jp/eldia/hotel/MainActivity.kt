package jp.eldia.hotel

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import jp.eldia.hotel.auth.AuthState
import jp.eldia.hotel.auth.AuthViewModel
import jp.eldia.hotel.auth.LoginScreen
import jp.eldia.hotel.core.AppPrefs
import jp.eldia.hotel.core.AppUpdater
import jp.eldia.hotel.core.ReleaseNotes
import jp.eldia.hotel.core.Reminders
import jp.eldia.hotel.core.TabRequests
import jp.eldia.hotel.feature.cleaning.CleaningSegment
import jp.eldia.hotel.feature.order.OrderSegment
import jp.eldia.hotel.ui.AppTab
import jp.eldia.hotel.ui.MainScreen
import jp.eldia.hotel.ui.ReleaseNotesDialog
import jp.eldia.hotel.ui.UpdateOverlay
import jp.eldia.hotel.ui.theme.EldiaTheme
import jp.eldia.hotel.ui.theme.LocalEldia

class MainActivity : ComponentActivity() {
    private val authVm: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // アプリ内アップデート（Google Play の更新画面）
        AppUpdater.attach(this)
        // お知らせの予約を作り直す（強制停止などで消えていても戻す）
        if (savedInstanceState == null) Reminders.schedule(this)
        // ショートカットから起動されたら該当の画面を開く（回転などで作り直したときは繰り返さない）
        if (savedInstanceState == null) handleLink(intent)
        setContent {
            val hotel by AppPrefs.hotel.collectAsState()
            val appearance by AppPrefs.appearance.collectAsState()
            EldiaTheme(hotel, appearance) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppGate(authVm)
                    UpdateOverlay()
                }
            }
        }
    }

    // 前面に戻るたびに新しい版を確認する
    override fun onResume() {
        super.onResume()
        AppUpdater.check(this)
        // アップデートのお知らせ（ログイン中のみ・同じお知らせは1回だけ）
        lifecycleScope.launch { ReleaseNotes.check(this@MainActivity) }
    }

    override fun onDestroy() {
        AppUpdater.detach()
        super.onDestroy()
    }

    // 起動中にショートカット・ウィジェットから呼ばれたとき（singleTop）
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    /** eldia://open/<画面> を各タブへの指定に変える（ログイン前なら、ログイン後に反映される） */
    private fun handleLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != LINK_SCHEME) return
        // プッシュ通知のリンク（iOS版と同じ形）：eldia://issue/<id> / eldia://facility / eldia://order
        when (uri.host) {
            "issue" -> {
                uri.lastPathSegment?.toLongOrNull()?.let { TabRequests.facilityIssue.value = it }
                TabRequests.mainTab.value = AppTab.FACILITY.name
                return
            }
            "facility" -> { TabRequests.mainTab.value = AppTab.FACILITY.name; return }
            "order" -> {
                TabRequests.orderSegment.value = OrderSegment.HISTORY.name
                TabRequests.mainTab.value = AppTab.ORDER.name
                return
            }
        }
        if (uri.host != "open") return
        when (uri.lastPathSegment) {
            "order" -> {
                TabRequests.orderSegment.value = OrderSegment.ORDER.name
                TabRequests.mainTab.value = AppTab.ORDER.name
            }
            "order-history" -> {
                TabRequests.orderSegment.value = OrderSegment.HISTORY.name
                TabRequests.mainTab.value = AppTab.ORDER.name
            }
            "cleaning" -> {
                TabRequests.cleaningSegment.value = CleaningSegment.CLEANING.name
                TabRequests.mainTab.value = AppTab.CLEANING.name
            }
            "aqua" -> {
                TabRequests.cleaningSegment.value = CleaningSegment.AQUA.name
                TabRequests.mainTab.value = AppTab.CLEANING.name
            }
            "facility-new" -> {
                TabRequests.facilityNew.value = true
                TabRequests.mainTab.value = AppTab.FACILITY.name
            }
            "facility" -> TabRequests.mainTab.value = AppTab.FACILITY.name
            "fridge" -> TabRequests.mainTab.value = AppTab.FRIDGE.name
            "home" -> TabRequests.mainTab.value = AppTab.HOME.name
        }
    }

    companion object {
        /** ショートカット（res/xml/shortcuts.xml）が使う独自スキーム */
        const val LINK_SCHEME = "eldia"
    }
}

/** 共通ログインのゲート。ログイン後にタブを表示する（iOS版の AppGate に相当） */
@Composable
private fun AppGate(vm: AuthViewModel) {
    val state by vm.state.collectAsState()
    // ログイン中にユーザー情報（担当者名など）が変わっても画面全体を切り替え直さないよう、種類だけで切り替える
    val kind = when (state) {
        AuthState.Loading -> 0
        AuthState.SignedOut -> 1
        is AuthState.SignedIn -> 2
    }
    val userId = (state as? AuthState.SignedIn)?.user?.id
    // ログインしたらアップデートのお知らせを確認する
    val ctx = LocalContext.current
    LaunchedEffect(userId) { if (userId != null) ReleaseNotes.check(ctx) }
    Crossfade(targetState = kind, label = "auth") { k ->
        when (k) {
            0 -> Splash()
            1 -> LoginScreen(vm)
            else -> UserViewModelScope(vm, userId) {
                MainScreen(vm)
                ReleaseNotesDialog()
            }
        }
    }
}

/** ログイン中ユーザー専用の ViewModelStoreOwner（AuthViewModel 自体は Activity 側にあるので消えない） */
private class UserViewModelStoreOwner(
    override val viewModelStore: ViewModelStore,
    app: Application,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    // AndroidViewModel を作るには Application を渡す Factory が要る
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory =
        ViewModelProvider.AndroidViewModelFactory.getInstance(app)
    override val defaultViewModelCreationExtras: CreationExtras =
        MutableCreationExtras().apply { set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, app) }
}

/** 中の viewModel() をユーザーごとの置き場から取る。別ユーザーでログインし直すと前の ViewModel は捨てられる */
@Composable
private fun UserViewModelScope(authVm: AuthViewModel, userId: String?, content: @Composable () -> Unit) {
    // ログアウト後のフェードアウト中（userId = null）は直前のユーザーのまま
    val last = remember { arrayOfNulls<String>(1) }
    if (userId != null) last[0] = userId
    val uid = last[0].orEmpty()
    val app = LocalContext.current.applicationContext as Application
    val owner = remember(uid) { UserViewModelStoreOwner(authVm.viewModelStoreFor(uid), app) }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}

@Composable
private fun Splash() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ELDIA", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 36.sp, color = LocalEldia.current.accentText)
        CircularProgressIndicator()
    }
}
