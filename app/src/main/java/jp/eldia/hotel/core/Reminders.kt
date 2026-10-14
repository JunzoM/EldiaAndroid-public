package jp.eldia.hotel.core

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import jp.eldia.hotel.MainActivity
import jp.eldia.hotel.R
import jp.eldia.hotel.feature.home.HomeSchedule
import jp.eldia.hotel.feature.home.HomeTaskKind
import jp.eldia.hotel.feature.widget.WidgetSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * やることのお知らせ（端末内の毎日の通知。iOS版 TaskReminders に相当・サーバー不要）
 * - 毎日決まった時刻に、今日が対象日の項目（発注・水槽・冷蔵庫）をまとめて1件通知する
 * - 完了状況は見ない（通信しない）ので「今日は発注日です」のように対象日であることだけを伝える
 * - AlarmManager の非正確アラーム（正確なアラームの権限は不要）。鳴ったら翌日分を予約し直す
 */
object Reminders {
    const val PREFS = "eldia_reminders"
    const val KEY_ENABLED = "enabled"
    const val KEY_ORDER = "order"
    const val KEY_AQUA = "aqua"
    const val KEY_FRIDGE = "fridge"
    const val KEY_HOUR = "hour"
    const val KEY_MINUTE = "minute"

    /** iOS版と同じ既定の時刻 */
    const val DEFAULT_HOUR = 9
    const val DEFAULT_MINUTE = 0

    const val CHANNEL_ID = "eldia_today"
    private const val NOTIFICATION_ID = 1001
    const val ACTION_FIRE = "jp.eldia.hotel.action.REMINDER_FIRE"

    data class Settings(
        val enabled: Boolean,
        val order: Boolean,
        val aqua: Boolean,
        val fridge: Boolean,
        val hour: Int,
        val minute: Int,
    ) {
        val hhmm: String get() = "%02d:%02d".format(hour, minute)
        /** 1つでも対象があり、全体がオン */
        val active: Boolean get() = enabled && (order || aqua || fridge)
    }

    fun load(ctx: Context): Settings {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(
            enabled = p.getBoolean(KEY_ENABLED, false),
            order = p.getBoolean(KEY_ORDER, true),
            aqua = p.getBoolean(KEY_AQUA, true),
            fridge = p.getBoolean(KEY_FRIDGE, true),
            hour = p.getInt(KEY_HOUR, DEFAULT_HOUR).coerceIn(0, 23),
            minute = p.getInt(KEY_MINUTE, DEFAULT_MINUTE).coerceIn(0, 59),
        )
    }

    /** 保存して予約し直す */
    fun save(ctx: Context, s: Settings) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, s.enabled)
            .putBoolean(KEY_ORDER, s.order)
            .putBoolean(KEY_AQUA, s.aqua)
            .putBoolean(KEY_FRIDGE, s.fridge)
            .putInt(KEY_HOUR, s.hour)
            .putInt(KEY_MINUTE, s.minute)
            .apply()
        schedule(ctx)
    }

    /** 通知を出してよいか（Android 13 以降は実行時の許可が必要） */
    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** 端末の設定で通知が止められていないか */
    fun notificationsEnabled(ctx: Context): Boolean =
        hasPermission(ctx) && NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    private fun alarmIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_FIRE)
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** 次の通知時刻（日本時間。今の時刻を過ぎていれば翌日） */
    fun nextFire(s: Settings, now: ZonedDateTime = ZonedDateTime.now(DateUtil.TZ)): ZonedDateTime {
        var t = now.toLocalDate().atTime(s.hour, s.minute).atZone(DateUtil.TZ)
        // 鳴った直後に予約し直すとき同じ日にならないよう、1分の余裕を見る
        if (!t.isAfter(now.plusMinutes(1))) t = t.plusDays(1)
        return t
    }

    /** 予約し直す（アプリ起動時・設定変更時・通知後・再起動後に呼ぶ） */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(ctx)
        am.cancel(pi)
        val s = load(ctx)
        if (!s.active) return
        ensureChannel(ctx)
        val at = nextFire(s).toInstant().toEpochMilli()
        // 非正確でよい（数分ずれることがある）。省電力中でも鳴るようにする
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "今日やること", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "発注日・水槽チェック・冷蔵庫の期限確認のお知らせ"
        }
        nm.createNotificationChannel(ch)
    }

    /**
     * 今日が対象日の項目（HomeSchedule と同じルール）。
     * @param done 今日すでに完了している項目（HomeTaskKind の name）。完了済みは知らせない（iOS版と同じ）
     */
    fun linesFor(s: Settings, today: LocalDate = DateUtil.today(), done: Set<String> = emptySet()): List<String> {
        val sch = HomeSchedule(today)
        val lines = mutableListOf<String>()
        if (s.order && sch.isOrderDay && HomeTaskKind.ORDER.name !in done) {
            lines += "今日は発注日です（両店舗分をまとめて）"
        }
        if (s.aqua && sch.isAquaWindow && HomeTaskKind.AQUA.name !in done) {
            lines += if (sch.aquaDaysLeft == 0) "水槽チェックは今日までです（未実施なら点検を）"
            else "今週は水槽チェックの週です（水曜まで）"
        }
        if (s.fridge && sch.isFridgeWindow && HomeTaskKind.FRIDGE.name !in done) {
            lines += if (sch.fridgeDaysLeft == 0) "冷蔵庫の期限確認は今日までです"
            else "冷蔵庫の期限確認の期間です（月末まであと${sch.fridgeDaysLeft}日）"
        }
        return lines
    }

    /**
     * 今日の完了済みの項目。ホームが今日読み込んだ結果（ウィジェット用の保存）から取る。
     * 今日の保存がなければ空（＝対象日の項目はすべて知らせる）
     */
    private fun doneToday(ctx: Context): Set<String> {
        val snap = WidgetSnapshot.load(ctx) ?: return emptySet()
        if (snap.day != DateUtil.todayString()) return emptySet()
        return snap.tasks.filter { it.done }.map { it.kind }.toSet()
    }

    /** 今日の分を通知する（対象がなければ何もしない） */
    @SuppressLint("MissingPermission")
    fun notifyToday(ctx: Context) {
        val s = load(ctx)
        if (!s.active || !hasPermission(ctx)) return
        val lines = linesFor(s, done = doneToday(ctx))
        if (lines.isEmpty()) return
        ensureChannel(ctx)
        // タップでアプリを開く（起動中ならそのまま前に出す）
        val launch = Intent(ctx, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val contentPi = PendingIntent.getActivity(ctx, 1, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = if (lines.size == 1) lines[0] else "今日やること ${lines.size}件"
        val body = if (lines.size == 1) "タップしてアプリを開きます" else lines.joinToString("\n")
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_eldia)
            .setContentTitle(title)
            .setContentText(if (lines.size == 1) body else lines.joinToString("・"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, n)
    }
}

/** 予約した時刻に呼ばれる：今日の分を通知して、翌日分を予約し直す */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Reminders.ACTION_FIRE) return
        runCatching { Reminders.notifyToday(context) }
        Reminders.schedule(context)
        // 日付が変わっていればウィジェットを今日の内容に描き直す（終わるまで受信を続ける）
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                WidgetSnapshot.refresh(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}

/** 端末の再起動・アプリの更新で消えた予約を作り直す */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> Reminders.schedule(context)
        }
    }
}
