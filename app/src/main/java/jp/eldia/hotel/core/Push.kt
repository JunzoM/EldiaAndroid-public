package jp.eldia.hotel.core

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import jp.eldia.hotel.BuildConfig
import jp.eldia.hotel.MainActivity
import jp.eldia.hotel.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

/**
 * プッシュ通知（FCM。iOS版 PushManager に相当）。
 * 端末の登録トークンを共通プロジェクトの device_tokens に登録し、Edge Function push-notify から
 * 「新しい不具合」「発注の承認待ち」を受け取る。
 * google-services.json が app/ にないビルドでは何もしない（configured = false）
 */
object Push {
    private const val PREFS = "eldia_push"
    private const val KEY_ISSUE = "issue"
    private const val KEY_ORDER = "order"
    private const val KEY_TOKEN = "token"
    private const val KEY_ASKED = "asked_permission"
    const val CHANNEL_ID = "eldia_push"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    /** このビルドに Firebase の設定が入っているか */
    val configured: Boolean get() = BuildConfig.FCM_APP_ID.isNotEmpty()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun notifyIssue(ctx: Context) = prefs(ctx).getBoolean(KEY_ISSUE, true)
    fun notifyOrder(ctx: Context) = prefs(ctx).getBoolean(KEY_ORDER, true)

    /** 種類ごとのオン／オフを保存してサーバーへ反映 */
    fun setPrefs(ctx: Context, issue: Boolean, order: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ISSUE, issue).putBoolean(KEY_ORDER, order).apply()
        registerLater(ctx)
    }

    /** 初回だけ通知の許可を求めるため（求めたら true にする） */
    fun askedPermission(ctx: Context) = prefs(ctx).getBoolean(KEY_ASKED, false)
    fun markAskedPermission(ctx: Context) = prefs(ctx).edit().putBoolean(KEY_ASKED, true).apply()

    /** アプリ起動時（Application.onCreate）に呼ぶ */
    fun init(ctx: Context) {
        if (!configured) return
        runCatching {
            if (FirebaseApp.getApps(ctx).isEmpty()) {
                FirebaseApp.initializeApp(
                    ctx,
                    FirebaseOptions.Builder()
                        .setProjectId(BuildConfig.FCM_PROJECT_ID)
                        .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                        .setApplicationId(BuildConfig.FCM_APP_ID)
                        .setApiKey(BuildConfig.FCM_API_KEY)
                        .build(),
                )
            }
        }
        ensureChannel(ctx)
    }

    private suspend fun fetchToken(): String? = suspendCancellableCoroutine { cont ->
        runCatching {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { t ->
                if (cont.isActive) cont.resume(if (t.isSuccessful) t.result else null)
            }
        }.onFailure { if (cont.isActive) cont.resume(null) }
    }

    /** ログイン後・店舗の切り替え・トークン更新・設定変更のときに呼ぶ（結果は待たない） */
    fun registerLater(ctx: Context, token: String? = null) {
        val app = ctx.applicationContext
        scope.launch { runCatching { register(app, token) } }
    }

    /** 端末トークンと通知設定をサーバーへ保存（ログインしていなければ何もしない） */
    suspend fun register(ctx: Context, newToken: String? = null) = lock.withLock {
        if (!configured || SB.main.auth.currentSessionOrNull() == null) return@withLock
        val token = newToken ?: fetchToken() ?: prefs(ctx).getString(KEY_TOKEN, null) ?: return@withLock
        prefs(ctx).edit().putString(KEY_TOKEN, token).apply()
        SB.main.postgrest.rpc("register_android_device", buildJsonObject {
            put("p_token", token)
            put("p_hotel", AppPrefs.hotel.value.rawValue)
            put("p_notify_issue", notifyIssue(ctx))
            put("p_notify_order", notifyOrder(ctx))
        })
    }

    /** サインアウト前に呼ぶ（この端末に通知が届かないようにする） */
    suspend fun unregister(ctx: Context) {
        val token = prefs(ctx).getString(KEY_TOKEN, null) ?: return
        runCatching { SB.main.from("device_tokens").delete { filter { eq("token", token) } } }
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "不具合・発注のお知らせ", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "新しい不具合の登録・発注の承認待ち"
            },
        )
    }

    /** 届いた通知を表示する（タップで link の画面を開く） */
    @SuppressLint("MissingPermission")
    fun show(ctx: Context, title: String, body: String, link: String?, tag: String?) {
        if (!Reminders.hasPermission(ctx)) return
        ensureChannel(ctx)
        val intent = Intent(ctx, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(link ?: "eldia://open/home"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val id = (tag ?: link ?: title).hashCode()
        val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_eldia)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(ctx).notify(tag, id, n)
    }
}

/** FCM の受け口（データだけのメッセージを受け取り、通知を作って出す） */
class EldiaMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Push.registerLater(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        val title = d["title"] ?: message.notification?.title ?: return
        val body = d["body"] ?: message.notification?.body.orEmpty()
        Push.show(applicationContext, title, body, d["link"], d["tag"])
    }
}
