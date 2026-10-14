package jp.eldia.hotel.feature.facility

import androidx.compose.ui.graphics.Color
import jp.eldia.hotel.core.DateUtil
import jp.eldia.hotel.core.FacilityConst
import jp.eldia.hotel.core.Hotel
import jp.eldia.hotel.core.StatusColor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/** 不具合の対象（店舗 or 外構部）。DB の issues.store 列 */
enum class IssueSite(val raw: String, val label: String, val shortName: String) {
    LUXURY("luxury", "ラグジュアリー", "Luxury"),
    MODERN("modern", "モダン", "Modern"),
    EXTERIOR("exterior", "外構部", "外構部");

    val hotel: Hotel? get() = when (this) { LUXURY -> Hotel.LUXURY; MODERN -> Hotel.MODERN; EXTERIOR -> null }
    val accent: Color get() = hotel?.accent ?: Color(0xFF4B5262)

    companion object {
        fun from(raw: String?) = entries.firstOrNull { it.raw == raw } ?: LUXURY
        fun of(h: Hotel) = if (h == Hotel.LUXURY) LUXURY else MODERN
    }
}

enum class IssueStatus(val raw: String, val label: String, val color: Color) {
    OPEN("open", "未対応", StatusColor.open),
    PROGRESS("progress", "対応中", StatusColor.progress),
    DONE("done", "完了", StatusColor.done);

    companion object { fun from(raw: String?) = entries.firstOrNull { it.raw == raw } ?: OPEN }
}

enum class IssuePriority(val raw: String, val label: String, val color: Color) {
    HIGH("high", "高", Color(0xFFE53935)),
    MEDIUM("medium", "中", Color(0xFFFB8C00)),
    LOW("low", "低", Color(0xFF1E88E5));

    companion object { fun from(raw: String?) = entries.firstOrNull { it.raw == raw } }
}

@Serializable
data class StatusChange(val from: String, val to: String)

/** コメント（issues.comments の jsonb 配列）。id は Web版の Date.now() の数値か文字列 */
@Serializable
data class IssueComment(
    val id: JsonElement? = null,
    val author: String = "",
    val text: String = "",
    val date: String = "",
    val photos: List<String> = emptyList(),
    val statusChange: StatusChange? = null,
) {
    /** 画面で使う識別子（id ＋ 日時） */
    val key: String get() = (id?.jsonPrimitive?.contentOrNull ?: "") + date

    companion object {
        fun create(author: String, text: String, photos: List<String>, change: StatusChange?) = IssueComment(
            id = JsonPrimitive(System.currentTimeMillis()),
            author = author, text = text, date = OffsetDateTime.now().toString(), photos = photos, statusChange = change,
        )
    }
}

/** 設備不具合（issues テーブル） */
@Serializable
data class Issue(
    val id: Long? = null,
    val store: String = "luxury",
    @SerialName("room_id") val roomId: String = "",
    val area: String = "",
    val title: String = "",
    val status: String = "open",
    @SerialName("occur_date") val occurDate: String? = null,
    @SerialName("report_date") val reportDate: String? = null,
    val assignee: String? = null,
    val memo: String? = null,
    val photos: List<String>? = null,
    val comments: List<IssueComment>? = null,
    val priority: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    val site: IssueSite get() = IssueSite.from(store)
    val issueStatus: IssueStatus get() = IssueStatus.from(status)
    val issuePriority: IssuePriority? get() = IssuePriority.from(priority)
    val photoList: List<String> get() = photos.orEmpty()
    val commentList: List<IssueComment> get() = comments.orEmpty()

    /** 7日超過（未完了） */
    val isOverdue: Boolean
        get() {
            if (issueStatus == IssueStatus.DONE) return false
            val base = DateUtil.parse(reportDate?.takeIf { it.isNotBlank() } ?: occurDate) ?: return false
            return ChronoUnit.DAYS.between(base, DateUtil.today()) > 7
        }

    /** 更新日時（日本時間の日付） */
    val updatedDate get() = updatedAt?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(DateUtil.TZ).toLocalDate() }.getOrNull() }

    /** 完了した日（最後に「完了」へ変えたコメントの日付。記録がなければ更新日） */
    val doneDate: LocalDate?
        get() = commentList
            .filter { it.statusChange?.to == IssueStatus.DONE.raw }
            .mapNotNull { c -> runCatching { OffsetDateTime.parse(c.date).atZoneSameInstant(DateUtil.TZ).toLocalDate() }.getOrNull() }
            .maxOrNull() ?: updatedDate
}

@Serializable
data class IssueTemplate(val title: String, val area: String)

@Serializable
data class RoomRank(val store: String, @SerialName("room_id") val roomId: String, val rank: String)

/** 場所の表示名（"301号室" / "3F 共用部" / "駐車場・駐輪場"） */
fun locationLabel(roomId: String, site: IssueSite): String {
    FacilityConst.locationName(roomId).takeIf { it.isNotEmpty() }?.let { return it }
    val h = site.hotel ?: return roomId
    return FacilityConst.rooms(h).firstOrNull { it.id == roomId }?.let { "${it.num}号室" } ?: roomId
}

val defaultTemplates = listOf(
    IssueTemplate("エアコン異音・不具合", "部屋"), IssueTemplate("水漏れ・水道不具合", "風呂"),
    IssueTemplate("照明切れ・点滅", "部屋"), IssueTemplate("ドア・鍵の不具合", "部屋"),
    IssueTemplate("テレビ・リモコン不具合", "部屋"), IssueTemplate("トイレ詰まり・不具合", "トイレ"),
    IssueTemplate("シャワー水圧・温度異常", "風呂"), IssueTemplate("洗面台排水不具合", "洗面"),
    IssueTemplate("備品破損・紛失", "部屋"), IssueTemplate("壁・床・天井の損傷", "部屋"),
    IssueTemplate("窓・カーテン不具合", "部屋"), IssueTemplate("冷蔵庫不具合", "部屋"),
)

/**
 * 電波がなくて送れなかった新規の不具合（端末に保存して、電波が戻ったら送る）。
 * @property issue 送る内容（photos はアップロード済みの写真の URL）
 * @property localPhotos まだアップロードしていない写真（filesDir/issue_pending_photos/<id>/ 内のファイル名）
 * @property uploaded localPhotos のうちアップロードできたもの（ファイル名 → URL。途中で止まっても二重に送らない）
 * @property updatedAt 送るときの updated_at（再送しても同じ値。前回の登録が届いていたかの確認に使う）
 * @property insertTried 登録まで進んだことがあるか
 */
@Serializable
data class PendingIssue(
    val id: String,
    val createdAt: String,
    val updatedAt: String,
    val issue: Issue,
    val localPhotos: List<String> = emptyList(),
    val uploaded: Map<String, String> = emptyMap(),
    val insertTried: Boolean = false,
    val failCount: Int = 0,
    val lastError: String? = null,
) {
    val photoCount: Int get() = issue.photoList.size + localPhotos.size
}

/** 新規登録の結果 */
sealed interface CreateResult {
    data class Saved(val issue: Issue) : CreateResult
    /** 電波がないため端末に保存した */
    data object Queued : CreateResult
    data class Failed(val message: String) : CreateResult
}
