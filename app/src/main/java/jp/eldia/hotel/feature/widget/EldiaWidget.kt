package jp.eldia.hotel.feature.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider as DayNight
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import jp.eldia.hotel.MainActivity
import jp.eldia.hotel.core.DateUtil
import jp.eldia.hotel.core.Hotel
import jp.eldia.hotel.feature.home.HomeSchedule

/** ウィジェットに表示する内容（保存された結果か、なければ今日の実施ルールだけ） */
private data class WidgetView(
    val hotel: Hotel,
    val dateLabel: String,
    val tasks: List<WidgetTask>,
    val unsent: Int,
    val updatedAt: String,
    /** 今日の結果がまだない（アプリを開くと更新される） */
    val stale: Boolean,
) {
    companion object {
        fun build(ctx: Context): WidgetView {
            val today = DateUtil.today()
            val sc = HomeSchedule(today)
            val snap = WidgetSnapshot.load(ctx)
            if (snap != null && snap.day == DateUtil.ymd(today)) {
                return WidgetView(
                    Hotel.from(snap.hotel) ?: Hotel.LUXURY, sc.dateLabel, snap.tasks, snap.unsent, snap.updatedAt, false,
                )
            }
            // アプリで選んでいる店舗（AppPrefs と同じ保存先）
            val raw = ctx.getSharedPreferences("eldia", Context.MODE_PRIVATE).getString("eldia_hotel", null)
            val hotel = Hotel.from(raw) ?: Hotel.from(snap?.hotel) ?: Hotel.LUXURY
            return WidgetView(hotel, sc.dateLabel, WidgetSnapshot.scheduleOnly(hotel, sc), 0, "", true)
        }
    }
}

/** 色（ライト／ダークで切り替え。アプリの配色に合わせる） */
private object WColor {
    val bg = DayNight(day = Color(0xFFFFFFFF), night = Color(0xFF1A1D27))
    val text = DayNight(day = Color(0xFF1A1614), night = Color(0xFFE8E2D4))
    val muted = DayNight(day = Color(0xFF6B6456), night = Color(0xFF9A9AAA))
    val done = DayNight(day = Color(0xFF2D7A52), night = Color(0xFF4CAF7D))
    val warn = DayNight(day = Color(0xFFB26A00), night = Color(0xFFFFC048))
    val urgent = DayNight(day = Color(0xFFC0212E), night = Color(0xFFFF5F6D))

    fun accent(h: Hotel): ColorProvider = DayNight(day = h.accentText(false), night = h.accentText(true))

    fun tone(t: String): ColorProvider = when (t) {
        "done" -> done
        "warn" -> warn
        "urgent" -> urgent
        else -> muted
    }
}

private const val MAX_ROWS = 5

/** ホーム画面のウィジェット「今日やること」。タップでアプリを開く */
class EldiaWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext
        provideContent {
            // アプリ側で保存し直したら描き直す（version を読んで変化を受け取る）
            WidgetSnapshot.version.collectAsState().value
            WidgetBody(WidgetView.build(app))
        }
    }
}

class EldiaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EldiaWidget()
}

@Composable
private fun WidgetBody(v: WidgetView) {
    Column(
        GlanceModifier
            .fillMaxSize()
            .background(WColor.bg)
            .cornerRadius(16.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        // 見出し・店舗
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "今日やること",
                style = TextStyle(color = WColor.text, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                v.hotel.english.uppercase(),
                style = TextStyle(color = WColor.accent(v.hotel), fontSize = 11.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
        Text(v.dateLabel, style = TextStyle(color = WColor.muted, fontSize = 11.sp), maxLines = 1)
        Spacer(GlanceModifier.height(6.dp))

        // 行（Glance は1つの入れ物に置ける数に上限があるので、行だけ別の Column にまとめる）
        Column(GlanceModifier.fillMaxWidth()) {
            v.tasks.take(MAX_ROWS).forEach { t -> TaskLine(t, v.hotel, v.stale) }
            val more = v.tasks.size - MAX_ROWS
            if (more > 0) {
                Text("ほか ${more}件", style = TextStyle(color = WColor.muted, fontSize = 11.sp), maxLines = 1)
            }
        }

        Spacer(GlanceModifier.defaultWeight())

        // 下：未送信・更新時刻
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (v.unsent > 0) {
                Text(
                    "未送信 ${v.unsent}件",
                    style = TextStyle(color = WColor.warn, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.defaultWeight())
            Text(
                if (v.stale) "アプリを開くと更新されます" else "更新 ${v.updatedAt}",
                style = TextStyle(color = WColor.muted, fontSize = 10.sp),
                maxLines = 1,
            )
        }
    }
}

/** 1行（完了は ✓、未完了は ○。今日の結果がないときは ○ だけ） */
@Composable
private fun TaskLine(t: WidgetTask, hotel: Hotel, stale: Boolean) {
    val done = t.done && !stale
    Row(
        GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (done) "✓" else "○",
            style = TextStyle(
                color = if (done) WColor.done else WColor.accent(hotel),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            ),
            modifier = GlanceModifier.width(20.dp),
        )
        Column(GlanceModifier.defaultWeight()) {
            Text(
                t.title,
                style = TextStyle(
                    color = if (done) WColor.muted else WColor.text,
                    fontSize = 13.sp,
                    fontWeight = if (done) FontWeight.Medium else FontWeight.Bold,
                ),
                maxLines = 1,
            )
            if (t.detail.isNotEmpty()) {
                Text(t.detail, style = TextStyle(color = WColor.muted, fontSize = 11.sp), maxLines = 1)
            }
        }
        if (t.badge.isNotEmpty()) {
            Text(
                t.badge,
                style = TextStyle(color = WColor.tone(t.tone), fontSize = 11.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                modifier = GlanceModifier.padding(start = 6.dp),
            )
        }
    }
}
