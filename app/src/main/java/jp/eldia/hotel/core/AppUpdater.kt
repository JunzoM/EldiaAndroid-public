package jp.eldia.hotel.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import jp.eldia.hotel.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * アプリ内アップデート（Google Play の更新画面）。
 * - ふだん：新しい版があれば「作業を止めずに更新」（裏でダウンロード → 終わったら「再起動して更新」）
 * - 共通 Supabase の app_settings「app_min_version」の android より古い版：更新するまで使えない画面にする
 * Play ストアから入れたアプリだけで動く（Android Studio から直接入れた版では何も出ない）
 */
object AppUpdater {
    enum class Banner { NONE, DOWNLOADING, READY }

    private val _banner = MutableStateFlow(Banner.NONE)
    /** 画面下の帯（ダウンロード中・再起動して更新） */
    val banner: StateFlow<Banner> = _banner

    private val _required = MutableStateFlow<String?>(null)
    /** null 以外：更新が必須（表示する文） */
    val required: StateFlow<String?> = _required

    private var manager: AppUpdateManager? = null
    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private const val PREFS = "eldia_update"
    private const val KEY_ASKED = "asked"   // "バージョンコード|日付"：同じ版は1日1回まで聞く

    private val listener = InstallStateUpdatedListener { st ->
        when (st.installStatus()) {
            InstallStatus.DOWNLOADING, InstallStatus.PENDING -> _banner.value = Banner.DOWNLOADING
            InstallStatus.DOWNLOADED -> _banner.value = Banner.READY
            InstallStatus.INSTALLED, InstallStatus.FAILED, InstallStatus.CANCELED -> _banner.value = Banner.NONE
            else -> Unit
        }
    }

    /** MainActivity の onCreate で呼ぶ（registerForActivityResult は onCreate の中で行う決まり） */
    fun attach(activity: ComponentActivity) {
        val m = AppUpdateManagerFactory.create(activity)
        manager = m
        m.registerListener(listener)
        launcher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            // 必須の更新を閉じられたら、更新を促す画面のままにする
            if (result.resultCode != android.app.Activity.RESULT_OK && _required.value != null) {
                activity.lifecycleScope.launch { check(activity) }
            }
        }
    }

    fun detach() {
        manager?.unregisterListener(listener)
        manager = null
        launcher = null
    }

    /** 前面に戻るたび（onResume）に呼ぶ */
    fun check(activity: ComponentActivity) {
        val m = manager ?: return
        activity.lifecycleScope.launch {
            val min = minVersionCode()
            val mustUpdate = min != null && BuildConfig.VERSION_CODE < min.first
            val info = runCatching { m.requestAppUpdateInfo() }.getOrNull()
            if (info == null) {
                // Play ストア以外から入れた版など：必須なら案内だけ出す
                _required.value = if (mustUpdate) min!!.second else null
                return@launch
            }
            if (info.installStatus() == InstallStatus.DOWNLOADED) {
                _banner.value = Banner.READY
            }
            val available = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
            val resuming = info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
            _required.value = if (mustUpdate) min!!.second else null
            val l = launcher ?: return@launch
            when {
                // 必須：更新するまで使えない全画面の更新
                (mustUpdate && available && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) || resuming ->
                    runCatching { m.startUpdateFlowForResult(info, l, AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE)) }
                // ふだん：作業を止めずに更新（同じ版は1日1回まで聞く）
                available && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) && shouldAsk(activity, info.availableVersionCode()) -> {
                    markAsked(activity, info.availableVersionCode())
                    runCatching { m.startUpdateFlowForResult(info, l, AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE)) }
                }
            }
        }
    }

    /** 「再起動して更新」 */
    fun completeUpdate() {
        manager?.completeUpdate()
    }

    /** Play ストアの ELDIA のページを開く（アプリ内アップデートが使えないとき） */
    fun openStore(ctx: Context) {
        val id = ctx.packageName
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun shouldAsk(ctx: Context, versionCode: Int): Boolean {
        val asked = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ASKED, null)
        return asked != "$versionCode|${DateUtil.todayString()}"
    }

    private fun markAsked(ctx: Context, versionCode: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ASKED, "$versionCode|${DateUtil.todayString()}").apply()
    }

    @Serializable
    private data class Setting(val key: String, val value: JsonObject)

    /** (最低バージョンコード, 表示する文)。ログイン前・読み込み失敗は null（必須にしない） */
    private suspend fun minVersionCode(): Pair<Int, String>? {
        if (SB.main.auth.currentSessionOrNull() == null) return null
        val v = runCatching {
            SB.main.from("app_settings").select(Columns.list("key", "value")) {
                filter { eq("key", "app_min_version") }
            }.decodeList<Setting>().firstOrNull()?.value
        }.getOrNull() ?: return null
        val code = v["android"]?.jsonPrimitive?.intOrNull ?: return null
        if (code <= 0) return null
        val msg = v["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "このバージョンは使えなくなりました。最新版に更新してください。"
        return code to msg
    }
}
