package jp.eldia.hotel.feature.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import jp.eldia.hotel.core.DateUtil
import jp.eldia.hotel.core.Hotel
import jp.eldia.hotel.feature.aqua.AquaConst
import jp.eldia.hotel.feature.home.HomeRow
import jp.eldia.hotel.feature.home.HomeSchedule
import jp.eldia.hotel.feature.home.HomeTaskKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** ウィジェットに出す1行 */
@Serializable
data class WidgetTask(
    val kind: String,
    val title: String,
    val detail: String,
    val done: Boolean,
    val badge: String = "",
    /** normal / warn / urgent / done / muted */
    val tone: String = "normal",
)

/**
 * ホームの「今日やること」の結果（iOS版 HomeSnapshot に相当）。
 * ホーム画面で計算し終えたら端末に保存し、ウィジェットはこれを読む（ウィジェット側では通信しない）。
 */
@Serializable
data class WidgetSnapshot(
    /** 日本時間の yyyy-MM-dd */
    val day: String,
    /** Hotel.rawValue */
    val hotel: String,
    val tasks: List<WidgetTask>,
    /** 未送信の記録の件数 */
    val unsent: Int = 0,
    /** 保存した時刻 HH:mm（日本時間） */
    val updatedAt: String = "",
) {
    companion object {
        private const val PREFS = "eldia_widget"
        private const val KEY = "snapshot"
        private val json = Json { ignoreUnknownKeys = true }

        /** 保存のたびに増える（表示中のウィジェットを描き直すため） */
        val version = MutableStateFlow(0L)

        fun load(ctx: Context): WidgetSnapshot? {
            val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
            return runCatching { json.decodeFromString(serializer(), s) }.getOrNull()
        }

        fun from(rows: List<HomeRow>, hotel: Hotel, unsent: Int): WidgetSnapshot = WidgetSnapshot(
            day = DateUtil.todayString(),
            hotel = hotel.rawValue,
            tasks = rows.map { r ->
                WidgetTask(
                    kind = r.kind.name,
                    title = if (r.kind.isShared) "${r.kind.title}（両店舗）" else r.kind.title,
                    detail = r.detail,
                    done = r.done,
                    badge = r.badge,
                    tone = r.tone.name.lowercase(),
                )
            },
            unsent = unsent,
            updatedAt = DateUtil.hhmm(),
        )

        /** 保存してウィジェットを描き直す。前回と同じ内容（時刻以外）なら何もしない */
        suspend fun saveAndUpdate(ctx: Context, rows: List<HomeRow>, hotel: Hotel, unsent: Int) {
            val snap = from(rows, hotel, unsent)
            val prev = load(ctx)
            if (prev != null && prev.copy(updatedAt = "") == snap.copy(updatedAt = "")) return
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, json.encodeToString(serializer(), snap))
                .apply()
            version.value = version.value + 1
            refresh(ctx)
        }

        /** 置いてあるウィジェットをすべて描き直す（ウィジェットがなければ何もしない） */
        suspend fun refresh(ctx: Context) {
            try {
                EldiaWidget().updateAll(ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // ウィジェットの更新に失敗してもアプリの動作には影響させない
            }
        }

        /** 今日の保存がないとき：実施ルールだけから今日の対象を出す（完了状況は分からない） */
        fun scheduleOnly(hotel: Hotel, sc: HomeSchedule = HomeSchedule(DateUtil.today())): List<WidgetTask> {
            val list = mutableListOf(WidgetTask(HomeTaskKind.CLEANING.name, HomeTaskKind.CLEANING.title, "毎日", false))
            if (sc.isOrderDay) {
                list += WidgetTask(HomeTaskKind.ORDER.name, "${HomeTaskKind.ORDER.title}（両店舗）", "毎週 水・日", false)
            }
            if (sc.isAquaWindow && AquaConst.rooms.any { it.hotel == hotel }) {
                val left = sc.aquaDaysLeft
                list += WidgetTask(
                    HomeTaskKind.AQUA.name, HomeTaskKind.AQUA.title, "月〜水のうち週1回", false,
                    if (left == 0) "今日まで" else "残り${left}日", if (left == 0) "warn" else "normal",
                )
            }
            if (sc.isFridgeWindow) {
                val left = sc.fridgeDaysLeft
                list += WidgetTask(
                    HomeTaskKind.FRIDGE.name, HomeTaskKind.FRIDGE.title, "25日〜月末のうち1回", false,
                    if (left == 0) "今日まで" else "残り${left}日", if (left == 0) "warn" else "normal",
                )
            }
            return list
        }
    }
}
